import { useEffect, useMemo, useState } from "react";
import type { Snapshot } from "../api/types";
import {
  describeJsonValue,
  filterJsonByQuery,
  parseJsonDeep,
  tryFormatJsonDeep,
} from "../utils/json";
import { getSystemInfo } from "../utils/systems";
import { JsonTree } from "./JsonTree";

interface SnapshotStateDialogProps {
  snapshot: Snapshot;
  onClose: () => void;
}

export function SnapshotStateDialog({ snapshot, onClose }: SnapshotStateDialogProps) {
  const info = getSystemInfo(snapshot.system);
  const parsedState = parseJsonDeep(snapshot.state_json);
  const [searchQuery, setSearchQuery] = useState("");
  const normalizedSearchQuery = searchQuery.trim();
  const searchResult = useMemo(() => {
    if (parsedState === null) {
      return { filteredValue: null, matchCount: 0 };
    }
    return filterJsonByQuery(parsedState, normalizedSearchQuery);
  }, [normalizedSearchQuery, parsedState]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }

    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      onKeyDown={(e) => {
        if (e.key === "Escape") onClose();
      }}
      role="dialog"
    >
      <div className="m-4 flex max-h-[85vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-stove-border bg-stove-surface shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-stove-border px-4 py-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2 text-sm font-medium text-[var(--stove-text-heading)]">
              <span style={{ color: info.color }}>{info.icon}</span>
              <span>{snapshot.system} State</span>
            </div>
            <div className="mt-1 text-xs text-[var(--stove-text-secondary)]">
              {snapshot.summary}
            </div>
            <div className="mt-1 text-[10px] uppercase tracking-[0.16em] text-[var(--stove-text-muted)]">
              {parsedState ? describeJsonValue(parsedState) : "raw text"}
            </div>
          </div>
          <button
            type="button"
            className="cursor-pointer border-0 bg-transparent text-lg text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
            onClick={onClose}
          >
            {"\u2715"}
          </button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto p-4">
          {parsedState ? (
            <>
              <div className="rounded-lg border border-stove-border bg-stove-base p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    type="search"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Filter by any key or value"
                    className="min-w-0 flex-1 rounded-md border border-stove-border bg-stove-surface px-3 py-2 text-sm text-[var(--stove-text)] outline-none placeholder:text-[var(--stove-text-muted)] focus:border-[var(--stove-blue)]"
                  />
                  {normalizedSearchQuery && (
                    <button
                      type="button"
                      className="cursor-pointer rounded-md border border-stove-border bg-stove-surface px-3 py-2 text-xs font-medium text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
                      onClick={() => setSearchQuery("")}
                    >
                      Clear
                    </button>
                  )}
                </div>
                <div className="mt-2 text-[11px] text-[var(--stove-text-secondary)]">
                  {normalizedSearchQuery
                    ? `${searchResult.matchCount} match${searchResult.matchCount === 1 ? "" : "es"}`
                    : "Type to narrow the state by any property name or value"}
                </div>
              </div>

              {searchResult.filteredValue !== null ? (
                <JsonTree
                  value={searchResult.filteredValue}
                  defaultExpandedDepth={2}
                  searchQuery={normalizedSearchQuery}
                />
              ) : (
                <div className="rounded-lg border border-dashed border-stove-border bg-stove-base p-4 text-sm text-[var(--stove-text-secondary)]">
                  No matches in this state payload.
                </div>
              )}
            </>
          ) : (
            <pre className="overflow-x-auto rounded-lg border border-stove-border bg-stove-base p-3 text-xs whitespace-pre-wrap break-words text-[var(--stove-text)]">
              {tryFormatJsonDeep(snapshot.state_json)}
            </pre>
          )}

          <details className="rounded-lg border border-stove-border bg-stove-base">
            <summary className="cursor-pointer select-none px-3 py-2 text-xs font-medium text-[var(--stove-text-secondary)]">
              Raw JSON
            </summary>
            <pre className="max-h-72 overflow-auto border-t border-stove-border p-3 text-xs whitespace-pre-wrap break-words text-[var(--stove-text)]">
              {tryFormatJsonDeep(snapshot.state_json)}
            </pre>
          </details>
        </div>
      </div>
    </div>
  );
}
