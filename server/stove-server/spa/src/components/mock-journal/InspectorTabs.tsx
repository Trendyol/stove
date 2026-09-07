export type InspectorTab = "overview" | "request" | "response" | "diagnostics";

export function InspectorTabs({
  active,
  diagnosticCount,
  onSelect,
}: {
  active: InspectorTab;
  diagnosticCount: number;
  onSelect: (tab: InspectorTab) => void;
}) {
  const tabs: readonly InspectorTab[] = ["overview", "request", "response", "diagnostics"];
  return (
    <nav className="inspector-tabs" aria-label="Exchange detail sections">
      {tabs.map((tab) => (
        <button
          type="button"
          key={tab}
          className={active === tab ? "is-active" : ""}
          onClick={() => onSelect(tab)}
        >
          {tab}
          {tab === "diagnostics" && diagnosticCount > 0 && <span>{diagnosticCount}</span>}
        </button>
      ))}
    </nav>
  );
}
