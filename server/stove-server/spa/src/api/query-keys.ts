export const dashboardKeys = {
  apps: ["apps"] as const,
  meta: ["meta"] as const,
  runsRoot: ["runs"] as const,
  runs: (appName: string) => ["runs", appName] as const,
  filteredRuns: (appName: string, metadataKey: string) => ["runs", appName, metadataKey] as const,
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
