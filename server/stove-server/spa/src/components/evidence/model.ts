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

export type EvidenceInspectorState =
  | { kind: "closed" }
  | {
      kind: "open";
      entry: Entry;
      position: number;
      total: number;
      previous?: Entry;
      next?: Entry;
    };

export function resolveEvidenceInspector(
  selection: EvidenceSelection,
  entries: readonly Entry[],
): EvidenceInspectorState {
  if (selection.kind === "none") return { kind: "closed" };
  const position = entries.findIndex((entry) => entry.id === selection.entryId);
  if (position < 0) return { kind: "closed" };
  return {
    kind: "open",
    entry: entries[position],
    position,
    total: entries.length,
    previous: position > 0 ? entries[position - 1] : undefined,
    next: position < entries.length - 1 ? entries[position + 1] : undefined,
  };
}
