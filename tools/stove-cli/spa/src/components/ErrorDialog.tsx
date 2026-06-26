import { useEffect } from "react";

interface ErrorDialogProps {
  error: string;
  onClose: () => void;
}

export function ErrorDialog({ error, onClose }: ErrorDialogProps) {
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
      <div className="m-4 flex max-h-[85vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-red-500/30 bg-stove-surface shadow-xl">
        <div className="flex items-start justify-between gap-4 border-b border-stove-border px-4 py-3">
          <div className="text-sm font-medium text-[var(--stove-red)]">Error</div>
          <button
            type="button"
            className="cursor-pointer border-0 bg-transparent text-lg text-[var(--stove-text-secondary)] hover:text-[var(--stove-text)]"
            onClick={onClose}
          >
            {"✕"}
          </button>
        </div>

        <pre className="flex-1 overflow-auto p-4 font-mono text-xs whitespace-pre-wrap break-words text-[var(--stove-red)]">
          {error}
        </pre>
      </div>
    </div>
  );
}
