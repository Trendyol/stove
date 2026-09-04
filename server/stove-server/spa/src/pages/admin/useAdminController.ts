import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api } from "../../api/client";
import type { AdminStatus, PurgePreview } from "../../api/types";

export function useAdminController() {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AdminStatus | null>(null);
  const [retention, setRetention] = useState(1);
  const [appName, setAppName] = useState("");
  const [olderThan, setOlderThan] = useState("");
  const [includeRunning, setIncludeRunning] = useState(false);
  const [preview, setPreview] = useState<PurgePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void loadStatus(controller.signal)
      .then((next) => {
        setStatus(next);
        setRetention(next.retention_runs_per_app);
      })
      .catch((reason: unknown) => {
        if (!controller.signal.aborted) setError(errorMessage(reason));
      });
    return () => controller.abort();
  }, []);

  const runAction = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setBusy(false);
    }
  };

  const refresh = async () => {
    setStatus(await loadStatus());
    await queryClient.resetQueries();
  };

  const updateRetention = () =>
    runAction(async () => {
      setStatus(await api.updateRetention(normalizeRetention(retention)));
      setPreview(null);
      await queryClient.resetQueries();
    });

  const previewPurge = () =>
    runAction(async () => {
      setPreview(
        await api.previewPurge({
          ...(appName ? { app_name: appName } : {}),
          ...(olderThan ? { older_than: new Date(olderThan).toISOString() } : {}),
          include_running: includeRunning,
        }),
      );
    });

  const purge = () => {
    if (!preview?.run_count) return;
    if (!confirm(`Purge ${preview.run_count} previewed run(s)? This cannot be undone.`)) return;
    void runAction(async () => {
      await api.purgeRuns(preview.run_ids, includeRunning);
      setPreview(null);
      await refresh();
    });
  };

  const clearAll = () => {
    if (!confirm("Clear all stored data? This cannot be undone.")) return;
    void runAction(async () => {
      await api.clearAll();
      setPreview(null);
      await refresh();
    });
  };

  const updatePurgeFilter = <T>(setter: (value: T) => void, value: T) => {
    setter(value);
    setPreview(null);
  };

  return {
    status,
    retention,
    setRetention,
    appName,
    setAppName: (value: string) => updatePurgeFilter(setAppName, value),
    olderThan,
    setOlderThan: (value: string) => updatePurgeFilter(setOlderThan, value),
    includeRunning,
    setIncludeRunning: (value: boolean) => updatePurgeFilter(setIncludeRunning, value),
    preview,
    busy,
    error,
    updateRetention,
    previewPurge,
    purge,
    clearAll,
    refresh,
  };
}

function loadStatus(signal?: AbortSignal): Promise<AdminStatus> {
  return api.getAdminStatus(signal);
}

function normalizeRetention(value: number): number {
  return Math.max(0, Math.trunc(value));
}

function errorMessage(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
