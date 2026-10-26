import { useId } from "react";

type Choice = Readonly<{ id: string; label: string }>;

export function ChoiceGroup({
  legend,
  choices,
  value,
  onChange,
  className = "",
}: Readonly<{
  legend: string;
  choices: readonly Choice[];
  value: string;
  onChange: (value: string) => void;
  className?: string;
}>) {
  const name = useId();
  return (
    <fieldset className={`agent-picker ${className}`}>
      <legend>{legend}</legend>
      <div className="agent-options">
        {choices.map((choice) => (
          <label key={choice.id}>
            <input
              type="radio"
              name={name}
              value={choice.id}
              checked={value === choice.id}
              onChange={() => onChange(choice.id)}
            />
            <span className="agent-option-label">{choice.label}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
