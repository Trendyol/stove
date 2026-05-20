import type { Test } from "../../api/types";
import { Badge } from "../../components/Badge";
import { formatDuration } from "../../utils/format";

interface TestListItemProps {
  test: Test;
  selected: boolean;
  onSelect: () => void;
  hideSpec?: boolean;
}

export function TestListItem({ test, selected, onSelect, hideSpec }: TestListItemProps) {
  return (
    <button
      type="button"
      aria-current={selected ? "true" : undefined}
      className={`mx-2 my-1 w-[calc(100%-1rem)] cursor-pointer rounded-lg border px-3 py-2 text-left transition-all hover:bg-[var(--stove-hover)] ${
        selected
          ? "border-amber-500/60 bg-[var(--stove-amber-bg)] shadow-sm"
          : "border-transparent bg-transparent"
      }`}
      onClick={onSelect}
    >
      <div className="flex items-center justify-between">
        {!hideSpec && (
          <span className="text-xs text-[var(--stove-text-secondary)] truncate">
            {test.spec_name}
          </span>
        )}
        <Badge status={test.status} />
      </div>
      <div className="text-sm text-[var(--stove-text)] truncate mt-0.5">{test.test_name}</div>
      <div className="text-xs text-[var(--stove-text-muted)] mt-0.5 font-mono">
        {formatDuration(test.duration_ms)}
      </div>
    </button>
  );
}
