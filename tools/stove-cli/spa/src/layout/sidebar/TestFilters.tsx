export type FilterValue = "all" | "pass" | "fail";

interface TestFiltersProps {
  filter: FilterValue;
  onFilterChange: (f: FilterValue) => void;
  search: string;
  onSearchChange: (s: string) => void;
}

export function TestFilters({ filter, onFilterChange, search, onSearchChange }: TestFiltersProps) {
  return (
    <div className="flex gap-2 border-b border-stove-border p-2">
      <div className="flex rounded-lg border border-stove-border bg-stove-base p-0.5">
        {(["all", "pass", "fail"] as const).map((f) => (
          <button
            type="button"
            key={f}
            className={`stove-focus-ring rounded-md px-2 py-1 text-xs transition-colors ${
              filter === f
                ? "bg-stove-surface text-[var(--stove-text-heading)] shadow-sm"
                : "text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
            }`}
            onClick={() => onFilterChange(f)}
          >
            {f.charAt(0).toUpperCase() + f.slice(1)}
          </button>
        ))}
      </div>
      <input
        className="stove-focus-ring ml-auto min-w-0 flex-1 rounded-lg border border-stove-border bg-stove-card px-2 py-1 text-xs text-[var(--stove-text)] focus:border-blue-500"
        placeholder="Search..."
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
      />
    </div>
  );
}
