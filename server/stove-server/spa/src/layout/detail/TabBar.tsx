import { Icon, type IconName } from "../../components/Icon";

export type Tab = "timeline" | "mocks" | "trace" | "snapshots" | "flow";

export interface TabDef {
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
    <div className="stove-tab-bar" role="tablist" aria-label="Test evidence">
      {tabs.map((t) => (
        <button
          type="button"
          role="tab"
          key={t.id}
          id={`tab-${t.id}`}
          aria-controls={`panel-${t.id}`}
          tabIndex={active === t.id ? 0 : -1}
          onKeyDown={(event) => {
            const index = tabs.findIndex((tab) => tab.id === t.id);
            const next =
              event.key === "ArrowRight"
                ? (index + 1) % tabs.length
                : event.key === "ArrowLeft"
                  ? (index - 1 + tabs.length) % tabs.length
                  : event.key === "Home"
                    ? 0
                    : event.key === "End"
                      ? tabs.length - 1
                      : undefined;
            if (next === undefined) return;
            event.preventDefault();
            onSelect(tabs[next].id);
            document.getElementById(`tab-${tabs[next].id}`)?.focus();
          }}
          aria-selected={active === t.id}
          className={`stove-focus-ring ${active === t.id ? "is-active" : ""}`}
          onClick={() => onSelect(t.id)}
        >
          <Icon name={t.icon} className="h-3.5 w-3.5" />
          <span>{t.label}</span>
          {t.count !== undefined && (
            <span className={`stove-tab-count ${t.attention ? "is-attention" : ""}`}>
              {t.count}
            </span>
          )}
        </button>
      ))}
    </div>
  );
}
