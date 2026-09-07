import { useCallback, useEffect, useMemo, useState } from "react";
import type { Entry } from "../../api/types";
import { useEvidenceNavigation } from "../../hooks/useEvidenceNavigation";
import {
  type EvidenceFilter,
  type EvidenceSelection,
  filterEvidence,
  isEntryIssue,
  resolveEvidenceInspector,
} from "./model";

export function useEvidenceWorkbench(entries: Entry[]) {
  const [filter, setFilter] = useState<EvidenceFilter>("all");
  const [search, setSearch] = useState("");
  const navigation = useEvidenceNavigation();
  const [localSelection, setLocalSelection] = useState<EvidenceSelection>({ kind: "none" });
  const selection: EvidenceSelection = navigation
    ? navigation.focus?.kind === "entry"
      ? { kind: "entry", entryId: navigation.focus.id }
      : { kind: "none" }
    : localSelection;
  const setSelection = useCallback(
    (next: EvidenceSelection) => {
      if (!navigation) setLocalSelection(next);
      else if (next.kind === "entry") navigation.select("entry", next.entryId);
      else navigation.clear();
    },
    [navigation],
  );
  useEffect(() => {
    if (navigation?.focus) {
      setFilter("all");
      setSearch("");
    }
  }, [navigation?.focus?.kind, navigation?.focus?.id]);

  useEffect(() => {
    if (
      !navigation &&
      selection.kind === "entry" &&
      !entries.some((entry) => entry.id === selection.entryId)
    ) {
      setSelection({ kind: "none" });
    }
  }, [entries, selection, navigation, setSelection]);

  const issueCount = useMemo(() => entries.filter(isEntryIssue).length, [entries]);
  const visibleEntries = useMemo(
    () => filterEvidence(entries, filter, search),
    [entries, filter, search],
  );
  const inspectorState = useMemo(
    () => resolveEvidenceInspector(selection, visibleEntries),
    [selection, visibleEntries],
  );
  const closeInspector = useCallback(() => setSelection({ kind: "none" }), [setSelection]);

  const jumpToFirstIssue = () => {
    const issue = entries.find(isEntryIssue);
    if (!issue) return;
    setFilter("all");
    setSearch("");
    setSelection({ kind: "entry", entryId: issue.id });
  };

  const selectEntry = (entryId: Entry["id"]) => setSelection({ kind: "entry", entryId });
  return {
    filter,
    setFilter,
    search,
    setSearch,
    issueCount,
    visibleEntries,
    inspectorState,
    selectedId: selection.kind === "entry" ? selection.entryId : undefined,
    selectEntry,
    closeInspector,
    jumpToFirstIssue,
  };
}
