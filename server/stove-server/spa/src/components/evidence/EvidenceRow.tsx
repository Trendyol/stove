import type { CSSProperties } from "react";
import type { Entry } from "../../api/types";
import { formatTimestamp } from "../../utils/format";
import { getSystemInfo } from "../../utils/systems";
import { Icon } from "../Icon";
import { isEntryIssue } from "./model";

interface EvidenceRowProps {
  entry: Entry;
  selected: boolean;
  first: boolean;
  last: boolean;
  inspectorId?: string;
  onSelect: () => void;
}

export function EvidenceRow({
  entry,
  selected,
  first,
  last,
  onSelect,
  inspectorId,
}: EvidenceRowProps) {
  const system = getSystemInfo(entry.system);
  const issue = isEntryIssue(entry);

  return (
    <button
      type="button"
      className={`evidence-ledger-row ${selected ? "is-selected" : ""} ${
        issue ? "is-issue" : "is-success"
      }`}
      data-entry-id={entry.id}
      data-first={first}
      data-last={last}
      aria-controls={inspectorId}
      aria-pressed={selected}
      onClick={onSelect}
    >
      <span className="ledger-rail-point" aria-hidden="true">
        {issue ? "!" : ""}
      </span>
      <time>{formatTimestamp(entry.timestamp)}</time>
      <span
        className="ledger-system-glyph"
        style={{ "--system-color": system.color } as CSSProperties}
      >
        {system.icon}
      </span>
      <span className="ledger-primary">
        <strong>{entry.action}</strong>
        <span>{entry.system}</span>
      </span>
      <span className="ledger-row-tail">
        {entry.attempt_count > 1 && (
          <span className="ledger-attempt-stamp">
            {entry.attempt_count} attempts · {entry.failure_count} failed
          </span>
        )}
        <span className={issue ? "ledger-result is-issue" : "sr-only"}>{entry.result}</span>
        <Icon name="chevron" className="h-4 w-4" />
      </span>
    </button>
  );
}
