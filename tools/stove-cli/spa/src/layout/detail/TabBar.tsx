export type Tab = "timeline" | "trace" | "snapshots" | "flow";

interface TabDef {
  id: Tab;
  label: string;
  icon: string;
}

interface TabBarProps {
  tabs: TabDef[];
  active: Tab;
  onSelect: (tab: Tab) => void;
}

export function TabBar({ tabs, active, onSelect }: TabBarProps) {
  return (
    <div
      className="mt-4 flex gap-1 rounded-lg border border-stove-border bg-stove-base p-1"
      role="tablist"
    >
      {tabs.map((t) => (
        <button
          type="button"
          role="tab"
          key={t.id}
          aria-selected={active === t.id}
          className={`stove-focus-ring rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
            active === t.id
              ? "bg-stove-surface text-[var(--stove-text-heading)] shadow-sm"
              : "text-[var(--stove-text-secondary)] hover:bg-[var(--stove-hover)] hover:text-[var(--stove-text)]"
          }`}
          onClick={() => onSelect(t.id)}
        >
          <span className="mr-1 opacity-80">{t.icon}</span>
          {t.label}
        </button>
      ))}
    </div>
  );
}
