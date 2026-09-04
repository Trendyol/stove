import { type QueryClient, skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo } from "react";
import { api } from "../api/client";
import {
  applyLiveDashboardEvents,
  invalidateDashboardQueries,
  loadAndReconcileDashboardData,
} from "../api/live-cache";
import { dashboardKeys } from "../api/query-keys";
import { useSSE } from "../api/sse";
import type { LiveDashboardEvent, Run, Test } from "../api/types";
import { filterRunsByMetadata } from "../utils/metadata-filter";
import { isRunning } from "../utils/status";
import { summarizeVersionMismatches } from "../utils/version-mismatch";
import { useDashboardSelection } from "./useDashboardSelection";

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

  const { data: apps = [] } = useQuery({
    queryKey: dashboardKeys.apps,
    queryFn: ({ signal }) =>
      loadAndReconcileDashboardData(queryClient, dashboardKeys.apps, () => api.getApps(signal)),
    refetchInterval: liveConnected ? false : 5000,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: meta } = useQuery({
    queryKey: dashboardKeys.meta,
    queryFn: ({ signal }) => api.getMeta(signal),
    staleTime: Number.POSITIVE_INFINITY,
  });

  const activeApp = selectedApp ?? apps[0]?.app_name;
  const cliVersion = meta?.stove_cli_version ?? null;
  const allRuns = useRunsQuery(queryClient, activeApp, liveConnected);
  const runs = useMemo(
    () => filterRunsByMetadata(allRuns, metadataFilter),
    [allRuns, metadataFilter],
  );

  const latestRun = runs.find((run) => run.id === selectedRunId) ?? runs[0];

  const testsQueryKey = latestRun ? dashboardKeys.tests(latestRun.id) : dashboardKeys.testsRoot;
  const testsQueryFn = latestRun
    ? ({ signal }: { signal: AbortSignal }) =>
        loadAndReconcileDashboardData(queryClient, testsQueryKey, () =>
          api.getTests(latestRun.id, signal),
        )
    : skipToken;
  const { data: tests = [] } = useQuery({
    queryKey: testsQueryKey,
    queryFn: testsQueryFn,
    refetchInterval: (query) => {
      if (!latestRun || liveConnected) return false;
      const cachedTests = query.state.data as Test[] | undefined;
      return isRunning(latestRun.status) || cachedTests?.length !== latestRun.total_tests
        ? 5000
        : false;
    },
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  useEffect(() => {
    if (selectedApp && !apps.some((app) => app.app_name === selectedApp)) {
      selection.clearApp();
    }
  }, [apps, selectedApp, selection.clearApp]);

  useEffect(() => {
    if (selectedRunId && !runs.some((run) => run.id === selectedRunId)) {
      selection.clearRun();
    }
  }, [runs, selectedRunId, selection.clearRun]);

  useEffect(() => {
    if (selectedTestId && !tests.some((test) => test.id === selectedTestId)) {
      selection.clearTest();
    }
  }, [selectedTestId, selection.clearTest, tests]);

  const selectedTest = tests.find((test) => test.id === selectedTestId) ?? tests[0];
  const versionMismatchSummary = summarizeVersionMismatches(apps, cliVersion, activeApp);
  const mismatchedApps = versionMismatchSummary?.affectedAppNames ?? [];

  return {
    apps,
    activeApp,
    cliVersion,
    latestRun,
    runs,
    allRuns,
    selectedRunId: latestRun?.id,
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
): Run[] {
  const queryKey = appName ? dashboardKeys.runs(appName) : dashboardKeys.runsRoot;
  const queryFn = appName
    ? ({ signal }: { signal: AbortSignal }) =>
        loadAndReconcileDashboardData(queryClient, queryKey, () => api.getRuns(appName, {}, signal))
    : skipToken;
  const { data = [] } = useQuery({
    queryKey,
    queryFn,
    refetchInterval: appName && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });
  return data;
}
