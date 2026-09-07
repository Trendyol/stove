import { type Edge, MarkerType, type Node } from "@xyflow/react";
import type { Span } from "../../api/types";
import { parseAttrs } from "../json";
import type { DurationEdgeData, FlowNodeData, SystemNodeData } from "./types";

export function spansToTraceDag(spans: Span[]): { nodes: Node<FlowNodeData>[]; edges: Edge[] } {
  if (spans.length === 0) return { nodes: [], edges: [] };

  const nodes: Node<FlowNodeData>[] = spans.map((span) => {
    const system = detectSystemFromSpan(span);

    return {
      id: span.span_id,
      type: "systemNode",
      position: { x: 0, y: 0 },
      data: {
        kind: "trace",
        system,
        action: span.operation_name,
        result: span.status,
        count: 1,
        error: span.exception_message ?? null,
        entries: [],
        traceId: span.trace_id,
        startedAt: null,
        endedAt: null,
        durationMs: Math.max(0, (span.end_time_nanos - span.start_time_nanos) / 1_000_000),
        inspectable: true,
      } satisfies SystemNodeData,
    };
  });

  const spanIds = new Set(spans.map((span) => span.span_id));
  const edges: Edge[] = spans.flatMap((span) => {
    const parentId = span.parent_span_id;
    if (!parentId || !spanIds.has(parentId)) return [];
    return [
      {
        id: `span-edge-${parentId}-${span.span_id}`,
        source: parentId,
        target: span.span_id,
        type: "durationEdge",
        markerEnd: { type: MarkerType.ArrowClosed },
        data: {
          durationMs: Math.max(0, (span.end_time_nanos - span.start_time_nanos) / 1_000_000),
        } satisfies DurationEdgeData,
      },
    ];
  });

  return { nodes, edges };
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

  if (keys.some((key) => key.startsWith("http."))) return "HTTP";
  if (keys.some((key) => key.startsWith("messaging."))) return "Kafka";
  if (keys.some((key) => key.startsWith("db."))) {
    const dbSystem = attrs["db.system"];
    if (dbSystem) return DB_SYSTEM_MAP[dbSystem.toLowerCase()] ?? dbSystem;
    return "Database";
  }
  if (keys.some((key) => key.startsWith("rpc."))) return "gRPC";

  return span.service_name || "Unknown";
}
