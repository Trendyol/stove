import { useLayoutEffect, useRef } from "react";
import type { Entry } from "../../api/types";
import { useIsPhone } from "../../hooks/usePhoneNavigation";
import { useRememberedScroll } from "../../hooks/useRememberedScroll";
import { formatTimestamp } from "../../utils/format";
import { getSystemInfo } from "../../utils/systems";
import { Detail } from "../Detail";
import { EntryDetails } from "../EntryDetails";
import { EvidenceActions } from "../EvidenceActions";
import { Icon } from "../Icon";
import { useRememberedState } from "./EvidenceViewMemory";
import { type EvidenceInspectorState, hasEntryDetail, isEntryIssue } from "./model";

interface EvidenceInspectorProps {
  state: EvidenceInspectorState;
  inspectorId: string;
  onSelect: (id: Entry["id"]) => void;
  onClose: () => void;
  onOpenTrace: (traceId: string) => void;
}

export function EvidenceInspector({
  state,
  inspectorId,
  onSelect,
  onClose,
  onOpenTrace,
}: EvidenceInspectorProps) {
  const [format, setFormat] = useRememberedState<"details" | "raw">("timeline.format", "details");
  const bodyRef = useRef<HTMLDivElement>(null);
  useRememberedScroll(bodyRef, "timeline.inspector", state.kind === "open");
  const inspectorRef = useRef<HTMLElement>(null);
  const isOpen = state.kind === "open";
  const isPhone = useIsPhone();
  useLayoutEffect(() => {
    if (isPhone && isOpen) inspectorRef.current?.querySelector<HTMLElement>("h2")?.focus();
  }, [isOpen, isPhone]);
  const close = () => {
    inspectorRef.current?.parentElement
      ?.querySelector<HTMLButtonElement>('.evidence-ledger-row[aria-pressed="true"]')
      ?.focus({ preventScroll: true });
    onClose();
  };
  if (state.kind === "closed") return null;

  const { entry, position, total, previous, next } = state;
  const system = getSystemInfo(entry.system);

  return (
    <aside
      ref={inspectorRef}
      id={inspectorId}
      onKeyDown={(event) => {
        if (
          event.key !== "Escape" ||
          event.defaultPrevented ||
          document.querySelector('[aria-modal="true"]')
        )
          return;
        event.stopPropagation();
        close();
      }}
      className="ledger-inspector evidence-side-inspector"
      aria-label={`Evidence details for ${entry.action}`}
    >
      <header className="ledger-inspector-header">
        <div>
          <h2 id={`${inspectorId}-heading`} tabIndex={-1}>
            {entry.action}
          </h2>
          <p>
            <span style={{ color: system.color }}>{system.icon}</span> {entry.system} ·{" "}
            {formatTimestamp(entry.timestamp)}
          </p>
        </div>
        <button
          type="button"
          className="inspector-close"
          onClick={close}
          aria-label="Close inspector"
        >
          ×
        </button>
      </header>

      <button type="button" className="phone-back" onClick={close}>
        ← Test
      </button>
      <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {entry.action}. Event {position + 1} of {total}.
      </span>
      <EvidenceActions />
      <div className="inspector-status-line">
        <span className={isEntryIssue(entry) ? "is-issue" : "is-success"}>{entry.result}</span>
        {entry.attempt_count > 1 && (
          <span>
            {entry.attempt_count} attempts · {entry.failure_count} failed
          </span>
        )}
        {entry.trace_id && (
          <button type="button" onClick={() => entry.trace_id && onOpenTrace(entry.trace_id)}>
            Related trace
            <Icon name="chevron" className="h-3.5 w-3.5" />
          </button>
        )}
      </div>

      <fieldset className="evidence-format">
        <legend className="sr-only">Evidence format</legend>
        {(["details", "raw"] as const).map((value) => (
          <button
            type="button"
            key={value}
            aria-pressed={format === value}
            onClick={() => setFormat(value)}
          >
            {value === "details" ? "Details" : "Raw"}
          </button>
        ))}
      </fieldset>
      <div ref={bodyRef} className="ledger-inspector-body">
        {format === "raw" ? (
          <Detail label="Recorded event" value={JSON.stringify(entry, null, 2)} />
        ) : (
          <EntryDetails entry={entry} />
        )}
        {format === "details" && !hasEntryDetail(entry) && (
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
    </aside>
  );
}
