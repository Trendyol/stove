import { useAppData } from "./hooks/useAppData";
import { Header } from "./layout/Header";
import { Sidebar } from "./layout/Sidebar";
import { TestDetail } from "./layout/TestDetail";

export default function App() {
  const { apps, activeApp, latestRun, tests, selectedTest, liveConnected, selectApp, selectTest } =
    useAppData();

  return (
    <div className="flex flex-col h-screen bg-stove-base text-[var(--stove-text)] font-sans">
      <Header />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar
          apps={apps}
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
          <div className="flex-1 flex items-center justify-center text-[var(--stove-text-muted)] text-sm">
            {apps.length === 0 ? "Waiting for test events..." : "Select a test to view details"}
          </div>
        )}
      </div>
    </div>
  );
}
