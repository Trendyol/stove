import { type MouseEvent, useEffect, useState } from "react";
import { useAppData } from "./hooks/useAppData";
import { DashboardWorkspace } from "./layout/DashboardWorkspace";
import { Header } from "./layout/Header";
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
          onSelectRun={selectRun}
          onMetadataFilterChange={filterRunsByMetadata}
          onSelectTest={selectTest}
        />
      )}
    </div>
  );
}
