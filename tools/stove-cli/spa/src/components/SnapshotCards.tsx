import type { Snapshot } from "../api/types";
import { getSystemInfo } from "../utils/systems";

interface SnapshotCardsProps {
  snapshots: Snapshot[];
}

export function SnapshotCards({ snapshots }: SnapshotCardsProps) {
  if (snapshots.length === 0) {
    return (
      <div className="text-[var(--stove-text-secondary)] text-sm p-4">No snapshots captured</div>
    );
  }

  return (
    <div
      className="grid gap-3 p-3"
      style={{ gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))" }}
    >
      {snapshots.map((snap) => {
        const info = getSystemInfo(snap.system);
        return (
          <div key={snap.id} className="bg-stove-card border border-stove-border rounded-lg p-3">
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <span style={{ color: info.color }}>{info.icon}</span>
              <span>{snap.system}</span>
            </div>
            <pre className="text-xs text-[var(--stove-text-secondary)] whitespace-pre-wrap">
              {snap.summary}
            </pre>
          </div>
        );
      })}
    </div>
  );
}
