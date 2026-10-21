import { ApiError } from "../api/client";
import { EvidenceActions } from "../components/EvidenceActions";
import { FocusedEvidenceView } from "../components/FocusedEvidenceView";
import { FocusedEvidenceProvider, useFocusedEvidenceState } from "../hooks/FocusedEvidenceProvider";
import { EvidenceNavigationProvider } from "../hooks/useEvidenceNavigation";
import { useLinkedWorkspace } from "../hooks/useLinkedWorkspace";
import { appPath, type EvidenceLocation, evidencePath, navigateLink } from "../utils/location";
import { isRunning } from "../utils/status";
import { Header } from "./Header";
import { TestDetail } from "./TestDetail";

export function LinkedWorkspace({ location }: { location: EvidenceLocation }) {
  const { runId, testId } = location;
  const workspace = useLinkedWorkspace(location);
  const { run, test, connected } = workspace;
  return (
    <div className="stove-app-shell flex h-screen flex-col font-sans text-[var(--stove-text)]">
      <Header
        activeRoute="dashboard"
        liveConnected={connected}
        versionMismatchSummary={null}
        onNavigateAdmin={(event) => navigateLink(event, "/admin")}
      />
      <nav className="evidence-breadcrumb" aria-label="Evidence location">
        <a href={appPath("/")}>Applications</a>
        <span>/</span>
        <a href={appPath(evidencePath(runId))}>
          {run.data?.app_name ?? "Run"} · {runId}
        </a>
        {testId && (
          <>
            <span>/</span>
            <a href={appPath(evidencePath(runId, testId))}>{test.data?.test_name ?? testId}</a>
          </>
        )}
      </nav>
      <LinkedWorkspaceContent location={location} workspace={workspace} />
    </div>
  );
}
function RunEvidence({ runId }: { runId: string }) {
  const query = useFocusedEvidenceState();
  if (!query) return null;
  return (
    <main className="test-detail">
      <EvidenceActions />
      <FocusedEvidenceView query={query} runId={runId} />
    </main>
  );
}

function LinkedWorkspaceContent({
  location,
  workspace,
}: {
  location: EvidenceLocation;
  workspace: ReturnType<typeof useLinkedWorkspace>;
}) {
  const { runId, testId } = location;
  const { run, test, tests, connected } = workspace;
  let error = run.error;
  if (!error && testId) error = test.error;
  else if (!error && !location.focus) error = tests.error;

  if (error)
    return (
      <div role="alert" className="stove-empty-state m-4">
        <strong>
          {error instanceof ApiError && error.status === 404
            ? "The requested run or test is unavailable."
            : "Could not load the requested run or test."}
        </strong>
        <p>No other run or test has been selected.</p>
        <button
          type="button"
          onClick={() => {
            void run.refetch();
            if (testId) void test.refetch();
            else if (!location.focus) void tests.refetch();
          }}
        >
          Retry
        </button>
      </div>
    );
  if (!run.data || (testId && !test.data))
    return (
      <div role="status" className="stove-empty-state m-4">
        Loading requested test run…
      </div>
    );
  if (test.data && testId)
    return (
      <EvidenceNavigationProvider runId={runId} testId={testId}>
        <FocusedEvidenceProvider running={isRunning(test.data.status)} liveConnected={connected}>
          <TestDetail
            key={JSON.stringify([runId, testId])}
            runId={runId}
            test={test.data}
            liveConnected={connected}
          />
        </FocusedEvidenceProvider>
      </EvidenceNavigationProvider>
    );
  if (location.traceId)
    return (
      <main className="stove-empty-state m-4" role="status">
        <strong>Related trace · {location.traceId}</strong>
        <p>Trace details are unavailable for evidence with no test attribution.</p>
        <a href={appPath(evidencePath(runId))}>Back to run</a>
      </main>
    );
  if (location.focus)
    return (
      <EvidenceNavigationProvider runId={runId}>
        <FocusedEvidenceProvider running={isRunning(run.data.status)} liveConnected={connected}>
          <RunEvidence runId={runId} />
        </FocusedEvidenceProvider>
      </EvidenceNavigationProvider>
    );
  return (
    <main className="m-4">
      <h1>{run.data.app_name} — tests in this run</h1>
      <LinkedTestList runId={runId} tests={tests} />
    </main>
  );
}

function LinkedTestList({
  runId,
  tests,
}: {
  runId: string;
  tests: ReturnType<typeof useLinkedWorkspace>["tests"];
}) {
  if (tests.isPending) return <p role="status">Loading tests…</p>;
  if (tests.data?.length === 0) return <p>No tests recorded.</p>;
  return (
    <ul className="linked-test-list">
      {tests.data?.map((test) => (
        <li key={test.id}>
          <a href={appPath(evidencePath(runId, test.id))}>
            {test.status} · {test.spec_name} · {test.test_path.join(" › ") || test.test_name}
          </a>
        </li>
      ))}
    </ul>
  );
}
