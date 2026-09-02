import { type MouseEvent, useEffect, useState } from "react";
import { useAppData } from "./hooks/useAppData";
import { Header } from "./layout/Header";
import { Sidebar } from "./layout/Sidebar";
import { TestDetail } from "./layout/TestDetail";
import { AdminPage } from "./pages/AdminPage";
import { pathForRoute, routeForPath, type StoveRoute } from "./utils/routes";

export default function App() {
  const [route, setRoute] = useState<StoveRoute>(() => routeForPath(window.location.pathname));
  const {
    apps,
    activeApp,
    latestRun,
    runs,
    allRuns,
    selectedRunId,
    metadataFilter,
    tests,
    selectedTest,
    liveConnected,
    mismatchedApps,
    versionMismatchSummary,
    selectApp,
    selectRun,
    filterRunsByMetadata,
    selectTest,
  } = useAppData();

  useEffect(() => {
    const syncRoute = () => setRoute(routeForPath(window.location.pathname));
    window.addEventListener("popstate", syncRoute);
    return () => window.removeEventListener("popstate", syncRoute);
  }, []);

  const navigate = (event: MouseEvent<HTMLAnchorElement>, nextRoute: StoveRoute) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }

    event.preventDefault();
    const nextPath = pathForRoute(nextRoute);
    if (window.location.pathname !== nextPath) window.history.pushState(null, "", nextPath);
    setRoute(nextRoute);
  };

  return (
    <div className="stove-app-shell flex h-screen flex-col font-sans text-[var(--stove-text)]">
      <Header
        activeRoute={route}
        liveConnected={liveConnected}
        versionMismatchSummary={versionMismatchSummary}
        onNavigateAdmin={(event) => navigate(event, "admin")}
      />
      {route === "admin" ? (
        <AdminPage apps={apps} onNavigateDashboard={(event) => navigate(event, "dashboard")} />
      ) : (
        <div className="stove-workspace">
          <Sidebar
            apps={apps}
            mismatchedApps={mismatchedApps}
            selectedApp={activeApp}
            onSelectApp={selectApp}
            runs={runs}
            availableRuns={allRuns}
            selectedRunId={selectedRunId}
            onSelectRun={selectRun}
            metadataFilter={metadataFilter}
            onMetadataFilterChange={filterRunsByMetadata}
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
      )}
    </div>
  );
}
