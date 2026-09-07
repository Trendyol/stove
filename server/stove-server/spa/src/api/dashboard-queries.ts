import { type DataTag, type QueryKey, queryOptions } from "@tanstack/react-query";
import { api } from "./client";
import {
  mergeApps,
  mergeEntries,
  mergeInteractions,
  mergeRuns,
  mergeSnapshotLists,
  mergeSpanLists,
  mergeTests,
  mergeWarnings,
} from "./live-cache/reconciliation";
import { dashboardKeys } from "./query-keys";

/** A key, REST loader, and reconciliation policy always describe the same records. */
export interface DashboardQuery<T> {
  readonly queryKey: DataTag<QueryKey, T[], Error>;
  readonly load: (signal?: AbortSignal) => Promise<T[]>;
  readonly merge: (persisted: readonly T[], cached: readonly T[]) => T[];
}

function dashboardQuery<T>(
  queryKey: QueryKey,
  load: (signal?: AbortSignal) => Promise<T[]>,
  merge: DashboardQuery<T>["merge"],
): DashboardQuery<T> {
  const options = queryOptions({ queryKey, queryFn: ({ signal }) => load(signal) });
  return { queryKey: options.queryKey, load, merge };
}

export const dashboardQueries = {
  apps: () => dashboardQuery(dashboardKeys.apps, api.getApps, mergeApps),
  runs: (app: string) =>
    dashboardQuery(dashboardKeys.runs(app), (signal) => api.getRuns(app, signal), mergeRuns),
  tests: (runId: string) =>
    dashboardQuery(dashboardKeys.tests(runId), (signal) => api.getTests(runId, signal), mergeTests),
  entries: (runId: string, testId: string) =>
    dashboardQuery(
      dashboardKeys.entries(runId, testId),
      (signal) => api.getEntries(runId, testId, signal),
      mergeEntries,
    ),
  spans: (runId: string, testId: string) =>
    dashboardQuery(
      dashboardKeys.spans(runId, testId),
      (signal) => api.getSpans(runId, testId, signal),
      mergeSpanLists,
    ),
  snapshots: (runId: string, testId: string) =>
    dashboardQuery(
      dashboardKeys.snapshots(runId, testId),
      (signal) => api.getSnapshots(runId, testId, signal),
      mergeSnapshotLists,
    ),
  testMockInteractions: (runId: string, testId: string) =>
    dashboardQuery(
      dashboardKeys.testMockInteractions(runId, testId),
      (signal) => api.getTestMockInteractions(runId, testId, signal),
      mergeInteractions,
    ),
  ambientMockInteractions: (runId: string) =>
    dashboardQuery(
      dashboardKeys.ambientMockInteractions(runId),
      (signal) => api.getAmbientMockInteractions(runId, signal),
      mergeInteractions,
    ),
  testMockWarnings: (runId: string, testId: string) =>
    dashboardQuery(
      dashboardKeys.testMockWarnings(runId, testId),
      (signal) => api.getTestMockWarnings(runId, testId, signal),
      mergeWarnings,
    ),
  ambientMockWarnings: (runId: string) =>
    dashboardQuery(
      dashboardKeys.ambientMockWarnings(runId),
      (signal) => api.getAmbientMockWarnings(runId, signal),
      mergeWarnings,
    ),
  trace: (traceId: string) =>
    dashboardQuery(
      dashboardKeys.trace(traceId),
      (signal) => api.getTrace(traceId, signal),
      mergeSpanLists,
    ),
};
