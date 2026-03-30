import type { Run, Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface RunSummaryProps {
  run: Run;
  tests: Test[];
}

export function RunSummary({ run, tests }: RunSummaryProps) {
  const hasLiveTests = tests.length > 0;
  const total = hasLiveTests ? tests.length : run.total_tests;
  const passed = hasLiveTests
    ? tests.filter((test) => test.status === "PASSED").length
    : run.passed;
  const failed = hasLiveTests
    ? tests.filter((test) => test.status === "FAILED" || test.status === "ERROR").length
    : run.failed;
  const running = hasLiveTests ? tests.filter((test) => test.status === "RUNNING").length : 0;

  return (
    <div className="p-3 border-b border-stove-border">
      <div className="flex items-center justify-between mb-2">
        <Badge status={run.status} />
        <span className="text-xs text-[var(--stove-text-secondary)] font-mono">
          {formatDuration(run.duration_ms)}
        </span>
      </div>
      <div className="flex gap-4 text-center">
        <Stat label="Total" value={total} />
        <Stat label="Running" value={running} color="var(--stove-blue)" />
        <Stat label="Pass" value={passed} color="var(--stove-green)" />
        <Stat label="Fail" value={failed} color="var(--stove-red)" />
      </div>
    </div>
  );
}

function Stat({ label, value, color }: { label: string; value: number; color?: string }) {
  return (
    <div>
      <div className="text-lg font-mono font-semibold" style={{ color }}>
        {value}
      </div>
      <div className="text-xs text-[var(--stove-text-secondary)]">{label}</div>
    </div>
  );
}
