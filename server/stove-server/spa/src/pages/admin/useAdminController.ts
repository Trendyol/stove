import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api/client";
import type { MetadataFilter } from "../../utils/metadata-filter";
import { adminKeys, refreshDatabaseQueries } from "./admin-queries";

type AdminCommand =
  | { kind: "retention"; runsPerApp: number }
  | { kind: "purge"; runIds: string[]; includeRunning: boolean }
  | { kind: "clear" };

interface PurgeFilters {
  appName: string;
  olderThan: string;
  includeRunning: boolean;
  metadata: MetadataFilter;
}

export function useAdminController() {
  const queryClient = useQueryClient();
  const statusQuery = useQuery({
    queryKey: adminKeys.status,
    queryFn: ({ signal }) => api.getAdminStatus(signal),
  });
  const [retentionDraft, setRetention] = useState<number>();
  const [filters, setFilters] = useState<PurgeFilters>({
    appName: "",
    olderThan: "",
    includeRunning: false,
    metadata: {},
  });
  const metadataRunsQuery = useQuery({
    queryKey: [...adminKeys.root, "purge-runs", filters.appName],
    queryFn: ({ signal }) => api.getRuns(filters.appName, signal),
    enabled: Boolean(filters.appName),
  });
  const previewMutation = useMutation({ mutationFn: previewPurge });
  const refresh = () => {
    previewMutation.reset();
    return refreshDatabaseQueries(queryClient);
  };
  const command = useMutation({
    mutationFn: executeCommand,
    onMutate: () => previewMutation.reset(),
    onSuccess: async (_, submitted) => {
      await refreshDatabaseQueries(queryClient);
      if (submitted.kind === "retention") {
        setRetention((draft) =>
          draft !== undefined && normalizeRetention(draft) === submitted.runsPerApp
            ? undefined
            : draft,
        );
      }
    },
  });
  const retention = retentionDraft ?? statusQuery.data?.retention_runs_per_app ?? 1;
  const preview = previewMutation.data ?? null;

  const updateFilter = (patch: Partial<PurgeFilters>) => {
    setFilters((current) => ({ ...current, ...patch }));
    // Detach the old preview so a late response cannot restore obsolete run ids.
    previewMutation.reset();
  };
  const purge = () => {
    if (
      !preview?.run_count ||
      !confirm(`Purge ${preview.run_count} previewed run(s)? This cannot be undone.`)
    )
      return;
    command.mutate({
      kind: "purge",
      runIds: preview.run_ids,
      includeRunning: filters.includeRunning,
    });
  };
  const clearAll = () => {
    if (confirm("Clear all stored data? This cannot be undone.")) command.mutate({ kind: "clear" });
  };

  return {
    status: statusQuery.data ?? null,
    retention,
    setRetention,
    ...filters,
    setAppName: (appName: string) => updateFilter({ appName, metadata: {} }),
    setMetadata: (metadata: MetadataFilter) => updateFilter({ metadata }),
    metadataRuns: metadataRunsQuery.data ?? [],
    metadataLoading: Boolean(filters.appName) && metadataRunsQuery.isPending,
    metadataError: metadataRunsQuery.error?.message ?? null,
    retryMetadata: () => void metadataRunsQuery.refetch(),
    setOlderThan: (olderThan: string) => updateFilter({ olderThan }),
    setIncludeRunning: (includeRunning: boolean) => updateFilter({ includeRunning }),
    preview,
    busy: command.isPending || previewMutation.isPending || statusQuery.isPending,
    error: (command.error ?? previewMutation.error ?? statusQuery.error)?.message ?? null,
    updateRetention: () =>
      command.mutate({ kind: "retention", runsPerApp: normalizeRetention(retention) }),
    previewPurge: () => {
      command.reset();
      previewMutation.mutate(filters);
    },
    purge,
    clearAll,
    refresh,
  };
}

async function executeCommand(command: AdminCommand): Promise<void> {
  switch (command.kind) {
    case "retention":
      await api.updateRetention(command.runsPerApp);
      return;
    case "purge":
      await api.purgeRuns(command.runIds, command.includeRunning);
      return;
    case "clear":
      await api.clearAll();
      return;
  }
}

function previewPurge({ appName, olderThan, includeRunning, metadata }: PurgeFilters) {
  return api.previewPurge({
    ...(appName ? { app_name: appName } : {}),
    ...(olderThan ? { older_than: new Date(olderThan).toISOString() } : {}),
    include_running: includeRunning,
    ...(appName && Object.keys(metadata).length > 0
      ? {
          metadata: Object.fromEntries(
            Object.entries(metadata).map(([key, values]) => [key, [...values]]),
          ),
        }
      : {}),
  });
}

function normalizeRetention(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 1;
}
