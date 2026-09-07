import { QueryClient } from "@tanstack/react-query";
import { dashboardQueries, type DashboardQuery } from "../src/api/dashboard-queries";
import { reconcileDashboardData } from "../src/api/live-cache/reconciliation";
import type { Entry, Run, Test } from "../src/api/types";

declare const client: QueryClient;
declare const runs: Run[];
declare const tests: Test[];
const query = dashboardQueries.tests("run-1");
reconcileDashboardData(client, query, tests);
// @ts-expect-error Run responses cannot be reconciled into test queries.
reconcileDashboardData(client, query, runs);
// @ts-expect-error The tagged key rejects records of another type.
client.setQueryData(query.queryKey, runs);
const invalid: DashboardQuery<Entry> = {
  ...dashboardQueries.entries("run-1", "test-1"),
  // @ts-expect-error A loader cannot change the descriptor's record type.
  load: async () => runs,
};
void invalid;
