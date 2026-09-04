import { useEffect, useRef } from "react";
import type { Span } from "../../api/types";
import { formatNanosDuration } from "../../utils/format";
import { parseAttrs } from "../../utils/json";
import { getResultTone } from "../../utils/result";

interface SpanInspectorProps {
  span?: Span;
  onClose: () => void;
}

export function SpanInspector({ span, onClose }: SpanInspectorProps) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const spanId = span?.span_id;

  useEffect(() => {
    if (!spanId) return;
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
  }, [onClose, spanId]);

  if (!span) return null;

  const attributes = Object.entries(parseAttrs(span.attributes));
  const tone = getResultTone(span.status);
  return (
    <div className="evidence-dialog-layer">
      <button
        type="button"
        className="evidence-dialog-backdrop"
        aria-label="Close span details"
        onClick={onClose}
      />
      <section
        className="ledger-inspector evidence-dialog trace-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={`Span details for ${span.operation_name}`}
      >
        <header className="ledger-inspector-header">
          <div>
            <strong>{span.operation_name}</strong>
            <p>{span.service_name}</p>
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
          <span className={`is-${tone}`}>{span.status}</span>
          <span>{formatNanosDuration(span.start_time_nanos, span.end_time_nanos)}</span>
          <span className="trace-dialog-id">{span.span_id}</span>
        </div>
        <div className="ledger-inspector-body trace-dialog-body">
          {span.exception_type && (
            <section className="trace-exception">
              <strong>{span.exception_type}</strong>
              {span.exception_message && <p>{span.exception_message}</p>}
              {span.exception_stack_trace && <pre>{span.exception_stack_trace}</pre>}
            </section>
          )}
          {attributes.length > 0 ? (
            <dl className="trace-attributes">
              {attributes.map(([key, value]) => (
                <div key={key}>
                  <dt>{key}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          ) : (
            !span.exception_type && (
              <div className="inspector-no-detail">
                No attributes or exception details were captured.
              </div>
            )
          )}
        </div>
      </section>
    </div>
  );
}
