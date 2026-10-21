import type { TopFilterValue } from "../api/types";
import { metadataLabel } from "../utils/metadata-label";
import { SelectField } from "./SelectField";

export interface TopFilterPickerProps {
  metadataKey: string | null;
  value: string | null;
  values: readonly TopFilterValue[];
  onChange: (value: string | null) => void;
}

export function TopFilterPicker({ metadataKey, value, values, onChange }: TopFilterPickerProps) {
  if (metadataKey === null) return null;
  const options = [
    { value: "all", label: "All values" },
    ...values.map((option) => ({
      value: `value:${option.value}`,
      label: `${option.value || "(empty value)"} · ${option.apps.length} ${option.apps.length === 1 ? "application" : "applications"}`,
    })),
  ];
  if (value !== null && !values.some((option) => option.value === value)) {
    options.push({
      value: `value:${value}`,
      label: `${value || "(empty value)"} · no applications`,
    });
  }
  return (
    <SelectField
      label={metadataLabel(metadataKey)}
      labelTitle={metadataKey}
      wrapperClassName="stove-top-filter"
      className="stove-focus-ring"
      value={value === null ? "all" : `value:${value}`}
      options={options}
      onChange={(value) => onChange(value === "all" ? null : value.slice(6))}
    />
  );
}
