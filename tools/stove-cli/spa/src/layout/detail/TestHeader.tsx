import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { Icon } from "../../components/Icon";
import { formatDuration } from "../../utils/format";
import type { Tab } from "./TabBar";

interface TestHeaderProps {
  test: Test;
  liveConnected: boolean;
  metrics: {
    entries: number;
    interactions: number;
    warnings: number;
    snapshots: number;
  };
  onSelectTab: (tab: Tab) => void;
}

export function TestHeader({ test, liveConnected, metrics, onSelectTab }: TestHeaderProps) {
  const path = test.test_path.filter((segment) => segment !== test.test_name);

  return (
    <div className="test-dossier">
      <div className={`test-dossier-beam is-${test.status.toLowerCase()}`} />
      <div className="test-dossier-main">
        <div className="test-breadcrumbs">
          <span>{test.spec_name}</span>
          {path.map((segment) => (
            <span key={segment}>{segment}</span>
          ))}
        </div>
        <div className="test-title-row">
          <h1>{test.test_name}</h1>
          <Badge status={test.status} />
        </div>
        <div className="test-dossier-meta">
          <span>
            <strong>{formatDuration(test.duration_ms)}</strong> elapsed
          </span>
          <span className={liveConnected ? "is-live" : ""}>
            <i />
            {liveConnected ? "live evidence" : "polling evidence"}
          </span>
          <code title={test.id}>{test.id}</code>
        </div>
      </div>
      <div className="test-signal-grid">
        <SignalButton
          icon="activity"
          label="Evidence"
          value={metrics.entries}
          onClick={() => onSelectTab("timeline")}
        />
        <SignalButton
          icon="mock"
          label="Mocks"
          value={metrics.interactions}
          onClick={() => onSelectTab("mocks")}
        />
        <SignalButton
          icon="warning"
          label="Warnings"
          value={metrics.warnings}
          attention={metrics.warnings > 0}
          onClick={() => onSelectTab("mocks")}
        />
        <SignalButton
          icon="snapshot"
          label="State"
          value={metrics.snapshots}
          onClick={() => onSelectTab("snapshots")}
        />
      </div>
    </div>
  );
}

function SignalButton({
  icon,
  label,
  value,
  attention = false,
  onClick,
}: {
  icon: "activity" | "mock" | "warning" | "snapshot";
  label: string;
  value: number;
  attention?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`test-signal ${attention ? "is-attention" : ""}`}
      onClick={onClick}
    >
      <Icon name={icon} className="h-3.5 w-3.5" />
      <span>{label}</span>
      <strong>{value}</strong>
    </button>
  );
}
