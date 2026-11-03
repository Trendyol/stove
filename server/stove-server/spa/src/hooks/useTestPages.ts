import { useInfiniteQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { mergeTests } from "../api/live-cache/reconciliation";
import { type TestListFilter, testPagesOptions } from "../api/test-queries";

export function useTestPages(
  runId: string | undefined,
  filter: TestListFilter,
  connected: boolean,
) {
  const query = useInfiniteQuery({
    ...testPagesOptions(runId ?? "", filter),
    enabled: Boolean(runId),
    staleTime: connected ? Infinity : 0,
    refetchInterval: runId && !connected ? 5000 : false,
  });
  const tests = useMemo(
    () => (runId ? mergeTests(query.data?.pages.flatMap((page) => page.tests) ?? [], []) : []),
    [runId, query.data],
  );
  return {
    ...query,
    tests,
    total: query.data?.pages[0]?.total ?? 0,
    summary: query.data?.pages[0]?.summary,
    loadMore: () => {
      if (query.hasNextPage && !query.isFetching)
        void query.fetchNextPage({ cancelRefetch: false });
    },
  };
}
