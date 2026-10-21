import type { KeyboardEvent } from "react";
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
  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let nextIndex: number;
    switch (event.key) {
      case "ArrowRight":
        nextIndex = (index + 1) % tabs.length;
        break;
      case "ArrowLeft":
        nextIndex = (index - 1 + tabs.length) % tabs.length;
        break;
      case "Home":
        nextIndex = 0;
        break;
      case "End":
        nextIndex = tabs.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    const nextTab = tabs[nextIndex];
    onSelect(nextTab.id);
    document.getElementById(`tab-${nextTab.id}`)?.focus();
  }

  return (
    <div className="stove-tab-bar" role="tablist" aria-label="Test evidence">
      {tabs.map((tab, index) => (
        <button
          type="button"
          role="tab"
          key={tab.id}
          id={`tab-${tab.id}`}
          aria-controls={`panel-${tab.id}`}
          tabIndex={active === tab.id ? 0 : -1}
          onKeyDown={(event) => handleKeyDown(event, index)}
          aria-selected={active === tab.id}
          className={`stove-focus-ring ${active === tab.id ? "is-active" : ""}`}
          onClick={() => onSelect(tab.id)}
        >
          <Icon name={tab.icon} className="h-3.5 w-3.5" />
          <span>{tab.label}</span>
          {tab.count !== undefined && (
            <span className={`stove-tab-count ${tab.attention ? "is-attention" : ""}`}>
              {tab.count}
            </span>
          )}
        </button>
      ))}
    </div>
  );
}
