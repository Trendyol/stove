import type { NodeProps } from "@xyflow/react";
import { Handle, Position } from "@xyflow/react";
import type { SystemNodeData } from "../utils/flow";
import { isFailed } from "../utils/result";
import { getSystemInfo } from "../utils/systems";
import { ResultIcon } from "./ResultIcon";
import { SysBadge } from "./SysBadge";

export function SystemNode({ data }: NodeProps) {
  const d = data as SystemNodeData;
  const info = getSystemInfo(d.system);
  const failed = isFailed(d.result);

  return (
    <div
      className="rounded-lg bg-stove-card border px-3 py-2 min-w-[180px] max-w-[220px]"
      style={{
        borderColor: failed ? "var(--stove-red)" : "var(--stove-border)",
        borderWidth: failed ? 2 : 1,
        borderLeftColor: info.color,
        borderLeftWidth: 3,
      }}
    >
      <Handle type="target" position={Position.Left} className="!bg-[var(--stove-border)]" />
      <Handle type="source" position={Position.Right} className="!bg-[var(--stove-border)]" />

      <div className="flex items-center gap-1.5 mb-1">
        <SysBadge system={d.system} />
        {d.count > 1 && (
          <span className="text-[10px] px-1 py-0.5 rounded bg-[var(--stove-amber-bg)] text-[var(--stove-amber)] font-mono">
            {d.count}x
          </span>
        )}
        <span className="ml-auto text-xs">
          <ResultIcon result={d.result} />
        </span>
      </div>

      <div className="text-xs text-[var(--stove-text)] truncate" title={d.action}>
        {d.action}
      </div>

      {failed && d.error && (
        <div className="text-[10px] text-[var(--stove-red)] truncate mt-1" title={d.error}>
          {d.error}
        </div>
      )}
    </div>
  );
}
