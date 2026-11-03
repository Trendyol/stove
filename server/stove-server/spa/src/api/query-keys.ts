import type { RunListFilter } from "./run-queries";

export const dashboardKeys = {
  apps: ["apps"] as const,
  meta: ["meta"] as const,
  topFilter: ["top-filter"] as const,
  runsRoot: ["runs"] as const,
  runs: (filter: RunListFilter) => ["runs", filter] as const,
  run: (id: string | undefined) => ["run", id] as const,
  runMetadata: (app: string | undefined, scope: Record<string, string>) =>
    ["run-metadata", app, scope] as const,
  testsRoot: ["tests"] as const,
  tests: (runId: string) => ["tests", runId] as const,
  entries: (runId: string, testId: string) => ["entries", runId, testId] as const,
  spans: (runId: string, testId: string) => ["spans", runId, testId] as const,
  snapshots: (runId: string, testId: string) => ["snapshots", runId, testId] as const,
  testMockInteractions: (runId: string, testId: string) =>
    ["mock-interactions", runId, testId] as const,
  ambientMockInteractions: (runId: string) => ["mock-interactions", runId] as const,
  testMockWarnings: (runId: string, testId: string) => ["mock-warnings", runId, testId] as const,
  ambientMockWarnings: (runId: string) => ["mock-warnings", runId] as const,
  trace: (traceId: string) => ["trace", traceId] as const,
};

const DASHBOARD_ROOTS = new Set([
  "linked",
  "focus",
  "apps",
  "runs",
  "run",
  "run-metadata",
  "tests",
  "test",
  "entries",
  "spans",
  "snapshots",
  "mock-interactions",
  "mock-warnings",
  "trace",
]);

export function isDashboardDataQuery({ queryKey }: { queryKey: readonly unknown[] }): boolean {
  return DASHBOARD_ROOTS.has(String(queryKey[0]));
}
