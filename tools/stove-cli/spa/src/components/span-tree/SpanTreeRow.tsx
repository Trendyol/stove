import { memo } from "react";
import { formatNanosDuration } from "../../utils/format";
import { getResultTone } from "../../utils/result";
import type { SpanTreeRowModel } from "./model";

interface SpanTreeRowProps {
  row: SpanTreeRowModel;
  selected: boolean;
  onToggle: () => void;
  onInspect: () => void;
}

export const SpanTreeRow = memo(function SpanTreeRow({
  row,
  selected,
  onToggle,
  onInspect,
}: SpanTreeRowProps) {
  const { span } = row;
  const tone = getResultTone(span.status);
  const statusIcon = tone === "failed" ? "✕" : tone === "success" ? "✓" : "•";

  return (
    <div
      className={`span-tree-row is-${tone} ${selected ? "is-selected" : ""}`}
      style={{ paddingLeft: `${row.depth * 18 + 8}px` }}
    >
      {row.hasChildren ? (
        <button
          type="button"
          className={`span-tree-toggle ${row.collapsed ? "" : "is-open"}`}
          aria-label={`${row.collapsed ? "Expand" : "Collapse"} ${span.operation_name}`}
          aria-expanded={!row.collapsed}
          onClick={onToggle}
        >
          ▶
        </button>
      ) : (
        <span className="span-tree-toggle-placeholder" />
      )}
      <button
        type="button"
        className="span-tree-primary"
        aria-label={`Inspect ${span.operation_name}, ${span.status}`}
        onClick={onInspect}
      >
        <span className="span-tree-status" aria-hidden="true">
          {statusIcon}
        </span>
        <strong>{span.operation_name}</strong>
        <span>{span.service_name}</span>
        {span.exception_type && <em>exception</em>}
        <time>{formatNanosDuration(span.start_time_nanos, span.end_time_nanos)}</time>
      </button>
    </div>
  );
});
