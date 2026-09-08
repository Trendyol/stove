import { InteractionInspector } from "./mock-journal/InteractionInspector";
import { InteractionLedger } from "./mock-journal/InteractionLedger";
import { JournalCommandBar } from "./mock-journal/JournalCommandBar";
import { JournalFilterBar } from "./mock-journal/JournalFilterBar";
import type { JournalRecords } from "./mock-journal/model";
import { useMockJournal } from "./mock-journal/useMockJournal";
import { WarningRibbon } from "./mock-journal/WarningRibbon";

interface MockJournalProps extends JournalRecords {
  onOpenTrace: (traceId: string) => void;
}

export function MockJournal({ onOpenTrace, ...records }: MockJournalProps) {
  const journal = useMockJournal(records);
  return (
    <div className="mock-workbench">
      <JournalCommandBar
        stats={journal.stats}
        ambientCount={journal.ambientCount}
        includeAmbient={journal.includeAmbient}
        search={journal.search}
        onIncludeAmbientChange={journal.setIncludeAmbient}
        onSearchChange={journal.setSearch}
        onJumpToIssue={journal.jumpToFirstIssue}
      />
      <WarningRibbon
        warnings={journal.allWarnings}
        expanded={journal.warningsExpanded}
        selectedWarningId={journal.selectedWarningId}
        onExpandedChange={journal.setWarningsExpanded}
        onSelect={journal.selectWarning}
      />
      <JournalFilterBar
        filter={journal.filter}
        stats={journal.stats}
        visibleCount={journal.visibleInteractions.length}
        onChange={journal.setFilter}
      />
      <div className="mock-ledger-layout">
        <InteractionLedger
          interactions={journal.visibleInteractions}
          sequenceById={journal.sequenceById}
          selectedId={journal.selectedInteractionId}
          filtered={journal.stats.all > 0}
          onSelect={journal.selectInteraction}
        />
        <InteractionInspector
          state={journal.inspectorState}
          interactions={journal.visibleInteractions}
          onSelect={journal.selectInteraction}
          onClose={journal.closeInspector}
          onOpenTrace={onOpenTrace}
        />
      </div>
    </div>
  );
}
