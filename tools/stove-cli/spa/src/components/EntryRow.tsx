import { useState } from "react";
import type { Entry } from "../api/types";
import { formatTimestamp } from "../utils/format";
import { EntryDetails } from "./EntryDetails";
import { ResultIcon } from "./ResultIcon";
import { SysBadge } from "./SysBadge";

interface EntryRowProps {
  entry: Entry;
}

export function EntryRow({ entry }: EntryRowProps) {
  const [expanded, setExpanded] = useState(false);
  const passed = entry.result === "PASSED";

  return (
    <button
      type="button"
      className="animate-fade-in w-full cursor-pointer rounded-xl border bg-stove-surface text-left shadow-sm transition-all hover:-translate-y-px hover:bg-[var(--stove-hover)] hover:shadow-md"
      style={{
        borderColor: "var(--stove-border)",
        borderLeftColor: passed ? "var(--stove-green)" : "var(--stove-red)",
        borderLeftWidth: 3,
      }}
      aria-expanded={expanded}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2 px-3 py-2.5 text-sm">
        <span className="w-24 shrink-0 font-mono text-xs text-[var(--stove-text-secondary)]">
          {formatTimestamp(entry.timestamp)}
        </span>
        <ResultIcon result={entry.result} />
        <SysBadge system={entry.system} />
        <span className="truncate font-medium text-[var(--stove-text)]">{entry.action}</span>
        <span className="ml-auto text-[var(--stove-text-muted)]">
          {expanded ? "Hide" : "Details"}
        </span>
      </div>

      {expanded && (
        <div className="space-y-2 border-t border-stove-border bg-stove-base/60 px-4 pb-3 pt-1 font-mono text-xs">
          <EntryDetails entry={entry} />
        </div>
      )}
    </button>
  );
}
