import type { Snapshot } from "../api/types";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import { partitionSnapshotsByDetail } from "../utils/snapshot-state";
import { getSystemInfo } from "../utils/systems";
import { useRememberedState } from "./evidence/EvidenceViewMemory";
import { SnapshotStateDialog } from "./SnapshotStateDialog";

interface SnapshotCardsProps {
  snapshots: Snapshot[];
  hiddenCount?: number;
}

type SnapshotSelection = { kind: "none" } | { kind: "snapshot"; snapshot: Snapshot };

export function SnapshotCards({ snapshots, hiddenCount = 0 }: SnapshotCardsProps) {
  const navigation = useEvidenceNavigation();
  const [localSelection, setSelection] = useRememberedState<SnapshotSelection>("state.selection", {
    kind: "none",
  });
  const focused =
    navigation?.focus?.kind === "snapshot"
      ? snapshots.find((item) => item.id === navigation.focus?.id)
      : undefined;
  const selection: SnapshotSelection = navigation
    ? focused
      ? { kind: "snapshot", snapshot: focused }
      : { kind: "none" }
    : localSelection;
  const visibleSnapshots = partitionSnapshotsByDetail(snapshots).detailedSnapshots;
  if (focused && !visibleSnapshots.some((item) => item.id === focused.id))
    visibleSnapshots.push(focused);

  if (visibleSnapshots.length === 0) {
    return (
      <div className="p-4">
        <div className="rounded-xl border border-dashed border-stove-border bg-stove-surface p-6 text-center text-sm text-[var(--stove-text-secondary)]">
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
        {visibleSnapshots.map((snap) => {
          return (
            <DetailedSnapshotCard
              key={snap.id}
              snapshot={snap}
              onOpen={() =>
                navigation
                  ? navigation.select("snapshot", snap.id)
                  : setSelection({ kind: "snapshot", snapshot: snap })
              }
            />
          );
        })}
      </div>
      {selection.kind === "snapshot" && (
        <SnapshotStateDialog
          key={`${selection.snapshot.id}:${navigation?.pointer ?? ""}`}
          snapshot={selection.snapshot}
          onClose={() => (navigation ? navigation.clear() : setSelection({ kind: "none" }))}
        />
      )}
    </div>
  );
}

function DetailedSnapshotCard({ snapshot, onOpen }: { snapshot: Snapshot; onOpen: () => void }) {
  const info = getSystemInfo(snapshot.system);
  const payloadSize = new Blob([snapshot.state_json]).size;

  return (
    <div
      className="flex flex-col gap-3 rounded-xl border border-stove-border bg-stove-surface p-3 shadow-sm transition-shadow hover:shadow-md"
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
      <div className="rounded-lg border border-stove-border bg-stove-base p-3">
        <div className="flex items-center justify-between gap-3">
          <span className="text-[10px] font-medium uppercase tracking-[0.16em] text-[var(--stove-text-muted)]">
            State
          </span>
          <span className="text-[10px] uppercase tracking-[0.16em] text-[var(--stove-text-secondary)]">
            {formatBytes(payloadSize)}
          </span>
        </div>
        <div className="mt-2 text-xs text-[var(--stove-text-secondary)]">
          Parsing and search start only when the state explorer is opened.
        </div>
        <button
          type="button"
          className="stove-focus-ring mt-3 w-full cursor-pointer rounded-md border border-stove-border bg-stove-card px-3 py-2 text-left text-xs font-medium text-[var(--stove-text)] hover:bg-[var(--stove-hover)]"
          onClick={onOpen}
        >
          Open state
        </button>
      </div>
    </div>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 102.4) / 10} KB`;
  return `${Math.round(bytes / (1024 * 102.4)) / 10} MB`;
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
