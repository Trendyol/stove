import { ErrorNotice } from "../../components/ErrorNotice";
import { SelectField } from "../../components/SelectField";
import { metadataLabel } from "../../utils/metadata-label";
import { useTopFilterSettings } from "./useTopFilterSettings";

export function TopFilterCard() {
  const filter = useTopFilterSettings();
  const options = [
    { value: "", label: "Disabled" },
    ...filter.keys.map((key) => ({ value: key, label: key })),
  ];
  if (filter.savedKey && !filter.keys.includes(filter.savedKey)) {
    options.push({ value: filter.savedKey, label: `${filter.savedKey} (no recorded values)` });
  }
  return (
    <section className="stove-admin-card">
      <h3>Browse by</h3>
      <p>
        Choose a metadata field for the dashboard. Users select a value to focus applications and
        their runs.
      </p>
      <SelectField
        label="Metadata field"
        wrapperClassName="stove-admin-field"
        value={filter.value}
        disabled={filter.disabled}
        options={options}
        onChange={filter.select}
      />
      {filter.value && (
        <p>
          Shown as <strong>{metadataLabel(filter.value)}</strong> above the Application picker.
        </p>
      )}
      {filter.loading && <p role="status">Loading metadata fields…</p>}
      {!filter.disabled && filter.keys.length === 0 && (
        <p>Metadata fields appear as runs are recorded.</p>
      )}
      {filter.error && <ErrorNotice onRetry={filter.retry}>{filter.error.message}</ErrorNotice>}
      <button
        type="button"
        disabled={filter.disabled || filter.value === filter.savedKey}
        onClick={filter.save}
      >
        {filter.saving ? "Saving…" : "Save browsing field"}
      </button>
      {filter.saved && <p role="status">Browsing field saved for everyone.</p>}
    </section>
  );
}
