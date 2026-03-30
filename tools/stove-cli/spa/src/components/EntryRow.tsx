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
      className="animate-fade-in border-l-2 bg-stove-card rounded-r mb-1 cursor-pointer hover:bg-[var(--stove-hover)] w-full text-left"
      style={{ borderLeftColor: passed ? "var(--stove-green)" : "var(--stove-red)" }}
      aria-expanded={expanded}
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2 px-3 py-2 text-sm">
        <span className="text-[var(--stove-text-secondary)] font-mono text-xs w-24 shrink-0">
          {formatTimestamp(entry.timestamp)}
        </span>
        <ResultIcon result={entry.result} />
        <SysBadge system={entry.system} />
        <span className="text-[var(--stove-text)] truncate">{entry.action}</span>
      </div>

      {expanded && (
        <div className="px-4 pb-3 text-xs font-mono space-y-2 border-t border-stove-border">
          <EntryDetails entry={entry} />
        </div>
      )}
    </button>
  );
}
