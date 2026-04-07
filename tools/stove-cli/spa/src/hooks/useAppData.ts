import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api } from "../api/client";
import { applyLiveDashboardEvent, invalidateDashboardQueries } from "../api/live-cache";
import { useSSE } from "../api/sse";
import { summarizeVersionMismatches } from "../utils/version-mismatch";

export function useAppData() {
  const queryClient = useQueryClient();
  const [selectedApp, setSelectedApp] = useState<string | null>(null);
  const [selectedTestId, setSelectedTestId] = useState<string | null>(null);

  const { connected: liveConnected } = useSSE({
    onEvent: (event) => applyLiveDashboardEvent(queryClient, event),
    onGap: (event) => invalidateDashboardQueries(queryClient, event.run_id),
    onReconnect: () => invalidateDashboardQueries(queryClient),
  });

  const { data: apps = [] } = useQuery({
    queryKey: ["apps"],
    queryFn: api.getApps,
    refetchInterval: liveConnected ? false : 5000,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: meta } = useQuery({
    queryKey: ["meta"],
    queryFn: api.getMeta,
    staleTime: Number.POSITIVE_INFINITY,
  });

  const activeApp = selectedApp ?? apps[0]?.app_name ?? null;
  const cliVersion = meta?.stove_cli_version ?? null;

  const { data: runs = [] } = useQuery({
    queryKey: ["runs", activeApp],
    queryFn: () => api.getRuns(activeApp!),
    enabled: !!activeApp,
    refetchInterval: !!activeApp && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const latestRun = runs[0] ?? null;

  const { data: tests = [] } = useQuery({
    queryKey: ["tests", latestRun?.id],
    queryFn: () => api.getTests(latestRun!.id),
    enabled: !!latestRun,
    refetchInterval: latestRun?.status === "RUNNING" && !liveConnected ? 5000 : false,
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
