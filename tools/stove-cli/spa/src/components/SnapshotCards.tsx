import { useState } from "react";
import type { Snapshot } from "../api/types";
import { describeJsonValue, getJsonPreviewKeys, parseJsonDeep } from "../utils/json";
import { getSystemInfo } from "../utils/systems";
import { SnapshotStateDialog } from "./SnapshotStateDialog";

interface SnapshotCardsProps {
  snapshots: Snapshot[];
}

export function SnapshotCards({ snapshots }: SnapshotCardsProps) {
  const [selectedSnapshot, setSelectedSnapshot] = useState<Snapshot | null>(null);

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
        const parsedState = parseJsonDeep(snap.state_json);
        const previewKeys = getJsonPreviewKeys(parsedState);

        return (
          <div
            key={snap.id}
            className="flex flex-col gap-3 rounded-lg border border-stove-border bg-stove-card p-3"
          >
            <div className="flex items-center gap-2 mb-2 text-sm font-medium">
              <span style={{ color: info.color }}>{info.icon}</span>
              <span>{snap.system}</span>
            </div>
            <pre className="text-xs text-[var(--stove-text-secondary)] whitespace-pre-wrap">
              {snap.summary}
            </pre>
            <div className="rounded-lg border border-stove-border bg-stove-base p-3">
              <div className="flex items-center justify-between gap-3">
                <span className="text-[10px] font-medium uppercase tracking-[0.16em] text-[var(--stove-text-muted)]">
                  State
                </span>
                <span className="text-[10px] uppercase tracking-[0.16em] text-[var(--stove-text-secondary)]">
                  {parsedState ? describeJsonValue(parsedState) : "raw text"}
                </span>
              </div>
              {previewKeys.length > 0 ? (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {previewKeys.map((key) => (
                    <span
                      key={key}
                      className="rounded-full border border-stove-border px-2 py-1 text-[10px] font-mono text-[var(--stove-text-secondary)]"
                    >
                      {key}
                    </span>
                  ))}
                </div>
              ) : (
                <div className="mt-2 text-xs text-[var(--stove-text-secondary)]">
                  Click to inspect the captured state.
                </div>
              )}
              <button
                type="button"
                className="mt-3 w-full cursor-pointer rounded-md border border-stove-border bg-stove-card px-3 py-2 text-left text-xs font-medium text-[var(--stove-text)] hover:bg-[var(--stove-hover)]"
                onClick={() => setSelectedSnapshot(snap)}
              >
                Open state
              </button>
            </div>
          </div>
        );
      })}
      {selectedSnapshot && (
        <SnapshotStateDialog
          snapshot={selectedSnapshot}
          onClose={() => setSelectedSnapshot(null)}
        />
      )}
    </div>
  );
}
