import type { Entry } from "../../api/types";

export type EvidenceFilter = "all" | "issues";

export type EvidenceSelection = { kind: "none" } | { kind: "entry"; entryId: Entry["id"] };

export function isEntryIssue(entry: Entry): boolean {
  return entry.result === "FAILED" || entry.result === "ERROR" || entry.error !== null;
}

export function hasEntryDetail(entry: Entry): boolean {
  return Boolean(
    entry.input ||
      entry.output ||
      entry.expected ||
      entry.actual ||
      entry.error ||
      (entry.metadata && entry.metadata !== "{}"),
  );
}

export function filterEvidence(
  entries: readonly Entry[],
  filter: EvidenceFilter,
  search: string,
): Entry[] {
  const query = search.trim().toLowerCase();
  return entries.filter((entry) => {
    if (filter === "issues" && !isEntryIssue(entry)) return false;
    if (!query) return true;
    return searchableValues(entry).some((value) => value?.toLowerCase().includes(query));
  });
}

function searchableValues(entry: Entry): Array<string | null> {
  return [
    entry.system,
    entry.action,
    entry.result,
    entry.input,
    entry.output,
    entry.expected,
    entry.actual,
    entry.error,
    entry.metadata,
  ];
}
