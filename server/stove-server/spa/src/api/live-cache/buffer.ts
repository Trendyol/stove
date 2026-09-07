import { notifyManager, type QueryClient, type QueryKey } from "@tanstack/react-query";

type CacheUpdater<T> = T | undefined | ((current: T | undefined) => T | undefined);

/** Owns cloned arrays until flush; published React Query data is never mutated. */
export class LiveCacheBuffer {
  private readonly pending = new Map<string, { queryKey: QueryKey; data: unknown }>();

  constructor(private readonly queryClient: QueryClient) {}

  getQueryData<T>(queryKey: QueryKey): T | undefined {
    const pending = this.pending.get(queryHash(queryKey));
    return pending ? (pending.data as T | undefined) : this.queryClient.getQueryData<T>(queryKey);
  }

  getQueriesData<T>(filters: { queryKey: QueryKey }): Array<[QueryKey, T | undefined]> {
    const merged = new Map<string, [QueryKey, T | undefined]>();
    for (const [queryKey, data] of this.queryClient.getQueriesData<T>(filters)) {
      merged.set(queryHash(queryKey), [queryKey, data]);
    }
    for (const pending of this.pending.values()) {
      if (queryKeyStartsWith(pending.queryKey, filters.queryKey)) {
        merged.set(queryHash(pending.queryKey), [pending.queryKey, pending.data as T | undefined]);
      }
    }
    return [...merged.values()];
  }

  hasQuery(queryKey: QueryKey): boolean {
    return (
      this.pending.has(queryHash(queryKey)) ||
      this.queryClient.getQueryCache().find({ queryKey, exact: true }) !== undefined
    );
  }

  setQueryData<T>(queryKey: QueryKey, updater: CacheUpdater<T>): void {
    const hash = queryHash(queryKey);
    let current = this.getQueryData<T>(queryKey);
    if (!this.pending.has(hash) && Array.isArray(current)) {
      current = [...current] as T;
    }
    const data =
      typeof updater === "function"
        ? (updater as (value: T | undefined) => T | undefined)(current)
        : updater;
    this.pending.set(hash, { queryKey, data });
  }

  flush(): void {
    notifyManager.batch(() => {
      for (const { queryKey, data } of this.pending.values()) {
        this.queryClient.setQueryData(queryKey, data);
      }
      this.pending.clear();
    });
  }
}

function queryHash(queryKey: QueryKey): string {
  return JSON.stringify(queryKey);
}

function queryKeyStartsWith(queryKey: QueryKey, prefix: QueryKey): boolean {
  return prefix.every((part, index) => Object.is(part, queryKey[index]));
}
