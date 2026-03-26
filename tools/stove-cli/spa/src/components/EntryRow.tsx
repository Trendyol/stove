import { useState } from "react";
import type { Entry } from "../api/types";
import { formatTimestamp } from "../utils/format";
import { tryFormatJson } from "../utils/json";
import { SysBadge } from "./SysBadge";

interface EntryRowProps {
  entry: Entry;
}

export function EntryRow({ entry }: EntryRowProps) {
  const [expanded, setExpanded] = useState(false);
  const passed = entry.result === "PASSED";
  const borderColor = passed ? "var(--stove-green)" : "var(--stove-red)";

  return (
    <button
      type="button"
      className="animate-fade-in border-l-2 bg-stove-card rounded-r mb-1 cursor-pointer hover:bg-[var(--stove-hover)] w-full text-left"
      style={{ borderLeftColor: borderColor }}
      aria-expanded={expanded}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2 px-3 py-2 text-sm">
        <span className="text-[var(--stove-text-secondary)] font-mono text-xs w-24 shrink-0">
          {formatTimestamp(entry.timestamp)}
        </span>
        <span style={{ color: passed ? "var(--stove-green)" : "var(--stove-red)" }}>
          {passed ? "\u2713" : "\u2717"}
        </span>
        <SysBadge system={entry.system} />
        <span className="text-[var(--stove-text)] truncate">{entry.action}</span>
      </div>

      {expanded && (
        <div className="px-4 pb-3 text-xs font-mono space-y-2 border-t border-stove-border">
          {entry.input && <Detail label="Input" value={entry.input} />}
          {entry.output && (
            <Detail label="Output" value={entry.output} color="var(--stove-green)" />
          )}
          {entry.expected && <Detail label="Expected" value={entry.expected} />}
          {entry.actual && <Detail label="Actual" value={entry.actual} />}
          {entry.error && <Detail label="Error" value={entry.error} color="var(--stove-red)" />}
          {entry.metadata && entry.metadata !== "{}" && (
            <Detail label="Metadata" value={entry.metadata} />
          )}
        </div>
      )}
    </button>
  );
}

function Detail({ label, value, color }: { label: string; value: string; color?: string }) {
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
