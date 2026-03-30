import {
  Controls,
  type Edge,
  type Node,
  type NodeMouseHandler,
  Panel,
  ReactFlow,
  useReactFlow,
} from "@xyflow/react";
import { useCallback } from "react";
import type { FlowNodeData } from "../utils/flow";
import { DurationEdge } from "./DurationEdge";
import { GapNode } from "./GapNode";
import { SystemNode } from "./SystemNode";

const nodeTypes = {
  systemNode: SystemNode,
  gapNode: GapNode,
};
const edgeTypes = { durationEdge: DurationEdge };
const defaultEdgeOptions = { animated: false };
const proOptions = { hideAttribution: true };

interface FlowDagProps {
  nodes: Node<FlowNodeData>[];
  edges: Edge[];
  onNodeClick?: (nodeData: FlowNodeData) => void;
  compact?: boolean;
}

export function FlowDag({ nodes, edges, onNodeClick, compact }: FlowDagProps) {
  const { fitView } = useReactFlow();
  const handleNodeClick: NodeMouseHandler = useCallback(
    (_, node) => {
      if (onNodeClick) {
        onNodeClick(node.data as FlowNodeData);
      }
    },
    [onNodeClick],
  );
  const handleCenterView = useCallback(() => {
    void fitView({ padding: 0.18, duration: 250 });
  }, [fitView]);

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
      fitViewOptions={{ padding: 0.18 }}
      className="h-full w-full"
    >
      {!compact && <Controls showInteractive={false} />}
      {!compact && (
        <Panel position="top-right" className="m-3">
          <button
            type="button"
            className="cursor-pointer rounded border border-stove-border bg-stove-surface px-2.5 py-1.5 text-xs text-[var(--stove-text)] shadow-sm hover:bg-[var(--stove-hover)]"
            onClick={handleCenterView}
          >
            Center View
          </button>
        </Panel>
      )}
    </ReactFlow>
  );
}
