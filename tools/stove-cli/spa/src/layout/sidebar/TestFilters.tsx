export type FilterValue = "all" | "pass" | "fail";

interface TestFiltersProps {
  filter: FilterValue;
  onFilterChange: (f: FilterValue) => void;
  search: string;
  onSearchChange: (s: string) => void;
}

export function TestFilters({ filter, onFilterChange, search, onSearchChange }: TestFiltersProps) {
  return (
    <div className="p-2 border-b border-stove-border flex gap-1">
      {(["all", "pass", "fail"] as const).map((f) => (
        <button
          type="button"
          key={f}
          className={`px-2 py-1 text-xs rounded ${
            filter === f
              ? "bg-stove-card text-[var(--stove-text-heading)]"
              : "text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
          }`}
          onClick={() => onFilterChange(f)}
        >
          {f.charAt(0).toUpperCase() + f.slice(1)}
        </button>
      ))}
      <input
        className="ml-auto bg-stove-card border border-stove-border rounded px-2 py-1 text-xs text-[var(--stove-text)] w-24 focus:outline-none focus:border-blue-500"
        placeholder="Search..."
        value={search}
        onChange={(e) => onSearchChange(e.target.value)}
      />
    </div>
  );
}
