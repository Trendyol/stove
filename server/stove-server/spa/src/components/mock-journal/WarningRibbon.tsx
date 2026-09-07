import type { MockWarning } from "../../api/types";
import { Icon } from "../Icon";
import { humanize } from "./model";

interface WarningRibbonProps {
  warnings: readonly MockWarning[];
  expanded: boolean;
  selectedWarningId: MockWarning["id"] | undefined;
  onExpandedChange: (expanded: boolean) => void;
  onSelect: (warning: MockWarning) => void;
}

export function WarningRibbon({
  warnings,
  expanded,
  selectedWarningId,
  onExpandedChange,
  onSelect,
}: WarningRibbonProps) {
  if (warnings.length === 0) return null;

  return (
    <section className="warning-ribbon" aria-label="Mock warnings">
      <header className="warning-ribbon-header">
        <div className="warning-ribbon-title">
          <span className="warning-ribbon-icon">
            <Icon name="warning" className="h-4 w-4" />
          </span>
          <span className="warning-ribbon-copy">
            <strong>Mock warnings</strong>
            <small>Select a warning to inspect its related exchange</small>
          </span>
          <em>{warnings.length}</em>
        </div>
        <button
          type="button"
          className="warning-ribbon-toggle"
          aria-controls="mock-warning-list"
          aria-expanded={expanded}
          onClick={() => onExpandedChange(!expanded)}
        >
          {expanded ? "Collapse" : "Review"}
          <Icon name="chevron" className="h-3.5 w-3.5" />
        </button>
      </header>
      {expanded && (
        <div id="mock-warning-list" className="warning-ribbon-list">
          {warnings.map((warning) => (
            <button
              type="button"
              key={warning.id}
              className={warning.id === selectedWarningId ? "is-selected" : ""}
              aria-pressed={warning.id === selectedWarningId}
              onClick={() => onSelect(warning)}
            >
              <span className="warning-ribbon-item-heading">
                <span>{humanize(warning.kind)}</span>
                <strong>{warning.target ?? warning.system}</strong>
              </span>
              <p>{warning.message}</p>
              <Icon name="chevron" className="h-3.5 w-3.5" />
            </button>
          ))}
        </div>
      )}
    </section>
  );
}
