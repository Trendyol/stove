import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Entry } from "../api/types";
import { useFocusShortcut } from "../hooks/useFocusShortcut";
import { EvidenceInspector, type EvidenceInspectorState } from "./evidence/EvidenceInspector";
import { EvidenceRow } from "./evidence/EvidenceRow";
import {
  type EvidenceFilter,
  type EvidenceSelection,
  filterEvidence,
  isEntryIssue,
} from "./evidence/model";
import { Icon } from "./Icon";
import { LedgerFilterButton } from "./LedgerFilterButton";
import { VirtualList } from "./VirtualList";

interface EvidenceWorkbenchProps {
  entries: Entry[];
  onOpenTrace: () => void;
}

export function EvidenceWorkbench({ entries, onOpenTrace }: EvidenceWorkbenchProps) {
  const [filter, setFilter] = useState<EvidenceFilter>("all");
  const [search, setSearch] = useState("");
  const [selection, setSelection] = useState<EvidenceSelection>({ kind: "none" });
  const searchRef = useRef<HTMLInputElement>(null);
  useFocusShortcut(searchRef, "/");

  useEffect(() => {
    if (selection.kind === "entry" && !entries.some((entry) => entry.id === selection.entryId)) {
      setSelection({ kind: "none" });
    }
  }, [entries, selection]);

  const issueCount = useMemo(() => entries.filter(isEntryIssue).length, [entries]);
  const visibleEntries = useMemo(
    () => filterEvidence(entries, filter, search),
    [entries, filter, search],
  );
  const inspectorState = useMemo(
    () => resolveInspectorState(selection, visibleEntries),
    [selection, visibleEntries],
  );
  const closeInspector = useCallback(() => setSelection({ kind: "none" }), []);

  const jumpToFirstIssue = () => {
    const issue = entries.find(isEntryIssue);
    if (!issue) return;
    setFilter("all");
    setSearch("");
    setSelection({ kind: "entry", entryId: issue.id });
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
            <LedgerFilterButton
              active={filter === "all"}
              count={entries.length}
              label="All"
              onClick={() => setFilter("all")}
            />
            <LedgerFilterButton
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

      {visibleEntries.length > 0 ? (
        <VirtualList
          className="evidence-ledger"
          ariaLabel="Recorded test evidence"
          items={visibleEntries}
          getKey={(entry) => `${entry.assertion_id}:${entry.id}`}
          getItemSize={56}
          renderItem={(entry) => (
            <EvidenceRow
              entry={entry}
              selected={selection.kind === "entry" && entry.id === selection.entryId}
              onSelect={() => setSelection({ kind: "entry", entryId: entry.id })}
            />
          )}
        />
      ) : (
        <section className="evidence-ledger" aria-label="Recorded test evidence">
          <EvidenceEmptyState filtered={entries.length > 0} />
        </section>
      )}

      <EvidenceInspector
        state={inspectorState}
        onSelect={(entryId) => setSelection({ kind: "entry", entryId })}
        onClose={closeInspector}
        onOpenTrace={onOpenTrace}
      />
    </div>
  );
}

function resolveInspectorState(
  selection: EvidenceSelection,
  entries: Entry[],
): EvidenceInspectorState {
  if (selection.kind === "none") return { kind: "closed" };
  const position = entries.findIndex((entry) => entry.id === selection.entryId);
  if (position < 0) return { kind: "closed" };
  return {
    kind: "open",
    entry: entries[position],
    position,
    total: entries.length,
    previous: position > 0 ? entries[position - 1] : undefined,
    next: position < entries.length - 1 ? entries[position + 1] : undefined,
  };
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
