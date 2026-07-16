import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";
import {
  applyLiveDashboardEvent,
  invalidateDashboardQueries,
  reconcileDashboardData,
} from "../api/live-cache";
import { useSSE } from "../api/sse";
import { EVENT_TYPE, type LiveDashboardEvent, type Test } from "../api/types";
import { isRunning } from "../utils/status";
import { summarizeVersionMismatches } from "../utils/version-mismatch";

export function useAppData() {
  const queryClient = useQueryClient();
  const [selectedApp, setSelectedApp] = useState<string | null>(null);
  const [selectedTestId, setSelectedTestId] = useState<string | null>(null);

  const handleLiveEvent = useCallback(
    (event: LiveDashboardEvent) => {
      applyLiveDashboardEvent(queryClient, event);
      if (event.event_type === EVENT_TYPE.RUN_STARTED) {
        setSelectedApp(event.payload.app_name);
        setSelectedTestId(null);
      }
    },
    [queryClient],
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

  const { data: runs = [] } = useQuery({
    queryKey: ["runs", activeApp],
    queryFn: async ({ signal }) =>
      reconcileDashboardData(
        queryClient,
        ["runs", activeApp],
        await api.getRuns(activeApp!, signal),
      ),
    enabled: !!activeApp,
    refetchInterval: activeApp && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const latestRun = runs[0] ?? null;

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
      setSelectedApp(null);
      setSelectedTestId(null);
    }
  }, [apps, selectedApp]);

  useEffect(() => {
    if (selectedTestId && !tests.some((test) => test.id === selectedTestId)) {
      setSelectedTestId(tests[0]?.id ?? null);
    }
  }, [selectedTestId, tests]);

  const selectedTest = tests.find((test) => test.id === selectedTestId) ?? tests[0] ?? null;
  const versionMismatchSummary = summarizeVersionMismatches(apps, cliVersion, activeApp);
  const mismatchedApps = versionMismatchSummary?.affectedAppNames ?? [];

  return {
    apps,
    activeApp,
    cliVersion,
    latestRun,
    tests,
    selectedTest,
    liveConnected,
    mismatchedApps,
    versionMismatchSummary,
    selectApp: (name: string) => {
      setSelectedApp(name);
      setSelectedTestId(null);
    },
    selectTest: setSelectedTestId,
  };
}
