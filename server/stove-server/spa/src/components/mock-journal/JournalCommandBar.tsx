import { formatDuration } from "../../utils/format";
import { LedgerSearch } from "../LedgerSearch";
import type { JournalStats } from "./model";

interface JournalCommandBarProps {
  stats: JournalStats;
  ambientCount: number;
  includeAmbient: boolean;
  search: string;
  onIncludeAmbientChange: (include: boolean) => void;
  onSearchChange: (search: string) => void;
  onJumpToIssue: () => void;
}

export function JournalCommandBar({
  stats,
  ambientCount,
  includeAmbient,
  search,
  onIncludeAmbientChange,
  onSearchChange,
  onJumpToIssue,
}: JournalCommandBarProps) {
  return (
    <header className="ledger-command-bar mock-command-bar">
      <div className="ledger-command-summary">
        <strong>{stats.all}</strong> exchanges
        {stats.issues > 0 && (
          <span className="is-issue">
            <i>!</i>
            {stats.issues} need attention
          </span>
        )}
        <span>{stats.matchRate.kind === "rate" ? `${stats.matchRate.value}%` : "—"} matched</span>
        {stats.slowest.kind === "duration" && (
          <span>{formatDuration(stats.slowest.milliseconds)} slowest</span>
        )}
      </div>
      <div className="ledger-command-actions">
        {stats.issues > 0 && (
          <button type="button" className="ledger-jump-button" onClick={onJumpToIssue}>
            <span />
            Jump to first issue
          </button>
        )}
        {ambientCount > 0 && (
          <label className="ambient-toggle">
            <input
              type="checkbox"
              checked={includeAmbient}
              onChange={(event) => onIncludeAmbientChange(event.target.checked)}
            />
            <span />
            Include ambient
            <strong>{ambientCount}</strong>
          </label>
        )}
        <LedgerSearch
          label="Search mock exchanges"
          placeholder="Search exchanges"
          value={search}
          onChange={onSearchChange}
        />
      </div>
    </header>
  );
}
