import type { Database, SqlJsStatic, SqlValue } from "sql.js";
import { parseLiveDashboardEvent } from "../api/live-event";
import type {
  AppSummary,
  DatabaseQueryResult,
  DatabaseSchema,
  EvidenceCounts,
  FocusedEvidence,
  LiveDashboardEvent,
  Run,
  Test,
} from "../api/types";
import { createDemoData, type DemoData } from "./fixtures";

const tables = [
  "runs",
  "tests",
  "entries",
  "spans",
  "snapshots",
  "mock_interactions",
  "mock_warnings",
] as const;
type Table = (typeof tables)[number];
const jsonFields = new Set(["systems", "metadata", "test_path", "near_misses"]);
const booleanFields = new Set(["matched", "request_body_truncated", "response_body_truncated"]);
const numericFields = new Set([
  "duration_ms",
  "total_tests",
  "passed",
  "failed",
  "attempt_count",
  "failure_count",
  "start_time_nanos",
  "end_time_nanos",
  "latency_ms",
  "configured_delay_ms",
  "client_deadline_ms",
]);
const quote = (value: string) => `"${value.replace(/"/g, '""')}"`;

/** A private, in-memory SQLite database; this adapter never contacts a server. */
export function createDemoBackend(SQL: SqlJsStatic, version: string) {
  let db: Database;
  let retention = 0;
  let seq = 0;
  let replayTimer: ReturnType<typeof setTimeout> | undefined;
  let replaying = false;
  let replayRunId: string | undefined;
  let replayNumber = 0;
  const listeners = new Set<(events: readonly LiveDashboardEvent[]) => void>();

  function insert(table: Table, row: object) {
    const fields = Object.keys(row);
    const values = Object.values(row).map(
      (value): SqlValue =>
        value === null
          ? null
          : typeof value === "object"
            ? JSON.stringify(value)
            : typeof value === "boolean"
              ? Number(value)
              : value,
    );
    db.run(
      `INSERT INTO ${quote(table)} (${fields.map(quote).join(",")}) VALUES (${fields.map(() => "?").join(",")})`,
      values,
    );
  }

  function cancelReplay() {
    clearTimeout(replayTimer);
    replaying = false;
    replayRunId = undefined;
    for (const listener of listeners) listener([]);
  }

  function reset() {
    cancelReplay();
    db?.close();
    db = new SQL.Database();
    retention = 0;
    const fixtures = createDemoData(version);
    for (const table of tables) {
      const first = fixtures[table][0];
      const definitions = Object.keys(first).map((field) => {
        const isId = field === "id" && table !== "runs" && table !== "tests";
        return `${quote(field)} ${isId || numericFields.has(field) || booleanFields.has(field) ? "INTEGER" : "TEXT"}`;
      });
      definitions.push(table === "tests" ? "PRIMARY KEY (run_id, id)" : "PRIMARY KEY (id)");
      db.run(`CREATE TABLE ${quote(table)} (${definitions.join(",")})`);
      for (const row of fixtures[table]) insert(table, row);
    }
  }

  function rows<T extends Table>(table: T): DemoData[T] {
    const result = db.exec(`SELECT * FROM ${quote(table)}`)[0];
    return (result?.values.map((values) =>
      Object.fromEntries(
        result.columns.map((field, index) => {
          let value = values[index];
          // Entry metadata is a JSON string in the REST contract, unlike run metadata.
          if (
            jsonFields.has(field) &&
            typeof value === "string" &&
            (field !== "metadata" || table === "runs")
          )
            value = JSON.parse(value);
          else if (booleanFields.has(field)) return [field, Boolean(value)];
          return [field, value];
        }),
      ),
    ) ?? []) as DemoData[T];
  }

  const runs = () => rows("runs").sort((a, b) => b.started_at.localeCompare(a.started_at));
  function counts(ids: string[]): EvidenceCounts {
    return Object.fromEntries(
      tables
        .filter((table) => table !== "runs")
        .map((table) => [
          table,
          rows(table).filter((row) => "run_id" in row && ids.includes(row.run_id)).length,
        ]),
    ) as unknown as EvidenceCounts;
  }
  const status = () => ({
    backend: "SQLite (demo, in browser memory)",
    retention_runs_per_app: retention,
    runs: runs().length,
    running_runs: runs().filter((run) => run.status === "RUNNING").length,
    evidence: counts(runs().map((run) => run.id)),
  });

  function removeRuns(ids: string[]) {
    if (replayRunId && ids.includes(replayRunId)) cancelReplay();
    for (const id of ids)
      for (const table of tables)
        db.run(`DELETE FROM ${quote(table)} WHERE ${table === "runs" ? "id" : "run_id"} = ?`, [id]);
  }
  function prune() {
    if (retention === 0) return;
    const seen = new Map<string, number>();
    const removed = runs().filter((run) => {
      if (run.status === "RUNNING") return false;
      const count = (seen.get(run.app_name) ?? 0) + 1;
      seen.set(run.app_name, count);
      return count > retention;
    });
    removeRuns(removed.map((run) => run.id));
  }

  function schema(): DatabaseSchema {
    return {
      backend: "sqlite",
      tables: tables.map((name) => ({
        name,
        columns: db.exec(`PRAGMA table_info(${quote(name)})`)[0].values.map((column) => ({
          name: String(column[1]),
          data_type: String(column[2]),
          nullable: column[3] === 0,
          primary_key: Number(column[5]) > 0,
        })),
      })),
    };
  }

  function execute(sql: string, maxRows: number): DatabaseQueryResult {
    const limit = Math.min(500, Math.max(1, Math.trunc(maxRows) || 100));
    const results = db.exec(sql);
    const result = results[results.length - 1];
    return {
      columns: result?.columns ?? [],
      rows: (result?.values ?? [])
        .slice(0, limit)
        .map((row) => row.map((value) => (value === null ? null : String(value)))),
      affected_rows: result ? 0 : db.getRowsModified(),
      truncated: (result?.values.length ?? 0) > limit,
    };
  }

  function emit(runId: string, eventType: string, payload: object) {
    const event = parseLiveDashboardEvent(
      JSON.stringify({ run_id: runId, event_type: eventType, payload, seq: ++seq }),
    );
    if (!event) throw new Error(`Invalid demo event: ${eventType}`);
    for (const listener of listeners) listener([event]);
  }

  function replay() {
    if (replaying) return null;
    replaying = true;
    replayNumber++;
    const fixture = createDemoData(version, Date.now() + 120_000);
    const source = fixture.runs[0];
    const test = fixture.tests[0];
    const run: Run = {
      ...source,
      id: `checkout-replay-${Date.now()}-${replayNumber}`,
      status: "RUNNING",
      ended_at: null,
      duration_ms: null,
      total_tests: 1,
      passed: 0,
      failed: 0,
      metadata: { ...source.metadata, pipeline: "demo-replay" },
    };
    replayRunId = run.id;
    insert("runs", run);
    emit(run.id, "run_started", run);
    const activeTest: Test = {
      ...test,
      run_id: run.id,
      ended_at: null,
      duration_ms: null,
      status: "RUNNING",
      error: null,
    };
    insert("tests", activeTest);
    emit(run.id, "test_started", { ...activeTest, test_id: test.id });
    const steps: (() => void)[] = [];
    const recordTypes = {
      entries: "entry_recorded",
      spans: "span_recorded",
      snapshots: "snapshot",
      mock_interactions: "mock_interaction",
      mock_warnings: "mock_warning",
    } as const;
    for (const table of Object.keys(recordTypes) as (keyof typeof recordTypes)[]) {
      for (const record of fixture[table].filter(
        (row) =>
          row.run_id === source.id &&
          ("test_id" in row
            ? row.test_id === test.id
            : row.trace_id ===
              fixture.entries.find(
                (entry) =>
                  entry.run_id === source.id && entry.test_id === test.id && entry.trace_id,
              )?.trace_id),
      )) {
        steps.push(() => {
          const next = {
            ...record,
            run_id: run.id,
            id: record.id + replayNumber * 100_000,
            ...("trace_id" in record && record.trace_id
              ? { trace_id: `d${replayNumber.toString(16).padStart(31, "0")}` }
              : {}),
          };
          insert(table, next);
          emit(run.id, recordTypes[table], { ...next, test_id: test.id });
        });
      }
    }
    steps.push(() => {
      const ended = new Date().toISOString();
      const duration = Date.now() - Date.parse(run.started_at);
      db.run(
        "UPDATE tests SET status = 'FAILED', ended_at = ?, duration_ms = ?, error = ? WHERE run_id = ?",
        [ended, duration, test.error, run.id],
      );
      db.run(
        "UPDATE runs SET status = 'FAILED', ended_at = ?, duration_ms = ?, failed = 1 WHERE id = ?",
        [ended, duration, run.id],
      );
      emit(run.id, "test_ended", {
        test_id: test.id,
        status: "FAILED",
        duration_ms: duration,
        error: test.error,
        ended_at: ended,
      });
      replaying = false;
      emit(run.id, "run_ended", {
        ended_at: ended,
        status: "FAILED",
        total_tests: 1,
        passed: 0,
        failed: 1,
        duration_ms: duration,
      });
      replayRunId = undefined;
      prune();
    });
    const next = () => {
      // SQL and purge tools can remove the active run while a replay is in flight.
      if (!runs().some((item) => item.id === run.id)) {
        cancelReplay();
        return;
      }
      steps.shift()?.();
      if (steps.length) replayTimer = setTimeout(next, 450);
    };
    replayTimer = setTimeout(next, 450);
    return { runId: run.id, testId: test.id };
  }

  async function request(input: string, init: RequestInit = {}): Promise<Response> {
    init.signal?.throwIfAborted();
    const url = new URL(input, "https://demo.invalid");
    const path = url.pathname.split("/api/v1")[1];
    const method = init.method ?? "GET";
    const body = init.body ? JSON.parse(String(init.body)) : {};
    const ok = (value: unknown, statusCode = 200) => Response.json(value, { status: statusCode });
    try {
      if (method === "GET") {
        if (path === "/meta")
          return ok({
            stove_server_version: version,
            mcp: { enabled: false, endpoint: "", scope: "demo", transport: "none" },
          });
        if (path === "/apps") {
          const apps = new Map<string, AppSummary>();
          for (const run of runs())
            if (!apps.has(run.app_name))
              apps.set(run.app_name, {
                app_name: run.app_name,
                latest_run_id: run.id,
                latest_run_started_at: run.started_at,
                latest_status: run.status,
                stove_version: version,
                metadata: run.metadata,
              });
          return ok([...apps.values()]);
        }
        if (path === "/runs")
          return ok(
            runs().filter(
              (run) => !url.searchParams.has("app") || run.app_name === url.searchParams.get("app"),
            ),
          );
        if (path === "/admin/status") return ok(status());
        if (path === "/admin/database/schema") return ok(schema());
        if (path?.startsWith("/traces/"))
          return ok(
            rows("spans").filter((span) => span.trace_id === decodeURIComponent(path.slice(8))),
          );
        const match = /^\/runs\/([^/]+)(?:\/tests(?:\/([^/]+))?)?(.*)$/.exec(path ?? "");
        if (match) {
          const runId = decodeURIComponent(match[1]);
          const testId = match[2] ? decodeURIComponent(match[2]) : undefined;
          const suffix = match[3];
          if (!runs().some((run) => run.id === runId)) return ok({ error: "Run unavailable" }, 404);
          if (testId && !rows("tests").some((test) => test.run_id === runId && test.id === testId))
            return ok({ error: "Test unavailable" }, 404);
          if (!suffix) {
            if (testId)
              return ok(rows("tests").find((test) => test.run_id === runId && test.id === testId));
            return ok(
              path.endsWith("/tests")
                ? rows("tests").filter((test) => test.run_id === runId)
                : runs().find((run) => run.id === runId),
            );
          }
          const selected = <T extends Exclude<Table, "runs" | "tests">>(table: T): DemoData[T] =>
            rows(table).filter(
              (record) =>
                record.run_id === runId &&
                ("test_id" in record
                  ? record.test_id === (testId ?? null)
                  : testId
                    ? rows("entries").some(
                        (entry) =>
                          entry.run_id === runId &&
                          entry.test_id === testId &&
                          entry.trace_id === record.trace_id,
                      )
                    : true),
            ) as DemoData[T];
          const evidence = /^\/evidence\/(entry|span|snapshot|interaction|warning)\/(\d+)$/.exec(
            suffix,
          );
          if (evidence) {
            const table = (
              {
                entry: "entries",
                span: "spans",
                snapshot: "snapshots",
                interaction: "mock_interactions",
                warning: "mock_warnings",
              } as const
            )[evidence[1] as "entry"];
            const value = selected(table).find((row) => row.id === Number(evidence[2]));
            if (!value) return ok({ error: "Evidence unavailable" }, 404);
            const limit = Math.min(100, Math.max(0, Number(url.searchParams.get("context") ?? 10)));
            const target = { kind: evidence[1], value } as FocusedEvidence["target"];
            const result: FocusedEvidence = {
              target,
              entries: [],
              spans: [],
              interactions: [],
              warnings: [],
              has_more_before: false,
              has_more_after: false,
              context_limit: limit,
            };
            switch (target.kind) {
              case "entry": {
                const entries = selected("entries").sort(
                  (a, b) => a.timestamp.localeCompare(b.timestamp) || a.id - b.id,
                );
                const index = entries.findIndex((entry) => entry.id === target.value.id);
                result.entries = entries.slice(Math.max(0, index - limit), index + limit + 1);
                result.has_more_before = index > limit;
                result.has_more_after = entries.length - index - 1 > limit;
                break;
              }
              case "span": {
                const trace = selected("spans").filter(
                  (span) => span.trace_id === target.value.trace_id,
                );
                let current = target.value;
                const seen = new Set<number>();
                while (!seen.has(current.id)) {
                  seen.add(current.id);
                  result.spans.unshift(current);
                  const parent = trace.find((span) => span.span_id === current.parent_span_id);
                  if (!parent) break;
                  if (result.spans.length > limit) {
                    result.has_more_before = true;
                    break;
                  }
                  current = parent;
                }
                break;
              }
              case "interaction": {
                result.interactions = [target.value];
                const related = selected("mock_warnings").filter(
                  (warning) => target.value.stub_id && warning.stub_id === target.value.stub_id,
                );
                result.warnings = related.slice(0, limit);
                result.has_more_after = related.length > limit;
                break;
              }
              case "warning": {
                result.warnings = [target.value];
                const related = selected("mock_interactions").filter(
                  (interaction) =>
                    target.value.stub_id && interaction.stub_id === target.value.stub_id,
                );
                result.interactions = related.slice(0, limit);
                result.has_more_after = related.length > limit;
                break;
              }
              case "snapshot":
                break;
            }
            return ok(result);
          }
          const table = suffix
            .replace(/^\//, "")
            .replace(/\/ambient$/, "")
            .replace(/-/g, "_") as Table;
          if (table !== "runs" && table !== "tests" && tables.includes(table))
            return ok(selected(table));
        }
      }
      if (method === "POST" && path === "/admin/database/query")
        return ok(execute(body.sql, body.max_rows));
      if (method === "PUT" && path === "/admin/retention") {
        if (!Number.isInteger(body.runs_per_app) || body.runs_per_app < 0)
          return ok({ error: "Retention must be a non-negative integer" }, 400);
        retention = body.runs_per_app;
        prune();
        return ok(status());
      }
      if (method === "POST" && path === "/admin/purge/preview") {
        const matches = runs().filter(
          (run) =>
            (body.include_running || run.status !== "RUNNING") &&
            (!body.app_name || run.app_name === body.app_name) &&
            (!body.older_than || run.started_at < body.older_than) &&
            Object.entries(body.metadata ?? {}).every(([key, values]) =>
              (values as string[]).includes(run.metadata[key]),
            ),
        );
        const ids = matches.map((run) => run.id);
        return ok({ run_ids: ids, run_count: ids.length, evidence: counts(ids) });
      }
      if (method === "POST" && path === "/admin/purge") {
        const ids = runs()
          .filter(
            (run) =>
              body.run_ids.includes(run.id) && (body.include_running || run.status !== "RUNNING"),
          )
          .map((run) => run.id);
        const evidence = counts(ids);
        removeRuns(ids);
        return ok({ purged_run_ids: ids, purged_runs: ids.length, evidence });
      }
      if (method === "DELETE" && path === "/data") {
        cancelReplay();
        removeRuns(runs().map((run) => run.id));
        return new Response(null, { status: 204 });
      }
      return ok({ error: "Demo route unavailable" }, 404);
    } catch (error) {
      return ok({ error: error instanceof Error ? error.message : String(error) }, 400);
    }
  }

  reset();
  return {
    request,
    reset,
    replay,
    get replaying() {
      return replaying;
    },
    subscribe(listener: (events: readonly LiveDashboardEvent[]) => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
