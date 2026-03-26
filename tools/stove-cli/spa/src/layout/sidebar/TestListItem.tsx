import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface TestListItemProps {
  test: Test;
  selected: boolean;
  onSelect: () => void;
}

export function TestListItem({ test, selected, onSelect }: TestListItemProps) {
  return (
    <button
      type="button"
      aria-current={selected ? "true" : undefined}
      className={`w-full text-left px-3 py-2 cursor-pointer border-l-2 hover:bg-[var(--stove-hover)] ${
        selected ? "border-l-amber-500 bg-[rgba(245,158,11,0.05)]" : "border-l-transparent"
      }`}
      onClick={onSelect}
    >
      <div className="flex items-center justify-between">
        <span className="text-xs text-[var(--stove-text-secondary)] truncate">
          {test.spec_name}
        </span>
        <Badge status={test.status} />
      </div>
      <div className="text-sm text-[var(--stove-text)] truncate mt-0.5">{test.test_name}</div>
      <div className="text-xs text-[var(--stove-text-muted)] mt-0.5 font-mono">
        {formatDuration(test.duration_ms)}
      </div>
    </button>
  );
}
