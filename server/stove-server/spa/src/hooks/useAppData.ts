import { skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useDeferredValue, useEffect, useMemo, useState } from "react";
import { ApiError, api } from "../api/client";
import { dashboardQueries } from "../api/dashboard-queries";
import {
  applyLiveDashboardEvents,
  invalidateDashboardQueries,
  loadAndReconcileDashboardData,
} from "../api/live-cache";
import { mergeRuns } from "../api/live-cache/reconciliation";
import { dashboardKeys } from "../api/query-keys";
import { matchesRun, normalizeRunFilter } from "../api/run-queries";
import { useSSE } from "../api/sse";
import type { TestBrowserState } from "../api/test-queries";
import type { LiveDashboardEvent, Run, Test } from "../api/types";
import { summarizeVersionMismatches } from "../utils/version-mismatch";
import { useDashboardSelection } from "./useDashboardSelection";
import { useRunPages } from "./useRunPages";
import { useTestPages } from "./useTestPages";
import { useTopFilter } from "./useTopFilter";

const EMPTY_LIST: never[] = [];

export function useAppData() {
  const queryClient = useQueryClient();
  const selection = useDashboardSelection();
  const topFilter = useTopFilter();
  const { selectedApp, selectedRunId, selectedTestId, metadataFilter } = selection;

  const handleLiveEvents = useCallback(
    (events: readonly LiveDashboardEvent[]) => applyLiveDashboardEvents(queryClient, events),
    [queryClient],
  );

  const { connected: liveConnected } = useSSE({
    onEvents: handleLiveEvents,
    onGap: (event) => invalidateDashboardQueries(queryClient, event.run_id),
    onOverflow: () => invalidateDashboardQueries(queryClient),
    onConnect: () => invalidateDashboardQueries(queryClient),
  });

  const appsQuery = useQuery({
    queryKey: dashboardKeys.apps,
    queryFn: ({ signal }) =>
      loadAndReconcileDashboardData(queryClient, dashboardQueries.apps(), signal),
    refetchInterval: liveConnected ? false : 5000,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const allApps = appsQuery.data ?? EMPTY_LIST;
  let apps = allApps;
  if (!topFilter.query.data) {
    apps = EMPTY_LIST;
  } else if (topFilter.value !== null) {
    const selectedValue = topFilter.values.find((option) => option.value === topFilter.value);
    apps = selectedValue?.apps ?? EMPTY_LIST;
  }
  const metaQuery = useQuery({
    queryKey: dashboardKeys.meta,
    queryFn: ({ signal }) => api.getMeta(signal),
    staleTime: Number.POSITIVE_INFINITY,
  });

  const appInScope = topFilter.value === null || apps.some((app) => app.app_name === selectedApp);
  const activeApp = (appInScope ? selectedApp : undefined) ?? apps[0]?.app_name;
  const serverVersion = metaQuery.data?.stove_server_version ?? null;
  const scope = useMemo(
    () =>
      topFilter.key !== null && topFilter.value !== null
        ? { [topFilter.key]: topFilter.value }
        : {},
    [topFilter.key, topFilter.value],
  );
  const combined = useMemo(() => {
    const result: Record<string, readonly string[]> = { ...metadataFilter };
    for (const [key, value] of Object.entries(scope)) {
      if (result[key] && !result[key].includes(value)) return null;
      result[key] = [value];
    }
    return result;
  }, [metadataFilter, scope]);
  const runsQuery = useRunPages(activeApp, combined, liveConnected);
  const facetsQuery = useQuery({
    queryKey: dashboardKeys.runMetadata(activeApp, scope),
    queryFn: ({ signal }) => api.getRunMetadata(activeApp, scope, signal),
    enabled: Boolean(activeApp),
    refetchInterval: liveConnected ? false : 5000,
  });
  const selectedRunQuery = useQuery<Run | null>({
    queryKey: dashboardKeys.run(selectedRunId),
    queryFn: selectedRunId
      ? async ({ signal }) => {
          try {
            return await api.getRun(selectedRunId, signal);
          } catch (error) {
            if (error instanceof ApiError && error.status === 404) return null;
            throw error;
          }
        }
      : skipToken,
    initialData: () => runsQuery.runs.find((run) => run.id === selectedRunId),
    refetchInterval: selectedRunId && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Infinity : 0,
  });
  const selectedRun = selectedRunQuery.data;
  const selectedRunMatches =
    selectedRun &&
    combined !== null &&
    selectedRun.app_name === activeApp &&
    matchesRun(selectedRun, normalizeRunFilter({ app: activeApp, metadata: combined }));
  const runs = useMemo(
    () =>
      selectedRunMatches
        ? mergeRuns(
            runsQuery.runs.filter((run) => run.id !== selectedRun.id),
            [selectedRun],
          )
        : runsQuery.runs,
    [runsQuery.runs, selectedRun, selectedRunMatches],
  );

  useEffect(() => {
    selection.clearApp();
  }, [topFilter.key, selection.clearApp]);

  const appsSettled = topFilter.query.isSuccess && appsQuery.isSuccess && !appsQuery.isFetching;
  const runsSettled =
    appsSettled && (combined === null || (runsQuery.isSuccess && !runsQuery.isFetching));
  const latestRun =
    runs.find((run) => run.id === selectedRunId) ??
    (!selectedRunId || (selectedRunQuery.isSuccess && !selectedRunMatches) ? runs[0] : undefined);

  const [testOutcome, setTestOutcome] = useState<"all" | "pass" | "fail">("all");
  const [testSearch, setTestSearch] = useState("");
  const deferredSearch = useDeferredValue(testSearch);
  const testsQuery = useTestPages(
    latestRun?.id,
    { outcome: testOutcome, search: deferredSearch },
    liveConnected,
  );
  const selectedTestQuery = useQuery<Test | null>({
    queryKey: ["test", latestRun?.id, selectedTestId],
    enabled:
      Boolean(latestRun && selectedTestId) &&
      !testsQuery.tests.some((test) => test.id === selectedTestId),
    queryFn:
      latestRun && selectedTestId
        ? async ({ signal }) => {
            try {
              return await api.getTest(latestRun.id, selectedTestId, signal);
            } catch (error) {
              if (error instanceof ApiError && error.status === 404) return null;
              throw error;
            }
          }
        : skipToken,
    initialData: () => testsQuery.tests.find((test) => test.id === selectedTestId),
    refetchInterval: selectedTestId && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Infinity : 0,
  });
  const tests = testsQuery.tests;
  const testBrowser: TestBrowserState = {
    outcome: testOutcome,
    search: testSearch,
    onOutcomeChange: setTestOutcome,
    onSearchChange: setTestSearch,
    hasMore: testsQuery.hasNextPage,
    loading: testsQuery.isFetching,
    error: testsQuery.error,
    loadMore: testsQuery.loadMore,
    retry: () => {
      void testsQuery.refetch();
    },
    total: testsQuery.total,
    summary: testsQuery.summary,
  };

  useEffect(() => {
    if (appsSettled && selectedApp && !apps.some((app) => app.app_name === selectedApp)) {
      selection.clearApp();
    }
  }, [appsSettled, apps, selectedApp, selection.clearApp]);

  useEffect(() => {
    if (appsSettled && selectedRunId && selectedRunQuery.isSuccess && !selectedRunMatches) {
      selection.clearRun();
    }
  }, [
    appsSettled,
    selectedRunId,
    selectedRunQuery.isSuccess,
    selectedRunMatches,
    selection.clearRun,
  ]);

  useEffect(() => {
    if (
      runsSettled &&
      selectedTestId &&
      selectedTestQuery.isSuccess &&
      selectedTestQuery.data === null
    ) {
      selection.clearTest();
    }
  }, [
    runsSettled,
    selectedTestId,
    selectedTestQuery.isSuccess,
    selectedTestQuery.data,
    selection.clearTest,
  ]);

  const selectedTest =
    tests.find((test) => test.id === selectedTestId) ??
    selectedTestQuery.data ??
    (!selectedTestId || selectedTestQuery.isSuccess ? tests[0] : undefined);
  const versionMismatchSummary = summarizeVersionMismatches(apps, serverVersion, activeApp);
  const mismatchedApps = versionMismatchSummary?.affectedAppNames ?? [];

  return {
    error:
      topFilter.query.error ??
      appsQuery.error ??
      runsQuery.error ??
      selectedRunQuery.error ??
      selectedTestQuery.error ??
      metaQuery.error,
    loading:
      topFilter.query.isPending ||
      appsQuery.isPending ||
      (Boolean(activeApp) && combined !== null && runsQuery.isPending) ||
      (Boolean(latestRun) && testsQuery.isPending),
    retry: () =>
      queryClient.refetchQueries({
        predicate: (query) =>
          ["apps", "runs", "run", "run-metadata", "tests", "test", "meta", "top-filter"].includes(
            String(query.queryKey[0]),
          ),
        type: "active",
      }),
    apps,
    allApps,
    topFilter: {
      metadataKey: topFilter.key,
      value: topFilter.value,
      values: topFilter.values,
      onChange: (value: string | null) => {
        topFilter.select(value);
        selection.clearApp();
      },
    },
    activeApp,
    serverVersion,
    latestRun,
    runs,
    metadataOptions: facetsQuery.data ?? EMPTY_LIST,
    runPickerFeedback: {
      pageError: runsQuery.pageError,
      metadataError: facetsQuery.error,
      metadataLoading: facetsQuery.isFetching,
      retryMetadata: () => {
        void facetsQuery.refetch();
      },
    },
    hasOlderRuns: runsQuery.hasOlderRuns,
    loadingOlderRuns: runsQuery.loadingOlderRuns,
    loadOlderRuns: runsQuery.loadOlderRuns,
    selectedRunId: selectedRunId ?? latestRun?.id,
    metadataFilter,
    tests,
    testBrowser,
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
