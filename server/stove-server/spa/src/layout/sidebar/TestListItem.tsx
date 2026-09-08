import type { Test } from "../../api/types";
import { ResultIcon } from "../../components/ResultIcon";
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
      title={test.test_name}
      className={`stove-test-item ${selected ? "is-selected" : ""}`}
      onClick={onSelect}
    >
      <span aria-hidden="true">
        <ResultIcon result={test.status} />
      </span>
      <div className="stove-test-item-copy">
        {!hideSpec && <span>{test.spec_name}</span>}
        <strong>{test.test_name}</strong>
        <small>
          {test.status.charAt(0) + test.status.slice(1).toLowerCase()} ·{" "}
          {formatDuration(test.duration_ms)}
        </small>
      </div>
    </button>
  );
}
