import type { Snapshot } from "../api/types";
import { describeJsonValue, getJsonPreviewKeys, parseJsonDeep } from "../utils/json";
import { getSystemInfo } from "../utils/systems";

interface CapturedStateLaneProps {
  snapshots: Snapshot[];
  onSelect: (snapshot: Snapshot) => void;
}

export function CapturedStateLane({ snapshots, onSelect }: CapturedStateLaneProps) {
  if (snapshots.length === 0) {
    return null;
  }

  return (
    <div className="shrink-0 border-t border-stove-border bg-stove-surface">
      <div className="flex items-center justify-between px-3 py-2">
        <div className="text-[11px] font-medium uppercase tracking-[0.18em] text-[var(--stove-text-muted)]">
          Captured State
        </div>
        <div className="text-[11px] text-[var(--stove-text-secondary)]">
          {snapshots.length} snapshot{snapshots.length === 1 ? "" : "s"}
        </div>
      </div>
      <div className="flex gap-3 overflow-x-auto px-3 pb-3">
        {snapshots.map((snapshot) => (
          <SnapshotLaneCard key={snapshot.id} snapshot={snapshot} onSelect={onSelect} />
        ))}
      </div>
    </div>
  );
}

function SnapshotLaneCard({
  snapshot,
  onSelect,
}: {
  snapshot: Snapshot;
  onSelect: (snapshot: Snapshot) => void;
}) {
  const info = getSystemInfo(snapshot.system);
  const parsedState = parseJsonDeep(snapshot.state_json);
  const previewKeys = getJsonPreviewKeys(parsedState, 3);
  return (
    <button
      type="button"
      className="w-[260px] shrink-0 cursor-pointer rounded-lg border border-stove-border bg-stove-card px-3 py-2 text-left hover:bg-[var(--stove-hover)]"
      style={{ borderLeftColor: info.color, borderLeftWidth: 3 }}
      onClick={() => onSelect(snapshot)}
    >
      <div className="mb-1 flex items-center gap-2">
        <span style={{ color: info.color }}>{info.icon}</span>
        <span className="text-sm font-medium text-[var(--stove-text)]">{snapshot.system}</span>
      </div>

      <div className="line-clamp-2 text-xs text-[var(--stove-text)]" title={snapshot.summary}>
        {snapshot.summary}
      </div>

      <div className="mt-2 text-[10px] text-[var(--stove-text-secondary)]">
        {parsedState ? describeJsonValue(parsedState) : "raw text"}
      </div>

      {previewKeys.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {previewKeys.map((key) => (
            <span
              key={key}
              className="rounded-full border border-stove-border px-1.5 py-0.5 font-mono text-[10px] text-[var(--stove-text-secondary)]"
            >
              {key}
            </span>
          ))}
        </div>
      )}
    </button>
  );
}
