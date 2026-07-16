export type FilterValue = "all" | "pass" | "fail";

interface TestFiltersProps {
  filter: FilterValue;
  onFilterChange: (f: FilterValue) => void;
  search: string;
  onSearchChange: (s: string) => void;
}

export function TestFilters({ filter, onFilterChange, search, onSearchChange }: TestFiltersProps) {
  return (
    <div className="stove-test-filters">
      <div className="stove-filter-tabs">
        {(["all", "pass", "fail"] as const).map((f) => (
          <button
            type="button"
            key={f}
            className={`stove-focus-ring ${filter === f ? "is-active" : ""}`}
            onClick={() => onFilterChange(f)}
          >
            {f.charAt(0).toUpperCase() + f.slice(1)}
          </button>
        ))}
      </div>
      <label className="stove-test-search">
        <svg aria-hidden="true" viewBox="0 0 16 16" fill="none" stroke="currentColor">
          <circle cx="7" cy="7" r="4.5" />
          <path d="m10.5 10.5 3 3" />
        </svg>
        <span className="sr-only">Search tests</span>
        <input
          className="stove-focus-ring"
          placeholder="Find a test"
          value={search}
          onChange={(e) => onSearchChange(e.target.value)}
        />
      </label>
    </div>
  );
}
