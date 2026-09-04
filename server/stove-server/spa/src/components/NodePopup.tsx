import { useEffect } from "react";
import type { Entry } from "../api/types";
import { EntryDetails } from "./EntryDetails";
import { ResultIcon } from "./ResultIcon";

interface NodePopupProps {
  entries: Entry[];
  traceId: string | null;
  onClose: () => void;
  onOpenTrace?: (() => void) | undefined;
}

export function NodePopup({ entries, traceId, onClose, onOpenTrace }: NodePopupProps) {
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      onKeyDown={(e) => {
        if (e.key === "Escape") onClose();
      }}
      role="dialog"
    >
      <div className="bg-stove-surface border border-stove-border rounded-lg shadow-xl w-full max-w-2xl max-h-[80vh] overflow-y-auto m-4">
        <div className="flex items-center justify-between px-4 py-3 border-b border-stove-border">
          <span className="text-sm font-medium text-[var(--stove-text-heading)]">
            Entry Details
          </span>
          <button
            type="button"
            className="text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)] text-lg cursor-pointer bg-transparent border-0"
            onClick={onClose}
          >
            {"\u2715"}
          </button>
        </div>

        <div className="p-4 space-y-4">
          {entries.length > 0 && (
            <div className="space-y-3">
              {entries.map((entry) => (
                <div key={entry.id} className="text-xs font-mono space-y-2">
                  <div className="flex items-center gap-2 text-sm">
                    <ResultIcon result={entry.result} />
                    <span className="text-[var(--stove-text)]">{entry.action}</span>
                  </div>
                  <EntryDetails entry={entry} />
                  {entries.length > 1 && <hr className="border-stove-border" />}
                </div>
              ))}
            </div>
          )}

          {traceId && (
            <div className="border-t border-stove-border pt-3">
              <div className="mb-2 text-xs text-[var(--stove-text-secondary)]">Trace Context</div>
              <div className="rounded-lg border border-stove-border bg-stove-base px-3 py-2">
                <div className="text-[10px] uppercase tracking-[0.16em] text-[var(--stove-text-muted)]">
                  Trace Id
                </div>
                <div className="mt-1 break-all font-mono text-xs text-[var(--stove-text)]">
                  {traceId}
                </div>
                <div className="mt-2 text-xs text-[var(--stove-text-secondary)]">
                  Full trace inspection lives in the Trace tab.
                </div>
                {onOpenTrace && (
                  <button
                    type="button"
                    className="mt-3 cursor-pointer rounded border border-stove-border bg-stove-card px-2.5 py-1.5 text-xs text-[var(--stove-text)] hover:bg-[var(--stove-hover)]"
                    onClick={onOpenTrace}
                  >
                    Open Trace Tab
                  </button>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
