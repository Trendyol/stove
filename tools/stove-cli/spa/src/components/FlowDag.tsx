import { Controls, type Edge, type Node, type NodeMouseHandler, ReactFlow } from "@xyflow/react";
import { useCallback } from "react";
import type { SystemNodeData } from "../utils/flow";
import { DurationEdge } from "./DurationEdge";
import { SystemNode } from "./SystemNode";

const nodeTypes = { systemNode: SystemNode };
const edgeTypes = { durationEdge: DurationEdge };
const defaultEdgeOptions = { animated: false };
const proOptions = { hideAttribution: true };

interface FlowDagProps {
  nodes: Node[];
  edges: Edge[];
  onNodeClick?: (nodeData: SystemNodeData) => void;
  compact?: boolean;
}

export function FlowDag({ nodes, edges, onNodeClick, compact }: FlowDagProps) {
  const handleNodeClick: NodeMouseHandler = useCallback(
    (_, node) => {
      if (onNodeClick) {
        onNodeClick(node.data as SystemNodeData);
      }
    },
    [onNodeClick],
  );

  if (nodes.length === 0) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-[var(--stove-text-secondary)]">
        No data to visualize
      </div>
    );
  }

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      onNodeClick={handleNodeClick}
      defaultEdgeOptions={defaultEdgeOptions}
      fitView
      nodesDraggable={!compact}
      nodesConnectable={false}
      panOnDrag={!compact}
      zoomOnScroll={!compact}
      zoomOnPinch={!compact}
      zoomOnDoubleClick={false}
      proOptions={proOptions}
      minZoom={0.2}
      maxZoom={2}
    >
      {!compact && <Controls showInteractive={false} />}
    </ReactFlow>
  );
}
