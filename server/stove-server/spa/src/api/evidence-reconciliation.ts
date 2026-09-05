import type { LiveRecordId } from "./types";

export function committedRecordId(id: LiveRecordId): string | undefined {
  if (typeof id === "number") return id > 0 ? String(id) : undefined;
  return /^[1-9][0-9]*$/.test(id) ? id : undefined;
}

export function sameRecordId(left: LiveRecordId, right: LiveRecordId): boolean {
  const committed = committedRecordId(left);
  return left === right || (committed !== undefined && committed === committedRecordId(right));
}

export function mergeEvidenceRecords<T extends { id: LiveRecordId }>(
  persisted: T[],
  cached: T[],
  identity: (record: T) => string,
  compare: (left: T, right: T) => number,
): T[] {
  const unmatchedPersisted = new Map<string, number>();
  for (const record of persisted) {
    const key = identity(record);
    unmatchedPersisted.set(key, (unmatchedPersisted.get(key) ?? 0) + 1);
  }

  const merged = [...persisted];
  const persistedIds = new Set(
    persisted
      .map((record) => committedRecordId(record.id))
      .filter((id): id is string => id !== undefined),
  );
  for (const record of cached) {
    const committedId = committedRecordId(record.id);
    if (committedId !== undefined) {
      if (!persistedIds.has(committedId)) {
        persistedIds.add(committedId);
        merged.push(record);
      }
      continue;
    }
    // Older servers publish temporary negative IDs; preserve their semantic
    // reconciliation while new streams reconcile exact committed identities.
    const key = identity(record);
    const remaining = unmatchedPersisted.get(key) ?? 0;
    if (remaining > 0) {
      unmatchedPersisted.set(key, remaining - 1);
    } else {
      merged.push(record);
    }
  }
  return merged.sort(compare);
}
