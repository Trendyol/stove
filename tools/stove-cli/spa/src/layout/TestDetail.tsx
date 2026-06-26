import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { api } from "../api/client";
import type { Test } from "../api/types";
import { EntryRow } from "../components/EntryRow";
import { ErrorDialog } from "../components/ErrorDialog";
import { FlowTab } from "../components/FlowTab";
import { SnapshotCards } from "../components/SnapshotCards";
import { SpanTree } from "../components/SpanTree";
import { partitionSnapshotsByDetail } from "../utils/snapshot-state";
import { isRunning } from "../utils/status";
import type { Tab } from "./detail/TabBar";
import { TabBar } from "./detail/TabBar";
import { TestHeader } from "./detail/TestHeader";

interface TestDetailProps {
  runId: string;
  test: Test;
  liveConnected: boolean;
}

export function TestDetail({ runId, test, liveConnected }: TestDetailProps) {
  const [tab, setTab] = useState<Tab>("timeline");
  const [errorOpen, setErrorOpen] = useState(false);
  const liveRefetchInterval = isRunning(test.status) && !liveConnected ? 5000 : false;

  const { data: entries = [], error: entriesError } = useQuery({
    queryKey: ["entries", runId, test.id],
    queryFn: () => api.getEntries(runId, test.id),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: spans = [],
    isLoading: spansLoading,
    error: spansError,
  } = useQuery({
    queryKey: ["spans", runId, test.id],
    queryFn: () => api.getSpans(runId, test.id),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: snapshots = [],
    isLoading: snapshotsLoading,
    error: snapshotsError,
  } = useQuery({
    queryKey: ["snapshots", runId, test.id],
    queryFn: () => api.getSnapshots(runId, test.id),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { detailedSnapshots, hiddenCount: hiddenSnapshotCount } = useMemo(
    () => partitionSnapshotsByDetail(snapshots),
    [snapshots],
  );

  const tabs = [
    { id: "timeline" as Tab, label: `Timeline (${entries.length})`, icon: "\u{1f4cb}" },
    { id: "trace" as Tab, label: `Trace (${spans.length})`, icon: "\u{1f50d}" },
    {
      id: "snapshots" as Tab,
      label: `Snapshots (${detailedSnapshots.length})`,
      icon: "\u{1f4f8}",
    },
    { id: "flow" as Tab, label: "Flow", icon: "\u{1f310}" },
  ];

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="sticky top-0 z-10 border-b border-stove-border bg-[var(--stove-panel-strong)] px-4 py-3 shadow-sm">
        <TestHeader test={test} />
        {test.error && (
          <button
            type="button"
            onClick={() => setErrorOpen(true)}
            className="mt-3 block w-full cursor-pointer truncate rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-left font-mono text-xs text-[var(--stove-red)] hover:bg-red-500/20"
            title="Click to view full error"
          >
            {test.error}
          </button>
        )}
        {errorOpen && test.error && (
          <ErrorDialog error={test.error} onClose={() => setErrorOpen(false)} />
        )}
        <TabBar tabs={tabs} active={tab} onSelect={setTab} />
      </div>

      <div className={`min-h-0 flex-1 ${tab === "flow" ? "overflow-hidden" : "overflow-y-auto"}`}>
        {tab === "timeline" && (
          <div className="p-4 space-y-2">
            {entriesError && (
              <QueryErrorMessage error={entriesError} fallback="Failed to load entries" />
            )}
            {entries.map((entry) => (
              <EntryRow key={entry.id} entry={entry} />
            ))}
            {entries.length === 0 && (
              <div className="rounded-xl border border-dashed border-stove-border bg-stove-surface p-6 text-center text-sm text-[var(--stove-text-secondary)]">
                No entries recorded
              </div>
            )}
          </div>
        )}
        {tab === "trace" &&
          (spansLoading ? (
            <div className="rounded-xl border border-dashed border-stove-border bg-stove-surface p-6 text-center text-sm text-[var(--stove-text-secondary)]">
              Loading traces...
            </div>
          ) : spansError ? (
            <QueryErrorMessage error={spansError} fallback="Failed to load traces" />
          ) : (
            <SpanTree spans={spans} />
          ))}
        {tab === "snapshots" &&
          (snapshotsLoading ? (
            <div className="rounded-xl border border-dashed border-stove-border bg-stove-surface p-6 text-center text-sm text-[var(--stove-text-secondary)]">
              Loading snapshots...
            </div>
          ) : snapshotsError ? (
            <QueryErrorMessage error={snapshotsError} fallback="Failed to load snapshots" />
          ) : (
            <SnapshotCards snapshots={detailedSnapshots} hiddenCount={hiddenSnapshotCount} />
          ))}
        {tab === "flow" && (
          <FlowTab
            entries={entries}
            spans={spans}
            snapshots={detailedSnapshots}
            onOpenTraceTab={() => setTab("trace")}
          />
        )}
      </div>
    </div>
  );
}

function QueryErrorMessage({ error, fallback }: { error: unknown; fallback: string }) {
  const message = error instanceof Error ? error.message : fallback;
  return (
    <div className="m-4 rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-[var(--stove-red)]">
      {message}
    </div>
  );
}
