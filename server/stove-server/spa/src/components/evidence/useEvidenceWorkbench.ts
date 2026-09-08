import { useCallback, useEffect, useMemo } from "react";
import type { Entry } from "../../api/types";
import { useEvidenceNavigation } from "../../hooks/useEvidenceNavigation";
import { useRememberedState, useRevealTarget } from "./EvidenceViewMemory";
import {
  type EvidenceFilter,
  type EvidenceSelection,
  filterEvidence,
  isEntryIssue,
  resolveEvidenceInspector,
} from "./model";

export function useEvidenceWorkbench(entries: Entry[]) {
  const [filter, setFilter] = useRememberedState<EvidenceFilter>("timeline.filter", "all");
  const [search, setSearch] = useRememberedState("timeline.search", "");
  const navigation = useEvidenceNavigation();
  const [localSelection, setLocalSelection] = useRememberedState<EvidenceSelection>(
    "timeline.selection",
    () => {
      const first = entries.find(isEntryIssue);
      return first ? { kind: "entry", entryId: first.id } : { kind: "none" };
    },
  );
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
  useRevealTarget(
    "timeline.target",
    navigation?.focus ? `${navigation.focus.kind}:${navigation.focus.id}` : undefined,
    useCallback(() => {
      setFilter("all");
      setSearch("");
    }, []),
  );

  useEffect(() => {
    if (
      !navigation &&
      selection.kind === "entry" &&
      !entries.some((entry) => entry.id === selection.entryId)
    ) {
      setSelection({ kind: "none" });
    }
  }, [entries, selection, navigation, setSelection]);

  const [acknowledgedIssues, setAcknowledgedIssues] = useRememberedState<Set<number>>(
    "timeline.acknowledgedIssues",
    () => new Set(entries.filter(isEntryIssue).map((entry) => entry.id)),
  );
  const newIssue = entries.find(
    (entry) => isEntryIssue(entry) && !acknowledgedIssues.has(entry.id),
  );
  const acknowledgeIssues = () =>
    setAcknowledgedIssues(new Set(entries.filter(isEntryIssue).map((entry) => entry.id)));
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
    newIssue,
    acknowledgeIssues,
    visibleEntries,
    inspectorState,
    selectedId: selection.kind === "entry" ? selection.entryId : undefined,
    selectEntry,
    closeInspector,
    jumpToFirstIssue,
  };
}
