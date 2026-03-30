import dagre from "@dagrejs/dagre";
import type { Edge, Node } from "@xyflow/react";
import { MarkerType } from "@xyflow/react";
import type { Entry, Span } from "../api/types";
import { parseAttrs } from "./json";
import { isFailed } from "./result";

export interface SystemNodeData extends Record<string, unknown> {
  system: string;
  action: string;
  result: string;
  count: number;
  error: string | null;
  entries: Entry[];
  traceId: string | null;
}

export interface DurationEdgeData extends Record<string, unknown> {
  durationMs: number;
  label?: string;
}

const NODE_WIDTH = 200;
const NODE_HEIGHT = 80;

export function entriesToDag(entries: Entry[]): { nodes: Node[]; edges: Edge[] } {
  if (entries.length === 0) return { nodes: [], edges: [] };

  const withTime = entries.map((e) => ({ entry: e, time: new Date(e.timestamp).getTime() }));
  withTime.sort((a, b) => a.time - b.time);
  const sorted = withTime.map((w) => w.entry);

  const groups: Entry[][] = [];
  let current: Entry[] = [sorted[0]];

  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].system === current[0].system) {
      current.push(sorted[i]);
    } else {
      groups.push(current);
      current = [sorted[i]];
    }
  }
  groups.push(current);

  const nodes: Node[] = groups.map((group, i) => {
    const hasFailed = group.some((e) => isFailed(e.result));
    const firstError = group.find((e) => e.error)?.error ?? null;
    const actions = [...new Set(group.map((e) => e.action))];
    const traceId = group.find((e) => e.trace_id)?.trace_id ?? null;

    return {
      id: `entry-group-${i}`,
      type: "systemNode",
      position: { x: 0, y: 0 },
      data: {
        system: group[0].system,
        action: actions.length === 1 ? actions[0] : `${actions.length} actions`,
        result: hasFailed ? "FAILED" : "PASSED",
        count: group.length,
        error: firstError,
        entries: group,
        traceId,
      } satisfies SystemNodeData,
    };
  });

  const timeOf = (e: Entry) => new Date(e.timestamp).getTime();
  const edges: Edge[] = [];
  for (let i = 1; i < groups.length; i++) {
    const prevLast = groups[i - 1][groups[i - 1].length - 1];
    const currFirst = groups[i][0];
    const durationMs = timeOf(currFirst) - timeOf(prevLast);

    edges.push({
      id: `edge-${i - 1}-${i}`,
      source: `entry-group-${i - 1}`,
      target: `entry-group-${i}`,
      type: "durationEdge",
      markerEnd: { type: MarkerType.ArrowClosed },
      data: { durationMs } satisfies DurationEdgeData,
    });
  }

  return { nodes, edges };
}

export function spansToTraceDag(spans: Span[]): { nodes: Node[]; edges: Edge[] } {
  if (spans.length === 0) return { nodes: [], edges: [] };

  const nodes: Node[] = spans.map((s) => {
    const system = detectSystemFromSpan(s);
    const isError = s.status === "ERROR";

    return {
      id: s.span_id,
      type: "systemNode",
      position: { x: 0, y: 0 },
      data: {
        system,
        action: s.operation_name,
        result: isError ? "FAILED" : "PASSED",
        count: 1,
        error: s.exception_message ?? null,
        entries: [],
        traceId: s.trace_id,
      } satisfies SystemNodeData,
    };
  });

  const spanIds = new Set(spans.map((s) => s.span_id));
  const edges: Edge[] = spans
    .filter((s) => s.parent_span_id && spanIds.has(s.parent_span_id))
    .map((s) => {
      const durationMs = (s.end_time_nanos - s.start_time_nanos) / 1_000_000;
      return {
        id: `span-edge-${s.parent_span_id}-${s.span_id}`,
        source: s.parent_span_id!,
        target: s.span_id,
        type: "durationEdge",
        markerEnd: { type: MarkerType.ArrowClosed },
        data: { durationMs } satisfies DurationEdgeData,
      };
    });

  return { nodes, edges };
}

export function applyDagreLayout(
  nodes: Node[],
  edges: Edge[],
  direction: "LR" | "TB" = "LR",
): Node[] {
  if (nodes.length === 0) return nodes;

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({ rankdir: direction, nodesep: 50, ranksep: 80 });

  for (const node of nodes) {
    g.setNode(node.id, { width: NODE_WIDTH, height: NODE_HEIGHT });
  }
  for (const edge of edges) {
    g.setEdge(edge.source, edge.target);
  }

  dagre.layout(g);

  return nodes.map((node) => {
    const pos = g.node(node.id);
    return {
      ...node,
      position: { x: pos.x - NODE_WIDTH / 2, y: pos.y - NODE_HEIGHT / 2 },
    };
  });
}

const DB_SYSTEM_MAP: Record<string, string> = {
  postgresql: "PostgreSQL",
  mysql: "MySQL",
  mssql: "MSSQL",
  mongodb: "MongoDB",
  redis: "Redis",
  couchbase: "Couchbase",
  elasticsearch: "Elasticsearch",
  cassandra: "Cassandra",
};

function detectSystemFromSpan(span: Span): string {
  const attrs = parseAttrs(span.attributes);
  const keys = Object.keys(attrs);

  if (keys.some((k) => k.startsWith("http."))) return "HTTP";
  if (keys.some((k) => k.startsWith("messaging."))) return "Kafka";
  if (keys.some((k) => k.startsWith("db."))) {
    const dbSystem = attrs["db.system"];
    if (dbSystem) return DB_SYSTEM_MAP[dbSystem.toLowerCase()] ?? dbSystem;
    return "Database";
  }
  if (keys.some((k) => k.startsWith("rpc."))) return "gRPC";

  return span.service_name || "Unknown";
}
