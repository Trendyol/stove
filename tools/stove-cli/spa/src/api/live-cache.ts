import type { QueryClient } from "@tanstack/react-query";
import type { Status } from "../utils/status";
import type { AppSummary, Entry, LiveDashboardEvent, Run, Snapshot, Span, Test } from "./types";
import { EVENT_TYPE } from "./types";

const RUNNING: Status = "RUNNING";

export function applyLiveDashboardEvent(queryClient: QueryClient, event: LiveDashboardEvent) {
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
      };

      queryClient.setQueryData<Snapshot[]>(
        ["snapshots", event.run_id, event.payload.test_id],
        (snapshots) => appendSnapshots(snapshots, snapshot),
      );
      break;
    }
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
  } else {
    queryClient.invalidateQueries();
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
