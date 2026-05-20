import { tryFormatJson } from "../utils/json";

interface DetailProps {
  label: string;
  value: string;
  color?: string;
}

export function Detail({ label, value, color }: DetailProps) {
  return (
    <div className="mt-2">
      <span className="text-[10px] font-semibold uppercase tracking-[0.14em] text-[var(--stove-text-muted)]">
        {label}
      </span>
      <pre
        className="mt-1 whitespace-pre-wrap break-words rounded-lg border border-stove-border bg-stove-surface p-2.5 text-xs"
        style={{ color: color ?? "var(--stove-text)" }}
      >
        {tryFormatJson(value)}
      </pre>
    </div>
  );
}
