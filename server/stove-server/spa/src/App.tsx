import { AgentConnectionProvider } from "./components/agent-connection/AgentConnectionProvider";
import { ErrorNotice } from "./components/ErrorNotice";
import { useAppData } from "./hooks/useAppData";
import { DashboardWorkspace } from "./layout/DashboardWorkspace";
import { Header } from "./layout/Header";
import { LinkedWorkspace } from "./layout/LinkedWorkspace";
import { AdminPage } from "./pages/AdminPage";
import { appPath, navigateLink, useLocation } from "./utils/location";
import type { StoveRoute } from "./utils/routes";

export default function App() {
  return (
    <AgentConnectionProvider>
      <AppContent />
    </AgentConnectionProvider>
  );
}

function AppContent() {
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
    allApps,
    topFilter,
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

  return (
    <div className="stove-app-shell flex h-screen flex-col font-sans text-[var(--stove-text)]">
      <Header
        activeRoute={route}
        liveConnected={liveConnected}
        versionMismatchSummary={versionMismatchSummary}
        onNavigateAdmin={(event) => navigateLink(event, "/admin")}
      />
      {route === "dashboard" && error && (
        <ErrorNotice onRetry={() => void retry()}>
          Could not load dashboard data: {error.message}
        </ErrorNotice>
      )}
      {route === "dashboard" && loading && !error && <div role="status">Loading dashboard…</div>}
      {route === "admin" ? (
        <AdminPage apps={allApps} onNavigateDashboard={(event) => navigateLink(event, "/")} />
      ) : (
        <DashboardWorkspace
          topFilter={topFilter}
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
