import { useCallback, useEffect, useMemo, useState } from "react";
import type { Span } from "../api/types";
import { getResultTone, isFailed } from "../utils/result";
import { buildSpanTreeRows } from "./span-tree/model";
import { SpanInspector } from "./span-tree/SpanInspector";
import { SpanTreeRow } from "./span-tree/SpanTreeRow";
import { VirtualList } from "./VirtualList";

interface SpanTreeProps {
  spans: Span[];
}

type SpanSelection = { kind: "none" } | { kind: "span"; spanId: string };

export function SpanTree({ spans }: SpanTreeProps) {
  const [collapsedSpanIds, setCollapsedSpanIds] = useState<Set<string>>(new Set());
  const [selection, setSelection] = useState<SpanSelection>({ kind: "none" });
  const rows = useMemo(() => buildSpanTreeRows(spans, collapsedSpanIds), [collapsedSpanIds, spans]);
  const totalFailed = useMemo(() => spans.filter((span) => isFailed(span.status)).length, [spans]);
  const totalNeutral = useMemo(
    () => spans.filter((span) => getResultTone(span.status) === "neutral").length,
    [spans],
  );
  const selectedSpan =
    selection.kind === "span" ? spans.find((span) => span.span_id === selection.spanId) : undefined;

  useEffect(() => {
    if (selection.kind === "span" && !selectedSpan) setSelection({ kind: "none" });
  }, [selectedSpan, selection]);

  const toggleSpan = useCallback((spanId: string) => {
    setCollapsedSpanIds((current) => {
      const next = new Set(current);
      if (next.has(spanId)) next.delete(spanId);
      else next.add(spanId);
      return next;
    });
  }, []);
  const closeInspector = useCallback(() => setSelection({ kind: "none" }), []);

  if (spans.length === 0) {
    return (
      <div className="m-4 rounded-xl border border-dashed border-stove-border bg-stove-surface p-6 text-center text-sm text-[var(--stove-text-secondary)]">
        No spans recorded
      </div>
    );
  }

  return (
    <div className="span-tree-workbench">
      <VirtualList
        className="span-tree-list"
        ariaLabel="Recorded trace spans"
        items={rows}
        getKey={(row) => `${row.span.trace_id}:${row.span.span_id}`}
        getItemSize={44}
        windowThreshold={120}
        renderItem={(row) => (
          <SpanTreeRow
            row={row}
            selected={row.span.span_id === selectedSpan?.span_id}
            onToggle={() => toggleSpan(row.span.span_id)}
            onInspect={() => setSelection({ kind: "span", spanId: row.span.span_id })}
          />
        )}
      />
      <footer className="span-tree-summary">
        <span>{spans.length} spans</span>
        {totalFailed > 0 && <span className="text-[var(--stove-red)]">{totalFailed} failed</span>}
        {totalNeutral > 0 && <span>{totalNeutral} unset</span>}
        {rows[0] && <span>root: {rows[0].span.operation_name}</span>}
      </footer>
      <SpanInspector span={selectedSpan} onClose={closeInspector} />
    </div>
  );
}
