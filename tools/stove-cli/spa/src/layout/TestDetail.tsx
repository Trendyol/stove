import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, useMemo, useState } from "react";
import { api } from "../api/client";
import type { Test } from "../api/types";
import { EntryRow } from "../components/EntryRow";
import { ErrorDialog } from "../components/ErrorDialog";
import { MockJournal } from "../components/MockJournal";
import { SnapshotCards } from "../components/SnapshotCards";
import { SpanTree } from "../components/SpanTree";
import { partitionSnapshotsByDetail } from "../utils/snapshot-state";
import { isRunning } from "../utils/status";
import type { Tab } from "./detail/TabBar";
import { TabBar } from "./detail/TabBar";
import { TestHeader } from "./detail/TestHeader";

const FlowTab = lazy(() =>
  import("../components/FlowTab").then((module) => ({ default: module.FlowTab })),
);

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

  const {
    data: interactions = [],
    isLoading: interactionsLoading,
    error: interactionsError,
  } = useQuery({
    queryKey: ["interactions", runId, test.id],
    queryFn: () => api.getTestInteractions(runId, test.id),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: warnings = [],
    isLoading: warningsLoading,
    error: warningsError,
  } = useQuery({
    queryKey: ["warnings", runId, test.id],
    queryFn: () => api.getTestWarnings(runId, test.id),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: runInteractions = [], error: runInteractionsError } = useQuery({
    queryKey: ["interactions", runId],
    queryFn: () => api.getRunInteractions(runId),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: runWarnings = [], error: runWarningsError } = useQuery({
    queryKey: ["warnings", runId],
    queryFn: () => api.getRunWarnings(runId),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { detailedSnapshots, hiddenCount: hiddenSnapshotCount } = useMemo(
    () => partitionSnapshotsByDetail(snapshots),
    [snapshots],
  );
  const ambientInteractions = useMemo(
    () => runInteractions.filter((interaction) => interaction.test_id == null),
    [runInteractions],
  );
  const ambientWarnings = useMemo(
    () => runWarnings.filter((warning) => warning.test_id == null),
    [runWarnings],
  );
  const mockError =
    interactionsError ?? warningsError ?? runInteractionsError ?? runWarningsError ?? null;

  const tabs = [
    { id: "timeline" as Tab, label: "Evidence", count: entries.length, icon: "activity" as const },
    {
      id: "mocks" as Tab,
      label: "Mock journal",
      count: interactions.length + ambientInteractions.length,
      attention: warnings.length + ambientWarnings.length > 0,
      icon: "mock" as const,
    },
    { id: "trace" as Tab, label: "Trace", count: spans.length, icon: "trace" as const },
    {
      id: "snapshots" as Tab,
      label: "State",
      count: detailedSnapshots.length,
      icon: "snapshot" as const,
    },
    { id: "flow" as Tab, label: "Flow", icon: "flow" as const },
  ];

  return (
    <main className="test-detail">
      <div className="test-detail-header">
        <TestHeader
          test={test}
          liveConnected={liveConnected}
          metrics={{
            entries: entries.length,
            interactions: interactions.length + ambientInteractions.length,
            warnings: warnings.length + ambientWarnings.length,
            snapshots: detailedSnapshots.length,
          }}
          onSelectTab={setTab}
        />
        {test.error && (
          <button
            type="button"
            onClick={() => setErrorOpen(true)}
            className="test-error-strip"
            title="Click to view full error"
          >
            <span>Failure</span>
            {test.error}
          </button>
        )}
        {errorOpen && test.error && (
          <ErrorDialog error={test.error} onClose={() => setErrorOpen(false)} />
        )}
        <TabBar tabs={tabs} active={tab} onSelect={setTab} />
      </div>

      <div className={`test-detail-body ${tab === "flow" ? "is-flow" : ""}`}>
        {tab === "timeline" && (
          <div className="evidence-page">
            <div className="section-heading">
              <div>
                <div className="stove-kicker">Chronological evidence</div>
                <h2>What the test did</h2>
              </div>
              <span>{entries.length} recorded actions</span>
            </div>
            {entriesError && (
              <QueryErrorMessage error={entriesError} fallback="Failed to load entries" />
            )}
            <div className="space-y-2">
              {entries.map((entry) => (
                <EntryRow key={entry.id} entry={entry} />
              ))}
            </div>
            {entries.length === 0 && <div className="stove-empty-state">No entries recorded</div>}
          </div>
        )}
        {tab === "mocks" &&
          (interactionsLoading || warningsLoading ? (
            <div className="stove-empty-state m-4">Loading mock journal…</div>
          ) : mockError ? (
            <QueryErrorMessage error={mockError} fallback="Failed to load mock journal" />
          ) : (
            <MockJournal
              interactions={interactions}
              warnings={warnings}
              ambientInteractions={ambientInteractions}
              ambientWarnings={ambientWarnings}
            />
          ))}
        {tab === "trace" &&
          (spansLoading ? (
            <div className="stove-empty-state m-4">Loading traces…</div>
          ) : spansError ? (
            <QueryErrorMessage error={spansError} fallback="Failed to load traces" />
          ) : (
            <SpanTree spans={spans} />
          ))}
        {tab === "snapshots" &&
          (snapshotsLoading ? (
            <div className="stove-empty-state m-4">Loading snapshots…</div>
          ) : snapshotsError ? (
            <QueryErrorMessage error={snapshotsError} fallback="Failed to load snapshots" />
          ) : (
            <SnapshotCards snapshots={detailedSnapshots} hiddenCount={hiddenSnapshotCount} />
          ))}
        {tab === "flow" && (
          <Suspense fallback={<div className="stove-empty-state m-4">Assembling flow…</div>}>
            <FlowTab
              entries={entries}
              spans={spans}
              snapshots={detailedSnapshots}
              onOpenTraceTab={() => setTab("trace")}
            />
          </Suspense>
        )}
      </div>
    </main>
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
