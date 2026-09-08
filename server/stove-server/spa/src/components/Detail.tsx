import { tryFormatJson } from "../utils/json";

interface DetailProps {
  label: string;
  value: string;
  color?: string;
}

export function Detail({ label, value, color }: DetailProps) {
  return (
    <div className="mt-2">
      <span className="text-xs font-medium text-[var(--stove-text-secondary)]">{label}</span>
      <pre
        className="mt-1 whitespace-pre-wrap break-words rounded-lg border border-stove-border bg-stove-surface p-2.5 text-xs"
        style={{ color: color ?? "var(--stove-text)" }}
      >
        {tryFormatJson(value)}
      </pre>
    </div>
  );
}
