import { useState } from "react";
import type { Snapshot } from "../api/types";
import { describeJsonValue, getJsonPreviewKeys, parseJsonDeep } from "../utils/json";
import { getKafkaSnapshotMetrics } from "../utils/snapshot-state";
import { getSystemInfo } from "../utils/systems";
import { SnapshotMetricTiles } from "./SnapshotMetricTiles";
import { SnapshotStateDialog } from "./SnapshotStateDialog";

interface SnapshotCardsProps {
  snapshots: Snapshot[];
  hiddenCount?: number;
}

export function SnapshotCards({ snapshots, hiddenCount = 0 }: SnapshotCardsProps) {
  const [selectedSnapshot, setSelectedSnapshot] = useState<Snapshot | null>(null);

  if (snapshots.length === 0) {
    return (
      <div className="p-4">
        <div className="text-sm text-[var(--stove-text-secondary)]">
          {hiddenCount > 0 ? "No detailed snapshots captured" : "No snapshots captured"}
        </div>
        <HiddenSnapshotNotice hiddenCount={hiddenCount} className="mt-1" />
      </div>
    );
  }

  return (
    <div className="p-3">
      <HiddenSnapshotNotice hiddenCount={hiddenCount} className="mb-3" boxed />
      <div
        className="grid gap-3"
        style={{ gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))" }}
      >
        {snapshots.map((snap) => {
          return (
            <DetailedSnapshotCard
              key={snap.id}
              snapshot={snap}
              onOpen={() => setSelectedSnapshot(snap)}
            />
          );
        })}
      </div>
      {selectedSnapshot && (
        <SnapshotStateDialog
          snapshot={selectedSnapshot}
          onClose={() => setSelectedSnapshot(null)}
        />
      )}
    </div>
  );
}

function DetailedSnapshotCard({ snapshot, onOpen }: { snapshot: Snapshot; onOpen: () => void }) {
  const info = getSystemInfo(snapshot.system);
  const parsedState = parseJsonDeep(snapshot.state_json);
  const previewKeys = getJsonPreviewKeys(parsedState);
  const kafkaMetrics =
    snapshot.system === "Kafka" ? getKafkaSnapshotMetrics(snapshot, parsedState) : [];

  return (
    <div
      className="flex flex-col gap-3 rounded-xl border border-stove-border bg-stove-surface p-3"
      style={{
        borderTopColor: info.color,
        borderTopWidth: 3,
      }}
    >
      <div className="flex items-center gap-2 text-sm font-medium">
        <span style={{ color: info.color }}>{info.icon}</span>
        <span>{snapshot.system}</span>
      </div>
      <pre className="text-xs text-[var(--stove-text-secondary)] whitespace-pre-wrap">
        {snapshot.summary}
      </pre>
      {kafkaMetrics.length > 0 && <SnapshotMetricTiles metrics={kafkaMetrics} compact />}
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
            Open the state explorer to inspect the captured payload.
          </div>
        )}
        <button
          type="button"
          className="mt-3 w-full cursor-pointer rounded-md border border-stove-border bg-stove-card px-3 py-2 text-left text-xs font-medium text-[var(--stove-text)] hover:bg-[var(--stove-hover)]"
          onClick={onOpen}
        >
          Open state
        </button>
      </div>
    </div>
  );
}

function HiddenSnapshotNotice({
  hiddenCount,
  className = "",
  boxed = false,
}: {
  hiddenCount: number;
  className?: string;
  boxed?: boolean;
}) {
  if (hiddenCount === 0) {
    return null;
  }

  return (
    <div
      className={
        boxed
          ? `${className} rounded-lg border border-dashed border-stove-border bg-stove-base px-3 py-2 text-xs text-[var(--stove-text-secondary)]`
          : `${className} text-xs text-[var(--stove-text-muted)]`
      }
    >
      {hiddenCount} system{hiddenCount === 1 ? "" : "s"} had no detailed state.
    </div>
  );
}
