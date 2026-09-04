import type { RefObject } from "react";
import type { MockWarning } from "../../api/types";
import { formatDuration } from "../../utils/format";
import { Icon } from "../Icon";
import { LedgerFilterButton } from "../LedgerFilterButton";
import type { InteractionFilter, JournalStats } from "./model";
import { humanize } from "./model";

interface JournalCommandBarProps {
  stats: JournalStats;
  ambientCount: number;
  includeAmbient: boolean;
  search: string;
  searchRef: RefObject<HTMLInputElement | null>;
  onIncludeAmbientChange: (include: boolean) => void;
  onSearchChange: (search: string) => void;
  onJumpToIssue: () => void;
}

export function JournalCommandBar({
  stats,
  ambientCount,
  includeAmbient,
  search,
  searchRef,
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
        <label className="ledger-search">
          <Icon name="search" className="h-4 w-4" />
          <span className="sr-only">Search mock exchanges</span>
          <input
            ref={searchRef}
            value={search}
            onChange={(event) => onSearchChange(event.target.value)}
            placeholder="Search exchanges"
          />
          <kbd>/</kbd>
        </label>
      </div>
    </header>
  );
}

interface WarningRibbonProps {
  warnings: readonly MockWarning[];
  expanded: boolean;
  selectedWarningId: MockWarning["id"] | undefined;
  onExpandedChange: (expanded: boolean) => void;
  onSelect: (warning: MockWarning) => void;
}

export function WarningRibbon({
  warnings,
  expanded,
  selectedWarningId,
  onExpandedChange,
  onSelect,
}: WarningRibbonProps) {
  if (warnings.length === 0) return null;

  return (
    <section className="warning-ribbon" aria-label="Mock warnings">
      <header className="warning-ribbon-header">
        <div className="warning-ribbon-title">
          <span className="warning-ribbon-icon">
            <Icon name="warning" className="h-4 w-4" />
          </span>
          <span className="warning-ribbon-copy">
            <strong>Mock warnings</strong>
            <small>Select a warning to inspect its related exchange</small>
          </span>
          <em>{warnings.length}</em>
        </div>
        <button
          type="button"
          className="warning-ribbon-toggle"
          aria-controls="mock-warning-list"
          aria-expanded={expanded}
          onClick={() => onExpandedChange(!expanded)}
        >
          {expanded ? "Collapse" : "Review"}
          <Icon name="chevron" className="h-3.5 w-3.5" />
        </button>
      </header>
      {expanded && (
        <div id="mock-warning-list" className="warning-ribbon-list">
          {warnings.map((warning) => (
            <button
              type="button"
              key={warning.id}
              className={warning.id === selectedWarningId ? "is-selected" : ""}
              aria-pressed={warning.id === selectedWarningId}
              onClick={() => onSelect(warning)}
            >
              <span className="warning-ribbon-item-heading">
                <span>{humanize(warning.kind)}</span>
                <strong>{warning.target ?? warning.system}</strong>
              </span>
              <p>{warning.message}</p>
              <Icon name="chevron" className="h-3.5 w-3.5" />
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

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

export function MockEmptyState({ filtered }: { filtered: boolean }) {
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
