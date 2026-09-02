import { type QueryClient, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo } from "react";
import { api } from "../api/client";
import {
  applyLiveDashboardEvent,
  invalidateDashboardQueries,
  reconcileDashboardData,
} from "../api/live-cache";
import { useSSE } from "../api/sse";
import { EVENT_TYPE, type LiveDashboardEvent, type Run, type Test } from "../api/types";
import { isRunning } from "../utils/status";
import { summarizeVersionMismatches } from "../utils/version-mismatch";
import { useDashboardSelection } from "./useDashboardSelection";

export function useAppData() {
  const queryClient = useQueryClient();
  const selection = useDashboardSelection();
  const { selectedApp, selectedRunId, selectedTestId, metadataFilter } = selection;

  const handleLiveEvent = useCallback(
    (event: LiveDashboardEvent) => {
      applyLiveDashboardEvent(queryClient, event);
      if (event.event_type === EVENT_TYPE.RUN_STARTED) {
        selection.selectApp(event.payload.app_name);
      }
    },
    [queryClient, selection.selectApp],
  );

  const { connected: liveConnected } = useSSE({
    onEvent: handleLiveEvent,
    onGap: (event) => invalidateDashboardQueries(queryClient, event.run_id),
    onConnect: () => invalidateDashboardQueries(queryClient),
  });

  const { data: apps = [] } = useQuery({
    queryKey: ["apps"],
    queryFn: async ({ signal }) =>
      reconcileDashboardData(queryClient, ["apps"], await api.getApps(signal)),
    refetchInterval: liveConnected ? false : 5000,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: meta } = useQuery({
    queryKey: ["meta"],
    queryFn: ({ signal }) => api.getMeta(signal),
    staleTime: Number.POSITIVE_INFINITY,
  });

  const activeApp = selectedApp ?? apps[0]?.app_name ?? null;
  const cliVersion = meta?.stove_cli_version ?? null;
  const metadataFilterKey = useMemo(() => JSON.stringify(metadataFilter), [metadataFilter]);
  const hasMetadataFilter = metadataFilterKey !== "{}";
  const allRunsQueryKey = ["runs", activeApp] as const;

  const allRuns = useRunsQuery(
    queryClient,
    allRunsQueryKey,
    activeApp,
    {},
    !!activeApp,
    liveConnected,
  );

  const filteredRunsQueryKey = ["runs", activeApp, metadataFilterKey] as const;
  const filteredRuns = useRunsQuery(
    queryClient,
    filteredRunsQueryKey,
    activeApp,
    metadataFilter,
    !!activeApp && hasMetadataFilter,
    liveConnected,
  );

  const runs = hasMetadataFilter ? filteredRuns : allRuns;

  const latestRun = runs.find((run) => run.id === selectedRunId) ?? runs[0] ?? null;

  const { data: tests = [] } = useQuery({
    queryKey: ["tests", latestRun?.id],
    queryFn: async ({ signal }) =>
      reconcileDashboardData(
        queryClient,
        ["tests", latestRun?.id],
        await api.getTests(latestRun!.id, signal),
      ),
    enabled: !!latestRun,
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
      selection.selectTest(tests[0]?.id ?? null);
    }
  }, [selectedTestId, selection.selectTest, tests]);

  const selectedTest = tests.find((test) => test.id === selectedTestId) ?? tests[0] ?? null;
  const versionMismatchSummary = summarizeVersionMismatches(apps, cliVersion, activeApp);
  const mismatchedApps = versionMismatchSummary?.affectedAppNames ?? [];

  return {
    apps,
    activeApp,
    cliVersion,
    latestRun,
    runs,
    allRuns,
    selectedRunId: latestRun?.id ?? null,
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
  queryKey: readonly unknown[],
  appName: string | null,
  metadata: Record<string, string>,
  enabled: boolean,
  liveConnected: boolean,
): Run[] {
  const { data = [] } = useQuery({
    queryKey,
    queryFn: async ({ signal }) =>
      reconcileDashboardData(queryClient, queryKey, await api.getRuns(appName!, metadata, signal)),
    enabled,
    refetchInterval: enabled && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });
  return data;
}
