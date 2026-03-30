import { useMemo, useState } from "react";
import type { Span } from "../api/types";
import { formatNanosDuration } from "../utils/format";
import { parseAttrs } from "../utils/json";
import { getResultTone, isFailed } from "../utils/result";

interface SpanTreeProps {
  spans: Span[];
}

interface SpanNode {
  span: Span;
  children: SpanNode[];
}

export function SpanTree({ spans }: SpanTreeProps) {
  const tree = useMemo(() => buildTree(spans), [spans]);
  const totalFailed = spans.filter((s) => isFailed(s.status)).length;
  const totalNeutral = spans.filter((s) => getResultTone(s.status) === "neutral").length;

  if (spans.length === 0) {
    return <div className="text-[var(--stove-text-secondary)] text-sm p-4">No spans recorded</div>;
  }

  return (
    <div className="space-y-1 p-2">
      {tree.map((node) => (
        <SpanNodeView key={node.span.span_id} node={node} depth={0} />
      ))}
      <div className="mt-3 pt-2 border-t border-stove-border text-xs text-[var(--stove-text-secondary)] flex gap-4">
        <span>{spans.length} spans</span>
        {totalFailed > 0 && <span className="text-[var(--stove-red)]">{totalFailed} failed</span>}
        {totalNeutral > 0 && <span>{totalNeutral} unset</span>}
        {tree[0] && <span>root: {tree[0].span.operation_name}</span>}
      </div>
    </div>
  );
}

function SpanNodeView({ node, depth }: { node: SpanNode; depth: number }) {
  const [collapsed, setCollapsed] = useState(false);
  const s = node.span;
  const tone = getResultTone(s.status);
  const isError = tone === "failed";
  const duration = formatNanosDuration(s.start_time_nanos, s.end_time_nanos);
  const attrs = parseAttrs(s.attributes);
  const relevantAttrs = Object.entries(attrs).filter(([k]) =>
    ["db.", "http.", "rpc.", "messaging."].some((p) => k.startsWith(p)),
  );
  const statusColor =
    tone === "failed"
      ? "var(--stove-red)"
      : tone === "success"
        ? "var(--stove-green)"
        : "var(--stove-text-secondary)";
  const statusIcon = tone === "failed" ? "\u2717" : tone === "success" ? "\u2713" : "\u2022";

  return (
    <div style={{ marginLeft: depth * 20 }}>
      <button
        type="button"
        className={`flex items-center gap-2 px-2 py-1 rounded text-sm cursor-pointer hover:bg-[var(--stove-hover)] w-full text-left bg-transparent border-0 ${
          isError ? "border-l-2 border-red-500 bg-[rgba(248,113,113,0.04)]" : ""
        }`}
        aria-expanded={!collapsed}
        onClick={() => setCollapsed(!collapsed)}
      >
        {node.children.length > 0 && (
          <span
            className="text-[var(--stove-text-secondary)] text-xs transition-transform"
            style={{ transform: collapsed ? "rotate(-90deg)" : "" }}
          >
            {"\u25bc"}
          </span>
        )}
        <span style={{ color: statusColor }}>{statusIcon}</span>
        <span className="text-[var(--stove-text)]">{s.operation_name}</span>
        <span className="text-[var(--stove-text-secondary)] font-mono text-xs">[{duration}]</span>
        <span className="text-[var(--stove-text-muted)] text-xs">{s.service_name}</span>
        {tone === "neutral" && (
          <span className="text-[var(--stove-text-secondary)] text-[10px] font-mono uppercase">
            {s.status}
          </span>
        )}
      </button>

      {!collapsed && (
        <>
          {isError && s.exception_type && (
            <div className="ml-8 mt-1 text-xs">
              <span className="text-[var(--stove-amber)]">{s.exception_type}: </span>
              <span className="text-[var(--stove-red)]">{s.exception_message}</span>
              {s.exception_stack_trace && (
                <pre className="mt-1 text-[var(--stove-text-muted)] text-[10px] whitespace-pre-wrap">
                  {s.exception_stack_trace}
                </pre>
              )}
            </div>
          )}

          {relevantAttrs.length > 0 && (
            <div className="ml-8 mt-0.5 text-xs text-[var(--stove-text-muted)] flex flex-wrap gap-2">
              {relevantAttrs.map(([k, v]) => (
                <span key={k}>
                  {k}=<span className="text-[var(--stove-text-secondary)]">{v}</span>
                </span>
              ))}
            </div>
          )}

          {node.children.map((child) => (
            <SpanNodeView key={child.span.span_id} node={child} depth={depth + 1} />
          ))}
        </>
      )}
    </div>
  );
}

function buildTree(spans: Span[]): SpanNode[] {
  const map = new Map<string, SpanNode>();
  const roots: SpanNode[] = [];

  for (const span of spans) {
    map.set(span.span_id, { span, children: [] });
  }

  for (const span of spans) {
    const node = map.get(span.span_id);
    if (!node) continue;
    const parent = span.parent_span_id ? map.get(span.parent_span_id) : undefined;
    if (parent) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
  }

  return roots;
}
