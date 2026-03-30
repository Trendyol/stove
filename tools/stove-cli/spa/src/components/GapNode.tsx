import type { NodeProps } from "@xyflow/react";
import { Handle, Position } from "@xyflow/react";
import type { GapNodeData } from "../utils/flow";
import { formatDuration, formatTimestamp } from "../utils/format";

export function GapNode({ data }: NodeProps) {
  const d = data as GapNodeData;

  return (
    <div className="w-[208px] min-h-[96px] rounded-xl border border-dashed border-stove-border bg-stove-base px-3 py-2 text-center">
      <Handle type="target" position={Position.Left} className="!bg-[var(--stove-border)]" />
      <Handle type="source" position={Position.Right} className="!bg-[var(--stove-border)]" />

      <div className="text-[10px] font-medium uppercase tracking-[0.18em] text-[var(--stove-text-muted)]">
        {d.label}
      </div>
      <div className="mt-1 text-sm font-semibold text-[var(--stove-text)]">
        {formatDuration(d.durationMs)}
      </div>
      <div className="mt-1 text-[10px] text-[var(--stove-text-secondary)]">
        {formatTimestamp(d.startedAt)} to {formatTimestamp(d.endedAt)}
      </div>
    </div>
  );
}
