import type { ComponentPropsWithoutRef } from "react";

export interface SelectOption {
  value: string;
  label: string;
}

interface SelectFieldProps
  extends Omit<
    ComponentPropsWithoutRef<"select">,
    "children" | "value" | "defaultValue" | "onChange" | "multiple"
  > {
  label: string;
  labelTitle?: string;
  wrapperClassName?: string;
  value: string;
  options: readonly SelectOption[];
  onChange: (value: string) => void;
}

/** A labelled, controlled single selection; callers own domain-specific value conversion. */
export function SelectField({
  label,
  labelTitle,
  wrapperClassName,
  value,
  options,
  onChange,
  ...props
}: SelectFieldProps) {
  return (
    <label className={wrapperClassName}>
      <span title={labelTitle}>{label}</span>
      <select {...props} value={value} onChange={(event) => onChange(event.target.value)}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}
