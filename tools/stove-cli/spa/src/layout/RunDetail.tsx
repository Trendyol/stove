import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Run, Test } from "../api/types";
import { RunLogsView } from "../components/RunLogsView";
import { isRunning } from "../utils/status";

interface RunDetailProps {
  run: Run;
  tests: Test[];
  liveConnected: boolean;
}

export function RunDetail({ run, tests, liveConnected }: RunDetailProps) {
  const liveRefetchInterval = isRunning(run.status) && !liveConnected ? 5000 : false;

  const {
    data: logs = [],
    isLoading,
    error,
  } = useQuery({
    queryKey: ["runLogs", run.id],
    queryFn: () => api.getRunLogs(run.id, { limit: 2000 }),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  if (isLoading) {
    return (
      <div className="flex-1 text-[var(--stove-text-secondary)] text-sm p-4">Loading logs...</div>
    );
  }
  if (error) {
    const message = error instanceof Error ? error.message : "Failed to load run logs";
    return <div className="flex-1 text-red-400 text-sm p-4">{message}</div>;
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="min-h-0 flex-1 overflow-y-auto">
        <RunLogsView logs={logs} tests={tests} />
      </div>
    </div>
  );
}
