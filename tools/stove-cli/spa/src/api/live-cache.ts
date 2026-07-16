import type { QueryClient } from "@tanstack/react-query";
import type { Status } from "../utils/status";
import type {
  AppSummary,
  Entry,
  LiveDashboardEvent,
  MockInteraction,
  MockWarning,
  Run,
  Snapshot,
  Span,
  Test,
} from "./types";
import { EVENT_TYPE } from "./types";

const RUNNING: Status = "RUNNING";

export function applyLiveDashboardEvent(queryClient: QueryClient, event: LiveDashboardEvent) {
  cancelConflictingQueries(queryClient, event);

  switch (event.event_type) {
    case EVENT_TYPE.RUN_STARTED: {
      const run: Run = {
        id: event.run_id,
        app_name: event.payload.app_name,
        started_at: event.payload.started_at,
        ended_at: null,
        status: RUNNING,
        total_tests: 0,
        passed: 0,
        failed: 0,
        duration_ms: null,
        stove_version: event.payload.stove_version,
        systems: event.payload.systems,
      };

      queryClient.setQueryData<AppSummary[]>(["apps"], (apps) =>
        upsertAppSummary(apps, {
          app_name: event.payload.app_name,
          latest_run_id: event.run_id,
          latest_status: RUNNING,
          stove_version: event.payload.stove_version,
          total_runs: nextRunCount(apps, event.payload.app_name, event.run_id),
        }),
      );
      queryClient.setQueryData<Run[]>(["runs", event.payload.app_name], (runs) =>
        upsertRun(runs, run),
      );
      queryClient.setQueryData<Test[]>(["tests", event.run_id], (tests) => tests ?? []);
      queryClient.setQueryData<MockInteraction[]>(
        ["interactions", event.run_id],
        (interactions) => interactions ?? [],
      );
      queryClient.setQueryData<MockWarning[]>(
        ["warnings", event.run_id],
        (warnings) => warnings ?? [],
      );
      break;
    }
    case EVENT_TYPE.RUN_ENDED: {
      updateCachedRuns(queryClient, event.run_id, (run) => ({
        ...run,
        ended_at: event.payload.ended_at,
        status: event.payload.status,
        total_tests: event.payload.total_tests,
        passed: event.payload.passed,
        failed: event.payload.failed,
        duration_ms: event.payload.duration_ms,
      }));
      queryClient.setQueryData<AppSummary[]>(
        ["apps"],
        (apps) =>
          apps?.map((app) =>
            app.latest_run_id === event.run_id
              ? { ...app, latest_status: event.payload.status }
              : app,
          ) ?? apps,
      );
      break;
    }
    case EVENT_TYPE.TEST_STARTED: {
      const test: Test = {
        id: event.payload.test_id,
        run_id: event.run_id,
        test_name: event.payload.test_name,
        spec_name: event.payload.spec_name,
        test_path: event.payload.test_path ?? [],
        started_at: event.payload.started_at,
        ended_at: null,
        status: event.payload.status,
        duration_ms: null,
        error: null,
      };

      queryClient.setQueryData<Test[]>(["tests", event.run_id], (tests) => upsertTest(tests, test));
      queryClient.setQueryData<Entry[]>(
        ["entries", event.run_id, event.payload.test_id],
        (entries) => entries ?? [],
      );
      queryClient.setQueryData<Span[]>(
        ["spans", event.run_id, event.payload.test_id],
        (spans) => spans ?? [],
      );
      queryClient.setQueryData<Snapshot[]>(
        ["snapshots", event.run_id, event.payload.test_id],
        (snapshots) => snapshots ?? [],
      );
      queryClient.setQueryData<MockInteraction[]>(
        ["interactions", event.run_id, event.payload.test_id],
        (interactions) => interactions ?? [],
      );
      queryClient.setQueryData<MockWarning[]>(
        ["warnings", event.run_id, event.payload.test_id],
        (warnings) => warnings ?? [],
      );
      break;
    }
    case EVENT_TYPE.TEST_ENDED: {
      updateCachedTests(queryClient, event.run_id, event.payload.test_id, (test) => ({
        ...test,
        ended_at: event.payload.ended_at,
        status: event.payload.status,
        duration_ms: event.payload.duration_ms,
        error: event.payload.error,
      }));
      break;
    }
    case EVENT_TYPE.ENTRY_RECORDED: {
      const entry: Entry = {
        id: event.payload.id,
        run_id: event.run_id,
        test_id: event.payload.test_id,
        timestamp: event.payload.timestamp,
        system: event.payload.system,
        action: event.payload.action,
        result: event.payload.result,
        input: event.payload.input,
        output: event.payload.output,
        metadata: event.payload.metadata,
        expected: event.payload.expected,
        actual: event.payload.actual,
        error: event.payload.error,
        trace_id: event.payload.trace_id,
      };

      queryClient.setQueryData<Entry[]>(
        ["entries", event.run_id, event.payload.test_id],
        (entries) => appendEntries(entries, entry),
      );

      if (event.payload.trace_id) {
        const traceSpans = queryClient.getQueryData<Span[]>(["trace", event.payload.trace_id]);
        if (traceSpans?.length) {
          queryClient.setQueryData<Span[]>(
            ["spans", event.run_id, event.payload.test_id],
            (spans) => mergeSpans(spans, traceSpans),
          );
        }
      }
      break;
    }
    case EVENT_TYPE.SPAN_RECORDED: {
      const span: Span = {
        id: event.payload.id,
        run_id: event.run_id,
        trace_id: event.payload.trace_id,
        span_id: event.payload.span_id,
        parent_span_id: event.payload.parent_span_id,
        operation_name: event.payload.operation_name,
        service_name: event.payload.service_name,
        start_time_nanos: event.payload.start_time_nanos,
        end_time_nanos: event.payload.end_time_nanos,
        status: event.payload.status,
        attributes: event.payload.attributes,
        exception_type: event.payload.exception_type,
        exception_message: event.payload.exception_message,
        exception_stack_trace: event.payload.exception_stack_trace,
      };

      queryClient.setQueryData<Span[]>(["trace", event.payload.trace_id], (trace) =>
        appendSpan(trace, span),
      );

      const testId =
        event.payload.test_id ??
        findTestIdForTrace(queryClient, event.run_id, event.payload.trace_id);
      if (testId) {
        queryClient.setQueryData<Span[]>(["spans", event.run_id, testId], (spans) =>
          appendSpan(spans, span),
        );
      }
      break;
    }
    case EVENT_TYPE.SNAPSHOT: {
      const snapshot: Snapshot = {
        id: event.payload.id,
        run_id: event.run_id,
        test_id: event.payload.test_id,
        system: event.payload.system,
        state_json: event.payload.state_json,
        summary: event.payload.summary,
        captured_at: event.payload.captured_at,
        trigger: event.payload.trigger,
      };

      queryClient.setQueryData<Snapshot[]>(
        ["snapshots", event.run_id, event.payload.test_id],
        (snapshots) => appendSnapshots(snapshots, snapshot),
      );
      break;
    }
    case EVENT_TYPE.MOCK_INTERACTION: {
      const interaction: MockInteraction = {
        id: event.payload.id,
        run_id: event.run_id,
        test_id: event.payload.test_id,
        timestamp: event.payload.timestamp,
        system: event.payload.system,
        protocol: event.payload.protocol,
        method: event.payload.method,
        target: event.payload.target,
        matched: event.payload.matched,
        stub_id: event.payload.stub_id,
        attribution: event.payload.attribution,
        request_body: event.payload.request_body,
        request_body_truncated: event.payload.request_body_truncated,
        response_body: event.payload.response_body,
        response_body_truncated: event.payload.response_body_truncated,
        status: event.payload.status,
        latency_ms: event.payload.latency_ms,
        near_misses: event.payload.near_misses,
        trace_id: event.payload.trace_id,
        scenario_name: event.payload.scenario_name,
        scenario_state: event.payload.scenario_state,
        next_scenario_state: event.payload.next_scenario_state,
        configured_delay_ms: event.payload.configured_delay_ms,
        fault: event.payload.fault,
        client_deadline_ms: event.payload.client_deadline_ms,
      };

      if (event.payload.test_id) {
        queryClient.setQueryData<MockInteraction[]>(
          ["interactions", event.run_id, event.payload.test_id],
          (interactions) => appendInteractions(interactions, interaction),
        );
      } else {
        queryClient.setQueryData<MockInteraction[]>(
          ["interactions", event.run_id],
          (interactions) => appendInteractions(interactions, interaction),
        );
      }
      break;
    }
    case EVENT_TYPE.MOCK_WARNING: {
      const warning: MockWarning = {
        id: event.payload.id,
        run_id: event.run_id,
        test_id: event.payload.test_id,
        timestamp: event.payload.timestamp,
        system: event.payload.system,
        kind: event.payload.kind,
        message: event.payload.message,
        stub_id: event.payload.stub_id,
        target: event.payload.target,
      };

      if (event.payload.test_id) {
        queryClient.setQueryData<MockWarning[]>(
          ["warnings", event.run_id, event.payload.test_id],
          (warnings) => appendWarnings(warnings, warning),
        );
      } else {
        queryClient.setQueryData<MockWarning[]>(["warnings", event.run_id], (warnings) =>
          appendWarnings(warnings, warning),
        );
      }
      break;
    }
  }
}

/**
 * Combines a persisted API response with records that arrived over SSE while
 * the request was in flight. Persistence is intentionally batched, so a valid
 * REST response can briefly be older than the live dashboard.
 */
export function reconcileDashboardData<T>(
  queryClient: QueryClient,
  queryKey: readonly unknown[],
  persisted: T,
): T {
  const cached = queryClient.getQueryData<T>(queryKey);
  if (!Array.isArray(persisted) || !Array.isArray(cached) || cached.length === 0) {
    return persisted;
  }

  switch (queryKey[0]) {
    case "apps":
      return mergeApps(persisted as AppSummary[], cached as AppSummary[]) as T;
    case "runs":
      return mergeRuns(persisted as Run[], cached as Run[]) as T;
    case "tests":
      return mergeTests(persisted as Test[], cached as Test[]) as T;
    case "entries":
      return mergeEntries(persisted as Entry[], cached as Entry[]) as T;
    case "spans":
    case "trace":
      return mergeSpanLists(persisted as Span[], cached as Span[]) as T;
    case "snapshots":
      return mergeSnapshotLists(persisted as Snapshot[], cached as Snapshot[]) as T;
    case "interactions":
      return mergeInteractions(persisted as MockInteraction[], cached as MockInteraction[]) as T;
    case "warnings":
      return mergeWarnings(persisted as MockWarning[], cached as MockWarning[]) as T;
    default:
      return persisted;
  }
}

export function invalidateDashboardQueries(queryClient: QueryClient, runId?: string) {
  queryClient.invalidateQueries({ queryKey: ["apps"] });
  queryClient.invalidateQueries({ queryKey: ["runs"] });
  if (runId) {
    queryClient.invalidateQueries({ queryKey: ["tests", runId] });
    queryClient.invalidateQueries({ queryKey: ["entries", runId] });
    queryClient.invalidateQueries({ queryKey: ["spans", runId] });
    queryClient.invalidateQueries({ queryKey: ["snapshots", runId] });
    queryClient.invalidateQueries({ queryKey: ["interactions", runId] });
    queryClient.invalidateQueries({ queryKey: ["warnings", runId] });
  } else {
    queryClient.invalidateQueries();
  }
}

function cancelConflictingQueries(queryClient: QueryClient, event: LiveDashboardEvent) {
  const cancel = (queryKey: readonly unknown[], exact = true) => {
    void queryClient.cancelQueries({ queryKey, exact }, { revert: false });
  };
  const cancelRunDetails = (runId: string) => {
    cancel(["tests", runId]);
    cancel(["entries", runId], false);
    cancel(["spans", runId], false);
    cancel(["snapshots", runId], false);
    cancel(["interactions", runId], false);
    cancel(["warnings", runId], false);
  };

  switch (event.event_type) {
    case EVENT_TYPE.RUN_STARTED:
      cancel(["apps"]);
      cancel(["runs", event.payload.app_name]);
      cancelRunDetails(event.run_id);
      break;
    case EVENT_TYPE.RUN_ENDED:
      cancel(["apps"]);
      cancel(["runs"], false);
      cancelRunDetails(event.run_id);
      break;
    case EVENT_TYPE.TEST_STARTED:
      cancel(["tests", event.run_id]);
      cancel(["entries", event.run_id, event.payload.test_id]);
      cancel(["spans", event.run_id, event.payload.test_id]);
      cancel(["snapshots", event.run_id, event.payload.test_id]);
      cancel(["interactions", event.run_id, event.payload.test_id]);
      cancel(["warnings", event.run_id, event.payload.test_id]);
      break;
    case EVENT_TYPE.TEST_ENDED:
      cancel(["tests", event.run_id]);
      break;
    case EVENT_TYPE.ENTRY_RECORDED:
      cancel(["entries", event.run_id, event.payload.test_id]);
      break;
    case EVENT_TYPE.SPAN_RECORDED:
      cancel(["trace", event.payload.trace_id]);
      if (event.payload.test_id) {
        cancel(["spans", event.run_id, event.payload.test_id]);
      } else {
        cancel(["spans", event.run_id], false);
      }
      break;
    case EVENT_TYPE.SNAPSHOT:
      cancel(["snapshots", event.run_id, event.payload.test_id]);
      break;
    case EVENT_TYPE.MOCK_INTERACTION:
      if (event.payload.test_id) {
        cancel(["interactions", event.run_id, event.payload.test_id]);
      } else {
        cancel(["interactions", event.run_id]);
      }
      break;
    case EVENT_TYPE.MOCK_WARNING:
      if (event.payload.test_id) {
        cancel(["warnings", event.run_id, event.payload.test_id]);
      } else {
        cancel(["warnings", event.run_id]);
      }
      break;
  }
}

function upsertAppSummary(apps: AppSummary[] | undefined, incoming: AppSummary): AppSummary[] {
  return [...(apps ?? []).filter((app) => app.app_name !== incoming.app_name), incoming].sort(
    (left, right) => left.app_name.localeCompare(right.app_name),
  );
}

function nextRunCount(apps: AppSummary[] | undefined, appName: string, runId: string): number {
  const existing = apps?.find((app) => app.app_name === appName);
  if (!existing) {
    return 1;
  }
  return existing.latest_run_id === runId ? existing.total_runs : existing.total_runs + 1;
}

function upsertRun(runs: Run[] | undefined, incoming: Run): Run[] {
  return [...(runs ?? []).filter((run) => run.id !== incoming.id), incoming].sort(compareRuns);
}

function upsertTest(tests: Test[] | undefined, incoming: Test): Test[] {
  return [...(tests ?? []).filter((test) => test.id !== incoming.id), incoming].sort(compareTests);
}

function updateCachedRuns(queryClient: QueryClient, runId: string, updater: (run: Run) => Run) {
  for (const [queryKey, runs] of queryClient.getQueriesData<Run[]>({ queryKey: ["runs"] })) {
    if (!runs?.some((run) => run.id === runId)) {
      continue;
    }
    queryClient.setQueryData(
      queryKey,
      runs.map((run) => (run.id === runId ? updater(run) : run)).sort(compareRuns),
    );
  }
}

function updateCachedTests(
  queryClient: QueryClient,
  runId: string,
  testId: string,
  updater: (test: Test) => Test,
) {
  queryClient.setQueryData<Test[]>(
    ["tests", runId],
    (tests) =>
      tests?.map((test) => (test.id === testId ? updater(test) : test)).sort(compareTests) ?? tests,
  );
}

function appendEntries(entries: Entry[] | undefined, incoming: Entry): Entry[] {
  if (entries?.some((entry) => entry.id === incoming.id)) {
    return entries;
  }
  return [...(entries ?? []), incoming].sort((left, right) =>
    left.timestamp.localeCompare(right.timestamp),
  );
}

function appendSpan(spans: Span[] | undefined, incoming: Span): Span[] {
  if (spans?.some((span) => isSameSpan(span, incoming))) {
    return spans;
  }
  return [...(spans ?? []), incoming].sort(
    (left, right) => left.start_time_nanos - right.start_time_nanos,
  );
}

function mergeSpans(existing: Span[] | undefined, incoming: Span[]): Span[] {
  return incoming.reduce<Span[]>((acc, span) => appendSpan(acc, span), existing ?? []);
}

function appendSnapshots(snapshots: Snapshot[] | undefined, incoming: Snapshot): Snapshot[] {
  if (
    snapshots?.some(
      (snapshot) =>
        snapshot.system === incoming.system &&
        snapshot.summary === incoming.summary &&
        snapshot.state_json === incoming.state_json,
    )
  ) {
    return snapshots;
  }
  return [...(snapshots ?? []), incoming];
}

function appendInteractions(
  interactions: MockInteraction[] | undefined,
  incoming: MockInteraction,
): MockInteraction[] {
  if (interactions?.some((interaction) => interaction.id === incoming.id)) {
    return interactions;
  }
  return [...(interactions ?? []), incoming].sort((left, right) =>
    left.timestamp.localeCompare(right.timestamp),
  );
}

function appendWarnings(warnings: MockWarning[] | undefined, incoming: MockWarning): MockWarning[] {
  if (warnings?.some((warning) => warning.id === incoming.id)) {
    return warnings;
  }
  return [...(warnings ?? []), incoming].sort((left, right) =>
    left.timestamp.localeCompare(right.timestamp),
  );
}

function mergeApps(persisted: AppSummary[], cached: AppSummary[]): AppSummary[] {
  const byName = new Map(persisted.map((app) => [app.app_name, app]));
  for (const live of cached) {
    const stored = byName.get(live.app_name);
    if (
      !stored ||
      live.total_runs > stored.total_runs ||
      (live.total_runs === stored.total_runs &&
        live.latest_run_id === stored.latest_run_id &&
        statusProgress(live.latest_status) > statusProgress(stored.latest_status)) ||
      (live.total_runs === stored.total_runs &&
        live.latest_run_id !== stored.latest_run_id &&
        isRunningStatus(live.latest_status))
    ) {
      byName.set(live.app_name, live);
    }
  }
  return [...byName.values()].sort((left, right) => left.app_name.localeCompare(right.app_name));
}

function mergeRuns(persisted: Run[], cached: Run[]): Run[] {
  const byId = new Map(persisted.map((run) => [run.id, run]));
  for (const live of cached) {
    const stored = byId.get(live.id);
    if (!stored || statusProgress(live.status) > statusProgress(stored.status)) {
      byId.set(live.id, live);
    }
  }
  return [...byId.values()].sort(compareRuns);
}

function mergeTests(persisted: Test[], cached: Test[]): Test[] {
  const byId = new Map(persisted.map((test) => [test.id, test]));
  for (const live of cached) {
    const stored = byId.get(live.id);
    if (!stored || statusProgress(live.status) > statusProgress(stored.status)) {
      byId.set(live.id, live);
    }
  }
  return [...byId.values()].sort(compareTests);
}

function mergeEntries(persisted: Entry[], cached: Entry[]): Entry[] {
  return mergeEvidenceRecords(
    persisted,
    cached,
    (entry) =>
      [
        entry.run_id,
        entry.test_id,
        entry.timestamp,
        entry.system,
        entry.action,
        entry.result,
        entry.trace_id,
      ].join("\u0000"),
    (left, right) => left.timestamp.localeCompare(right.timestamp),
  );
}

function mergeSpanLists(persisted: Span[], cached: Span[]): Span[] {
  return mergeEvidenceRecords(
    persisted,
    cached,
    (span) => `${span.trace_id}\u0000${span.span_id}`,
    (left, right) => left.start_time_nanos - right.start_time_nanos,
  );
}

function mergeSnapshotLists(persisted: Snapshot[], cached: Snapshot[]): Snapshot[] {
  return mergeEvidenceRecords(
    persisted,
    cached,
    (snapshot) =>
      [
        snapshot.run_id,
        snapshot.test_id,
        snapshot.system,
        snapshot.captured_at,
        snapshot.trigger,
        snapshot.summary,
        snapshot.state_json,
      ].join("\u0000"),
    (left, right) => (left.captured_at ?? "").localeCompare(right.captured_at ?? ""),
  );
}

function mergeInteractions(
  persisted: MockInteraction[],
  cached: MockInteraction[],
): MockInteraction[] {
  return mergeEvidenceRecords(
    persisted,
    cached,
    (interaction) =>
      [
        interaction.run_id,
        interaction.test_id,
        interaction.timestamp,
        interaction.system,
        interaction.protocol,
        interaction.method,
        interaction.target,
        interaction.stub_id,
        interaction.attribution,
      ].join("\u0000"),
    (left, right) => left.timestamp.localeCompare(right.timestamp),
  );
}

function mergeWarnings(persisted: MockWarning[], cached: MockWarning[]): MockWarning[] {
  return mergeEvidenceRecords(
    persisted,
    cached,
    (warning) =>
      [
        warning.run_id,
        warning.test_id,
        warning.timestamp,
        warning.system,
        warning.kind,
        warning.message,
        warning.stub_id,
        warning.target,
      ].join("\u0000"),
    (left, right) => left.timestamp.localeCompare(right.timestamp),
  );
}

function mergeEvidenceRecords<T>(
  persisted: T[],
  cached: T[],
  identity: (record: T) => string,
  compare: (left: T, right: T) => number,
): T[] {
  const unmatchedPersisted = new Map<string, number>();
  for (const record of persisted) {
    const key = identity(record);
    unmatchedPersisted.set(key, (unmatchedPersisted.get(key) ?? 0) + 1);
  }

  const merged = [...persisted];
  for (const record of cached) {
    const key = identity(record);
    const remaining = unmatchedPersisted.get(key) ?? 0;
    if (remaining > 0) {
      unmatchedPersisted.set(key, remaining - 1);
    } else {
      merged.push(record);
    }
  }
  return merged.sort(compare);
}

function statusProgress(status: Status): number {
  return isRunningStatus(status) ? 0 : 1;
}

function isRunningStatus(status: Status): boolean {
  return status === RUNNING;
}

function compareRuns(left: Run, right: Run): number {
  return right.started_at.localeCompare(left.started_at) || right.id.localeCompare(left.id);
}

function compareTests(left: Test, right: Test): number {
  return left.started_at.localeCompare(right.started_at) || left.id.localeCompare(right.id);
}

function isSameSpan(left: Span, right: Span): boolean {
  return left.trace_id === right.trace_id && left.span_id === right.span_id;
}

function findTestIdForTrace(
  queryClient: QueryClient,
  runId: string,
  traceId: string,
): string | null {
  for (const [queryKey, entries] of queryClient.getQueriesData<Entry[]>({
    queryKey: ["entries", runId],
  })) {
    if (!entries?.some((entry) => entry.trace_id === traceId)) {
      continue;
    }
    if (Array.isArray(queryKey) && typeof queryKey[2] === "string") {
      return queryKey[2];
    }
  }
  return null;
}
