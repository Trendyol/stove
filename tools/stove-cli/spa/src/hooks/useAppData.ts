import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api/client";
import { useSSE } from "../api/sse";

export function useAppData() {
  const queryClient = useQueryClient();
  const [selectedApp, setSelectedApp] = useState<string | null>(null);
  const [selectedTestId, setSelectedTestId] = useState<string | null>(null);

  const { data: apps = [] } = useQuery({
    queryKey: ["apps"],
    queryFn: api.getApps,
  });

  const activeApp = selectedApp ?? apps[0]?.app_name ?? null;

  const { data: runs = [] } = useQuery({
    queryKey: ["runs", activeApp],
    queryFn: () => api.getRuns(activeApp!),
    enabled: !!activeApp,
    refetchInterval: 3000,
  });

  const latestRun = runs[0] ?? null;

  const { data: tests = [] } = useQuery({
    queryKey: ["tests", latestRun?.id],
    queryFn: () => api.getTests(latestRun!.id),
    enabled: !!latestRun,
    refetchInterval: latestRun?.status === "RUNNING" ? 2000 : false,
  });

  const selectedTest = tests.find((t) => t.id === selectedTestId) ?? tests[0] ?? null;

  useSSE((event) => {
    queryClient.invalidateQueries({ queryKey: ["apps"] });
    queryClient.invalidateQueries({ queryKey: ["runs"] });
    // Use event.run_id for prefix-based invalidation — avoids stale closure
    // issues and ensures all queries for this run are refreshed
    queryClient.invalidateQueries({ queryKey: ["tests", event.run_id] });
    queryClient.invalidateQueries({ queryKey: ["entries", event.run_id] });
    queryClient.invalidateQueries({ queryKey: ["spans", event.run_id] });
    queryClient.invalidateQueries({ queryKey: ["snapshots", event.run_id] });
  });

  return {
    apps,
    activeApp,
    latestRun,
    tests,
    selectedTest,
    selectApp: (name: string) => {
      setSelectedApp(name);
      setSelectedTestId(null);
    },
    selectTest: setSelectedTestId,
  };
}
