import { Icon, type IconName } from "../../components/Icon";

export type Tab = "timeline" | "mocks" | "trace" | "snapshots" | "flow";

interface TabDef {
  id: Tab;
  label: string;
  icon: IconName;
  count?: number;
  attention?: boolean;
}

interface TabBarProps {
  tabs: TabDef[];
  active: Tab;
  onSelect: (tab: Tab) => void;
}

export function TabBar({ tabs, active, onSelect }: TabBarProps) {
  return (
    <div className="stove-tab-bar" role="tablist">
      {tabs.map((t) => (
        <button
          type="button"
          role="tab"
          key={t.id}
          aria-selected={active === t.id}
          className={`stove-focus-ring ${active === t.id ? "is-active" : ""}`}
          onClick={() => onSelect(t.id)}
        >
          <Icon name={t.icon} className="h-3.5 w-3.5" />
          <span>{t.label}</span>
          {t.count != null && (
            <span className={`stove-tab-count ${t.attention ? "is-attention" : ""}`}>
              {t.count}
            </span>
          )}
        </button>
      ))}
    </div>
  );
}
