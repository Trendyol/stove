import { useEffect, useMemo, useRef, useState } from "react";
import type { Run } from "../../api/types";
import { formatTimestamp } from "../../utils/format";
import { metadataOptionsForRuns } from "../../utils/metadata-options";

interface FilterRow {
  id: number;
  key: string;
  value: string;
}

interface RunPickerProps {
  runs: Run[];
  availableRuns: Run[];
  selectedRunId: string | undefined;
  onSelectRun: (runId: string) => void;
  metadataFilter: Record<string, string>;
  onMetadataFilterChange: (metadata: Record<string, string>) => void;
}

export function RunPicker({
  runs,
  availableRuns,
  selectedRunId,
  onSelectRun,
  metadataFilter,
  onMetadataFilterChange,
}: RunPickerProps) {
  return (
    <section className="stove-run-picker" aria-label="Run selection">
      <RunSelect runs={runs} selectedRunId={selectedRunId} onSelectRun={onSelectRun} />
      <MetadataFilters
        availableRuns={availableRuns}
        metadataFilter={metadataFilter}
        onChange={onMetadataFilterChange}
      />
    </section>
  );
}

function RunSelect({
  runs,
  selectedRunId,
  onSelectRun,
}: Pick<RunPickerProps, "runs" | "selectedRunId" | "onSelectRun">) {
  return (
    <>
      <div className="stove-sidebar-section-label">
        <span>Runs</span>
        <span>{runs.length}</span>
      </div>
      <select
        aria-label="Run"
        className="stove-focus-ring"
        disabled={runs.length === 0}
        value={selectedRunId ?? ""}
        onChange={(event) => onSelectRun(event.target.value)}
      >
        {runs.length === 0 ? <option value="">No matching runs</option> : null}
        {runs.map((run) => (
          <option key={run.id} value={run.id}>
            {formatTimestamp(run.started_at)} · {run.status} · {run.id}
          </option>
        ))}
      </select>
    </>
  );
}

interface MetadataFiltersProps {
  availableRuns: Run[];
  metadataFilter: Record<string, string>;
  onChange: (metadata: Record<string, string>) => void;
}

function MetadataFilters({ availableRuns, metadataFilter, onChange }: MetadataFiltersProps) {
  const nextId = useRef(Math.max(1, Object.keys(metadataFilter).length));
  const [rows, setRows] = useState<FilterRow[]>(() => rowsFromFilter(metadataFilter));
  const metadataOptions = useMemo(() => metadataOptionsForRuns(availableRuns), [availableRuns]);
  const availableKeys = useMemo(
    () => metadataOptions.map((option) => option.key),
    [metadataOptions],
  );
  const selectedKeys = useMemo(() => new Set(rows.map((row) => row.key).filter(Boolean)), [rows]);
  const canAddFilter =
    rows.every((row) => row.key && row.value) &&
    availableKeys.some((key) => !selectedKeys.has(key));

  useEffect(() => {
    const nextRows = rowsFromFilter(metadataFilter);
    setRows(nextRows);
    nextId.current = Math.max(1, nextRows.length);
  }, [metadataFilter]);

  const updateRow = (id: number, field: "key" | "value", value: string) => {
    setRows((current) =>
      current.map((row) => (row.id === id ? updatedRow(row, field, value) : row)),
    );
  };

  const addRow = () => {
    setRows((current) => [...current, { id: nextId.current, key: "", value: "" }]);
    nextId.current += 1;
  };

  const clearFilter = () => {
    setRows([{ id: 0, key: "", value: "" }]);
    onChange({});
  };

  return (
    <>
      <div className="stove-metadata-filter-heading">
        <span>Metadata filters</span>
        <button type="button" disabled={!canAddFilter} onClick={addRow}>
          + Add
        </button>
      </div>
      {rows.map((row) => (
        <MetadataFilterRow
          key={row.id}
          row={row}
          availableKeys={availableKeys}
          availableValues={metadataOptions.find((option) => option.key === row.key)?.values ?? []}
          selectedKeys={selectedKeys}
          onUpdate={updateRow}
          onRemove={(id) =>
            setRows((current) => current.filter((candidate) => candidate.id !== id))
          }
        />
      ))}
      <div className="stove-metadata-filter-actions">
        <button type="button" onClick={() => onChange(filterFromRows(rows))}>
          Apply
        </button>
        {Object.keys(metadataFilter).length > 0 ? (
          <button type="button" onClick={clearFilter}>
            Clear
          </button>
        ) : null}
      </div>
    </>
  );
}

interface MetadataFilterRowProps {
  row: FilterRow;
  availableKeys: string[];
  availableValues: string[];
  selectedKeys: Set<string>;
  onUpdate: (id: number, field: "key" | "value", value: string) => void;
  onRemove: (id: number) => void;
}

function MetadataFilterRow({
  row,
  availableKeys,
  availableValues,
  selectedKeys,
  onUpdate,
  onRemove,
}: MetadataFilterRowProps) {
  return (
    <div className="stove-metadata-filter-row">
      <select
        aria-label="Metadata key"
        value={row.key}
        onChange={(event) => onUpdate(row.id, "key", event.target.value)}
      >
        <option value="">Select key</option>
        {withCurrentValue(availableKeys, row.key).map((key) => (
          <option key={key} value={key} disabled={key !== row.key && selectedKeys.has(key)}>
            {key}
          </option>
        ))}
      </select>
      <select
        aria-label="Metadata value"
        disabled={!row.key}
        value={row.value}
        onChange={(event) => onUpdate(row.id, "value", event.target.value)}
      >
        <option value="">Select value</option>
        {withCurrentValue(availableValues, row.value).map((value) => (
          <option key={value} value={value}>
            {value}
          </option>
        ))}
      </select>
      <button type="button" aria-label="Remove metadata filter" onClick={() => onRemove(row.id)}>
        ×
      </button>
    </div>
  );
}

function rowsFromFilter(metadata: Record<string, string>): FilterRow[] {
  const rows = Object.entries(metadata).map(([key, value], id) => ({ id, key, value }));
  return rows.length > 0 ? rows : [{ id: 0, key: "", value: "" }];
}

function updatedRow(row: FilterRow, field: "key" | "value", value: string): FilterRow {
  return field === "key" ? { ...row, key: value, value: "" } : { ...row, value };
}

function filterFromRows(rows: FilterRow[]): Record<string, string> {
  return Object.fromEntries(
    rows
      .map(({ key, value }) => [key.trim(), value] as const)
      .filter(([key, value]) => key.length > 0 && value.length > 0),
  );
}

function withCurrentValue(values: string[], current: string): string[] {
  if (!current || values.includes(current)) return values;
  return [...values, current].sort((left, right) => left.localeCompare(right));
}
