import type { MockInteraction } from "../../api/types";
import { Icon } from "../Icon";
import { VirtualList } from "../VirtualList";
import { InteractionRow } from "./InteractionRow";

interface InteractionLedgerProps {
  interactions: MockInteraction[];
  sequenceById: ReadonlyMap<MockInteraction["id"], number>;
  selectedId?: MockInteraction["id"];
  filtered: boolean;
  onSelect: (id: MockInteraction["id"]) => void;
}

export function InteractionLedger({
  interactions,
  sequenceById,
  selectedId,
  filtered,
  onSelect,
}: InteractionLedgerProps) {
  return interactions.length > 0 ? (
    <VirtualList
      className="mock-ledger"
      ariaLabel="Mock exchanges"
      items={interactions}
      getKey={(interaction) => interaction.id}
      scrollToKey={selectedId}
      getItemSize={68}
      renderItem={(interaction) => (
        <InteractionRow
          interaction={interaction}
          sequence={sequenceById.get(interaction.id) ?? 0}
          selected={interaction.id === selectedId}
          onSelect={() => onSelect(interaction.id)}
        />
      )}
    />
  ) : (
    <section className="mock-ledger" aria-label="Mock exchanges">
      <MockEmptyState filtered={filtered} />
    </section>
  );
}

function MockEmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="ledger-empty">
      <span className="mock-empty-orbit">
        <Icon name="mock" className="h-5 w-5" />
      </span>
      <div>
        <strong>{filtered ? "No exchanges match this lens" : "No mock exchanges captured"}</strong>
        <p>
          {filtered
            ? "Broaden the filter or clear the search."
            : "This test did not communicate with a journal-enabled mock system."}
        </p>
      </div>
    </div>
  );
}
