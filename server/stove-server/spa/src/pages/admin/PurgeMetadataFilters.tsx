import { useMemo, useState } from "react";
import type { Run } from "../../api/types";
import {
  isMetadataValueSelected,
  type MetadataFilter,
  toggleMetadataValue,
} from "../../utils/metadata-filter";
import { metadataOptionsForRuns } from "../../utils/metadata-options";

interface PurgeMetadataFiltersProps {
  appName: string;
  runs: Run[];
  value: MetadataFilter;
  loading: boolean;
  error: string | null;
  onChange: (value: MetadataFilter) => void;
  onRetry: () => void;
}

export function PurgeMetadataFilters({
  appName,
  runs,
  value,
  loading,
  error,
  onChange,
  onRetry,
}: PurgeMetadataFiltersProps) {
  const options = useMemo(() => metadataOptionsForRuns(runs), [runs]);
  const hasSelections = Object.keys(value).length > 0;

  if (!appName) return null;

  return (
    <section className="stove-purge-metadata" aria-label="Purge metadata filters">
      <div className="stove-purge-metadata-heading">
        <h4>Metadata</h4>
        {hasSelections && (
          <button type="button" aria-label="Clear metadata filters" onClick={() => onChange({})}>
            Clear filters
          </button>
        )}
      </div>
      {loading ? (
        <p role="status">Loading metadata…</p>
      ) : error ? (
        <div role="alert">
          <p>Could not load metadata for this application.</p>
          <button type="button" onClick={onRetry}>
            Try again
          </button>
        </div>
      ) : (
        <>
          <p>
            {options.length === 0
              ? "This application has no recorded run metadata."
              : "Choose any value within a field. Each selected field must match."}
          </p>
          <div className="stove-purge-metadata-fields">
            {options.map((option) => (
              <fieldset key={option.key}>
                <legend>{option.key}</legend>
                {option.values.length > 5 ? (
                  <MetadataDropdown
                    key={appName}
                    field={option.key}
                    options={option.values}
                    selected={value[option.key] ?? []}
                    onToggle={(metadataValue) =>
                      onChange(toggleMetadataValue(value, option.key, metadataValue))
                    }
                  />
                ) : (
                  <div className="stove-purge-metadata-values">
                    {option.values.map(({ value: metadataValue, count }) => (
                      <button
                        type="button"
                        key={metadataValue}
                        aria-label={`${option.key}: ${metadataValue}`}
                        aria-pressed={isMetadataValueSelected(value, option.key, metadataValue)}
                        onClick={() =>
                          onChange(toggleMetadataValue(value, option.key, metadataValue))
                        }
                      >
                        <span>{metadataValue || "(empty value)"}</span>
                        <small>{count}</small>
                      </button>
                    ))}
                  </div>
                )}
              </fieldset>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

interface MetadataDropdownProps {
  field: string;
  options: { value: string; count: number }[];
  selected: readonly string[];
  onToggle: (value: string) => void;
}

function MetadataDropdown({ field, options, selected, onToggle }: MetadataDropdownProps) {
  const [search, setSearch] = useState("");
  const matches = options.filter(({ value }) =>
    value.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
  );
  const selectionLabel =
    selected.length === 0
      ? "Any value"
      : selected.length === 1
        ? selected[0] || "(empty value)"
        : `${selected.length} values selected`;

  return (
    <details className="stove-purge-metadata-dropdown">
      <summary aria-label={`${field}: ${selectionLabel}`}>
        <span>{selectionLabel}</span>
      </summary>
      <div className="stove-purge-metadata-menu">
        <input
          type="search"
          aria-label={`Find ${field} values`}
          placeholder="Find a value…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <div className="stove-purge-metadata-options">
          {matches.map(({ value, count }) => (
            <label key={value}>
              <input
                type="checkbox"
                aria-label={`${field}: ${value}`}
                checked={selected.includes(value)}
                onChange={() => onToggle(value)}
              />
              <span>{value || "(empty value)"}</span>
              <small>{count}</small>
            </label>
          ))}
          {matches.length === 0 && <p role="status">No values found.</p>}
        </div>
      </div>
    </details>
  );
}
