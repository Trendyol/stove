import type { Entry } from "../api/types";
import { EvidenceCommandBar } from "./evidence/EvidenceCommandBar";
import { EvidenceInspector } from "./evidence/EvidenceInspector";
import { EvidenceLedger } from "./evidence/EvidenceLedger";
import { useEvidenceWorkbench } from "./evidence/useEvidenceWorkbench";

interface EvidenceWorkbenchProps {
  entries: Entry[];
  onOpenTrace: () => void;
}

export function EvidenceWorkbench({ entries, onOpenTrace }: EvidenceWorkbenchProps) {
  const workbench = useEvidenceWorkbench(entries);
  return (
    <div className="evidence-workbench">
      <EvidenceCommandBar
        total={entries.length}
        issueCount={workbench.issueCount}
        filter={workbench.filter}
        search={workbench.search}
        onFilterChange={workbench.setFilter}
        onSearchChange={workbench.setSearch}
        onJumpToIssue={workbench.jumpToFirstIssue}
      />
      <EvidenceLedger
        entries={workbench.visibleEntries}
        selectedId={workbench.selectedId}
        filtered={entries.length > 0}
        onSelect={workbench.selectEntry}
      />
      <EvidenceInspector
        state={workbench.inspectorState}
        onSelect={workbench.selectEntry}
        onClose={workbench.closeInspector}
        onOpenTrace={onOpenTrace}
      />
    </div>
  );
}
