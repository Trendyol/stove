import type { Entry, MockInteraction, MockWarning, Run, Snapshot, Span, Test } from "../types";

// These helpers mutate only arrays owned by LiveCacheBuffer. Record objects are
// replaced, never mutated, so references held by UI observers stay unchanged.
export function upsertTest(tests: Test[] | undefined, incoming: Test): Test[] {
  const result = tests ?? [];
  const existingIndex = result.findIndex((test) => test.id === incoming.id);
  if (existingIndex >= 0) result.splice(existingIndex, 1);
  insertSorted(result, incoming, compareTests);
  return result;
}

export function appendEntries(entries: Entry[] | undefined, incoming: Entry): Entry[] {
  if (incoming.id !== 0 && entries?.some((entry) => entry.id === incoming.id)) {
    return entries;
  }

  const existing = entries ?? [];
  const assertionIndex = existing.findIndex(
    (entry) => entry.assertion_id === incoming.assertion_id,
  );
  if (assertionIndex < 0) {
    insertSorted(existing, incoming, (left, right) =>
      left.timestamp.localeCompare(right.timestamp),
    );
    return existing;
  }

  const previous = existing[assertionIndex];
  const correlated = mergeEntryAttempts(previous, incoming);
  existing.splice(assertionIndex, 1);
  insertSorted(existing, correlated, (left, right) =>
    left.timestamp.localeCompare(right.timestamp),
  );
  return existing;
}

export function appendSpan(spans: Span[] | undefined, incoming: Span): Span[] {
  if (spans?.some((span) => isSameSpan(span, incoming))) {
    return spans;
  }
  const result = spans ?? [];
  insertSorted(result, incoming, (left, right) => left.start_time_nanos - right.start_time_nanos);
  return result;
}

export function mergeSpans(existing: Span[] | undefined, incoming: readonly Span[]): Span[] {
  return incoming.reduce<Span[]>((acc, span) => appendSpan(acc, span), existing ?? []);
}

export function appendSnapshots(snapshots: Snapshot[] | undefined, incoming: Snapshot): Snapshot[] {
  if (
    snapshots?.some(
      (snapshot) =>
        snapshot.system === incoming.system &&
        snapshot.summary === incoming.summary &&
        snapshot.state_json === incoming.state_json,
    )
  ) {
    return snapshots;
  }
  const result = snapshots ?? [];
  result.push(incoming);
  return result;
}

export function appendInteractions(
  interactions: MockInteraction[] | undefined,
  incoming: MockInteraction,
): MockInteraction[] {
  if (interactions?.some((interaction) => interaction.id === incoming.id)) {
    return interactions;
  }
  const result = interactions ?? [];
  insertSorted(result, incoming, (left, right) => left.timestamp.localeCompare(right.timestamp));
  return result;
}

export function appendWarnings(
  warnings: MockWarning[] | undefined,
  incoming: MockWarning,
): MockWarning[] {
  if (warnings?.some((warning) => warning.id === incoming.id)) {
    return warnings;
  }
  const result = warnings ?? [];
  insertSorted(result, incoming, (left, right) => left.timestamp.localeCompare(right.timestamp));
  return result;
}

export function compareRuns(left: Run, right: Run): number {
  return right.started_at.localeCompare(left.started_at) || right.id.localeCompare(left.id);
}

export function compareTests(left: Test, right: Test): number {
  return left.started_at.localeCompare(right.started_at) || left.id.localeCompare(right.id);
}

function isSameSpan(left: Span, right: Span): boolean {
  return left.trace_id === right.trace_id && left.span_id === right.span_id;
}

function insertSorted<T>(items: T[], incoming: T, compare: (left: T, right: T) => number): void {
  let low = 0;
  let high = items.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (compare(items[middle], incoming) <= 0) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  items.splice(low, 0, incoming);
}

export function mergeEntryAttempts(previous: Readonly<Entry>, incoming: Readonly<Entry>): Entry {
  const latest =
    incoming.attempt_count > previous.attempt_count ||
    (incoming.attempt_count === previous.attempt_count && incoming.timestamp > previous.timestamp)
      ? incoming
      : previous;
  return {
    ...latest,
    id: previous.id,
    attempt_count: Math.max(previous.attempt_count, incoming.attempt_count),
    failure_count: Math.max(previous.failure_count, incoming.failure_count),
  };
}
