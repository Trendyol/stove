import { useEffect, useMemo, useRef, useState } from "react";
import type { MockInteraction, MockWarning } from "../api/types";
import { useFocusShortcut } from "../hooks/useFocusShortcut";
import { InteractionInspector } from "./mock-journal/InteractionInspector";
import { InteractionRow } from "./mock-journal/InteractionRow";
import {
  JournalCommandBar,
  JournalFilterBar,
  MockEmptyState,
  WarningRibbon,
} from "./mock-journal/JournalControls";
import {
  filterInteractions,
  findRelatedInteraction,
  hasInteractionIssue,
  type InteractionFilter,
  type JournalSelection,
  journalStats,
  resolveJournalInspector,
} from "./mock-journal/model";
import { VirtualList } from "./VirtualList";

interface MockJournalProps {
  interactions: MockInteraction[];
  warnings: MockWarning[];
  ambientInteractions: MockInteraction[];
  ambientWarnings: MockWarning[];
  onOpenTrace: () => void;
}

export function MockJournal({
  interactions,
  warnings,
  ambientInteractions,
  ambientWarnings,
  onOpenTrace,
}: MockJournalProps) {
  const [includeAmbient, setIncludeAmbient] = useState(false);
  const [filter, setFilter] = useState<InteractionFilter>("all");
  const [search, setSearch] = useState("");
  const [warningsExpanded, setWarningsExpanded] = useState(true);
  const [selection, setSelection] = useState<JournalSelection>({ kind: "none" });
  const searchRef = useRef<HTMLInputElement>(null);
  const initialSelectionMade = useRef(false);
  useFocusShortcut(searchRef, "/");

  const allInteractions = useMemo(
    () =>
      [...interactions, ...(includeAmbient ? ambientInteractions : [])].sort((left, right) =>
        left.timestamp.localeCompare(right.timestamp),
      ),
    [ambientInteractions, includeAmbient, interactions],
  );
  const allWarnings = useMemo(
    () =>
      [...warnings, ...(includeAmbient ? ambientWarnings : [])].sort((left, right) =>
        left.timestamp.localeCompare(right.timestamp),
      ),
    [ambientWarnings, includeAmbient, warnings],
  );

  const stats = useMemo(() => journalStats(allInteractions), [allInteractions]);
  const visibleInteractions = useMemo(
    () => filterInteractions(allInteractions, filter, search),
    [allInteractions, filter, search],
  );
  const inspectorState = useMemo(
    () => resolveJournalInspector(selection, visibleInteractions, allWarnings),
    [allWarnings, selection, visibleInteractions],
  );

  useEffect(() => {
    if (selection.kind !== "none" && inspectorState.kind !== "empty") {
      initialSelectionMade.current = true;
      return;
    }
    if (selection.kind !== "none") {
      initialSelectionMade.current = true;
      setSelection({ kind: "none" });
      return;
    }
    if (initialSelectionMade.current) return;

    const initial = allInteractions.find(hasInteractionIssue) ?? allInteractions[0];
    if (initial) {
      initialSelectionMade.current = true;
      setSelection({ kind: "interaction", interactionId: initial.id });
    }
  }, [allInteractions, inspectorState.kind, selection]);

  const sequenceById = useMemo(
    () => new Map(allInteractions.map((interaction, index) => [interaction.id, index + 1])),
    [allInteractions],
  );

  const selectedInteractionId =
    selection.kind === "interaction"
      ? selection.interactionId
      : selection.kind === "warning" && selection.related.kind === "interaction"
        ? selection.related.interactionId
        : undefined;
  const selectedWarningId = selection.kind === "warning" ? selection.warningId : undefined;

  const selectWarning = (warning: MockWarning) => {
    const related = findRelatedInteraction(warning, allInteractions);
    if (related) {
      setFilter("all");
      setSearch("");
    }
    setSelection({
      kind: "warning",
      warningId: warning.id,
      related: related ? { kind: "interaction", interactionId: related.id } : { kind: "none" },
    });
  };

  const jumpToFirstIssue = () => {
    const issue = allInteractions.find(hasInteractionIssue);
    if (!issue) return;
    setFilter("all");
    setSearch("");
    setSelection({ kind: "interaction", interactionId: issue.id });
  };

  return (
    <div className="mock-workbench">
      <JournalCommandBar
        stats={stats}
        ambientCount={ambientInteractions.length + ambientWarnings.length}
        includeAmbient={includeAmbient}
        search={search}
        searchRef={searchRef}
        onIncludeAmbientChange={setIncludeAmbient}
        onSearchChange={setSearch}
        onJumpToIssue={jumpToFirstIssue}
      />
      <WarningRibbon
        warnings={allWarnings}
        expanded={warningsExpanded}
        selectedWarningId={selectedWarningId}
        onExpandedChange={setWarningsExpanded}
        onSelect={selectWarning}
      />
      <JournalFilterBar
        filter={filter}
        stats={stats}
        visibleCount={visibleInteractions.length}
        onChange={setFilter}
      />

      <div className="mock-ledger-layout">
        {visibleInteractions.length > 0 ? (
          <VirtualList
            className="mock-ledger"
            ariaLabel="Mock exchanges"
            items={visibleInteractions}
            getKey={(interaction) => interaction.id}
            getItemSize={68}
            renderItem={(interaction) => (
              <InteractionRow
                interaction={interaction}
                sequence={sequenceById.get(interaction.id) ?? 0}
                selected={interaction.id === selectedInteractionId}
                onSelect={() =>
                  setSelection({ kind: "interaction", interactionId: interaction.id })
                }
              />
            )}
          />
        ) : (
          <section className="mock-ledger" aria-label="Mock exchanges">
            <MockEmptyState filtered={allInteractions.length > 0} />
          </section>
        )}

        <InteractionInspector
          state={inspectorState}
          interactions={visibleInteractions}
          onSelect={(interactionId) => setSelection({ kind: "interaction", interactionId })}
          onClose={() => setSelection({ kind: "none" })}
          onOpenTrace={onOpenTrace}
        />
      </div>
    </div>
  );
}
