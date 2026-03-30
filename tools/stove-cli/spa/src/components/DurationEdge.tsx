import type { EdgeProps } from "@xyflow/react";
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath } from "@xyflow/react";
import type { DurationEdgeData } from "../utils/flow";
import { formatDuration } from "../utils/format";

export function DurationEdge(props: EdgeProps) {
  const { sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, markerEnd } = props;
  const d = props.data as DurationEdgeData | undefined;

  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  });

  const label =
    d?.label ?? (d?.durationMs != null && d.durationMs > 0 ? formatDuration(d.durationMs) : null);

  return (
    <>
      <BaseEdge path={edgePath} markerEnd={markerEnd} style={{ stroke: "var(--stove-border)" }} />
      {label && (
        <EdgeLabelRenderer>
          <div
            className="absolute text-[10px] font-mono px-1 py-0.5 rounded bg-stove-surface text-[var(--stove-text-secondary)] border border-stove-border pointer-events-none"
            style={{
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
