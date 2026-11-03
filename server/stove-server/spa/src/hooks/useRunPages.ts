import { useInfiniteQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { mergeRuns } from "../api/live-cache/reconciliation";
import { runPagesOptions } from "../api/run-queries";
import type { MetadataFilter } from "../utils/metadata-filter";

export function useRunPages(
  app: string | undefined,
  metadata: MetadataFilter | null,
  liveConnected: boolean,
) {
  const enabled = Boolean(app) && metadata !== null;
  const query = useInfiniteQuery({
    ...runPagesOptions({ app, metadata: metadata ?? {} }),
    enabled,
    refetchInterval: enabled && !liveConnected ? 5000 : false,
    staleTime: liveConnected ? Infinity : 0,
  });
  const runs = useMemo(
    () => (enabled ? mergeRuns(query.data?.pages.flatMap((page) => page.runs) ?? [], []) : []),
    [enabled, query.data],
  );
  return {
    ...query,
    runs,
    hasOlderRuns: enabled && query.hasNextPage,
    loadingOlderRuns: query.isFetching,
    pageError: query.isFetchNextPageError ? query.error : null,
    error: query.isFetchNextPageError ? null : query.error,
    loadOlderRuns: () => {
      if (enabled && query.hasNextPage && !query.isFetching)
        void query.fetchNextPage({ cancelRefetch: false });
    },
  };
}
