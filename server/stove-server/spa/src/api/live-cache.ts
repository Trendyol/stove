import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { LiveCacheBuffer } from "./live-cache/buffer";
import {
  appendEntries,
  appendInteractions,
  appendSnapshots,
  appendSpan,
  appendWarnings,
  compareRuns,
  compareTests,
  mergeSpans,
  upsertTest,
} from "./live-cache/records";
import type {
  AppSummary,
  Entry,
  LiveDashboardEvent,
  LiveEventOf,
  MockInteraction,
  MockWarning,
  Run,
  Snapshot,
  Span,
  Test,
} from "./types";
import { EVENT_TYPE } from "./types";

export { loadAndReconcileDashboardData, reconcileDashboardData } from "./live-cache/reconciliation";

/** Commit one browser frame with a single cache write per affected query. */
export function applyLiveDashboardEvents(
  queryClient: QueryClient,
  events: readonly LiveDashboardEvent[],
) {
  if (events.length === 0) return;
  const cache = new LiveCacheBuffer(queryClient);
  for (const event of events) applyEvent(cache, event);
  cache.flush();
}

function applyEvent(cache: LiveCacheBuffer, event: LiveDashboardEvent) {
  switch (event.event_type) {
    case EVENT_TYPE.RUN_STARTED:
      return applyRunStarted(cache, event);
    case EVENT_TYPE.RUN_ENDED:
      return applyRunEnded(cache, event);
    case EVENT_TYPE.TEST_STARTED:
      return applyTestStarted(cache, event);
    case EVENT_TYPE.TEST_ENDED:
      return applyTestEnded(cache, event);
    case EVENT_TYPE.ENTRY_RECORDED:
      return applyEntryRecorded(cache, event);
    case EVENT_TYPE.SPAN_RECORDED:
      return applySpanRecorded(cache, event);
    case EVENT_TYPE.SNAPSHOT:
      return applySnapshot(cache, event);
    case EVENT_TYPE.MOCK_INTERACTION:
      return applyMockInteraction(cache, event);
    case EVENT_TYPE.MOCK_WARNING:
      return applyMockWarning(cache, event);
  }
  event satisfies never;
}

function applyRunStarted(cache: LiveCacheBuffer, event: LiveEventOf<"run_started">) {
  const run: Run = {
    ...event.payload,
    id: event.run_id,
    ended_at: null,
    status: "RUNNING",
    total_tests: 0,
    passed: 0,
    failed: 0,
    duration_ms: null,
  };
  cache.setQueryData<AppSummary[]>(["apps"], (apps) =>
    upsertAppSummary(apps, {
      app_name: run.app_name,
      latest_run_id: run.id,
      latest_run_started_at: run.started_at,
      latest_status: run.status,
      stove_version: run.stove_version,
      metadata: run.metadata,
    }),
  );
  updateRunQueriesForStart(cache, run);
}

function applyRunEnded(cache: LiveCacheBuffer, event: LiveEventOf<"run_ended">) {
  updateCachedRuns(cache, event.run_id, (run) => ({ ...run, ...event.payload }));
  cache.setQueryData<AppSummary[]>(["apps"], (apps) =>
    apps?.map((app) =>
      app.latest_run_id === event.run_id ? { ...app, latest_status: event.payload.status } : app,
    ),
  );
}

function applyTestStarted(cache: LiveCacheBuffer, event: LiveEventOf<"test_started">) {
  const { test_id, ...payload } = event.payload;
  const test: Test = {
    ...payload,
    id: test_id,
    run_id: event.run_id,
    test_path: payload.test_path ?? [],
    ended_at: null,
    duration_ms: null,
    error: null,
  };
  updateExistingQueryData<Test[]>(cache, ["tests", event.run_id], (tests) =>
    upsertTest(tests, test),
  );
}

function applyTestEnded(cache: LiveCacheBuffer, event: LiveEventOf<"test_ended">) {
  const { test_id, ...payload } = event.payload;
  updateCachedTests(cache, event.run_id, test_id, (test) => ({ ...test, ...payload }));
}

function applyEntryRecorded(cache: LiveCacheBuffer, event: LiveEventOf<"entry_recorded">) {
  const entry: Entry = { ...event.payload, run_id: event.run_id };
  updateExistingQueryData<Entry[]>(cache, ["entries", event.run_id, entry.test_id], (entries) =>
    appendEntries(entries, entry),
  );
  if (!entry.trace_id) return;
  const traceSpans = cache.getQueryData<Span[]>(["trace", entry.trace_id]);
  if (traceSpans?.length) {
    updateExistingQueryData<Span[]>(cache, ["spans", event.run_id, entry.test_id], (spans) =>
      mergeSpans(spans, traceSpans),
    );
  }
}

function applySpanRecorded(cache: LiveCacheBuffer, event: LiveEventOf<"span_recorded">) {
  const { test_id, ...payload } = event.payload;
  const span: Span = { ...payload, run_id: event.run_id };
  updateExistingQueryData<Span[]>(cache, ["trace", span.trace_id], (trace) =>
    appendSpan(trace, span),
  );
  const testId = test_id ?? findTestIdForTrace(cache, event.run_id, span.trace_id);
  if (testId) {
    updateExistingQueryData<Span[]>(cache, ["spans", event.run_id, testId], (spans) =>
      appendSpan(spans, span),
    );
  }
}

function applySnapshot(cache: LiveCacheBuffer, event: LiveEventOf<"snapshot">) {
  const snapshot = { ...event.payload, run_id: event.run_id };
  updateExistingQueryData<Snapshot[]>(
    cache,
    ["snapshots", event.run_id, snapshot.test_id],
    (snapshots) => appendSnapshots(snapshots, snapshot),
  );
}

function applyMockInteraction(cache: LiveCacheBuffer, event: LiveEventOf<"mock_interaction">) {
  const interaction = { ...event.payload, run_id: event.run_id };
  const key = interaction.test_id
    ? ["mock-interactions", event.run_id, interaction.test_id]
    : ["mock-interactions", event.run_id];
  updateExistingQueryData<MockInteraction[]>(cache, key, (interactions) =>
    appendInteractions(interactions, interaction),
  );
}

function applyMockWarning(cache: LiveCacheBuffer, event: LiveEventOf<"mock_warning">) {
  const warning = { ...event.payload, run_id: event.run_id };
  const key = warning.test_id
    ? ["mock-warnings", event.run_id, warning.test_id]
    : ["mock-warnings", event.run_id];
  updateExistingQueryData<MockWarning[]>(cache, key, (warnings) =>
    appendWarnings(warnings, warning),
  );
}

export function invalidateDashboardQueries(queryClient: QueryClient, runId?: string) {
  queryClient.invalidateQueries({ queryKey: ["apps"] });
  queryClient.invalidateQueries({ queryKey: ["runs"] });
  if (runId) {
    queryClient.invalidateQueries({ queryKey: ["tests", runId] });
    queryClient.invalidateQueries({ queryKey: ["entries", runId] });
    queryClient.invalidateQueries({ queryKey: ["spans", runId] });
    queryClient.invalidateQueries({ queryKey: ["snapshots", runId] });
    queryClient.invalidateQueries({ queryKey: ["mock-interactions", runId] });
    queryClient.invalidateQueries({ queryKey: ["mock-warnings", runId] });
  } else {
    queryClient.invalidateQueries();
  }
}

function upsertAppSummary(apps: AppSummary[] | undefined, incoming: AppSummary): AppSummary[] {
  return [...(apps ?? []).filter((app) => app.app_name !== incoming.app_name), incoming].sort(
    (left, right) => left.app_name.localeCompare(right.app_name),
  );
}

function updateRunQueriesForStart(queryClient: LiveCacheBuffer, incoming: Run) {
  updateExistingQueryData<Run[]>(queryClient, ["runs", incoming.app_name], (runs) =>
    [...(runs ?? []).filter((run) => run.id !== incoming.id), incoming].sort(compareRuns),
  );
}

function updateCachedRuns(queryClient: LiveCacheBuffer, runId: string, updater: (run: Run) => Run) {
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
  queryClient: LiveCacheBuffer,
  runId: string,
  testId: string,
  updater: (test: Test) => Test,
) {
  updateExistingQueryData<Test[]>(
    queryClient,
    ["tests", runId],
    (tests) =>
      tests?.map((test) => (test.id === testId ? updater(test) : test)).sort(compareTests) ?? tests,
  );
}

function findTestIdForTrace(
  queryClient: LiveCacheBuffer,
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

function updateExistingQueryData<T>(
  queryClient: LiveCacheBuffer,
  queryKey: QueryKey,
  updater: (current: T | undefined) => T | undefined,
) {
  if (queryClient.hasQuery(queryKey)) {
    queryClient.setQueryData(queryKey, updater);
  }
}
