import { type CSSProperties, useCallback, useRef, useState } from "react";
import type { AppSummary, Run, Test } from "../api/types";
import { filterTests } from "../utils/filters";
import type { MetadataFilter } from "../utils/metadata-filter";
import { AppPicker } from "./sidebar/AppPicker";
import { RunPicker } from "./sidebar/RunPicker";
import { RunSummary } from "./sidebar/RunSummary";
import type { FilterValue } from "./sidebar/TestFilters";
import { TestFilters } from "./sidebar/TestFilters";
import { TestTree } from "./sidebar/TestTree";

const SIDEBAR_MIN_WIDTH = 240;
const SIDEBAR_MAX_WIDTH = 600;
const SIDEBAR_DEFAULT_WIDTH = 280;
const SIDEBAR_STORAGE_KEY = "stove-sidebar-width";

function loadSidebarWidth(): number {
  const stored = localStorage.getItem(SIDEBAR_STORAGE_KEY);
  if (!stored) return SIDEBAR_DEFAULT_WIDTH;
  const parsed = Number(stored);
  return Number.isFinite(parsed)
    ? Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, parsed))
    : SIDEBAR_DEFAULT_WIDTH;
}

interface SidebarProps {
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
}

export function Sidebar({
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
}: SidebarProps) {
  const [filter, setFilter] = useState<FilterValue>("all");
  const [search, setSearch] = useState("");
  const [width, setWidth] = useState(loadSidebarWidth);
  const draggingRef = useRef(false);

  const filteredTests = filterTests(tests, filter, search);

  const setSidebarWidth = useCallback((value: number) => {
    const next = Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, value));
    setWidth(next);
    localStorage.setItem(SIDEBAR_STORAGE_KEY, String(next));
  }, []);

  return (
    <aside
      aria-label="Test navigator"
      className="stove-sidebar stove-glass-panel"
      style={{ "--sidebar-width": `${width}px` } as CSSProperties}
    >
      <div className="stove-run-controls">
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
      {/* biome-ignore lint/a11y/useSemanticElements: An adjustable window splitter exposes width, unlike a thematic break. */}
      <div
        className="stove-sidebar-resizer"
        role="separator"
        tabIndex={0}
        aria-label="Test navigator width"
        aria-orientation="vertical"
        aria-valuemin={SIDEBAR_MIN_WIDTH}
        aria-valuemax={SIDEBAR_MAX_WIDTH}
        aria-valuenow={width}
        aria-valuetext={`${width} pixels`}
        onKeyDown={(event) => {
          const value =
            event.key === "ArrowLeft"
              ? width - 16
              : event.key === "ArrowRight"
                ? width + 16
                : event.key === "Home"
                  ? SIDEBAR_MIN_WIDTH
                  : event.key === "End"
                    ? SIDEBAR_MAX_WIDTH
                    : event.key === "Enter"
                      ? SIDEBAR_DEFAULT_WIDTH
                      : undefined;
          if (value === undefined) return;
          event.preventDefault();
          setSidebarWidth(value);
        }}
        onPointerDown={(event) => {
          event.preventDefault();
          draggingRef.current = true;
          event.currentTarget.setPointerCapture(event.pointerId);
          event.currentTarget.focus();
        }}
        onPointerMove={(event) => {
          if (!draggingRef.current) return;
          const left = event.currentTarget.parentElement?.getBoundingClientRect().left ?? 0;
          setSidebarWidth(event.clientX - left);
        }}
        onPointerUp={() => {
          draggingRef.current = false;
        }}
        onLostPointerCapture={() => {
          draggingRef.current = false;
        }}
      />
    </aside>
  );
}
