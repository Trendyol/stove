import { useCallback, useEffect, useMemo } from "react";
import type { MockInteraction, MockWarning } from "../../api/types";
import { useEvidenceNavigation } from "../../hooks/useEvidenceNavigation";
import { useRememberedState, useRevealTarget } from "../evidence/EvidenceViewMemory";
import { hasInteractionIssue, type JournalRecords } from "./model";
import {
  findRelatedInteraction,
  type JournalSelection,
  resolveJournalInspector,
} from "./selection";

interface JournalSelectionOptions extends JournalRecords {
  allInteractions: MockInteraction[];
  allWarnings: MockWarning[];
  visibleInteractions: MockInteraction[];
  resetFilters: () => void;
}

export function useJournalSelection({
  interactions,
  warnings,
  ambientInteractions,
  ambientWarnings,
  allInteractions,
  allWarnings,
  visibleInteractions,
  resetFilters,
}: JournalSelectionOptions) {
  const navigation = useEvidenceNavigation();
  const [localSelection, setLocalSelection] = useRememberedState<JournalSelection>(
    "mocks.selection",
    { kind: "none" },
  );
  const [initialSelectionMade, setInitialSelectionMade] = useRememberedState(
    "mocks.initialized",
    false,
  );
  const sourceInteractions = useMemo(
    () => [...interactions, ...ambientInteractions],
    [interactions, ambientInteractions],
  );
  const sourceWarnings = useMemo(
    () => [...warnings, ...ambientWarnings],
    [warnings, ambientWarnings],
  );
  const selection = useMemo<JournalSelection>(() => {
    if (!navigation) return localSelection;
    const focus = navigation.focus;
    if (focus?.kind === "interaction") return { kind: "interaction", interactionId: focus.id };
    const warning =
      focus?.kind === "warning" ? sourceWarnings.find((item) => item.id === focus.id) : undefined;
    if (!warning) return { kind: "none" };
    const related = findRelatedInteraction(warning, sourceInteractions);
    return {
      kind: "warning",
      warningId: warning.id,
      related: related ? { kind: "interaction", interactionId: related.id } : { kind: "none" },
    };
  }, [navigation, localSelection, sourceInteractions, sourceWarnings]);

  const setSelection = useCallback(
    (next: JournalSelection) => {
      if (!navigation) setLocalSelection(next);
      else if (next.kind === "interaction") {
        const item = sourceInteractions.find((item) => item.id === next.interactionId);
        navigation.select("interaction", next.interactionId, item?.test_id);
      } else if (next.kind === "warning") {
        const item = sourceWarnings.find((item) => item.id === next.warningId);
        navigation.select("warning", next.warningId, item?.test_id);
      } else navigation.clear();
    },
    [navigation, sourceInteractions, sourceWarnings],
  );

  useRevealTarget(
    "mocks.target",
    navigation?.focus ? `${navigation.focus.kind}:${navigation.focus.id}` : undefined,
    resetFilters,
  );

  const inspectorState = useMemo(
    () => resolveJournalInspector(selection, visibleInteractions, allWarnings),
    [selection, visibleInteractions, allWarnings],
  );

  useEffect(() => {
    if (navigation) return;
    if (selection.kind !== "none") {
      setInitialSelectionMade(true);
      if (inspectorState.kind === "empty") setSelection({ kind: "none" });
      return;
    }
    if (initialSelectionMade) return;
    const initial = allInteractions.find(hasInteractionIssue) ?? allInteractions[0];
    if (initial) {
      setInitialSelectionMade(true);
      setSelection({ kind: "interaction", interactionId: initial.id });
    }
  }, [allInteractions, inspectorState.kind, selection, navigation, setSelection]);

  const selectWarning = (warning: MockWarning) => {
    const related = findRelatedInteraction(warning, allInteractions);
    if (related) resetFilters();
    setSelection({
      kind: "warning",
      warningId: warning.id,
      related: related ? { kind: "interaction", interactionId: related.id } : { kind: "none" },
    });
  };
  const selectInteraction = (interactionId: MockInteraction["id"]) =>
    setSelection({ kind: "interaction", interactionId });
  const closeInspector = () => setSelection({ kind: "none" });
  const selectedInteractionId =
    selection.kind === "interaction"
      ? selection.interactionId
      : selection.kind === "warning" && selection.related.kind === "interaction"
        ? selection.related.interactionId
        : undefined;
  return {
    inspectorState,
    selectWarning,
    selectInteraction,
    closeInspector,
    selectedInteractionId,
    selectedWarningId: selection.kind === "warning" ? selection.warningId : undefined,
  };
}
