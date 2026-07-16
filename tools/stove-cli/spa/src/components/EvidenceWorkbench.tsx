import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Entry } from "../api/types";
import { formatTimestamp } from "../utils/format";
import { getSystemInfo } from "../utils/systems";
import { EntryDetails } from "./EntryDetails";
import { Icon } from "./Icon";

interface EvidenceWorkbenchProps {
  entries: Entry[];
  onOpenTrace: () => void;
}

type EvidenceFilter = "all" | "issues";

export function EvidenceWorkbench({ entries, onOpenTrace }: EvidenceWorkbenchProps) {
  const [filter, setFilter] = useState<EvidenceFilter>("all");
  const [search, setSearch] = useState("");
  const [selectedId, setSelectedId] = useState<Entry["id"] | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const closeInspector = useCallback(() => setSelectedId(null), []);

  useEffect(() => {
    if (selectedId != null && !entries.some((entry) => entry.id === selectedId)) {
      setSelectedId(null);
    }
  }, [entries, selectedId]);

  useEffect(() => {
    const focusSearch = (event: KeyboardEvent) => {
      if (event.key !== "/" || isEditableTarget(event.target)) return;
      event.preventDefault();
      searchRef.current?.focus();
    };
    window.addEventListener("keydown", focusSearch);
    return () => window.removeEventListener("keydown", focusSearch);
  }, []);

  const issueCount = entries.filter(isEntryIssue).length;
  const visibleEntries = useMemo(() => {
    const query = search.trim().toLowerCase();
    return entries.filter((entry) => {
      if (filter === "issues" && !isEntryIssue(entry)) return false;
      if (!query) return true;
      return [
        entry.system,
        entry.action,
        entry.result,
        entry.input,
        entry.output,
        entry.expected,
        entry.actual,
        entry.error,
        entry.metadata,
      ].some((value) => value?.toLowerCase().includes(query));
    });
  }, [entries, filter, search]);
  const selectedEntry = entries.find((entry) => entry.id === selectedId) ?? null;

  const jumpToFirstIssue = () => {
    const issue = entries.find(isEntryIssue);
    if (issue) {
      setFilter("all");
      setSearch("");
      setSelectedId(issue.id);
    }
  };

  return (
    <div className="evidence-workbench">
      <header className="ledger-command-bar">
        <div className="ledger-command-summary">
          <strong>{entries.length}</strong> events
          {issueCount > 0 && (
            <span className="is-issue">
              <i>!</i>
              {issueCount} need attention
            </span>
          )}
        </div>
        <div className="ledger-command-actions">
          {issueCount > 0 && (
            <button type="button" className="ledger-jump-button" onClick={jumpToFirstIssue}>
              <span />
              Jump to first issue
            </button>
          )}
          <fieldset className="ledger-filter-group">
            <legend className="sr-only">Filter evidence</legend>
            <FilterButton
              active={filter === "all"}
              count={entries.length}
              label="All"
              onClick={() => setFilter("all")}
            />
            <FilterButton
              active={filter === "issues"}
              count={issueCount}
              label="Needs attention"
              onClick={() => setFilter("issues")}
            />
          </fieldset>
          <label className="ledger-search">
            <Icon name="search" className="h-4 w-4" />
            <span className="sr-only">Search evidence</span>
            <input
              ref={searchRef}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search evidence"
            />
            <kbd>/</kbd>
          </label>
        </div>
      </header>

      <section className="evidence-ledger" aria-label="Recorded test evidence">
        {visibleEntries.length > 0 ? (
          visibleEntries.map((entry) => (
            <EvidenceRow
              key={entry.id}
              entry={entry}
              selected={entry.id === selectedId}
              onSelect={() => setSelectedId(entry.id)}
            />
          ))
        ) : (
          <LedgerEmpty
            title={entries.length > 0 ? "No matching evidence" : "No evidence recorded"}
            detail={
              entries.length > 0
                ? "Broaden the filter or clear the search."
                : "This test did not report any events."
            }
          />
        )}
      </section>

      <EvidenceInspector
        entry={selectedEntry}
        entries={entries}
        onSelect={setSelectedId}
        onClose={closeInspector}
        onOpenTrace={onOpenTrace}
      />
    </div>
  );
}

function EvidenceRow({
  entry,
  selected,
  onSelect,
}: {
  entry: Entry;
  selected: boolean;
  onSelect: () => void;
}) {
  const system = getSystemInfo(entry.system);
  const issue = isEntryIssue(entry);

  return (
    <button
      type="button"
      className={`evidence-ledger-row ${selected ? "is-selected" : ""} ${
        issue ? "is-issue" : "is-success"
      }`}
      aria-haspopup="dialog"
      onClick={onSelect}
    >
      <span className="ledger-rail-point" aria-hidden="true">
        {issue ? "!" : ""}
      </span>
      <time>{formatTimestamp(entry.timestamp)}</time>
      <span
        className="ledger-system-glyph"
        style={{ "--system-color": system.color } as React.CSSProperties}
      >
        {system.icon}
      </span>
      <span className="ledger-primary">
        <strong>{entry.action}</strong>
        <span>{entry.system}</span>
      </span>
      {entry.trace_id && <span className="ledger-trace-stamp">trace</span>}
      <span className={`ledger-result is-${issue ? "issue" : "success"}`}>{entry.result}</span>
      <Icon name="chevron" className="h-4 w-4" />
    </button>
  );
}

function EvidenceInspector({
  entry,
  entries,
  onSelect,
  onClose,
  onOpenTrace,
}: {
  entry: Entry | null;
  entries: Entry[];
  onSelect: (id: Entry["id"]) => void;
  onClose: () => void;
  onOpenTrace: () => void;
}) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const isOpen = entry != null;

  useEffect(() => {
    if (!isOpen) return;
    const previouslyFocused = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButtonRef.current?.focus();
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", closeOnEscape);
      if (previouslyFocused instanceof HTMLElement) previouslyFocused.focus();
    };
  }, [isOpen, onClose]);

  if (!entry) return null;

  const position = entries.findIndex((candidate) => candidate.id === entry.id);
  const previous = position > 0 ? entries[position - 1] : null;
  const next = position >= 0 && position < entries.length - 1 ? entries[position + 1] : null;
  const system = getSystemInfo(entry.system);

  return (
    <div className="evidence-dialog-layer">
      <button
        type="button"
        className="evidence-dialog-backdrop"
        aria-label="Close evidence details"
        onClick={onClose}
      />
      <section
        className="ledger-inspector evidence-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={`Evidence details for ${entry.action}`}
      >
        <header className="ledger-inspector-header">
          <div>
            <strong>{entry.action}</strong>
            <p>
              <span style={{ color: system.color }}>{system.icon}</span> {entry.system} ·{" "}
              {formatTimestamp(entry.timestamp)}
            </p>
          </div>
          <button
            type="button"
            className="inspector-close"
            ref={closeButtonRef}
            onClick={onClose}
            aria-label="Close inspector"
          >
            ×
          </button>
        </header>

        <div className="inspector-status-line">
          <span className={isEntryIssue(entry) ? "is-issue" : "is-success"}>{entry.result}</span>
          {entry.trace_id && (
            <button type="button" onClick={onOpenTrace}>
              Open trace
              <Icon name="chevron" className="h-3.5 w-3.5" />
            </button>
          )}
        </div>

        <div className="ledger-inspector-body">
          <EntryDetails entry={entry} />
          {!hasEntryDetail(entry) && (
            <div className="inspector-no-detail">No payload was captured for this event.</div>
          )}
        </div>

        <footer className="ledger-inspector-nav">
          <button
            type="button"
            disabled={!previous}
            onClick={() => previous && onSelect(previous.id)}
          >
            ← Previous
          </button>
          <span>
            {position + 1} / {entries.length}
          </span>
          <button type="button" disabled={!next} onClick={() => next && onSelect(next.id)}>
            Next →
          </button>
        </footer>
      </section>
    </div>
  );
}

function FilterButton({
  active,
  count,
  label,
  onClick,
}: {
  active: boolean;
  count: number;
  label: string;
  onClick: () => void;
}) {
  return (
    <button type="button" className={active ? "is-active" : ""} onClick={onClick}>
      {label}
      <span>{count}</span>
    </button>
  );
}

function LedgerEmpty({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="ledger-empty">
      <span className="mock-empty-orbit">
        <Icon name="activity" className="h-5 w-5" />
      </span>
      <div>
        <strong>{title}</strong>
        <p>{detail}</p>
      </div>
    </div>
  );
}

function isEntryIssue(entry: Entry): boolean {
  return entry.result === "FAILED" || entry.result === "ERROR" || entry.error != null;
}

function hasEntryDetail(entry: Entry): boolean {
  return Boolean(
    entry.input ||
      entry.output ||
      entry.expected ||
      entry.actual ||
      entry.error ||
      (entry.metadata && entry.metadata !== "{}"),
  );
}

function isEditableTarget(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement ||
    (target instanceof HTMLElement && target.isContentEditable)
  );
}
