import { useId, useMemo, useState } from "react";
import type { Run } from "../../api/types";
import {
  isMetadataValueSelected,
  type MetadataFilter,
  metadataSelections,
  toggleMetadataValue,
} from "../../utils/metadata-filter";
import { metadataOptionsForRuns } from "../../utils/metadata-options";

interface MetadataFiltersProps {
  availableRuns: Run[];
  visibleRunCount: number;
  value: MetadataFilter;
  onChange: (value: MetadataFilter) => void;
}

export function MetadataFilters({
  availableRuns,
  visibleRunCount,
  value,
  onChange,
}: MetadataFiltersProps) {
  const [expanded, setExpanded] = useState(true);
  const panelId = useId();
  const options = useMemo(() => metadataOptionsForRuns(availableRuns), [availableRuns]);
  const selections = metadataSelections(value);
  const hasSelections = selections.length > 0;

  const toggle = (key: string, metadataValue: string) => {
    onChange(toggleMetadataValue(value, key, metadataValue));
  };

  if (options.length === 0 && !hasSelections) {
    return (
      <div className="stove-metadata-empty">
        <span>Run filters</span>
        <small>No metadata is available yet</small>
      </div>
    );
  }

  return (
    <section className="stove-metadata-filters" aria-label="Filter runs by metadata">
      <div className="stove-metadata-filter-heading">
        <button
          type="button"
          className="stove-metadata-filter-toggle stove-focus-ring"
          aria-controls={panelId}
          aria-expanded={expanded}
          onClick={() => setExpanded((current) => !current)}
        >
          <span className="stove-filter-glyph" aria-hidden="true">
            <i />
            <i />
            <i />
          </span>
          <span className="stove-metadata-filter-copy">
            <strong>Filter runs</strong>
            <small>
              {hasSelections
                ? `${visibleRunCount} of ${availableRuns.length} matching`
                : "Choose one or more metadata values"}
            </small>
          </span>
          {hasSelections ? (
            <span className="stove-metadata-filter-count">{selections.length}</span>
          ) : null}
          <span
            className={`stove-metadata-filter-chevron ${expanded ? "is-expanded" : ""}`}
            aria-hidden="true"
          >
            ›
          </span>
        </button>
        {hasSelections ? (
          <button
            type="button"
            className="stove-metadata-clear stove-focus-ring"
            onClick={() => onChange({})}
          >
            Clear
          </button>
        ) : null}
      </div>

      {hasSelections ? (
        <ul className="stove-metadata-active" aria-label="Active metadata filters">
          {selections.map((selection) => (
            <li key={`${selection.key}:${selection.value}`}>
              <button
                type="button"
                className="stove-metadata-active-chip stove-focus-ring"
                aria-label={`Remove ${selection.key} ${selection.value} filter`}
                onClick={() => toggle(selection.key, selection.value)}
              >
                <span>{selection.key}</span>
                <strong>{selection.value}</strong>
                <span aria-hidden="true">×</span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      <div id={panelId} className="stove-metadata-facet-panel" hidden={!expanded}>
        <p>Pick several values. Values in one field match any; separate fields must all match.</p>
        <div className="stove-metadata-facet-list">
          {options.map((option) => {
            const selectedCount = option.values.filter(({ value: metadataValue }) =>
              isMetadataValueSelected(value, option.key, metadataValue),
            ).length;

            return (
              <fieldset className="stove-metadata-facet" key={option.key}>
                <legend>
                  <span>{option.key}</span>
                  {selectedCount > 0 ? <strong>{selectedCount} selected</strong> : null}
                </legend>
                <div className="stove-metadata-values">
                  {option.values.map(({ value: metadataValue, count }) => {
                    const selected = isMetadataValueSelected(value, option.key, metadataValue);
                    return (
                      <button
                        type="button"
                        className={`stove-metadata-value stove-focus-ring ${
                          selected ? "is-selected" : ""
                        }`}
                        key={metadataValue}
                        aria-pressed={selected}
                        onClick={() => toggle(option.key, metadataValue)}
                      >
                        <span className="stove-metadata-value-label">{metadataValue}</span>
                        <span className="stove-metadata-value-count">{count}</span>
                      </button>
                    );
                  })}
                </div>
              </fieldset>
            );
          })}
        </div>
      </div>
    </section>
  );
}
