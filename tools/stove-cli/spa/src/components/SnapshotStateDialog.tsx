import { useState } from "react";
import type { Snapshot } from "../api/types";
import { useModalDialog } from "../hooks/useModalDialog";
import { useSnapshotExplorer } from "../hooks/useSnapshotExplorer";
import { getSystemInfo } from "../utils/systems";
import { JsonTree } from "./JsonTree";
import { SnapshotMetricTiles } from "./SnapshotMetricTiles";

interface SnapshotStateDialogProps {
  snapshot: Snapshot;
  onClose: () => void;
}

export function SnapshotStateDialog({ snapshot, onClose }: SnapshotStateDialogProps) {
  const [searchQuery, setSearchQuery] = useState("");
  const normalizedSearchQuery = searchQuery.trim();
  const explorer = useSnapshotExplorer(snapshot, normalizedSearchQuery);
  const closeButtonRef = useModalDialog(true, onClose);
  const info = getSystemInfo(snapshot.system);

  const detailed = explorer.kind !== "loading" && explorer.detailed;
  const detailDescription =
    explorer.kind === "loading"
      ? "loading state"
      : explorer.kind === "structured"
        ? explorer.description
        : explorer.detailed
          ? "raw text"
          : "no details";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/55"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        event.stopPropagation();
        onClose();
      }}
      role="dialog"
      aria-modal="true"
      aria-label={`${snapshot.system} state`}
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
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <span className="text-[10px] uppercase tracking-[0.16em] text-[var(--stove-text-muted)]">
                {detailDescription}
              </span>
              <span
                className="rounded-full border px-2 py-1 text-[10px] font-medium uppercase tracking-[0.14em]"
                style={
                  detailed
                    ? { borderColor: info.color, color: info.color }
                    : {
                        borderColor: "var(--stove-border)",
                        color: "var(--stove-text-secondary)",
                      }
                }
              >
                {explorer.kind === "loading"
                  ? "Loading"
                  : detailed
                    ? "Detailed state"
                    : "Summary only"}
              </span>
            </div>
          </div>
          <button
            ref={closeButtonRef}
            type="button"
            aria-label="Close state explorer"
            className="cursor-pointer border-0 bg-transparent text-lg text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
            onClick={onClose}
          >
            {"\u2715"}
          </button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto p-4">
          {explorer.kind !== "loading" && explorer.metrics.length > 0 && (
            <SnapshotMetricTiles metrics={explorer.metrics} />
          )}

          {explorer.kind === "loading" && <StateMessage>Preparing state explorer…</StateMessage>}
          {explorer.kind === "structured" && explorer.detailed && (
            <StructuredState
              explorer={explorer}
              searchQuery={searchQuery}
              normalizedSearchQuery={normalizedSearchQuery}
              onSearchChange={setSearchQuery}
            />
          )}
          {explorer.kind === "raw" && explorer.detailed && (
            <pre className="overflow-x-auto rounded-lg border border-stove-border bg-stove-base p-3 text-xs whitespace-pre-wrap break-words text-[var(--stove-text)]">
              {explorer.value}
            </pre>
          )}
          {explorer.kind !== "loading" && !explorer.detailed && (
            <StateMessage>
              This snapshot only recorded the summary. There is no detailed state payload to
              inspect.
            </StateMessage>
          )}

          {detailed && (
            <details className="rounded-lg border border-stove-border bg-stove-base">
              <summary className="cursor-pointer select-none px-3 py-2 text-xs font-medium text-[var(--stove-text-secondary)]">
                Raw JSON
              </summary>
              <pre className="max-h-72 overflow-auto border-t border-stove-border p-3 text-xs whitespace-pre-wrap break-words text-[var(--stove-text)]">
                {snapshot.state_json}
              </pre>
            </details>
          )}
        </div>
      </div>
    </div>
  );
}

function StructuredState({
  explorer,
  searchQuery,
  normalizedSearchQuery,
  onSearchChange,
}: {
  explorer: Extract<ReturnType<typeof useSnapshotExplorer>, { kind: "structured" }>;
  searchQuery: string;
  normalizedSearchQuery: string;
  onSearchChange: (query: string) => void;
}) {
  return (
    <>
      <div className="rounded-lg border border-stove-border bg-stove-base p-3">
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="search"
            value={searchQuery}
            onChange={(event) => onSearchChange(event.target.value)}
            placeholder="Filter by any key or value"
            className="min-w-0 flex-1 rounded-md border border-stove-border bg-stove-surface px-3 py-2 text-sm text-[var(--stove-text)] outline-none placeholder:text-[var(--stove-text-muted)] focus:border-[var(--stove-blue)]"
          />
          {normalizedSearchQuery && (
            <button
              type="button"
              className="cursor-pointer rounded-md border border-stove-border bg-stove-surface px-3 py-2 text-xs font-medium text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
              onClick={() => onSearchChange("")}
            >
              Clear
            </button>
          )}
        </div>
        <div className="mt-2 text-[11px] text-[var(--stove-text-secondary)]">
          {explorer.filtering
            ? "Searching…"
            : normalizedSearchQuery
              ? `${explorer.matchCount} match${explorer.matchCount === 1 ? "" : "es"}`
              : "Type to narrow the state by any property name or value"}
        </div>
      </div>

      {explorer.filteredValue !== null ? (
        <JsonTree
          value={explorer.filteredValue}
          defaultExpandedDepth={2}
          searchQuery={normalizedSearchQuery}
        />
      ) : (
        <StateMessage>No matches in this state payload.</StateMessage>
      )}
    </>
  );
}

function StateMessage({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-stove-border bg-stove-base p-4 text-sm text-[var(--stove-text-secondary)]">
      {children}
    </div>
  );
}
