import { tryFormatJson } from "../utils/json";

interface DetailProps {
  label: string;
  value: string;
  color?: string;
}

export function Detail({ label, value, color }: DetailProps) {
  return (
    <div className="mt-2">
      <span className="text-[var(--stove-text-secondary)]">{label}:</span>
      <pre
        className="mt-0.5 p-2 bg-stove-base rounded text-xs whitespace-pre-wrap break-words"
        style={{ color: color ?? "var(--stove-text)" }}
      >
        {tryFormatJson(value)}
      </pre>
    </div>
  );
}
