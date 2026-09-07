import type { QueryClient } from "@tanstack/react-query";

export const adminKeys = {
  root: ["admin"] as const,
  status: ["admin", "status"] as const,
  schema: ["admin", "schema"] as const,
};

const DASHBOARD_ROOTS = new Set([
  "apps",
  "runs",
  "tests",
  "entries",
  "spans",
  "snapshots",
  "mock-interactions",
  "mock-warnings",
  "trace",
]);

/** Destructive operations must discard cached evidence before REST reconciliation. */
export async function refreshDatabaseQueries(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.resetQueries({
      predicate: (query) => DASHBOARD_ROOTS.has(String(query.queryKey[0])),
    }),
    queryClient.invalidateQueries({ queryKey: adminKeys.root }),
  ]);
}
