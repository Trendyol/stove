import { notifyManager, type QueryClient, type QueryKey } from "@tanstack/react-query";
import type { Status } from "../utils/status";
import { committedRecordId, mergeEvidenceRecords, sameRecordId } from "./evidence-reconciliation";
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

type CacheUpdater<T> = T | undefined | ((current: T | undefined) => T | undefined);

interface LiveCacheClient {
  readonly mutableArrays: boolean;
  getQueryData<T>(queryKey: QueryKey): T | undefined;
  getQueriesData<T>(filters: { queryKey: QueryKey }): Array<[QueryKey, T | undefined]>;
  hasQuery(queryKey: QueryKey): boolean;
  setQueryData<T>(queryKey: QueryKey, updater: CacheUpdater<T>): void;
}

export function applyLiveDashboardEvent(queryClient: QueryClient, event: LiveDashboardEvent) {
  applyEvent(createDirectCacheClient(queryClient), event);
}

/**
 * Reduces one browser-frame worth of SSE messages into a single cache write per
 * affected query. Arrays are cloned once and then updated in place inside this
 * private buffer, so a burst does not repeatedly copy growing evidence lists.
 */
export function applyLiveDashboardEvents(
  queryClient: QueryClient,
  events: readonly LiveDashboardEvent[],
) {
  if (events.length === 0) return;
  if (events.length === 1) {
    applyLiveDashboardEvent(queryClient, events[0]);
    return;
  }

  const cache = new BufferedLiveCacheClient(queryClient);
  for (const event of events) {
    applyEvent(cache, event);
  }
  cache.flush();
}

export async function loadAndReconcileDashboardData<T>(
  queryClient: QueryClient,
  queryKey: QueryKey,
  load: () => Promise<T>,
): Promise<T> {
  return reconcileDashboardData(queryClient, queryKey, await load());
}

function createDirectCacheClient(queryClient: QueryClient): LiveCacheClient {
  return {
    mutableArrays: false,
    getQueryData: <T>(queryKey: QueryKey) => queryClient.getQueryData<T>(queryKey),
    getQueriesData: <T>(filters: { queryKey: QueryKey }) => queryClient.getQueriesData<T>(filters),
    hasQuery: (queryKey) =>
      queryClient.getQueryCache().find({ queryKey, exact: true }) !== undefined,
    setQueryData: <T>(queryKey: QueryKey, updater: CacheUpdater<T>) => {
      queryClient.setQueryData<T>(queryKey, updater as T | ((current: T | undefined) => T));
    },
  };
}

class BufferedLiveCacheClient implements LiveCacheClient {
  readonly mutableArrays = true;
  private readonly pending = new Map<string, { queryKey: QueryKey; data: unknown }>();

  constructor(private readonly queryClient: QueryClient) {}

  getQueryData<T>(queryKey: QueryKey): T | undefined {
    const pending = this.pending.get(queryHash(queryKey));
    return pending ? (pending.data as T | undefined) : this.queryClient.getQueryData<T>(queryKey);
  }

  getQueriesData<T>(filters: { queryKey: QueryKey }): Array<[QueryKey, T | undefined]> {
    const merged = new Map<string, [QueryKey, T | undefined]>();
    for (const [queryKey, data] of this.queryClient.getQueriesData<T>(filters)) {
      merged.set(queryHash(queryKey), [queryKey, data]);
    }
    for (const pending of this.pending.values()) {
      if (queryKeyStartsWith(pending.queryKey, filters.queryKey)) {
        merged.set(queryHash(pending.queryKey), [pending.queryKey, pending.data as T | undefined]);
      }
    }
    return [...merged.values()];
  }

  hasQuery(queryKey: QueryKey): boolean {
    return (
      this.pending.has(queryHash(queryKey)) ||
      this.queryClient.getQueryCache().find({ queryKey, exact: true }) !== undefined
    );
  }

  setQueryData<T>(queryKey: QueryKey, updater: CacheUpdater<T>): void {
    const hash = queryHash(queryKey);
    let current = this.getQueryData<T>(queryKey);
    if (!this.pending.has(hash) && Array.isArray(current)) {
      current = [...current] as T;
    }
    const data =
      typeof updater === "function"
        ? (updater as (value: T | undefined) => T | undefined)(current)
        : updater;
    this.pending.set(hash, { queryKey, data });
  }

  flush(): void {
    notifyManager.batch(() => {
      for (const { queryKey, data } of this.pending.values()) {
        this.queryClient.setQueryData(queryKey, data);
      }
    });
  }
}

function updateExistingQueryData<T>(
  queryClient: LiveCacheClient,
  queryKey: QueryKey,
  updater: CacheUpdater<T>,
) {
  if (queryClient.hasQuery(queryKey)) {
    queryClient.setQueryData(queryKey, updater);
  }
}

function queryHash(queryKey: QueryKey): string {
  return JSON.stringify(queryKey);
}

function queryKeyStartsWith(queryKey: QueryKey, prefix: QueryKey): boolean {
  return prefix.every((part, index) => Object.is(part, queryKey[index]));
}

function applyEvent(queryClient: LiveCacheClient, event: LiveDashboardEvent) {
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
        metadata: event.payload.metadata,
      };

      queryClient.setQueryData<AppSummary[]>(["apps"], (apps) =>
        upsertAppSummary(apps, {
          app_name: event.payload.app_name,
          latest_run_id: event.run_id,
          latest_run_started_at: event.payload.started_at,
          latest_status: RUNNING,
          stove_version: event.payload.stove_version,
          metadata: event.payload.metadata,
        }),
      );
      updateRunQueriesForStart(queryClient, run);
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

      updateExistingQueryData<Test[]>(queryClient, ["tests", event.run_id], (tests) =>
        upsertTest(tests, test, queryClient.mutableArrays),
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
        assertion_id: event.payload.assertion_id,
        attempt_count: event.payload.attempt_count,
        failure_count: event.payload.failure_count,
      };

      updateExistingQueryData<Entry[]>(
        queryClient,
        ["entries", event.run_id, event.payload.test_id],
        (entries) => appendEntries(entries, entry, queryClient.mutableArrays),
      );

      if (event.payload.trace_id) {
        const traceSpans = queryClient.getQueryData<Span[]>(["trace", event.payload.trace_id]);
        if (traceSpans?.length) {
          updateExistingQueryData<Span[]>(
            queryClient,
            ["spans", event.run_id, event.payload.test_id],
            (spans) => mergeSpans(spans, traceSpans, queryClient.mutableArrays),
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

      updateExistingQueryData<Span[]>(queryClient, ["trace", event.payload.trace_id], (trace) =>
        appendSpan(trace, span, queryClient.mutableArrays),
      );

      const testId =
        event.payload.test_id ??
        findTestIdForTrace(queryClient, event.run_id, event.payload.trace_id);
      if (testId) {
        updateExistingQueryData<Span[]>(queryClient, ["spans", event.run_id, testId], (spans) =>
          appendSpan(spans, span, queryClient.mutableArrays),
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

      updateExistingQueryData<Snapshot[]>(
        queryClient,
        ["snapshots", event.run_id, event.payload.test_id],
        (snapshots) => appendSnapshots(snapshots, snapshot, queryClient.mutableArrays),
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
        updateExistingQueryData<MockInteraction[]>(
          queryClient,
          ["mock-interactions", event.run_id, event.payload.test_id],
          (interactions) =>
            appendInteractions(interactions, interaction, queryClient.mutableArrays),
        );
      } else {
        updateExistingQueryData<MockInteraction[]>(
          queryClient,
          ["mock-interactions", event.run_id],
          (interactions) =>
            appendInteractions(interactions, interaction, queryClient.mutableArrays),
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
        updateExistingQueryData<MockWarning[]>(
          queryClient,
          ["mock-warnings", event.run_id, event.payload.test_id],
          (warnings) => appendWarnings(warnings, warning, queryClient.mutableArrays),
        );
      } else {
        updateExistingQueryData<MockWarning[]>(
          queryClient,
          ["mock-warnings", event.run_id],
          (warnings) => appendWarnings(warnings, warning, queryClient.mutableArrays),
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
    case "mock-interactions":
      return mergeInteractions(persisted as MockInteraction[], cached as MockInteraction[]) as T;
    case "mock-warnings":
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

function upsertTest(tests: Test[] | undefined, incoming: Test, mutable: boolean): Test[] {
  const result = mutable ? (tests ?? []) : [...(tests ?? [])];
  const existingIndex = result.findIndex((test) => test.id === incoming.id);
  if (existingIndex >= 0) result.splice(existingIndex, 1);
  insertSorted(result, incoming, compareTests);
  return result;
}

function updateRunQueriesForStart(queryClient: LiveCacheClient, incoming: Run) {
  const matchingQueries = queryClient.getQueriesData<Run[]>({
    queryKey: ["runs", incoming.app_name],
  });
  if (matchingQueries.length === 0) {
    return;
  }

  for (const [queryKey, runs] of matchingQueries) {
    const encodedFilter = queryKey[2];
    const metadataFilter =
      typeof encodedFilter === "string"
        ? (JSON.parse(encodedFilter) as Record<string, string>)
        : {};
    const matches = Object.entries(metadataFilter).every(
      ([key, value]) => incoming.metadata[key] === value,
    );
    if (matches) {
      queryClient.setQueryData(
        queryKey,
        [...(runs ?? []).filter((run) => run.id !== incoming.id), incoming].sort(compareRuns),
      );
    }
  }
}

function updateCachedRuns(queryClient: LiveCacheClient, runId: string, updater: (run: Run) => Run) {
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
  queryClient: LiveCacheClient,
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

function appendEntries(entries: Entry[] | undefined, incoming: Entry, mutable: boolean): Entry[] {
  if (incoming.id !== 0 && entries?.some((entry) => entry.id === incoming.id)) {
    return entries;
  }

  const existing = entries ?? [];
  const assertionIndex = existing.findIndex(
    (entry) => entry.assertion_id === incoming.assertion_id,
  );
  if (assertionIndex < 0) {
    const result = mutable ? existing : [...existing];
    insertSorted(result, incoming, (left, right) => left.timestamp.localeCompare(right.timestamp));
    return result;
  }

  const previous = existing[assertionIndex];
  const latest =
    incoming.attempt_count > previous.attempt_count ||
    (incoming.attempt_count === previous.attempt_count && incoming.timestamp > previous.timestamp)
      ? incoming
      : previous;
  const correlated = {
    ...latest,
    id: previous.id,
    attempt_count: Math.max(previous.attempt_count, incoming.attempt_count),
    failure_count: Math.max(previous.failure_count, incoming.failure_count),
  };
  const result = mutable ? existing : [...existing];
  result.splice(assertionIndex, 1);
  insertSorted(result, correlated, (left, right) => left.timestamp.localeCompare(right.timestamp));
  return result;
}

function appendSpan(spans: Span[] | undefined, incoming: Span, mutable: boolean): Span[] {
  if (spans?.some((span) => isSameSpan(span, incoming))) {
    return spans;
  }
  const result = mutable ? (spans ?? []) : [...(spans ?? [])];
  insertSorted(result, incoming, (left, right) => left.start_time_nanos - right.start_time_nanos);
  return result;
}

function mergeSpans(existing: Span[] | undefined, incoming: Span[], mutable: boolean): Span[] {
  return incoming.reduce<Span[]>((acc, span) => appendSpan(acc, span, mutable), existing ?? []);
}

function appendSnapshots(
  snapshots: Snapshot[] | undefined,
  incoming: Snapshot,
  mutable: boolean,
): Snapshot[] {
  if (
    snapshots?.some(
      (snapshot) =>
        sameRecordId(snapshot.id, incoming.id) ||
        ((committedRecordId(snapshot.id) !== undefined) !==
          (committedRecordId(incoming.id) !== undefined) &&
          snapshot.system === incoming.system &&
          snapshot.summary === incoming.summary &&
          snapshot.captured_at === incoming.captured_at &&
          snapshot.trigger === incoming.trigger &&
          snapshot.state_json === incoming.state_json),
    )
  ) {
    return snapshots;
  }
  const result = mutable ? (snapshots ?? []) : [...(snapshots ?? [])];
  result.push(incoming);
  return result;
}

function appendInteractions(
  interactions: MockInteraction[] | undefined,
  incoming: MockInteraction,
  mutable: boolean,
): MockInteraction[] {
  if (interactions?.some((interaction) => interaction.id === incoming.id)) {
    return interactions;
  }
  const result = mutable ? (interactions ?? []) : [...(interactions ?? [])];
  insertSorted(result, incoming, (left, right) => left.timestamp.localeCompare(right.timestamp));
  return result;
}

function appendWarnings(
  warnings: MockWarning[] | undefined,
  incoming: MockWarning,
  mutable: boolean,
): MockWarning[] {
  if (warnings?.some((warning) => warning.id === incoming.id)) {
    return warnings;
  }
  const result = mutable ? (warnings ?? []) : [...(warnings ?? [])];
  insertSorted(result, incoming, (left, right) => left.timestamp.localeCompare(right.timestamp));
  return result;
}

function mergeApps(persisted: AppSummary[], cached: AppSummary[]): AppSummary[] {
  const byName = new Map(persisted.map((app) => [app.app_name, app]));
  for (const live of cached) {
    const stored = byName.get(live.app_name);
    if (
      !stored ||
      compareAppRecency(live, stored) > 0 ||
      (live.latest_run_id === stored.latest_run_id &&
        statusProgress(live.latest_status) > statusProgress(stored.latest_status))
    ) {
      byName.set(live.app_name, live);
    }
  }
  return [...byName.values()].sort((left, right) => left.app_name.localeCompare(right.app_name));
}

function compareAppRecency(left: AppSummary, right: AppSummary): number {
  return (
    left.latest_run_started_at.localeCompare(right.latest_run_started_at) ||
    left.latest_run_id.localeCompare(right.latest_run_id)
  );
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
  const byAssertion = new Map(persisted.map((entry) => [entry.assertion_id, entry]));
  for (const live of cached) {
    const stored = byAssertion.get(live.assertion_id);
    if (!stored) {
      byAssertion.set(live.assertion_id, live);
      continue;
    }

    const latest =
      live.attempt_count > stored.attempt_count ||
      (live.attempt_count === stored.attempt_count && live.timestamp > stored.timestamp)
        ? live
        : stored;
    byAssertion.set(live.assertion_id, {
      ...latest,
      id: stored.id,
      attempt_count: Math.max(stored.attempt_count, live.attempt_count),
      failure_count: Math.max(stored.failure_count, live.failure_count),
    });
  }
  return [...byAssertion.values()].sort((left, right) =>
    left.timestamp.localeCompare(right.timestamp),
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
  queryClient: LiveCacheClient,
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

function insertSorted<T>(items: T[], incoming: T, compare: (left: T, right: T) => number): void {
  let low = 0;
  let high = items.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (compare(items[middle], incoming) <= 0) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  items.splice(low, 0, incoming);
}
