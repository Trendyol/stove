import { useCallback, useEffect, useMemo } from "react";
import type { Span } from "../api/types";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import { getResultTone, isFailed } from "../utils/result";
import { useRememberedState, useRevealTarget } from "./evidence/EvidenceViewMemory";
import { buildSpanTreeRows, spanKey } from "./span-tree/model";
import { SpanInspector } from "./span-tree/SpanInspector";
import { SpanTreeRow } from "./span-tree/SpanTreeRow";
import { VirtualList } from "./VirtualList";

interface SpanTreeProps {
  spans: Span[];
  traceId?: string;
}

type SpanSelection = { kind: "none" } | { kind: "span"; spanId: string };

export function SpanTree({ spans, traceId }: SpanTreeProps) {
  const [collapsedSpanIds, setCollapsedSpanIds] = useRememberedState<Set<string>>(
    `trace.${traceId ?? "all"}.collapsed`,
    new Set(),
  );
  const navigation = useEvidenceNavigation();
  const [selection, setSelection] = useRememberedState<SpanSelection>(
    `trace.${traceId ?? "all"}.selection`,
    { kind: "none" },
  );
  useRevealTarget(
    `trace.${traceId ?? "all"}.target`,
    navigation?.focus?.kind === "span" ? String(navigation.focus.id) : undefined,
    useCallback(() => setCollapsedSpanIds(new Set()), []),
  );
  const rows = useMemo(() => buildSpanTreeRows(spans, collapsedSpanIds), [collapsedSpanIds, spans]);
  const totalFailed = useMemo(() => spans.filter((span) => isFailed(span.status)).length, [spans]);
  const totalNeutral = useMemo(
    () => spans.filter((span) => getResultTone(span.status) === "neutral").length,
    [spans],
  );
  const selectedSpan = navigation
    ? spans.find((span) => navigation.focus?.kind === "span" && span.id === navigation.focus.id)
    : selection.kind === "span"
      ? spans.find((span) => spanKey(span) === selection.spanId)
      : undefined;

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
  const closeInspector = useCallback(
    () => (navigation ? navigation.clear() : setSelection({ kind: "none" })),
    [navigation],
  );

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
        scrollToKey={selectedSpan ? spanKey(selectedSpan) : undefined}
        windowThreshold={120}
        renderItem={(row) => (
          <SpanTreeRow
            row={row}
            selected={row.span.id === selectedSpan?.id}
            onToggle={() => toggleSpan(spanKey(row.span))}
            onInspect={() =>
              navigation
                ? navigation.select("span", row.span.id)
                : setSelection({ kind: "span", spanId: spanKey(row.span) })
            }
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
