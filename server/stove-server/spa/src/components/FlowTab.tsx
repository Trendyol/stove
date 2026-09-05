import { ReactFlowProvider } from "@xyflow/react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Entry, Snapshot, Span } from "../api/types";
import type { FlowNodeData, GapNodeData, SystemNodeData } from "../utils/flow";
import {
  FLOW_NODE_LIMIT,
  type FlowGraph,
  type FlowInput,
  TIMELINE_RECORD_LIMIT,
} from "../utils/flow-work";
import { LatestTask } from "../utils/latest-task";
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
interface FlowWork {
  input: FlowInput;
  scope: string;
  start: number;
  end: number;
  total: number;
}
type FlowResult = { graph: FlowGraph; error?: never } | { error: string; graph?: never };

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
  const [endAnchor, setEndAnchor] = useState<number | null>(null);
  const [completed, setCompleted] = useState<{ result: FlowResult; work: FlowWork } | null>(null);
  const schedulerRef = useRef<LatestTask<FlowWork, FlowResult> | null>(null);
  const pageSize = mode === "trace" ? FLOW_NODE_LIMIT : TIMELINE_RECORD_LIMIT;
  const total = mode === "trace" ? spans.length : entries.length;
  const end = Math.min(endAnchor ?? total, total);
  const start = Math.max(0, end - pageSize);
  const scope = `${mode}:${endAnchor ?? "latest"}`;

  useEffect(() => {
    const worker = new Worker(new URL("../workers/flow-layout.worker.ts", import.meta.url), {
      type: "module",
    });
    const scheduler = new LatestTask<FlowWork, FlowResult>(
      (work) => worker.postMessage(work.input),
      (result, work) => setCompleted({ result, work }),
    );
    schedulerRef.current = scheduler;
    worker.onmessage = (message: MessageEvent<FlowResult>) => scheduler.complete(message.data);
    worker.onerror = () => scheduler.complete({ error: "Flow calculation failed" });
    return () => {
      scheduler.dispose();
      schedulerRef.current = null;
      worker.terminate();
    };
  }, []);

  useEffect(() => {
    const input: FlowInput =
      mode === "trace"
        ? { mode, records: spans.slice(start, end) }
        : { mode, records: entries.slice(start, end) };
    schedulerRef.current?.submit(scope, { input, scope, start, end, total });
  }, [mode, entries, spans, start, end, total, scope]);

  const current = completed?.work.scope === scope ? completed : null;
  const { nodes, edges } = current?.result.graph ?? { nodes: [], edges: [] };

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
          onClick={() => {
            setMode("timeline");
            setEndAnchor(null);
          }}
        >
          Timeline Flow
        </button>
        {spans.length > 0 && (
          <button
            type="button"
            className={modeButtonClass(mode === "trace")}
            onClick={() => {
              setMode("trace");
              setEndAnchor(null);
            }}
          >
            Trace Flow
          </button>
        )}
        <div className="ml-auto text-[11px] text-[var(--stove-text-secondary)]">{summary}</div>
      </div>

      <div className="flex shrink-0 items-center gap-3 border-b border-stove-border px-3 py-2 text-xs text-[var(--stove-text-secondary)]">
        <span role="status">
          {current
            ? `Showing ${current.work.end ? current.work.start + 1 : 0}–${current.work.end} of ${current.work.total} ${mode === "trace" ? "spans" : "entries"} (${nodes.length} nodes)`
            : "Calculating flow…"}
        </span>
        <button
          type="button"
          className="stove-focus-ring disabled:opacity-40"
          disabled={start === 0}
          onClick={() => setEndAnchor(start)}
        >
          Older
        </button>
        <button
          type="button"
          className="stove-focus-ring disabled:opacity-40"
          disabled={end >= total}
          onClick={() => setEndAnchor(Math.min(total, end + pageSize))}
        >
          Newer
        </button>
        {endAnchor !== null && (
          <button type="button" className="stove-focus-ring" onClick={() => setEndAnchor(null)}>
            Follow latest
          </button>
        )}
        {current?.result.error && <span role="alert">{current.result.error}</span>}
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
