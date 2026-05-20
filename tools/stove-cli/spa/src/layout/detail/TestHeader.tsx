import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface TestHeaderProps {
  test: Test;
}

export function TestHeader({ test }: TestHeaderProps) {
  return (
    <div className="flex items-start gap-3">
      <div className="flex-1 min-w-0">
        <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-[var(--stove-text-muted)]">
          {test.spec_name}
        </div>
        <div className="mt-1 truncate text-base font-semibold text-[var(--stove-text-heading)]">
          {test.test_name}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-2">
        <Badge status={test.status} />
        <span className="rounded-full border border-stove-border bg-stove-base px-2.5 py-1 font-mono text-xs text-[var(--stove-text-secondary)]">
          {formatDuration(test.duration_ms)}
        </span>
      </div>
    </div>
  );
}
