import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface TestHeaderProps {
  test: Test;
}

export function TestHeader({ test }: TestHeaderProps) {
  return (
    <div className="flex items-center gap-3">
      <div className="flex-1 min-w-0">
        <div className="text-xs text-[var(--stove-text-secondary)]">{test.spec_name}</div>
        <div className="text-sm text-[var(--stove-text-heading)] font-medium truncate">
          {test.test_name}
        </div>
      </div>
      <Badge status={test.status} />
      <span className="text-xs text-[var(--stove-text-secondary)] font-mono">
        {formatDuration(test.duration_ms)}
      </span>
    </div>
  );
}
