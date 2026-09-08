import { lazy, Suspense, useEffect, useMemo } from "react";
import { dashboardQueries } from "../../api/dashboard-queries";
import type { Entry, MockInteraction, MockWarning, Snapshot, Span } from "../../api/types";
import { EvidenceWorkbench } from "../../components/EvidenceWorkbench";
import { FocusedEvidenceView } from "../../components/FocusedEvidenceView";
import { MockJournal } from "../../components/MockJournal";
import { SnapshotCards } from "../../components/SnapshotCards";
import { SpanTree } from "../../components/SpanTree";
import { useFocusedEvidenceState } from "../../hooks/FocusedEvidenceProvider";
import { type DashboardListQuery, useDashboardListQuery } from "../../hooks/useDashboardListQuery";
import { useEvidenceNavigation } from "../../hooks/useEvidenceNavigation";
import { partitionSnapshotsByDetail } from "../../utils/snapshot-state";
import type { Tab } from "./TabBar";

const FlowTab = lazy(() =>
  import("../../components/FlowTab").then((module) => ({ default: module.FlowTab })),
);

export interface TabSummary {
  count: number;
  attention?: boolean;
}

interface TestDetailTabProps {
  tab: Tab;
  runId: string;
  testId: string;
  testRunning: boolean;
  liveConnected: boolean;
  onSelectTab: (tab: Tab) => void;
  onOpenTrace: (traceId: string) => void;
  traceId?: string;
  onClearTrace: () => void;
  onSummary: (tab: Tab, summary: TabSummary) => void;
}

interface TestQueryScope {
  runId: string;
  testId: string;
  liveConnected: boolean;
  pollWhileDisconnected: boolean;
}

export function TestDetailTab({
  tab,
  runId,
  testId,
  testRunning,
  liveConnected,
  onOpenTrace,
  traceId,
  onClearTrace,
  onSummary,
}: TestDetailTabProps) {
  const navigation = useEvidenceNavigation();
  const focus = useFocusedEvidenceState();
  const scope = {
    runId,
    testId,
    liveConnected,
    pollWhileDisconnected: testRunning,
  };

  if (navigation?.focus && focus && (!navigation.full || focus.error || focus.isPending))
    return <FocusedEvidenceView query={focus} runId={runId} testId={testId} />;

  switch (tab) {
    case "timeline":
      return (
        <EvidenceTab
          citedEntry={focus?.data?.target.kind === "entry" ? focus.data.target.value : undefined}
          scope={scope}
          onOpenTrace={onOpenTrace}
          onSummary={onSummary}
        />
      );
    case "mocks":
      return <MocksTab scope={scope} onOpenTrace={onOpenTrace} onSummary={onSummary} />;
    case "trace":
      return (
        <TraceTab
          key={traceId ?? "all"}
          scope={scope}
          traceId={traceId}
          onClearTrace={onClearTrace}
          onSummary={onSummary}
        />
      );
    case "snapshots":
      return <SnapshotsTab scope={scope} onSummary={onSummary} />;
    case "flow":
      return <FlowView scope={scope} onOpenTrace={onOpenTrace} />;
  }
}

function EvidenceTab({
  citedEntry,
  scope,
  onOpenTrace,
  onSummary,
}: {
  citedEntry?: Entry;
  scope: TestQueryScope;
  onOpenTrace: (traceId: string) => void;
  onSummary: TestDetailTabProps["onSummary"];
}) {
  const query = useEntries(scope);
  useTabSummary("timeline", query.data.length, false, onSummary);

  return (
    <ListQueryView query={query} loading="Loading evidence…" failure="Failed to load entries">
      {(entries) => (
        <EvidenceWorkbench
          key={scope.testId}
          entries={
            citedEntry && !entries.some((entry) => entry.id === citedEntry.id)
              ? [...entries, citedEntry].sort(
                  (a, b) => a.timestamp.localeCompare(b.timestamp) || a.id - b.id,
                )
              : entries
          }
          onOpenTrace={onOpenTrace}
        />
      )}
    </ListQueryView>
  );
}

function MocksTab({
  scope,
  onOpenTrace,
  onSummary,
}: {
  scope: TestQueryScope;
  onOpenTrace: (traceId: string) => void;
  onSummary: TestDetailTabProps["onSummary"];
}) {
  const interactions = useDashboardListQuery<MockInteraction>({
    query: dashboardQueries.testMockInteractions(scope.runId, scope.testId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
  const warnings = useDashboardListQuery<MockWarning>({
    query: dashboardQueries.testMockWarnings(scope.runId, scope.testId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
  const ambientInteractions = useDashboardListQuery<MockInteraction>({
    query: dashboardQueries.ambientMockInteractions(scope.runId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
  const ambientWarnings = useDashboardListQuery<MockWarning>({
    query: dashboardQueries.ambientMockWarnings(scope.runId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
  const queries = [interactions, warnings, ambientInteractions, ambientWarnings] as const;
  const count = queries.reduce((total, query) => total + query.data.length, 0);
  const warningCount = warnings.data.length + ambientWarnings.data.length;
  useTabSummary("mocks", count, warningCount > 0, onSummary);

  if (queries.some((query) => query.kind === "loading")) {
    return <LoadingMessage>Loading mock journal…</LoadingMessage>;
  }
  const error = firstQueryError(queries);
  if (error) return <QueryErrorMessage error={error} fallback="Failed to load mock journal" />;

  return (
    <MockJournal
      key={scope.testId}
      interactions={interactions.data}
      warnings={warnings.data}
      ambientInteractions={ambientInteractions.data}
      ambientWarnings={ambientWarnings.data}
      onOpenTrace={onOpenTrace}
    />
  );
}

function TraceTab({
  scope,
  traceId,
  onClearTrace,
  onSummary,
}: {
  scope: TestQueryScope;
  traceId?: string;
  onClearTrace: () => void;
  onSummary: TestDetailTabProps["onSummary"];
}) {
  const query = useSpans(scope);
  useTabSummary("trace", query.data.length, false, onSummary);
  return (
    <ListQueryView query={query} loading="Loading traces…" failure="Failed to load traces">
      {(spans) => (
        <>
          {traceId && (
            <div className="evidence-scope-note" role="status">
              Related trace · <code>{traceId}</code>{" "}
              <button type="button" onClick={onClearTrace}>
                Show all traces
              </button>
            </div>
          )}
          {traceId && !spans.some((span) => span.trace_id === traceId) ? (
            <div className="stove-empty-state m-4" role="status">
              No spans were recorded for this trace.
            </div>
          ) : (
            <SpanTree
              spans={traceId ? spans.filter((span) => span.trace_id === traceId) : spans}
              traceId={traceId}
            />
          )}
        </>
      )}
    </ListQueryView>
  );
}

function SnapshotsTab({
  scope,
  onSummary,
}: {
  scope: TestQueryScope;
  onSummary: TestDetailTabProps["onSummary"];
}) {
  const query = useSnapshots(scope);
  const { detailedSnapshots, hiddenCount } = useMemo(
    () => partitionSnapshotsByDetail(query.data),
    [query.data],
  );
  useTabSummary("snapshots", detailedSnapshots.length, false, onSummary);

  return (
    <ListQueryView query={query} loading="Loading snapshots…" failure="Failed to load snapshots">
      {() => <SnapshotCards snapshots={query.data} hiddenCount={hiddenCount} />}
    </ListQueryView>
  );
}

function FlowView({
  scope,
  onOpenTrace,
}: {
  scope: TestQueryScope;
  onOpenTrace: (traceId: string) => void;
}) {
  const entries = useEntries(scope);
  const spans = useSpans(scope);
  const snapshots = useSnapshots(scope);
  const { detailedSnapshots } = useMemo(
    () => partitionSnapshotsByDetail(snapshots.data),
    [snapshots.data],
  );
  const queries = [entries, spans, snapshots] as const;
  const error = firstQueryError(queries);

  if (queries.some((query) => query.kind === "loading")) {
    return <LoadingMessage>Assembling flow…</LoadingMessage>;
  }
  if (error) return <QueryErrorMessage error={error} fallback="Failed to assemble flow" />;

  return (
    <Suspense fallback={<LoadingMessage>Assembling flow…</LoadingMessage>}>
      <FlowTab
        entries={entries.data}
        spans={spans.data}
        snapshots={detailedSnapshots}
        onOpenTraceTab={onOpenTrace}
      />
    </Suspense>
  );
}

function useEntries(scope: TestQueryScope) {
  return useDashboardListQuery<Entry>({
    query: dashboardQueries.entries(scope.runId, scope.testId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
}

function useSpans(scope: TestQueryScope) {
  return useDashboardListQuery<Span>({
    query: dashboardQueries.spans(scope.runId, scope.testId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
}

function useSnapshots(scope: TestQueryScope) {
  return useDashboardListQuery<Snapshot>({
    query: dashboardQueries.snapshots(scope.runId, scope.testId),
    liveConnected: scope.liveConnected,
    pollWhileDisconnected: scope.pollWhileDisconnected,
  });
}

function useTabSummary(
  tab: Tab,
  count: number,
  attention: boolean,
  onSummary: TestDetailTabProps["onSummary"],
) {
  useEffect(() => {
    onSummary(tab, attention ? { count, attention } : { count });
  }, [attention, count, onSummary, tab]);
}

function ListQueryView<T>({
  query,
  loading,
  failure,
  children,
}: {
  query: DashboardListQuery<T>;
  loading: string;
  failure: string;
  children: (data: T[]) => React.ReactNode;
}) {
  switch (query.kind) {
    case "loading":
      return <LoadingMessage>{loading}</LoadingMessage>;
    case "error":
      return <QueryErrorMessage error={query.error} fallback={failure} />;
    case "ready":
      return children(query.data);
  }
}

function firstQueryError(queries: readonly DashboardListQuery<unknown>[]): Error | undefined {
  return queries.find((query) => query.kind === "error")?.error;
}

function LoadingMessage({ children }: { children: React.ReactNode }) {
  return <div className="stove-empty-state m-4">{children}</div>;
}

function QueryErrorMessage({ error, fallback }: { error: Error; fallback: string }) {
  return (
    <div className="m-4 rounded-xl border border-red-500/30 bg-red-500/10 p-4 text-sm text-[var(--stove-red)]">
      {error.message || fallback}
    </div>
  );
}
