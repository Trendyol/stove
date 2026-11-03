import type { CSSProperties } from "react";
import type { TestBrowserState } from "../api/test-queries";
import type { AppSummary, Run, RunMetadataFacet, Test } from "../api/types";
import { TopFilterPicker, type TopFilterPickerProps } from "../components/TopFilterPicker";
import type { MetadataFilter } from "../utils/metadata-filter";
import { AppPicker } from "./sidebar/AppPicker";
import type { RunPickerFeedback } from "./sidebar/RunPicker";
import { RunPicker } from "./sidebar/RunPicker";
import { RunSummary } from "./sidebar/RunSummary";
import { SidebarResizer, useSidebarWidth } from "./sidebar/SidebarResizer";
import { TestFilters } from "./sidebar/TestFilters";
import { TestTree } from "./sidebar/TestTree";

interface SidebarProps {
  topFilter: TopFilterPickerProps;
  apps: AppSummary[];
  mismatchedApps: string[];
  selectedApp: string | undefined;
  onSelectApp: (name: string) => void;
  runs: Run[];
  metadataOptions: RunMetadataFacet[];
  runPickerFeedback?: RunPickerFeedback;
  hasOlderRuns: boolean;
  loadingOlderRuns: boolean;
  onLoadOlderRuns: () => void;
  selectedRunId: string | undefined;
  onSelectRun: (runId: string) => void;
  metadataFilter: MetadataFilter;
  onMetadataFilterChange: (metadata: MetadataFilter) => void;
  run: Run | undefined;
  tests: Test[];
  testBrowser: TestBrowserState;
  selectedTestId: string | undefined;
  onSelectTest: (testId: string) => void;
  onSelectProfiles?: () => void;
  profilesSelected?: boolean;
}

export function Sidebar({
  topFilter,
  apps,
  mismatchedApps,
  selectedApp,
  onSelectApp,
  runs,
  metadataOptions,
  runPickerFeedback,
  hasOlderRuns,
  loadingOlderRuns,
  onLoadOlderRuns,
  selectedRunId,
  onSelectRun,
  metadataFilter,
  onMetadataFilterChange,
  run,
  tests,
  testBrowser,
  selectedTestId,
  onSelectTest,
  onSelectProfiles,
  profilesSelected,
}: SidebarProps) {
  const [width, setWidth] = useSidebarWidth();

  return (
    <aside
      aria-label="Test navigator"
      className="stove-sidebar stove-glass-panel"
      style={{ "--sidebar-width": `${width}px` } as CSSProperties}
    >
      <div className="stove-run-controls">
        <TopFilterPicker {...topFilter} />
        <AppPicker
          apps={apps}
          mismatchedApps={mismatchedApps}
          selectedApp={selectedApp}
          onSelectApp={onSelectApp}
        />
        <RunPicker
          runs={runs}
          metadataOptions={metadataOptions}
          feedback={runPickerFeedback}
          hasOlderRuns={hasOlderRuns}
          loadingOlderRuns={loadingOlderRuns}
          onLoadOlderRuns={onLoadOlderRuns}
          selectedRunId={selectedRunId}
          onSelectRun={onSelectRun}
          metadataFilter={metadataFilter}
          onMetadataFilterChange={onMetadataFilterChange}
        />
        {run && (
          <RunSummary
            run={run}
            summary={testBrowser.summary}
            metadataFilter={metadataFilter}
            onMetadataFilterChange={onMetadataFilterChange}
          />
        )}
      </div>
      {run && onSelectProfiles && (
        <button
          type="button"
          className="profiles-nav stove-focus-ring"
          aria-pressed={profilesSelected}
          onClick={onSelectProfiles}
        >
          Drill Down · whole run
        </button>
      )}
      <TestFilters
        filter={testBrowser.outcome}
        onFilterChange={testBrowser.onOutcomeChange}
        search={testBrowser.search}
        onSearchChange={testBrowser.onSearchChange}
      />
      <div className="stove-test-tree-panel">
        <div className="stove-sidebar-section-label">
          <span>Tests</span>
          <span>
            {tests.length} of {testBrowser.total}
          </span>
        </div>
        <TestTree tests={tests} selectedTestId={selectedTestId} onSelectTest={onSelectTest} />
        {testBrowser.error && (
          <p role="alert">
            Could not load tests.{" "}
            <button type="button" onClick={testBrowser.retry}>
              Retry tests
            </button>
          </p>
        )}
        {testBrowser.loading && <p role="status">Loading tests…</p>}
        {testBrowser.hasMore && (
          <button type="button" disabled={testBrowser.loading} onClick={testBrowser.loadMore}>
            Load more tests
          </button>
        )}
      </div>
      <SidebarResizer width={width} onResize={setWidth} />
    </aside>
  );
}
