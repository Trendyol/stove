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
      className={`stove-test-item ${selected ? "is-selected" : ""}`}
      onClick={onSelect}
    >
      <span className={`stove-test-status is-${test.status.toLowerCase()}`} />
      <div className="stove-test-item-copy">
        {!hideSpec && <span>{test.spec_name}</span>}
        <strong>{test.test_name}</strong>
        <code>{formatDuration(test.duration_ms)}</code>
      </div>
      <Badge status={test.status} />
    </button>
  );
}
