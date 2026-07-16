import { useQueryClient } from "@tanstack/react-query";
import { type CSSProperties, useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { AppSummary, Run, Test } from "../api/types";
import { filterTests } from "../utils/filters";
import { AppPicker } from "./sidebar/AppPicker";
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
  selectedApp: string | null;
  onSelectApp: (name: string) => void;
  run: Run | null;
  tests: Test[];
  selectedTestId: string | null;
  onSelectTest: (testId: string) => void;
}

export function Sidebar({
  apps,
  mismatchedApps,
  selectedApp,
  onSelectApp,
  run,
  tests,
  selectedTestId,
  onSelectTest,
}: SidebarProps) {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<FilterValue>("all");
  const [search, setSearch] = useState("");
  const [clearing, setClearing] = useState(false);
  const [width, setWidth] = useState(loadSidebarWidth);
  const draggingRef = useRef(false);

  const handleClear = async () => {
    if (!confirm("Clear all stored data? This cannot be undone.")) return;
    setClearing(true);
    try {
      await api.clearAll();
      await queryClient.resetQueries();
    } finally {
      setClearing(false);
    }
  };

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
      {run && <RunSummary run={run} tests={tests} />}
      <TestFilters
        filter={filter}
        onFilterChange={setFilter}
        search={search}
        onSearchChange={setSearch}
      />
      <div className="stove-test-tree-scroll">
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
      <div className="stove-sidebar-footer">
        <button
          type="button"
          onClick={handleClear}
          disabled={clearing}
          className="stove-clear-button stove-focus-ring"
        >
          <svg aria-hidden="true" className="w-3 h-3" viewBox="0 0 16 16" fill="currentColor">
            <path d="M5.5 5.5A.5.5 0 016 6v6a.5.5 0 01-1 0V6a.5.5 0 01.5-.5zm2.5 0a.5.5 0 01.5.5v6a.5.5 0 01-1 0V6a.5.5 0 01.5-.5zm3 .5a.5.5 0 00-1 0v6a.5.5 0 001 0V6z" />
            <path
              fillRule="evenodd"
              d="M14.5 3a1 1 0 01-1 1H13v9a2 2 0 01-2 2H5a2 2 0 01-2-2V4h-.5a1 1 0 010-2H6a1 1 0 011-1h2a1 1 0 011 1h3.5a1 1 0 011 1zM4.118 4L4 4.059V13a1 1 0 001 1h6a1 1 0 001-1V4.059L11.882 4H4.118zM7 1.5a.5.5 0 00-.5.5h3a.5.5 0 00-.5-.5H7z"
            />
          </svg>
          {clearing ? "Clearing..." : "Clear data"}
        </button>
      </div>
      {/* biome-ignore lint/a11y/noStaticElementInteractions: resize drag handle */}
      <div className="stove-sidebar-resizer" onMouseDown={handleMouseDown} />
    </aside>
  );
}
