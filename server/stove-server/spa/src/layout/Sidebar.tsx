import { type CSSProperties, useCallback, useEffect, useRef, useState } from "react";
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
const SIDEBAR_DEFAULT_WIDTH = 344;
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

  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    document.body.style.cursor = "col-resize";
    document.body.style.userSelect = "none";
  }, []);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!draggingRef.current) return;
      const clamped = Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, e.clientX));
      setWidth(clamped);
    };

    const handleMouseUp = () => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      document.body.style.cursor = "";
      document.body.style.userSelect = "";
      setWidth((w) => {
        localStorage.setItem(SIDEBAR_STORAGE_KEY, String(w));
        return w;
      });
    };

    document.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseup", handleMouseUp);
    return () => {
      document.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseup", handleMouseUp);
    };
  }, []);

  return (
    <aside
      className="stove-sidebar stove-glass-panel"
      style={{ "--sidebar-width": `${width}px` } as CSSProperties}
    >
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
      <TestFilters
        filter={filter}
        onFilterChange={setFilter}
        search={search}
        onSearchChange={setSearch}
      />
      <div className="stove-test-tree-panel">
        <div className="stove-sidebar-section-label">
          <span>Run navigator</span>
          <span>{filteredTests.length}</span>
        </div>
        <TestTree
          tests={filteredTests}
          selectedTestId={selectedTestId}
          onSelectTest={onSelectTest}
        />
      </div>
      {/* biome-ignore lint/a11y/noStaticElementInteractions: resize drag handle */}
      <div className="stove-sidebar-resizer" onMouseDown={handleMouseDown} />
    </aside>
  );
}
