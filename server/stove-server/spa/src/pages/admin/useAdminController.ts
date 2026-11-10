import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api } from "../../api/client";
import type { RetentionPolicy } from "../../api/types";
import { DEFAULT_IDLE_TIMEOUT_SECONDS, isIdleTimeout } from "../../utils/idle-timeout";
import type { MetadataFilter } from "../../utils/metadata-filter";
import { defaultRetention } from "../../utils/retention";
import { adminKeys, refreshDatabaseQueries } from "./admin-queries";
import {
  createRetentionDraft,
  type RetentionDraft,
  retentionPolicyFromDraft,
} from "./retention-draft";
import { useAdminMetadata } from "./useAdminMetadata";

type AdminCommand =
  | { kind: "idle-timeout"; seconds: number }
  | { kind: "retention"; policy: RetentionPolicy; draft: RetentionDraft }
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
  const [idleTimeoutDraft, setIdleTimeout] = useState<number>();
  const [retentionDraft, setRetention] = useState<RetentionDraft>();
  const [filters, setFilters] = useState<PurgeFilters>({
    appName: "",
    olderThan: "",
    includeRunning: false,
    metadata: {},
  });
  const metadataFacetsQuery = useAdminMetadata(filters.appName);
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
      if (submitted.kind === "idle-timeout") {
        setIdleTimeout((draft) => (draft === submitted.seconds ? undefined : draft));
      }
      if (submitted.kind === "retention") {
        setRetention((draft) => (draft === submitted.draft ? undefined : draft));
      }
    },
  });
  const idleTimeout =
    idleTimeoutDraft ?? statusQuery.data?.run_idle_timeout_seconds ?? DEFAULT_IDLE_TIMEOUT_SECONDS;
  const idleTimeoutValid = isIdleTimeout(idleTimeout);
  const savedRetention = statusQuery.data?.retention ?? defaultRetention;
  const savedDraft = useMemo(() => createRetentionDraft(savedRetention), [savedRetention]);
  const retention = retentionDraft ?? savedDraft;
  const retentionPolicy = retentionPolicyFromDraft(retention);
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
    idleTimeout,
    idleTimeoutValid,
    setIdleTimeout,
    updateIdleTimeout: () => {
      if (idleTimeoutValid) command.mutate({ kind: "idle-timeout", seconds: idleTimeout });
    },
    retention,
    retentionValid: retentionPolicy !== null,
    setRetention,
    ...filters,
    setAppName: (appName: string) => updateFilter({ appName, metadata: {} }),
    setMetadata: (metadata: MetadataFilter) => updateFilter({ metadata }),
    metadataFacets: metadataFacetsQuery.data ?? [],
    metadataLoading: Boolean(filters.appName) && metadataFacetsQuery.isPending,
    metadataError: metadataFacetsQuery.error?.message ?? null,
    retryMetadata: () => void metadataFacetsQuery.refetch(),
    setOlderThan: (olderThan: string) => updateFilter({ olderThan }),
    setIncludeRunning: (includeRunning: boolean) => updateFilter({ includeRunning }),
    preview,
    busy: command.isPending || previewMutation.isPending || statusQuery.isPending,
    error: (command.error ?? previewMutation.error ?? statusQuery.error)?.message ?? null,
    retry: statusQuery.error ? () => void statusQuery.refetch() : undefined,
    updateRetention: () => {
      if (retentionPolicy)
        command.mutate({ kind: "retention", policy: retentionPolicy, draft: retention });
    },
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
    case "idle-timeout":
      await api.updateIdleTimeout(command.seconds);
      return;
    case "retention":
      await api.updateRetention(command.policy);
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
