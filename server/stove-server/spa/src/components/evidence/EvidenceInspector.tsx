import type { Entry } from "../../api/types";
import { useModalDialog } from "../../hooks/useModalDialog";
import { formatTimestamp } from "../../utils/format";
import { getSystemInfo } from "../../utils/systems";
import { EntryDetails } from "../EntryDetails";
import { Icon } from "../Icon";
import { hasEntryDetail, isEntryIssue } from "./model";

export type EvidenceInspectorState =
  | { kind: "closed" }
  | {
      kind: "open";
      entry: Entry;
      position: number;
      total: number;
      previous?: Entry;
      next?: Entry;
    };

interface EvidenceInspectorProps {
  state: EvidenceInspectorState;
  onSelect: (id: Entry["id"]) => void;
  onClose: () => void;
  onOpenTrace: () => void;
}

export function EvidenceInspector({
  state,
  onSelect,
  onClose,
  onOpenTrace,
}: EvidenceInspectorProps) {
  const closeButtonRef = useModalDialog(state.kind === "open", onClose);
  if (state.kind === "closed") return null;

  const { entry, position, total, previous, next } = state;
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
          {entry.attempt_count > 1 && (
            <span>
              {entry.attempt_count} attempts · {entry.failure_count} failed
            </span>
          )}
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
            disabled={previous === undefined}
            onClick={() => previous && onSelect(previous.id)}
          >
            ← Previous
          </button>
          <span>
            {position + 1} / {total}
          </span>
          <button
            type="button"
            disabled={next === undefined}
            onClick={() => next && onSelect(next.id)}
          >
            Next →
          </button>
        </footer>
      </section>
    </div>
  );
}
