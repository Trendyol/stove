import type { Span } from "../../api/types";

interface SpanNode {
  span: Span;
  children: SpanNode[];
}

export interface SpanTreeRowModel {
  span: Span;
  depth: number;
  hasChildren: boolean;
  collapsed: boolean;
}

export function buildSpanTreeRows(
  spans: readonly Span[],
  collapsedSpanIds: ReadonlySet<string>,
): SpanTreeRowModel[] {
  const nodes = new Map<string, SpanNode>();
  for (const span of spans) {
    nodes.set(span.span_id, { span, children: [] });
  }

  const roots: SpanNode[] = [];
  for (const node of nodes.values()) {
    const parentId = node.span.parent_span_id;
    const parent = parentId && parentId !== node.span.span_id ? nodes.get(parentId) : undefined;
    if (parent) parent.children.push(node);
    else roots.push(node);
  }

  roots.sort(compareNodes);
  for (const node of nodes.values()) node.children.sort(compareNodes);

  const rows: SpanTreeRowModel[] = [];
  const visited = new Set<string>();
  const append = (node: SpanNode, depth: number) => {
    if (visited.has(node.span.span_id)) return;
    visited.add(node.span.span_id);
    const collapsed = collapsedSpanIds.has(node.span.span_id);
    rows.push({
      span: node.span,
      depth,
      hasChildren: node.children.length > 0,
      collapsed,
    });
    if (collapsed) {
      for (const child of node.children) markVisited(child, visited);
      return;
    }
    for (const child of node.children) append(child, depth + 1);
  };

  for (const root of roots) append(root, 0);
  // Malformed cyclic traces have no natural root. Keep them inspectable without
  // allowing the tree traversal to recurse forever.
  for (const node of nodes.values()) {
    if (!visited.has(node.span.span_id)) append(node, 0);
  }
  return rows;
}

function markVisited(node: SpanNode, visited: Set<string>) {
  if (visited.has(node.span.span_id)) return;
  visited.add(node.span.span_id);
  for (const child of node.children) markVisited(child, visited);
}

function compareNodes(left: SpanNode, right: SpanNode): number {
  return (
    left.span.start_time_nanos - right.span.start_time_nanos ||
    left.span.span_id.localeCompare(right.span.span_id)
  );
}
