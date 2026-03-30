export type Tab = "timeline" | "trace" | "snapshots" | "kafka" | "flow";

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
    <div className="flex gap-1 mt-3" role="tablist">
      {tabs.map((t) => (
        <button
          type="button"
          role="tab"
          key={t.id}
          aria-selected={active === t.id}
          className={`px-3 py-1.5 text-xs rounded-t ${
            active === t.id
              ? "bg-stove-card text-[var(--stove-text-heading)] border-b-2 border-amber-500"
              : "text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
          }`}
          onClick={() => onSelect(t.id)}
        >
          {t.icon} {t.label}
        </button>
      ))}
    </div>
  );
}
