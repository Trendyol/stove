import { type QueryClient, skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo } from "react";
import { api } from "../api/client";
import { dashboardQueries } from "../api/dashboard-queries";
import {
  applyLiveDashboardEvents,
  invalidateDashboardQueries,
  loadAndReconcileDashboardData,
} from "../api/live-cache";
import { dashboardKeys } from "../api/query-keys";
import { useSSE } from "../api/sse";
import type { LiveDashboardEvent, Run } from "../api/types";
import { filterRunsByMetadata } from "../utils/metadata-filter";
import { isRunning } from "../utils/status";
import { summarizeVersionMismatches } from "../utils/version-mismatch";
import { useDashboardSelection } from "./useDashboardSelection";

const EMPTY_LIST: never[] = [];

export function useAppData() {
  const queryClient = useQueryClient();
  const selection = useDashboardSelection();
  const { selectedApp, selectedRunId, selectedTestId, metadataFilter } = selection;

  const handleLiveEvents = useCallback(
    (events: readonly LiveDashboardEvent[]) => applyLiveDashboardEvents(queryClient, events),
    [queryClient],
  );

  const { connected: liveConnected } = useSSE({
    onEvents: handleLiveEvents,
    onGap: (event) => invalidateDashboardQueries(queryClient, event.run_id),
    onOverflow: () => invalidateDashboardQueries(queryClient),
    onConnect: () => invalidateDashboardQueries(queryClient),
  });

  const appsQuery = useQuery({
    queryKey: dashboardKeys.apps,
    queryFn: ({ signal }) =>
      loadAndReconcileDashboardData(queryClient, dashboardQueries.apps(), signal),
    refetchInterval: liveConnected ? false : 5000,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const apps = appsQuery.data ?? EMPTY_LIST;
  const metaQuery = useQuery({
    queryKey: dashboardKeys.meta,
    queryFn: ({ signal }) => api.getMeta(signal),
    staleTime: Number.POSITIVE_INFINITY,
  });

  const activeApp = selectedApp ?? apps[0]?.app_name;
  const serverVersion = metaQuery.data?.stove_server_version ?? null;
  const runsQuery = useRunsQuery(queryClient, activeApp, liveConnected);
  const allRuns = runsQuery.data ?? EMPTY_LIST;
  const runs = useMemo(
    () => filterRunsByMetadata(allRuns, metadataFilter),
    [allRuns, metadataFilter],
  );

  const appsSettled = appsQuery.isSuccess && !appsQuery.isFetching;
  const runsSettled = appsSettled && runsQuery.isSuccess && !runsQuery.isFetching;
  const latestRun =
    runs.find((run) => run.id === selectedRunId) ??
    (!selectedRunId || runsSettled ? runs[0] : undefined);

  const testsQueryKey = latestRun ? dashboardKeys.tests(latestRun.id) : dashboardKeys.testsRoot;
  const testsQueryFn = latestRun
    ? ({ signal }: { signal: AbortSignal }) =>
        loadAndReconcileDashboardData(queryClient, dashboardQueries.tests(latestRun.id), signal)
    : skipToken;
  const testsQuery = useQuery({
    queryKey: testsQueryKey,
    queryFn: testsQueryFn,
    refetchInterval: (query) => {
      if (!latestRun || liveConnected) return false;
      const cachedTests = query.state.data;
      return isRunning(latestRun.status) || cachedTests?.length !== latestRun.total_tests
        ? 5000
        : false;
    },
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const tests = testsQuery.data ?? EMPTY_LIST;
  const testsSettled = runsSettled && testsQuery.isSuccess && !testsQuery.isFetching;

  useEffect(() => {
    if (appsSettled && selectedApp && !apps.some((app) => app.app_name === selectedApp)) {
      selection.clearApp();
    }
  }, [appsSettled, apps, selectedApp, selection.clearApp]);

  useEffect(() => {
    if (runsSettled && selectedRunId && !runs.some((run) => run.id === selectedRunId)) {
      selection.clearRun();
    }
  }, [runsSettled, runs, selectedRunId, selection.clearRun]);

  useEffect(() => {
    if (testsSettled && selectedTestId && !tests.some((test) => test.id === selectedTestId)) {
      selection.clearTest();
    }
  }, [testsSettled, selectedTestId, selection.clearTest, tests]);

  const selectedTest =
    tests.find((test) => test.id === selectedTestId) ??
    (!selectedTestId || testsSettled ? tests[0] : undefined);
  const versionMismatchSummary = summarizeVersionMismatches(apps, serverVersion, activeApp);
  const mismatchedApps = versionMismatchSummary?.affectedAppNames ?? [];

  return {
    error: appsQuery.error ?? runsQuery.error ?? testsQuery.error ?? metaQuery.error,
    loading:
      appsQuery.isPending ||
      (Boolean(activeApp) && runsQuery.isPending) ||
      (Boolean(latestRun) && testsQuery.isPending),
    retry: () =>
      queryClient.refetchQueries({
        predicate: (query) => ["apps", "runs", "tests", "meta"].includes(String(query.queryKey[0])),
        type: "active",
      }),
    apps,
    activeApp,
    serverVersion,
    latestRun,
    runs,
    allRuns,
    selectedRunId: selectedRunId ?? latestRun?.id,
    metadataFilter,
    tests,
    selectedTest,
    liveConnected,
    mismatchedApps,
    versionMismatchSummary,
    selectApp: selection.selectApp,
    selectRun: selection.selectRun,
    filterRunsByMetadata: selection.filterRunsByMetadata,
    selectTest: selection.selectTest,
  };
}

function useRunsQuery(
  queryClient: QueryClient,
  appName: string | undefined,
  liveConnected: boolean,
) {
  const queryKey = appName ? dashboardKeys.runs(appName) : dashboardKeys.runsRoot;
  const queryFn = appName
    ? ({ signal }: { signal: AbortSignal }) =>
        loadAndReconcileDashboardData(queryClient, dashboardQueries.runs(appName), signal)
    : skipToken;
  return useQuery<Run[]>({
    queryKey,
    queryFn,
    refetchInterval: appName && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });
}
