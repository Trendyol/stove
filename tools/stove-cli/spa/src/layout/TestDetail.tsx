import { useQuery, useQueryClient } from "@tanstack/react-query";
import { lazy, Suspense, useEffect, useMemo, useState } from "react";
import { api } from "../api/client";
import { reconcileDashboardData } from "../api/live-cache";
import type { Entry, MockInteraction, MockWarning, Snapshot, Span, Test } from "../api/types";
import { ErrorDialog } from "../components/ErrorDialog";
import { EvidenceWorkbench } from "../components/EvidenceWorkbench";
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
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<Tab>("timeline");
  const [errorOpen, setErrorOpen] = useState(false);
  const liveRefetchInterval = isRunning(test.status) ? 5000 : false;

  const { data: entries = [], error: entriesError } = useQuery({
    queryKey: ["entries", runId, test.id],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<Entry[]>(
        queryClient,
        ["entries", runId, test.id],
        await api.getEntries(runId, test.id, signal),
      ),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: spans = [],
    isLoading: spansLoading,
    error: spansError,
  } = useQuery({
    queryKey: ["spans", runId, test.id],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<Span[]>(
        queryClient,
        ["spans", runId, test.id],
        await api.getSpans(runId, test.id, signal),
      ),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: snapshots = [],
    isLoading: snapshotsLoading,
    error: snapshotsError,
  } = useQuery({
    queryKey: ["snapshots", runId, test.id],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<Snapshot[]>(
        queryClient,
        ["snapshots", runId, test.id],
        await api.getSnapshots(runId, test.id, signal),
      ),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: interactions = [],
    isLoading: interactionsLoading,
    error: interactionsError,
  } = useQuery({
    queryKey: ["interactions", runId, test.id],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<MockInteraction[]>(
        queryClient,
        ["interactions", runId, test.id],
        await api.getTestInteractions(runId, test.id, signal),
      ),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const {
    data: warnings = [],
    isLoading: warningsLoading,
    error: warningsError,
  } = useQuery({
    queryKey: ["warnings", runId, test.id],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<MockWarning[]>(
        queryClient,
        ["warnings", runId, test.id],
        await api.getTestWarnings(runId, test.id, signal),
      ),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: runInteractions = [], error: runInteractionsError } = useQuery({
    queryKey: ["interactions", runId],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<MockInteraction[]>(
        queryClient,
        ["interactions", runId],
        await api.getRunInteractions(runId, signal),
      ),
    refetchInterval: liveRefetchInterval,
    staleTime: liveConnected ? Number.POSITIVE_INFINITY : 0,
  });

  const { data: runWarnings = [], error: runWarningsError } = useQuery({
    queryKey: ["warnings", runId],
    queryFn: async ({ signal }) =>
      reconcileDashboardData<MockWarning[]>(
        queryClient,
        ["warnings", runId],
        await api.getRunWarnings(runId, signal),
      ),
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
  const mockEvidenceCount =
    interactions.length + ambientInteractions.length + warnings.length + ambientWarnings.length;
  const hasMockEvidence = mockEvidenceCount > 0;

  useEffect(() => {
    setTab("timeline");
  }, [runId, test.id]);

  useEffect(() => {
    if (tab === "mocks" && !hasMockEvidence && !interactionsLoading && !warningsLoading) {
      setTab("timeline");
    }
  }, [hasMockEvidence, interactionsLoading, tab, warningsLoading]);

  const tabs = [
    { id: "timeline" as Tab, label: "Evidence", count: entries.length, icon: "activity" as const },
    ...(hasMockEvidence
      ? [
          {
            id: "mocks" as Tab,
            label: "Mock journal",
            count: mockEvidenceCount,
            attention: warnings.length + ambientWarnings.length > 0,
            icon: "mock" as const,
          },
        ]
      : []),
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
        <TestHeader test={test} liveConnected={liveConnected} />
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
          <>
            {entriesError && (
              <QueryErrorMessage error={entriesError} fallback="Failed to load entries" />
            )}
            {!entriesError && (
              <EvidenceWorkbench
                key={test.id}
                entries={entries}
                onOpenTrace={() => setTab("trace")}
              />
            )}
          </>
        )}
        {tab === "mocks" &&
          (interactionsLoading || warningsLoading ? (
            <div className="stove-empty-state m-4">Loading mock journal…</div>
          ) : mockError ? (
            <QueryErrorMessage error={mockError} fallback="Failed to load mock journal" />
          ) : (
            <MockJournal
              key={test.id}
              interactions={interactions}
              warnings={warnings}
              ambientInteractions={ambientInteractions}
              ambientWarnings={ambientWarnings}
              onOpenTrace={() => setTab("trace")}
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
