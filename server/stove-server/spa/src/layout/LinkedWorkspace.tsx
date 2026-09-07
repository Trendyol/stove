import { ApiError } from "../api/client";
import { EvidenceActions } from "../components/EvidenceActions";
import { FocusedEvidenceView } from "../components/FocusedEvidenceView";
import { FocusedEvidenceProvider, useFocusedEvidenceState } from "../hooks/FocusedEvidenceProvider";
import { EvidenceNavigationProvider } from "../hooks/useEvidenceNavigation";
import { useLinkedWorkspace } from "../hooks/useLinkedWorkspace";
import { appPath, type EvidenceLocation, evidencePath, navigateTo } from "../utils/location";
import { isRunning } from "../utils/status";
import { Header } from "./Header";
import { TestDetail } from "./TestDetail";

export function LinkedWorkspace({ location }: { location: EvidenceLocation }) {
  const { runId, testId } = location;
  const { run, test, tests, connected } = useLinkedWorkspace(location);
  const error = run.error ?? (testId ? test.error : !location.focus ? tests.error : null);
  return (
    <div className="stove-app-shell flex h-screen flex-col font-sans text-[var(--stove-text)]">
      <Header
        activeRoute="dashboard"
        liveConnected={connected}
        versionMismatchSummary={null}
        onNavigateAdmin={(event) => {
          if (
            !event.metaKey &&
            !event.ctrlKey &&
            !event.shiftKey &&
            !event.altKey &&
            event.button === 0
          ) {
            event.preventDefault();
            navigateTo("/admin");
          }
        }}
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
      {error ? (
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
      ) : !run.data || (testId && !test.data) ? (
        <div role="status" className="stove-empty-state m-4">
          Loading requested test run…
        </div>
      ) : test.data && testId ? (
        <TestDetail
          key={JSON.stringify([runId, testId])}
          runId={runId}
          test={test.data}
          liveConnected={connected}
        />
      ) : location.focus ? (
        <EvidenceNavigationProvider runId={runId}>
          <FocusedEvidenceProvider running={isRunning(run.data.status)} liveConnected={connected}>
            <RunEvidence runId={runId} />
          </FocusedEvidenceProvider>
        </EvidenceNavigationProvider>
      ) : (
        <main className="m-4">
          <h1>{run.data.app_name} — tests in this run</h1>
          {tests.isPending ? (
            <p role="status">Loading tests…</p>
          ) : tests.data?.length === 0 ? (
            <p>No tests recorded.</p>
          ) : (
            <ul className="linked-test-list">
              {tests.data?.map((item) => (
                <li key={item.id}>
                  <a href={appPath(evidencePath(runId, item.id))}>
                    {item.status} · {item.spec_name} ·{" "}
                    {item.test_path.join(" › ") || item.test_name}
                  </a>
                </li>
              ))}
            </ul>
          )}
        </main>
      )}
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
