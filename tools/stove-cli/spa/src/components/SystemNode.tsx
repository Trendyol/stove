import type { NodeProps } from "@xyflow/react";
import { Handle, Position } from "@xyflow/react";
import type { SystemNodeData } from "../utils/flow";
import { formatDuration, formatTimestamp } from "../utils/format";
import { isFailed } from "../utils/result";
import { getSystemInfo } from "../utils/systems";
import { ResultIcon } from "./ResultIcon";
import { SysBadge } from "./SysBadge";

export function SystemNode({ data }: NodeProps) {
  const d = data as SystemNodeData;
  const info = getSystemInfo(d.system);
  const failed = isFailed(d.result);
  const isArrange = d.kind === "arrange";
  const sizeClass = d.kind === "trace" ? "w-[240px] min-h-[120px]" : "w-[240px] min-h-[128px]";

  return (
    <div
      className={`${sizeClass} rounded-lg border bg-stove-card px-3 py-2`}
      style={{
        borderColor: failed ? "var(--stove-red)" : "var(--stove-border)",
        borderWidth: failed ? 2 : 1,
        borderLeftColor: info.color,
        borderLeftWidth: 3,
        backgroundColor: isArrange ? "rgba(148, 163, 184, 0.08)" : undefined,
      }}
    >
      <Handle type="target" position={Position.Left} className="!bg-[var(--stove-border)]" />
      <Handle type="source" position={Position.Right} className="!bg-[var(--stove-border)]" />

      <div className="flex items-center gap-1.5 mb-1">
        <SysBadge system={d.system} />
        {isArrange && (
          <span className="text-[10px] px-1 py-0.5 rounded bg-stove-base text-[var(--stove-blue)] font-mono">
            arrange
          </span>
        )}
        {d.count > 1 && (
          <span className="text-[10px] px-1 py-0.5 rounded bg-[var(--stove-amber-bg)] text-[var(--stove-amber)] font-mono">
            {d.count}x
          </span>
        )}
        <span className="ml-auto text-xs">
          <ResultIcon result={d.result} />
        </span>
      </div>

      <div
        className="text-xs text-[var(--stove-text)] break-words line-clamp-2 min-h-[2rem]"
        title={d.action}
      >
        {d.action}
      </div>

      {(d.startedAt || d.durationMs) && (
        <div className="mt-1 flex flex-wrap gap-2 text-[10px] text-[var(--stove-text-secondary)]">
          {d.startedAt && <span>{formatTimestamp(d.startedAt)}</span>}
          {d.durationMs != null && d.durationMs > 0 && <span>{formatDuration(d.durationMs)}</span>}
        </div>
      )}

      {failed && d.error && (
        <div className="text-[10px] text-[var(--stove-red)] truncate mt-1" title={d.error}>
          {d.error}
        </div>
      )}
    </div>
  );
}
