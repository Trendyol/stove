import { ReactFlowProvider } from "@xyflow/react";
import { useCallback, useMemo, useState } from "react";
import type { Entry, Snapshot, Span } from "../api/types";
import type { FlowNodeData, GapNodeData, SystemNodeData } from "../utils/flow";
import { applyDagreLayout, entriesToDag, spansToTraceDag } from "../utils/flow";
import { CapturedStateLane } from "./CapturedStateLane";
import { FlowDag } from "./FlowDag";
import { NodePopup } from "./NodePopup";
import { SnapshotStateDialog } from "./SnapshotStateDialog";

interface FlowTabProps {
  entries: Entry[];
  spans: Span[];
  snapshots: Snapshot[];
  onOpenTraceTab?: (() => void) | undefined;
}

type FlowMode = "timeline" | "trace";

function modeButtonClass(active: boolean): string {
  return `px-2.5 py-1 rounded text-xs cursor-pointer border-0 ${
    active
      ? "bg-[var(--stove-blue)] text-white"
      : "bg-stove-card text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
  }`;
}

export function FlowTab({ entries, spans, snapshots, onOpenTraceTab }: FlowTabProps) {
  const [mode, setMode] = useState<FlowMode>("timeline");
  const [selectedNode, setSelectedNode] = useState<SystemNodeData | null>(null);
  const [selectedSnapshot, setSelectedSnapshot] = useState<Snapshot | null>(null);

  const { nodes, edges } = useMemo(() => {
    if (mode === "trace" && spans.length > 0) {
      const dag = spansToTraceDag(spans);
      return { nodes: applyDagreLayout(dag.nodes, dag.edges), edges: dag.edges };
    }
    const dag = entriesToDag(entries);
    return { nodes: applyDagreLayout(dag.nodes, dag.edges), edges: dag.edges };
  }, [mode, entries, spans]);

  const handleNodeClick = useCallback((data: FlowNodeData) => {
    if (!data.inspectable) {
      return;
    }
    setSelectedNode(data);
  }, []);

  const handleOpenTraceTab = useCallback(() => {
    setSelectedNode(null);
    onOpenTraceTab?.();
  }, [onOpenTraceTab]);

  const summary = useMemo(() => {
    if (mode === "trace") {
      return `${spans.length} spans`;
    }

    const stepCount = nodes.filter(
      (node) => node.type === "systemNode" && node.data.kind === "step",
    ).length;
    const arrangeCount = nodes.filter(
      (node) => node.type === "systemNode" && node.data.kind === "arrange",
    ).length;
    const gapNodes = nodes.filter((node) => node.type === "gapNode");
    const gapCount = gapNodes.length;
    const totalGapMs = gapNodes.reduce(
      (sum, node) => sum + ((node.data as GapNodeData).durationMs ?? 0),
      0,
    );

    const parts = [`${stepCount} steps`];
    if (arrangeCount > 0) {
      parts.push(`${arrangeCount} arrange`);
    }
    if (gapCount > 0) {
      parts.push(`${gapCount} waits`);
    }
    if (snapshots.length > 0) {
      parts.push(`${snapshots.length} snapshots`);
    }
    if (totalGapMs > 0) {
      parts.push(`${Math.round(totalGapMs / 100) / 10}s idle`);
    }
    return parts.join(" • ");
  }, [mode, nodes, snapshots.length, spans.length]);

  if (entries.length === 0 && spans.length === 0 && snapshots.length === 0) {
    return (
      <div className="text-[var(--stove-text-secondary)] text-sm p-4">No data to visualize</div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
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
        <div className="ml-auto text-[11px] text-[var(--stove-text-secondary)]">{summary}</div>
      </div>

      <div className="min-h-0 flex-1">
        <ReactFlowProvider>
          <FlowDag nodes={nodes} edges={edges} onNodeClick={handleNodeClick} />
        </ReactFlowProvider>
      </div>

      {mode === "timeline" && (
        <CapturedStateLane snapshots={snapshots} onSelect={setSelectedSnapshot} />
      )}

      {selectedNode && (
        <NodePopup
          entries={selectedNode.entries}
          traceId={selectedNode.traceId}
          onClose={() => setSelectedNode(null)}
          onOpenTrace={selectedNode.traceId ? handleOpenTraceTab : undefined}
        />
      )}
      {selectedSnapshot && (
        <SnapshotStateDialog
          snapshot={selectedSnapshot}
          onClose={() => setSelectedSnapshot(null)}
        />
      )}
    </div>
  );
}
