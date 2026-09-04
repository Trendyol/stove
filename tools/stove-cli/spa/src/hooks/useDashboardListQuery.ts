import { type QueryKey, useQuery, useQueryClient } from "@tanstack/react-query";
import { loadAndReconcileDashboardData } from "../api/live-cache";

const EMPTY_LIST: never[] = [];

interface DashboardListQueryOptions<T> {
  queryKey: QueryKey;
  load: (signal: AbortSignal) => Promise<T[]>;
  liveConnected: boolean;
  pollWhileDisconnected: boolean;
}

export type DashboardListQuery<T> =
  | { kind: "loading"; data: T[] }
  | { kind: "error"; data: T[]; error: Error }
  | { kind: "ready"; data: T[] };

/** Shared loading, reconciliation, and fallback-polling policy for visible dashboard lists. */
export function useDashboardListQuery<T>({
  queryKey,
  load,
  liveConnected,
  pollWhileDisconnected,
}: DashboardListQueryOptions<T>): DashboardListQuery<T> {
  const queryClient = useQueryClient();
  const query = useQuery<T[], Error>({
    queryKey,
    queryFn: ({ signal }) =>
      loadAndReconcileDashboardData(queryClient, queryKey, () => load(signal)),
    refetchInterval: !liveConnected && pollWhileDisconnected ? 5000 : false,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const data = query.data ?? EMPTY_LIST;
  if (query.isPending) return { kind: "loading", data };
  if (query.isError) return { kind: "error", data, error: query.error };
  return { kind: "ready", data };
}
