import type { QueryClient } from "@tanstack/react-query";
import type { Status } from "../../utils/status";
import type { DashboardQuery } from "../dashboard-queries";
import type {
  AppSummary,
  Entry,
  MockInteraction,
  MockWarning,
  Run,
  Snapshot,
  Span,
  Test,
} from "../types";
import { compareRuns, compareTests, mergeEntryAttempts } from "./records";

export async function loadAndReconcileDashboardData<T>(
  queryClient: QueryClient,
  query: DashboardQuery<T>,
  signal?: AbortSignal,
): Promise<T[]> {
  return reconcileDashboardData(queryClient, query, await query.load(signal));
}

/** Preserve live events committed after the REST request's database read. */
export function reconcileDashboardData<T>(
  queryClient: QueryClient,
  query: DashboardQuery<T>,
  persisted: NoInfer<T>[],
): T[] {
  const cached = queryClient.getQueryData(query.queryKey);
  return cached?.length ? query.merge(persisted, cached) : persisted;
}

export function mergeApps(
  persisted: readonly AppSummary[],
  cached: readonly AppSummary[],
): AppSummary[] {
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

export function mergeRuns(persisted: readonly Run[], cached: readonly Run[]): Run[] {
  const byId = new Map(persisted.map((run) => [run.id, run]));
  for (const live of cached) {
    const stored = byId.get(live.id);
    if (!stored || statusProgress(live.status) > statusProgress(stored.status)) {
      byId.set(live.id, live);
    }
  }
  return [...byId.values()].sort(compareRuns);
}

export function mergeTests(persisted: readonly Test[], cached: readonly Test[]): Test[] {
  const byId = new Map(persisted.map((test) => [test.id, test]));
  for (const live of cached) {
    const stored = byId.get(live.id);
    if (!stored || statusProgress(live.status) > statusProgress(stored.status)) {
      byId.set(live.id, live);
    }
  }
  return [...byId.values()].sort(compareTests);
}

export function mergeEntries(persisted: readonly Entry[], cached: readonly Entry[]): Entry[] {
  const byAssertion = new Map(persisted.map((entry) => [entry.assertion_id, entry]));
  for (const live of cached) {
    const stored = byAssertion.get(live.assertion_id);
    if (!stored) {
      byAssertion.set(live.assertion_id, live);
      continue;
    }

    byAssertion.set(live.assertion_id, mergeEntryAttempts(stored, live));
  }
  return [...byAssertion.values()].sort((left, right) =>
    left.timestamp.localeCompare(right.timestamp),
  );
}

export function mergeSpanLists(persisted: readonly Span[], cached: readonly Span[]): Span[] {
  return mergeEvidenceRecords(
    persisted,
    cached,
    (span) => `${span.trace_id}\u0000${span.span_id}`,
    (left, right) => left.start_time_nanos - right.start_time_nanos,
  );
}

export function mergeSnapshotLists(
  persisted: readonly Snapshot[],
  cached: readonly Snapshot[],
): Snapshot[] {
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

export function mergeInteractions(
  persisted: readonly MockInteraction[],
  cached: readonly MockInteraction[],
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

export function mergeWarnings(
  persisted: readonly MockWarning[],
  cached: readonly MockWarning[],
): MockWarning[] {
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
  persisted: readonly T[],
  cached: readonly T[],
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
  return status === "RUNNING" ? 0 : 1;
}
