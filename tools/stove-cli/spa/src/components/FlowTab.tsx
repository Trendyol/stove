import { ReactFlowProvider } from "@xyflow/react";
import { useCallback, useMemo, useState } from "react";
import type { Entry, Span } from "../api/types";
import type { SystemNodeData } from "../utils/flow";
import { applyDagreLayout, entriesToDag, spansToTraceDag } from "../utils/flow";
import { FlowDag } from "./FlowDag";
import { NodePopup } from "./NodePopup";

interface FlowTabProps {
  entries: Entry[];
  spans: Span[];
}

type FlowMode = "timeline" | "trace";

function modeButtonClass(active: boolean): string {
  return `px-2.5 py-1 rounded text-xs cursor-pointer border-0 ${
    active
      ? "bg-[var(--stove-blue)] text-white"
      : "bg-stove-card text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
  }`;
}

export function FlowTab({ entries, spans }: FlowTabProps) {
  const [mode, setMode] = useState<FlowMode>("timeline");
  const [selectedNode, setSelectedNode] = useState<SystemNodeData | null>(null);

  const { nodes, edges } = useMemo(() => {
    if (mode === "trace" && spans.length > 0) {
      const dag = spansToTraceDag(spans);
      return { nodes: applyDagreLayout(dag.nodes, dag.edges), edges: dag.edges };
    }
    const dag = entriesToDag(entries);
    return { nodes: applyDagreLayout(dag.nodes, dag.edges), edges: dag.edges };
  }, [mode, entries, spans]);

  const handleNodeClick = useCallback((data: SystemNodeData) => {
    setSelectedNode(data);
  }, []);

  if (entries.length === 0 && spans.length === 0) {
    return (
      <div className="text-[var(--stove-text-secondary)] text-sm p-4">No data to visualize</div>
    );
  }

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <div className="flex items-center gap-1 px-3 py-2 border-b border-stove-border shrink-0">
        <button
          type="button"
          className={modeButtonClass(mode === "timeline")}
          onClick={() => setMode("timeline")}
        >
          Timeline Flow
        </button>
        {spans.length > 0 && (
          <button
            type="button"
            className={modeButtonClass(mode === "trace")}
            onClick={() => setMode("trace")}
          >
            Trace Flow
          </button>
        )}
      </div>

      <div className="flex-1">
        <ReactFlowProvider>
          <FlowDag nodes={nodes} edges={edges} onNodeClick={handleNodeClick} />
        </ReactFlowProvider>
      </div>

      {selectedNode && (
        <NodePopup
          entries={selectedNode.entries}
          traceId={selectedNode.traceId}
          onClose={() => setSelectedNode(null)}
        />
      )}
    </div>
  );
}
