import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api/client";
import type { AppSummary, Run, Test } from "../api/types";
import { AppPicker } from "./sidebar/AppPicker";
import { RunSummary } from "./sidebar/RunSummary";
import type { FilterValue } from "./sidebar/TestFilters";
import { TestFilters } from "./sidebar/TestFilters";
import { TestListItem } from "./sidebar/TestListItem";

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

  const handleClear = async () => {
    if (!confirm("Clear all stored data? This cannot be undone.")) return;
    setClearing(true);
    try {
      await api.clearAll();
      queryClient.invalidateQueries();
    } finally {
      setClearing(false);
    }
  };

  const filteredTests = tests.filter((t) => {
    if (filter === "pass" && t.status !== "PASSED") return false;
    if (filter === "fail" && t.status !== "FAILED" && t.status !== "ERROR") return false;
    if (search && !t.test_name.toLowerCase().includes(search.toLowerCase())) return false;
    return true;
  });

  return (
    <aside className="w-80 shrink-0 border-r border-stove-border bg-stove-surface flex flex-col overflow-hidden">
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
      <div className="flex-1 overflow-y-auto">
        {filteredTests.map((test) => (
          <TestListItem
            key={test.id}
            test={test}
            selected={selectedTestId === test.id}
            onSelect={() => onSelectTest(test.id)}
          />
        ))}
      </div>
      <div className="border-t border-stove-border px-3 py-2">
        <button
          type="button"
          onClick={handleClear}
          disabled={clearing}
          className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 text-xs text-[var(--stove-text-secondary)] hover:text-red-400 hover:bg-red-400/10 rounded transition-colors cursor-pointer disabled:opacity-50 bg-transparent border border-stove-border"
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
    </aside>
  );
}
