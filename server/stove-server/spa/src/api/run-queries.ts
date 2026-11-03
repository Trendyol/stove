import { type InfiniteData, infiniteQueryOptions } from "@tanstack/react-query";
import type { MetadataFilter } from "../utils/metadata-filter";
import { api } from "./client";
import { dashboardKeys } from "./query-keys";
import type { Run, RunPage } from "./types";

export interface RunListFilter {
  app?: string;
  status?: Run["status"];
  metadata?: MetadataFilter;
}

export interface RunListRequest extends RunListFilter {
  cursor?: string;
  limit?: number;
}

export type RunPages = InfiniteData<RunPage, string | undefined>;

export function normalizeRunFilter(filter: RunListFilter): RunListFilter {
  return {
    ...filter,
    metadata: Object.fromEntries(
      Object.entries(filter.metadata ?? {})
        .filter(([, values]) => values.length > 0)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, values]) => [key, [...new Set(values)].sort()]),
    ),
  };
}

export function matchesRun(run: Run, filter: RunListFilter): boolean {
  return (
    (!filter.app || run.app_name === filter.app) &&
    (!filter.status || run.status === filter.status) &&
    Object.entries(filter.metadata ?? {}).every(([key, values]) =>
      values.includes(run.metadata[key]),
    )
  );
}

export function runPagesOptions(filter: RunListFilter) {
  const normalized = normalizeRunFilter(filter);
  return infiniteQueryOptions({
    queryKey: dashboardKeys.runs(normalized),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) => api.getRuns({ ...normalized, cursor: pageParam }, signal),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
  });
}
