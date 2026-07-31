import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface TestHeaderProps {
  test: Test;
  liveConnected: boolean;
}

export function TestHeader({ test, liveConnected }: TestHeaderProps) {
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
    </div>
  );
}
