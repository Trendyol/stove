import type { SnapshotMetric } from "../utils/snapshot-state";

interface SnapshotMetricTilesProps {
  metrics: SnapshotMetric[];
  compact?: boolean;
}

export function SnapshotMetricTiles({ metrics, compact = false }: SnapshotMetricTilesProps) {
  if (metrics.length === 0) {
    return null;
  }

  return (
    <div className={compact ? "grid grid-cols-2 gap-2" : "grid gap-2 sm:grid-cols-4"}>
      {metrics.map((metric) => (
        <SnapshotMetricTile key={metric.key} metric={metric} compact={compact} />
      ))}
    </div>
  );
}

function SnapshotMetricTile({ metric, compact }: { metric: SnapshotMetric; compact: boolean }) {
  const style = toneStyle(metric.tone);

  return (
    <div
      className={
        compact
          ? "rounded-lg border bg-stove-base px-2.5 py-2"
          : "rounded-lg border bg-stove-base px-3 py-2.5"
      }
      style={{ borderColor: style.border }}
    >
      <div
        className="text-[10px] font-medium uppercase tracking-[0.16em]"
        style={{ color: style.label }}
      >
        {metric.label}
      </div>
      <div
        className={
          compact
            ? "mt-1 font-mono text-base font-semibold"
            : "mt-1 font-mono text-lg font-semibold"
        }
        style={{ color: style.value }}
      >
        {metric.value}
      </div>
    </div>
  );
}

function toneStyle(tone: SnapshotMetric["tone"]): {
  border: string;
  label: string;
  value: string;
} {
  switch (tone) {
    case "info":
      return {
        border: "var(--stove-blue)",
        label: "var(--stove-blue)",
        value: "var(--stove-blue)",
      };
    case "success":
      return {
        border: "var(--stove-green)",
        label: "var(--stove-green)",
        value: "var(--stove-green)",
      };
    case "danger":
      return {
        border: "var(--stove-red)",
        label: "var(--stove-red)",
        value: "var(--stove-red)",
      };
    case "warning":
      return {
        border: "var(--stove-amber)",
        label: "var(--stove-amber)",
        value: "var(--stove-amber)",
      };
    default:
      return {
        border: "var(--stove-border)",
        label: "var(--stove-text-secondary)",
        value: "var(--stove-text)",
      };
  }
}
