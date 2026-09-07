import { LedgerFilterButton } from "../LedgerFilterButton";
import { LedgerSearch } from "../LedgerSearch";
import type { EvidenceFilter } from "./model";

interface EvidenceCommandBarProps {
  total: number;
  issueCount: number;
  filter: EvidenceFilter;
  search: string;
  onFilterChange: (filter: EvidenceFilter) => void;
  onSearchChange: (search: string) => void;
  onJumpToIssue: () => void;
}

export function EvidenceCommandBar({
  total,
  issueCount,
  filter,
  search,
  onFilterChange,
  onSearchChange,
  onJumpToIssue,
}: EvidenceCommandBarProps) {
  return (
    <header className="ledger-command-bar">
      <div className="ledger-command-summary">
        <strong>{total}</strong> events
        {issueCount > 0 && (
          <span className="is-issue">
            <i>!</i>
            {issueCount} need attention
          </span>
        )}
      </div>
      <div className="ledger-command-actions">
        {issueCount > 0 && (
          <button type="button" className="ledger-jump-button" onClick={onJumpToIssue}>
            <span />
            Jump to first issue
          </button>
        )}
        <fieldset className="ledger-filter-group">
          <legend className="sr-only">Filter evidence</legend>
          <LedgerFilterButton
            active={filter === "all"}
            count={total}
            label="All"
            onClick={() => onFilterChange("all")}
          />
          <LedgerFilterButton
            active={filter === "issues"}
            count={issueCount}
            label="Needs attention"
            onClick={() => onFilterChange("issues")}
          />
        </fieldset>
        <LedgerSearch label="Search evidence" value={search} onChange={onSearchChange} />
      </div>
    </header>
  );
}
