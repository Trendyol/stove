import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { LiveCacheBuffer } from "./live-cache/buffer";
import { cancelPreDeletionRequests } from "./live-cache/reconciliation";
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
import { dashboardKeys } from "./query-keys";
import { matchesRun, type RunListFilter, type RunPages } from "./run-queries";
import type { TestPages } from "./test-queries";
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
  const deletions = events.filter((event) => event.event_type === EVENT_TYPE.RUN_DELETED);
  if (deletions.length) cancelPreDeletionRequests(queryClient);
  // An in-flight page read may predate this event. Cancel it before publishing live
  // changes, then refetch through the infinite query so all page boundaries agree.
  const runChanged = events.some(
    (event) =>
      event.event_type === EVENT_TYPE.RUN_STARTED || event.event_type === EVENT_TYPE.RUN_ENDED,
  );
  const changedTests = new Set(
    events
      .filter(
        (event) =>
          event.event_type === EVENT_TYPE.TEST_STARTED ||
          event.event_type === EVENT_TYPE.TEST_ENDED,
      )
      .map((event) => event.run_id),
  );
  const interrupted = queryClient
    .getQueryCache()
    .findAll({
      predicate: (query) =>
        ((runChanged && (query.queryKey[0] === "runs" || query.queryKey[0] === "run")) ||
          (changedTests.has(String(query.queryKey[1])) &&
            (query.queryKey[0] === "tests" || query.queryKey[0] === "test"))) &&
        query.state.fetchStatus === "fetching",
    })
    .map((query) => query.queryKey);
  for (const queryKey of interrupted) void queryClient.cancelQueries({ queryKey, exact: true });
  const cache = new LiveCacheBuffer(queryClient);
  for (const event of events) applyEvent(cache, event);
  cache.flush();
  for (const runId of changedTests) {
    void queryClient.invalidateQueries({ queryKey: ["tests", runId] }, { cancelRefetch: false });
    void queryClient.invalidateQueries({ queryKey: ["test", runId] }, { cancelRefetch: false });
  }
  for (const queryKey of interrupted) void queryClient.invalidateQueries({ queryKey, exact: true });
  if (
    events.some(
      (event) =>
        event.event_type === EVENT_TYPE.RUN_STARTED || event.event_type === EVENT_TYPE.RUN_DELETED,
    )
  ) {
    void queryClient.invalidateQueries({ queryKey: ["run-metadata"] });
  }
  if (deletions.length) {
    for (const event of deletions) invalidateDashboardQueries(queryClient, event.run_id);
    void queryClient.invalidateQueries({ queryKey: ["admin"] });
    void queryClient.invalidateQueries({ queryKey: ["top-filter"] });
  }
}

function applyEvent(cache: LiveCacheBuffer, event: LiveDashboardEvent) {
  switch (event.event_type) {
    case EVENT_TYPE.RUN_DELETED:
      return applyRunDeleted(cache, event);
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
  cache.setQueryData<Run | null>(dashboardKeys.run(event.run_id), (run) =>
    run ? { ...run, ...event.payload } : run,
  );
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
  for (const [key, data] of cache.getQueriesData<TestPages>({
    queryKey: ["tests", event.run_id],
  })) {
    if (!data?.pages.length) continue;
    const filter = key[2] as { outcome?: string; search?: string } | undefined;
    if (filter?.search || (filter?.outcome && filter.outcome !== "all")) continue;
    cache.setQueryData<TestPages>(key, {
      ...data,
      pages: data.pages.map((page, index) => ({
        ...page,
        tests:
          index === data.pages.length - 1 && !page.next_cursor
            ? upsertTest(page.tests, test).slice(0, 100)
            : page.tests,
      })),
    });
  }
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
  queryClient.invalidateQueries({ queryKey: ["run"] });
  queryClient.invalidateQueries({ queryKey: ["run-metadata"] });
  if (runId) {
    queryClient.invalidateQueries({ queryKey: ["linked", runId] });
    queryClient.invalidateQueries({ queryKey: ["focus", runId] });
    queryClient.invalidateQueries({ queryKey: ["trace"] });
    queryClient.invalidateQueries({ queryKey: ["tests", runId] });
    queryClient.invalidateQueries({ queryKey: ["test", runId] });
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

function updateRunQueriesForStart(cache: LiveCacheBuffer, incoming: Run) {
  for (const [key, data] of cache.getQueriesData<RunPages>({ queryKey: ["runs"] })) {
    if (!data?.pages.length || !matchesRun(incoming, key[1] as RunListFilter)) continue;
    cache.setQueryData<RunPages>(key, {
      ...data,
      pages: data.pages.map((page, index) => ({
        ...page,
        runs:
          index === 0
            ? [...page.runs.filter((run) => run.id !== incoming.id), incoming].sort(compareRuns)
            : page.runs.filter((run) => run.id !== incoming.id),
      })),
    });
  }
}

function updateCachedRuns(cache: LiveCacheBuffer, runId: string, updater: (run: Run) => Run) {
  for (const [key, data] of cache.getQueriesData<RunPages>({ queryKey: ["runs"] })) {
    if (!data) continue;
    cache.setQueryData<RunPages>(key, {
      ...data,
      pages: data.pages.map((page) => ({
        ...page,
        runs: page.runs
          .map((run) => (run.id === runId ? updater(run) : run))
          .filter((run) => matchesRun(run, key[1] as RunListFilter)),
      })),
    });
  }
}

function updateCachedTests(
  queryClient: LiveCacheBuffer,
  runId: string,
  testId: string,
  updater: (test: Test) => Test,
) {
  for (const [key, data] of queryClient.getQueriesData<TestPages>({ queryKey: ["tests", runId] })) {
    if (!data) continue;
    queryClient.setQueryData<TestPages>(key, {
      ...data,
      pages: data.pages.map((page) => ({
        ...page,
        tests: page.tests
          .map((test) => (test.id === testId ? updater(test) : test))
          .sort(compareTests),
      })),
    });
  }
  queryClient.setQueryData<Test | null>(["test", runId, testId], (test) =>
    test ? updater(test) : test,
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

function applyRunDeleted(cache: LiveCacheBuffer, event: LiveEventOf<"run_deleted">) {
  const runId = event.run_id;
  for (const [key, data] of cache.getQueriesData<RunPages>({ queryKey: ["runs"] })) {
    if (data)
      cache.setQueryData<RunPages>(key, {
        ...data,
        pages: data.pages.map((page) => ({
          ...page,
          runs: page.runs.filter((run) => run.id !== runId),
        })),
      });
  }
  for (const [key, spans] of cache.getQueriesData<Span[]>({ queryKey: ["trace"] })) {
    cache.setQueryData(
      key,
      spans?.filter((span) => span.run_id !== runId),
    );
  }
  // Refetching apps selects the next latest surviving run, if any.
  cache.setQueryData<AppSummary[]>(["apps"], (apps) =>
    apps?.filter((app) => app.latest_run_id !== runId),
  );
  for (const [key, data] of cache.getQueriesData<unknown>({ queryKey: [] })) {
    if (key[0] === "tests" && key[1] === runId && data) {
      const pages = data as TestPages;
      cache.setQueryData<TestPages>(key, {
        ...pages,
        pages: pages.pages.map((page) => ({
          ...page,
          tests: [],
          total: 0,
          remaining: 0,
          next_cursor: null,
          summary: { total: 0, passed: 0, failed: 0, running: 0 },
        })),
      });
    } else if (key[0] !== "runs" && key.includes(runId)) {
      cache.setQueryData(key, Array.isArray(data) ? [] : null);
    }
  }
}
