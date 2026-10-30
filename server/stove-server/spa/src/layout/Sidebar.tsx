import { type CSSProperties, useState } from "react";
import type { AppSummary, Run, Test } from "../api/types";
import { TopFilterPicker, type TopFilterPickerProps } from "../components/TopFilterPicker";
import { filterTests } from "../utils/filters";
import type { MetadataFilter } from "../utils/metadata-filter";
import { AppPicker } from "./sidebar/AppPicker";
import { RunPicker } from "./sidebar/RunPicker";
import { RunSummary } from "./sidebar/RunSummary";
import { SidebarResizer, useSidebarWidth } from "./sidebar/SidebarResizer";
import type { FilterValue } from "./sidebar/TestFilters";
import { TestFilters } from "./sidebar/TestFilters";
import { TestTree } from "./sidebar/TestTree";

interface SidebarProps {
  topFilter: TopFilterPickerProps;
  apps: AppSummary[];
  mismatchedApps: string[];
  selectedApp: string | undefined;
  onSelectApp: (name: string) => void;
  runs: Run[];
  availableRuns: Run[];
  selectedRunId: string | undefined;
  onSelectRun: (runId: string) => void;
  metadataFilter: MetadataFilter;
  onMetadataFilterChange: (metadata: MetadataFilter) => void;
  run: Run | undefined;
  tests: Test[];
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
  availableRuns,
  selectedRunId,
  onSelectRun,
  metadataFilter,
  onMetadataFilterChange,
  run,
  tests,
  selectedTestId,
  onSelectTest,
  onSelectProfiles,
  profilesSelected,
}: SidebarProps) {
  const [filter, setFilter] = useState<FilterValue>("all");
  const [search, setSearch] = useState("");
  const [width, setWidth] = useSidebarWidth();
  const filteredTests = filterTests(tests, filter, search);

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
          availableRuns={availableRuns}
          selectedRunId={selectedRunId}
          onSelectRun={onSelectRun}
          metadataFilter={metadataFilter}
          onMetadataFilterChange={onMetadataFilterChange}
        />
        {run && (
          <RunSummary
            run={run}
            tests={tests}
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
        filter={filter}
        onFilterChange={setFilter}
        search={search}
        onSearchChange={setSearch}
      />
      <div className="stove-test-tree-panel">
        <div className="stove-sidebar-section-label">
          <span>Tests</span>
          <span>{filteredTests.length}</span>
        </div>
        <TestTree
          tests={filteredTests}
          selectedTestId={selectedTestId}
          onSelectTest={onSelectTest}
        />
      </div>
      <SidebarResizer width={width} onResize={setWidth} />
    </aside>
  );
}
