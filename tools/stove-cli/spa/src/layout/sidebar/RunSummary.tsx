import type { Run, Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";
import { isFailed, isPassed, isRunning } from "../../utils/status";

interface RunSummaryProps {
  run: Run;
  tests: Test[];
}

export function RunSummary({ run, tests }: RunSummaryProps) {
  const hasLiveTests = tests.length > 0;
  const total = hasLiveTests ? tests.length : run.total_tests;
  const passed = hasLiveTests ? tests.filter((t) => isPassed(t.status)).length : run.passed;
  const failed = hasLiveTests ? tests.filter((t) => isFailed(t.status)).length : run.failed;
  const running = hasLiveTests ? tests.filter((t) => isRunning(t.status)).length : 0;
  const completed = passed + failed;
  const passPercent = total === 0 ? 0 : Math.round((passed / total) * 100);

  return (
    <div className="stove-run-summary">
      <div className="stove-run-heading">
        <div>
          <span>Latest run</span>
          <code title={run.id}>{run.id.slice(0, 12)}</code>
        </div>
        <Badge status={run.status} />
      </div>
      <div className="stove-run-progress">
        <div>
          <span style={{ width: `${total === 0 ? 0 : (completed / total) * 100}%` }} />
        </div>
        <p>
          <strong>{passPercent}%</strong> pass rate
          <span>{formatDuration(run.duration_ms)}</span>
        </p>
      </div>
      <div className="stove-run-stats">
        <Stat label="Tests" value={total} />
        <Stat label="Live" value={running} color="var(--stove-blue)" />
        <Stat label="Passed" value={passed} color="var(--stove-green)" />
        <Stat label="Failed" value={failed} color="var(--stove-red)" />
      </div>
    </div>
  );
}

function Stat({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <div>
      <div style={{ color }}>{value}</div>
      <span>{label}</span>
    </div>
  );
}
