import { useCallback, useState } from "react";

export function useDashboardSelection() {
  const [selectedApp, setSelectedApp] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedTestId, setSelectedTestId] = useState<string | null>(null);
  const [metadataFilter, setMetadataFilter] = useState<Record<string, string>>({});

  const clearRun = useCallback(() => {
    setSelectedRunId(null);
    setSelectedTestId(null);
  }, []);

  const clearApp = useCallback(() => {
    setSelectedApp(null);
    clearRun();
    setMetadataFilter({});
  }, [clearRun]);

  const selectApp = useCallback(
    (appName: string) => {
      setSelectedApp(appName);
      clearRun();
      setMetadataFilter({});
    },
    [clearRun],
  );

  const selectRun = useCallback((runId: string) => {
    setSelectedRunId(runId);
    setSelectedTestId(null);
  }, []);

  const filterRunsByMetadata = useCallback((metadata: Record<string, string>) => {
    setMetadataFilter(metadata);
    setSelectedRunId(null);
    setSelectedTestId(null);
  }, []);

  return {
    selectedApp,
    selectedRunId,
    selectedTestId,
    metadataFilter,
    selectApp,
    selectRun,
    selectTest: setSelectedTestId,
    filterRunsByMetadata,
    clearApp,
    clearRun,
  };
}
