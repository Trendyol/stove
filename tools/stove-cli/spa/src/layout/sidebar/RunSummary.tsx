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

  return (
    <div className="border-b border-stove-border p-3">
      <div className="flex items-center justify-between mb-2">
        <Badge status={run.status} />
        <span className="text-xs text-[var(--stove-text-secondary)] font-mono">
          {formatDuration(run.duration_ms)}
        </span>
      </div>
      <div className="grid grid-cols-4 gap-1.5 text-center">
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
    <div className="rounded-lg border border-stove-border bg-stove-base px-1.5 py-1.5">
      <div className="font-mono text-base font-semibold leading-none" style={{ color }}>
        {value}
      </div>
      <div className="mt-1 text-[10px] text-[var(--stove-text-secondary)]">{label}</div>
    </div>
  );
}
