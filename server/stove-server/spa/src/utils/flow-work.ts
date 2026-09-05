import type { Entry, Span } from "../api/types";
import { applyDagreLayout, applyLinearTimelineLayout, entriesToDag, spansToTraceDag } from "./flow";

export const FLOW_NODE_LIMIT = 1_000;
// Each timeline entry can introduce a step and an idle-gap node.
export const TIMELINE_RECORD_LIMIT = FLOW_NODE_LIMIT / 2;
export type FlowInput = { mode: "timeline"; records: Entry[] } | { mode: "trace"; records: Span[] };
export type FlowGraph = ReturnType<typeof entriesToDag>;
export type FlowResult = { graph: FlowGraph; error?: never } | { error: string; graph?: never };

export function calculateFlow(input: FlowInput): FlowGraph {
  if (input.mode === "timeline") {
    const graph = entriesToDag(input.records.slice(-TIMELINE_RECORD_LIMIT));
    return { nodes: applyLinearTimelineLayout(graph.nodes), edges: graph.edges };
  }
  const graph = spansToTraceDag(input.records.slice(-FLOW_NODE_LIMIT));
  return { nodes: applyDagreLayout(graph.nodes, graph.edges), edges: graph.edges };
}
