import { QueryClient } from "@tanstack/react-query";
import { dashboardQueries, type DashboardQuery } from "../src/api/dashboard-queries";
import { reconcileDashboardData } from "../src/api/live-cache/reconciliation";
import type { Entry, Run } from "../src/api/types";

declare const client: QueryClient;
declare const runs: Run[];
declare const entries: Entry[];
const query = dashboardQueries.entries("run-1", "test-1");
reconcileDashboardData(client, query, entries);
// @ts-expect-error Run responses cannot be reconciled into entry queries.
reconcileDashboardData(client, query, runs);
// @ts-expect-error The tagged key rejects records of another type.
client.setQueryData(query.queryKey, runs);
const invalid: DashboardQuery<Entry> = {
  ...dashboardQueries.entries("run-1", "test-1"),
  // @ts-expect-error A loader cannot change the descriptor's record type.
  load: async () => runs,
};
void invalid;

import { testPagesOptions, type TestPages } from "../src/api/test-queries";
declare const pages: TestPages;
const pageQuery = testPagesOptions("run-1");
client.setQueryData(pageQuery.queryKey, pages);
// @ts-expect-error Test list queries require pages, never flat arrays.
client.setQueryData(pageQuery.queryKey, entries);
