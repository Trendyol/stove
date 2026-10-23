import type { QueryClient } from "@tanstack/react-query";
import { dashboardKeys, isDashboardDataQuery } from "../../api/query-keys";

export const adminKeys = {
  root: ["admin"] as const,
  status: ["admin", "status"] as const,
  schema: ["admin", "schema"] as const,
};

/** Destructive operations must discard cached evidence before REST reconciliation. */
export async function refreshDatabaseQueries(queryClient: QueryClient): Promise<void> {
  await Promise.all([
    queryClient.resetQueries({
      predicate: isDashboardDataQuery,
    }),
    queryClient.invalidateQueries({ queryKey: adminKeys.root }),
    // Refresh catalog membership without dropping the user's current browsing scope.
    queryClient.invalidateQueries({ queryKey: dashboardKeys.topFilter }),
  ]);
}
