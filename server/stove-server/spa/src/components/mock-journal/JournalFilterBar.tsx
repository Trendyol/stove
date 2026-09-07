import { LedgerFilterButton } from "../LedgerFilterButton";
import type { InteractionFilter, JournalStats } from "./model";

interface JournalFilterBarProps {
  filter: InteractionFilter;
  stats: JournalStats;
  visibleCount: number;
  onChange: (filter: InteractionFilter) => void;
}

export function JournalFilterBar({ filter, stats, visibleCount, onChange }: JournalFilterBarProps) {
  return (
    <div className="mock-ledger-toolbar">
      <fieldset className="ledger-filter-group">
        <legend className="sr-only">Filter mock exchanges</legend>
        <LedgerFilterButton
          active={filter === "all"}
          count={stats.all}
          label="All"
          onClick={() => onChange("all")}
        />
        <LedgerFilterButton
          active={filter === "issues"}
          count={stats.issues}
          label="Needs attention"
          onClick={() => onChange("issues")}
        />
        <LedgerFilterButton
          active={filter === "unmatched"}
          count={stats.unmatched}
          label="Unmatched"
          onClick={() => onChange("unmatched")}
        />
        <LedgerFilterButton
          active={filter === "slow"}
          count={stats.slow}
          label="Slow"
          onClick={() => onChange("slow")}
        />
      </fieldset>
      <span>
        Showing {visibleCount} of {stats.all}
      </span>
    </div>
  );
}
