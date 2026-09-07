import { useCallback, useMemo, useState } from "react";
import {
  filterInteractions,
  hasInteractionIssue,
  type InteractionFilter,
  type JournalRecords,
  journalStats,
} from "./model";
import { useJournalSelection } from "./useJournalSelection";

export function useMockJournal(records: JournalRecords) {
  const { interactions, warnings, ambientInteractions, ambientWarnings } = records;
  const [includeAmbient, setIncludeAmbient] = useState(false);
  const [filter, setFilter] = useState<InteractionFilter>("all");
  const [search, setSearch] = useState("");
  const [warningsExpanded, setWarningsExpanded] = useState(true);
  const resetFilters = useCallback(() => {
    setFilter("all");
    setSearch("");
  }, []);
  const allInteractions = useMemo(
    () =>
      [...interactions, ...(includeAmbient ? ambientInteractions : [])].sort((left, right) =>
        left.timestamp.localeCompare(right.timestamp),
      ),
    [interactions, ambientInteractions, includeAmbient],
  );
  const allWarnings = useMemo(
    () =>
      [...warnings, ...(includeAmbient ? ambientWarnings : [])].sort((left, right) =>
        left.timestamp.localeCompare(right.timestamp),
      ),
    [warnings, ambientWarnings, includeAmbient],
  );
  const stats = useMemo(() => journalStats(allInteractions), [allInteractions]);
  const visibleInteractions = useMemo(
    () => filterInteractions(allInteractions, filter, search),
    [allInteractions, filter, search],
  );
  const sequenceById = useMemo(
    () => new Map(allInteractions.map((interaction, index) => [interaction.id, index + 1])),
    [allInteractions],
  );
  const selection = useJournalSelection({
    ...records,
    allInteractions,
    allWarnings,
    visibleInteractions,
    resetFilters,
  });
  const jumpToFirstIssue = () => {
    const issue = allInteractions.find(hasInteractionIssue);
    if (!issue) return;
    resetFilters();
    selection.selectInteraction(issue.id);
  };
  return {
    ...selection,
    includeAmbient,
    setIncludeAmbient,
    filter,
    setFilter,
    search,
    setSearch,
    warningsExpanded,
    setWarningsExpanded,
    allWarnings,
    stats,
    visibleInteractions,
    sequenceById,
    ambientCount: ambientInteractions.length + ambientWarnings.length,
    jumpToFirstIssue,
  };
}
