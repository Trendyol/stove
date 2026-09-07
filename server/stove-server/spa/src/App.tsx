import type { MouseEvent } from "react";
import { useAppData } from "./hooks/useAppData";
import { DashboardWorkspace } from "./layout/DashboardWorkspace";
import { Header } from "./layout/Header";
import { LinkedWorkspace } from "./layout/LinkedWorkspace";
import { AdminPage } from "./pages/AdminPage";
import { appPath, evidencePath, navigateTo, useLocation } from "./utils/location";
import { pathForRoute, type StoveRoute } from "./utils/routes";

export default function App() {
  const location = useLocation();
  if (location.kind === "evidence") return <LinkedWorkspace location={location.value} />;
  if (location.kind === "invalid")
    return (
      <main className="stove-empty-state m-4" role="alert">
        This evidence link is invalid. <a href={appPath("/")}>Open dashboard</a>
      </main>
    );
  return <DashboardApp route={location.kind === "admin" ? "admin" : "dashboard"} />;
}
function DashboardApp({ route }: { route: StoveRoute }) {
  const {
    error,
    loading,
    retry,
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

  const navigate = (event: MouseEvent<HTMLAnchorElement>, nextRoute: StoveRoute) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }

    event.preventDefault();
    const nextPath = pathForRoute(nextRoute);
    navigateTo(nextPath);
  };

  return (
    <div className="stove-app-shell flex h-screen flex-col font-sans text-[var(--stove-text)]">
      <Header
        activeRoute={route}
        liveConnected={liveConnected}
        versionMismatchSummary={versionMismatchSummary}
        onNavigateAdmin={(event) => navigate(event, "admin")}
      />
      {route === "dashboard" && error && (
        <div role="alert" className="stove-admin-error flex items-center justify-between">
          <span>Could not load dashboard data: {error.message}</span>
          <button type="button" onClick={() => void retry()}>
            Retry
          </button>
        </div>
      )}
      {route === "dashboard" && loading && !error && <div role="status">Loading dashboard…</div>}
      {route === "admin" ? (
        <AdminPage apps={apps} onNavigateDashboard={(event) => navigate(event, "dashboard")} />
      ) : (
        <DashboardWorkspace
          apps={apps}
          activeApp={activeApp}
          mismatchedApps={mismatchedApps}
          runs={runs}
          allRuns={allRuns}
          selectedRunId={selectedRunId}
          metadataFilter={metadataFilter}
          latestRun={latestRun}
          tests={tests}
          selectedTest={selectedTest}
          liveConnected={liveConnected}
          onSelectApp={selectApp}
          onSelectRun={(runId) => {
            selectRun(runId);
            navigateTo(evidencePath(runId));
          }}
          onMetadataFilterChange={filterRunsByMetadata}
          onSelectTest={(testId) => {
            selectTest(testId);
            if (latestRun) navigateTo(evidencePath(latestRun.id, testId));
          }}
        />
      )}
    </div>
  );
}
