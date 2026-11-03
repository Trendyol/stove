import { type InfiniteData, infiniteQueryOptions } from "@tanstack/react-query";
import { api } from "./client";
import type { TestPage, TestSummary } from "./types";

export interface TestListFilter {
  outcome?: "all" | "pass" | "fail";
  search?: string;
}
export interface TestListRequest extends TestListFilter {
  cursor?: string;
  limit?: number;
}
export type TestPages = InfiniteData<TestPage, string | undefined>;

export interface TestBrowserState {
  outcome: "all" | "pass" | "fail";
  search: string;
  onOutcomeChange: (outcome: "all" | "pass" | "fail") => void;
  onSearchChange: (search: string) => void;
  hasMore: boolean;
  loading: boolean;
  error: Error | null;
  loadMore: () => void;
  retry: () => void;
  total: number;
  summary: TestSummary | undefined;
}

export function testPagesOptions(runId: string, filter: TestListFilter = {}) {
  const normalized = { outcome: filter.outcome ?? "all", search: filter.search ?? "" };
  return infiniteQueryOptions({
    queryKey: ["tests", runId, normalized] as const,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.getTests(runId, { ...normalized, cursor: pageParam }, signal),
    getNextPageParam: (page) => page.next_cursor ?? undefined,
  });
}
