import { VersionMismatchBanner } from "./components/VersionMismatchBanner";
import { useAppData } from "./hooks/useAppData";
import { Header } from "./layout/Header";
import { Sidebar } from "./layout/Sidebar";
import { TestDetail } from "./layout/TestDetail";

export default function App() {
  const {
    apps,
    activeApp,
    latestRun,
    tests,
    selectedTest,
    liveConnected,
    mismatchedApps,
    versionMismatchSummary,
    selectApp,
    selectTest,
  } = useAppData();

  return (
    <div className="stove-app-shell flex h-screen flex-col font-sans text-[var(--stove-text)]">
      <Header liveConnected={liveConnected} />
      {versionMismatchSummary ? <VersionMismatchBanner summary={versionMismatchSummary} /> : null}
      <div className="stove-workspace">
        <Sidebar
          apps={apps}
          mismatchedApps={mismatchedApps}
          selectedApp={activeApp}
          onSelectApp={selectApp}
          run={latestRun}
          tests={tests}
          selectedTestId={selectedTest?.id ?? null}
          onSelectTest={selectTest}
        />
        {latestRun && selectedTest ? (
          <TestDetail runId={latestRun.id} test={selectedTest} liveConnected={liveConnected} />
        ) : (
          <div className="workspace-empty">
            <div className="workspace-empty-mark">
              <span />
              <span />
              <span />
            </div>
            <div className="stove-kicker">
              {apps.length === 0 ? "Listening for a run" : "Evidence workspace"}
            </div>
            <h1>
              {apps.length === 0 ? "Waiting for the first signal" : "Choose a test to inspect"}
            </h1>
            <p>
              {apps.length === 0
                ? "The dashboard will assemble the run as test, trace, state and mock events arrive."
                : "Select a test from the run navigator to open its evidence dossier."}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
