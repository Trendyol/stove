import { useCallback, useState } from "react";
import type { MetadataFilter } from "../utils/metadata-filter";

type Selection<T> = { kind: "automatic" } | { kind: "explicit"; value: T };

interface DashboardSelectionState {
  app: Selection<string>;
  run: Selection<string>;
  test: Selection<string>;
  metadataFilter: MetadataFilter;
}

const AUTOMATIC = { kind: "automatic" } as const;

const INITIAL_SELECTION: DashboardSelectionState = {
  app: AUTOMATIC,
  run: AUTOMATIC,
  test: AUTOMATIC,
  metadataFilter: {},
};

/** Owns valid dashboard navigation transitions; child selections reset atomically. */
export function useDashboardSelection() {
  const [state, setState] = useState<DashboardSelectionState>(INITIAL_SELECTION);

  const clearRun = useCallback(() => {
    setState((current) => ({ ...current, run: AUTOMATIC, test: AUTOMATIC }));
  }, []);

  const clearApp = useCallback(() => {
    setState(INITIAL_SELECTION);
  }, []);

  const clearTest = useCallback(() => {
    setState((current) => ({ ...current, test: AUTOMATIC }));
  }, []);

  const selectApp = useCallback((appName: string) => {
    setState({
      app: { kind: "explicit", value: appName },
      run: AUTOMATIC,
      test: AUTOMATIC,
      metadataFilter: {},
    });
  }, []);

  const selectRun = useCallback((runId: string) => {
    setState((current) => ({
      ...current,
      run: { kind: "explicit", value: runId },
      test: AUTOMATIC,
    }));
  }, []);

  const selectTest = useCallback((testId: string) => {
    setState((current) => ({
      ...current,
      test: { kind: "explicit", value: testId },
    }));
  }, []);

  const filterRunsByMetadata = useCallback((metadataFilter: MetadataFilter) => {
    setState((current) => ({
      ...current,
      metadataFilter,
    }));
  }, []);

  return {
    selectedApp: explicitValue(state.app),
    selectedRunId: explicitValue(state.run),
    selectedTestId: explicitValue(state.test),
    metadataFilter: state.metadataFilter,
    selectApp,
    selectRun,
    selectTest,
    filterRunsByMetadata,
    clearApp,
    clearRun,
    clearTest,
  };
}

function explicitValue<T>(selection: Selection<T>): T | undefined {
  return selection.kind === "explicit" ? selection.value : undefined;
}
