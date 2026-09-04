import { ReactFlowProvider } from "@xyflow/react";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { Entry, Snapshot, Span } from "../api/types";
import type { FlowNodeData, GapNodeData, SystemNodeData } from "../utils/flow";
import { applyLinearTimelineLayout, entriesToDag } from "../utils/flow";
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
type FlowSelection =
  | { kind: "none" }
  | { kind: "node"; node: SystemNodeData }
  | { kind: "snapshot"; snapshot: Snapshot };
const TRACE_NODE_LIMIT = 1_000;

function modeButtonClass(active: boolean): string {
  return `stove-focus-ring cursor-pointer rounded-md px-2.5 py-1 text-xs border-0 transition-colors ${
    active
      ? "bg-[var(--stove-blue)] text-white shadow-sm"
      : "bg-transparent text-[var(--stove-text-secondary)] hover:bg-[var(--stove-hover)] hover:text-[var(--stove-text)]"
  }`;
}

export function FlowTab({ entries, spans, snapshots, onOpenTraceTab }: FlowTabProps) {
  const [mode, setMode] = useState<FlowMode>("timeline");
  const [selection, setSelection] = useState<FlowSelection>({ kind: "none" });
  const [traceGraph, setTraceGraph] = useState<{
    nodes: ReturnType<typeof entriesToDag>["nodes"];
    edges: ReturnType<typeof entriesToDag>["edges"];
  }>({ nodes: [], edges: [] });

  const timelineGraph = useMemo(() => {
    const dag = entriesToDag(entries);
    return { nodes: applyLinearTimelineLayout(dag.nodes), edges: dag.edges };
  }, [entries]);

  useEffect(() => {
    if (mode !== "trace" || spans.length === 0) return;
    let worker: Worker | undefined;
    const timer = window.setTimeout(() => {
      const layoutWorker = new Worker(
        new URL("../workers/flow-layout.worker.ts", import.meta.url),
        {
          type: "module",
        },
      );
      worker = layoutWorker;
      layoutWorker.onmessage = (message) => {
        setTraceGraph(message.data);
        layoutWorker.terminate();
      };
      layoutWorker.postMessage(spans.slice(-TRACE_NODE_LIMIT));
    }, 100);
    return () => {
      window.clearTimeout(timer);
      worker?.terminate();
    };
  }, [mode, spans]);

  const { nodes, edges } = mode === "trace" ? traceGraph : timelineGraph;

  const handleNodeClick = useCallback((data: FlowNodeData) => {
    if (!data.inspectable) {
      return;
    }
    setSelection({ kind: "node", node: data });
  }, []);

  const handleOpenTraceTab = useCallback(() => {
    setSelection({ kind: "none" });
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
      <div className="m-4 rounded-xl border border-dashed border-stove-border bg-stove-surface p-6 text-center text-sm text-[var(--stove-text-secondary)]">
        No data to visualize
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col overflow-hidden">
      <div className="flex shrink-0 items-center gap-1 border-b border-stove-border bg-[var(--stove-panel-strong)] px-3 py-2">
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
        <CapturedStateLane
          snapshots={snapshots}
          onSelect={(snapshot) => setSelection({ kind: "snapshot", snapshot })}
        />
      )}

      {selection.kind === "node" && (
        <NodePopup
          entries={selection.node.entries}
          traceId={selection.node.traceId}
          onClose={() => setSelection({ kind: "none" })}
          onOpenTrace={selection.node.traceId ? handleOpenTraceTab : undefined}
        />
      )}
      {selection.kind === "snapshot" && (
        <SnapshotStateDialog
          snapshot={selection.snapshot}
          onClose={() => setSelection({ kind: "none" })}
        />
      )}
    </div>
  );
}
