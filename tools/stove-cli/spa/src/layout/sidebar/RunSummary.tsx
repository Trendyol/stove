import type { Run } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface RunSummaryProps {
  run: Run;
}

export function RunSummary({ run }: RunSummaryProps) {
  return (
    <div className="p-3 border-b border-stove-border">
      <div className="flex items-center justify-between mb-2">
        <Badge status={run.status} />
        <span className="text-xs text-[var(--stove-text-secondary)] font-mono">
          {formatDuration(run.duration_ms)}
        </span>
      </div>
      <div className="flex gap-4 text-center">
        <Stat label="Total" value={run.total_tests} />
        <Stat label="Pass" value={run.passed} color="var(--stove-green)" />
        <Stat label="Fail" value={run.failed} color="var(--stove-red)" />
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
