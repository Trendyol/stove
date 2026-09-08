import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";
import { isRunning } from "../../utils/status";

interface TestHeaderProps {
  test: Test;
  liveConnected: boolean;
}

export function TestHeader({ test, liveConnected }: TestHeaderProps) {
  const path = test.test_path.filter((segment) => segment !== test.test_name);

  return (
    <div className="test-dossier">
      <div className="test-dossier-main">
        <div className="test-breadcrumbs">
          <span>{test.spec_name}</span>
          {path.map((segment) => (
            <span key={segment}>{segment}</span>
          ))}
        </div>
        <div className="test-title-row">
          <h1 tabIndex={-1}>{test.test_name}</h1>
          <Badge status={test.status} />
        </div>
        <div className="test-dossier-meta">
          <span>
            <strong>{formatDuration(test.duration_ms)}</strong> elapsed
          </span>
          <span className={isRunning(test.status) ? "is-live" : ""}>
            {isRunning(test.status) && <i />}
            {isRunning(test.status)
              ? liveConnected
                ? "Receiving evidence"
                : "Checking for updates"
              : "Test complete"}
          </span>
          <code title={test.id}>{test.id}</code>
        </div>
      </div>
    </div>
  );
}
