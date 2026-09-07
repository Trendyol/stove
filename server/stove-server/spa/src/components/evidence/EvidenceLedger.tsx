import type { Entry } from "../../api/types";
import { Icon } from "../Icon";
import { VirtualList } from "../VirtualList";
import { EvidenceRow } from "./EvidenceRow";

interface EvidenceLedgerProps {
  entries: Entry[];
  selectedId?: Entry["id"];
  filtered: boolean;
  onSelect: (id: Entry["id"]) => void;
}

const evidenceKey = (entry: Entry) => `${entry.assertion_id}:${entry.id}`;

export function EvidenceLedger({ entries, selectedId, filtered, onSelect }: EvidenceLedgerProps) {
  const selectedEntry = entries.find((entry) => entry.id === selectedId);
  return entries.length > 0 ? (
    <VirtualList
      className="evidence-ledger"
      ariaLabel="Recorded test evidence"
      items={entries}
      getKey={evidenceKey}
      getItemSize={56}
      scrollToKey={selectedEntry ? evidenceKey(selectedEntry) : undefined}
      renderItem={(entry) => (
        <EvidenceRow
          entry={entry}
          selected={selectedId !== undefined && entry.id === selectedId}
          onSelect={() => onSelect(entry.id)}
        />
      )}
    />
  ) : (
    <section className="evidence-ledger" aria-label="Recorded test evidence">
      <EvidenceEmptyState filtered={filtered} />
    </section>
  );
}

function EvidenceEmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="ledger-empty">
      <span className="mock-empty-orbit">
        <Icon name="activity" className="h-5 w-5" />
      </span>
      <div>
        <strong>{filtered ? "No matching evidence" : "No evidence recorded"}</strong>
        <p>
          {filtered
            ? "Broaden the filter or clear the search."
            : "This test did not report any events."}
        </p>
      </div>
    </div>
  );
}
