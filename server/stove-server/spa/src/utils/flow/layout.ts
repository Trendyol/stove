import dagre from "@dagrejs/dagre";
import type { Edge, Node } from "@xyflow/react";
import type { FlowNodeData } from "./types";

const STEP_NODE_SIZE = { width: 240, height: 128 };
const TRACE_NODE_SIZE = { width: 240, height: 120 };
const ARRANGE_NODE_SIZE = { width: 240, height: 128 };
const GAP_NODE_SIZE = { width: 208, height: 96 };

export function applyDagreLayout(
  nodes: Node<FlowNodeData>[],
  edges: Edge[],
  direction: "LR" | "TB" = "LR",
): Node<FlowNodeData>[] {
  if (nodes.length === 0) return nodes;

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: direction,
    nodesep: 128,
    ranksep: 176,
    edgesep: 48,
    marginx: 24,
    marginy: 24,
  });

  for (const node of nodes) {
    const size = getNodeLayoutSize(node);
    g.setNode(node.id, size);
  }
  for (const edge of edges) {
    g.setEdge(edge.source, edge.target);
  }

  dagre.layout(g);

  return nodes.map((node) => {
    const pos = g.node(node.id);
    const size = getNodeLayoutSize(node);
    return {
      ...node,
      position: { x: pos.x - size.width / 2, y: pos.y - size.height / 2 },
    };
  });
}

/** Timeline data is already ordered, so a general graph solver only adds quadratic work. */
export function applyLinearTimelineLayout(nodes: Node<FlowNodeData>[]): Node<FlowNodeData>[] {
  let timelineIndex = 0;
  let arrangeIndex = 0;
  return nodes.map((node) => {
    const arrange = node.type === "systemNode" && node.data.kind === "arrange";
    if (arrange) {
      arrangeIndex += 1;
      return {
        ...node,
        position: { x: 24, y: -(arrangeIndex * (ARRANGE_NODE_SIZE.height + 48)) },
      };
    }
    const position = { x: 24 + timelineIndex * (STEP_NODE_SIZE.width + 176), y: 24 };
    timelineIndex += 1;
    return { ...node, position };
  });
}

export function getNodeLayoutSize(node: Node<FlowNodeData>): { width: number; height: number } {
  switch (node.type) {
    case "gapNode":
      return cloneLayoutSize(GAP_NODE_SIZE);
    case "systemNode":
      return getSystemNodeLayoutSize(node.data);
    default:
      return cloneLayoutSize(STEP_NODE_SIZE);
  }
}

function getSystemNodeLayoutSize(data: FlowNodeData): { width: number; height: number } {
  switch (data.kind) {
    case "trace":
      return cloneLayoutSize(TRACE_NODE_SIZE);
    case "arrange":
      return cloneLayoutSize(ARRANGE_NODE_SIZE);
    default:
      return cloneLayoutSize(STEP_NODE_SIZE);
  }
}

function cloneLayoutSize(size: { width: number; height: number }): {
  width: number;
  height: number;
} {
  return { width: size.width, height: size.height };
}
