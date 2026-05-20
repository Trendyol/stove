import stoveMarkUrl from "../assets/stove-mark.svg";
import { useTheme } from "../hooks/useTheme";

interface HeaderProps {
  liveConnected: boolean;
}

export function Header({ liveConnected }: HeaderProps) {
  const { theme, toggle } = useTheme();

  return (
    <header className="flex min-h-14 flex-wrap items-center justify-between gap-3 border-b border-stove-border bg-[var(--stove-panel-strong)] px-4 py-2 shadow-sm">
      <div className="flex min-w-0 items-center gap-3">
        <img className="h-9 w-9 shrink-0" src={stoveMarkUrl} alt="" aria-hidden="true" />
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold text-[var(--stove-text-heading)]">
            Stove Dashboard
          </span>
          <span className="block text-[11px] text-[var(--stove-text-secondary)]">
            v{__STOVE_VERSION__} local evidence stream
          </span>
        </span>
      </div>
      <div className="flex flex-wrap items-center justify-end gap-2 text-xs text-[var(--stove-text-secondary)]">
        <span
          className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 ${
            liveConnected
              ? "border-emerald-500/25 bg-emerald-500/10 text-[var(--stove-green)]"
              : "border-amber-500/30 bg-amber-500/10 text-[var(--stove-amber)]"
          }`}
          title={liveConnected ? "Live SSE stream connected" : "SSE disconnected; polling APIs"}
        >
          <span
            className={`h-1.5 w-1.5 rounded-full ${liveConnected ? "bg-green-500" : "bg-amber-500"} animate-pulse-dot`}
          />
          {liveConnected ? "Live" : "Polling"}
        </span>
        <code
          className="rounded-full border border-stove-border bg-stove-base px-2.5 py-1 font-mono text-[10px] text-[var(--stove-text-secondary)]"
          title={`MCP endpoint: ${window.location.origin}/mcp`}
        >
          MCP /mcp
        </code>
        <a
          href="https://trendyol.github.io/stove/"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 transition-colors hover:bg-[var(--stove-hover)] hover:text-[var(--stove-text)]"
          title="Documentation"
        >
          <svg aria-hidden="true" className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
            <path d="M1 2.828c.885-.37 2.154-.769 3.388-.893 1.33-.134 2.458.063 3.112.752v9.746c-.935-.53-2.12-.603-3.213-.493-1.18.12-2.37.461-3.287.811V2.828zm7.5-.141c.654-.689 1.782-.886 3.112-.752 1.234.124 2.503.523 3.388.893v9.923c-.918-.35-2.107-.692-3.287-.81-1.094-.111-2.278-.039-3.213.492V2.687zM8 1.783C7.015.936 5.587.81 4.287.94c-1.514.153-3.042.672-3.994 1.105A.5.5 0 000 2.5v11a.5.5 0 00.707.455c.882-.4 2.303-.881 3.68-1.02 1.409-.142 2.59.087 3.223.877a.5.5 0 00.78 0c.633-.79 1.814-1.019 3.222-.877 1.378.139 2.8.62 3.681 1.02A.5.5 0 0016 13.5v-11a.5.5 0 00-.293-.455c-.952-.433-2.48-.952-3.994-1.105C10.413.809 8.985.936 8 1.783z" />
          </svg>
          Docs
        </a>
        <a
          href="https://github.com/Trendyol/stove"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 transition-colors hover:bg-[var(--stove-hover)] hover:text-[var(--stove-text)]"
          title="GitHub"
        >
          <svg aria-hidden="true" className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
          </svg>
          GitHub
        </a>
        <button
          type="button"
          onClick={toggle}
          className="stove-focus-ring cursor-pointer rounded-md border border-stove-border bg-stove-base p-1.5 text-xs text-[var(--stove-text-secondary)] transition-colors hover:bg-[var(--stove-hover)] hover:text-[var(--stove-text)]"
          title={`Switch to ${theme === "dark" ? "light" : "dark"} mode`}
        >
          {theme === "dark" ? (
            <svg aria-hidden="true" className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
              <path d="M8 12a4 4 0 100-8 4 4 0 000 8zM8 0a.5.5 0 01.5.5v2a.5.5 0 01-1 0v-2A.5.5 0 018 0zm0 13a.5.5 0 01.5.5v2a.5.5 0 01-1 0v-2A.5.5 0 018 13zm8-5a.5.5 0 01-.5.5h-2a.5.5 0 010-1h2a.5.5 0 01.5.5zM3 8a.5.5 0 01-.5.5h-2a.5.5 0 010-1h2A.5.5 0 013 8zm10.657-5.657a.5.5 0 010 .707l-1.414 1.415a.5.5 0 11-.707-.708l1.414-1.414a.5.5 0 01.707 0zm-9.193 9.193a.5.5 0 010 .707L3.05 13.657a.5.5 0 01-.707-.707l1.414-1.414a.5.5 0 01.707 0zm9.193 2.121a.5.5 0 01-.707 0l-1.414-1.414a.5.5 0 01.707-.707l1.414 1.414a.5.5 0 010 .707zM4.464 4.465a.5.5 0 01-.707 0L2.343 3.05a.5.5 0 11.707-.707l1.414 1.414a.5.5 0 010 .708z" />
            </svg>
          ) : (
            <svg aria-hidden="true" className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
              <path d="M6 .278a.768.768 0 01.08.858 7.208 7.208 0 00-.878 3.46c0 4.021 3.278 7.277 7.318 7.277.527 0 1.04-.055 1.533-.16a.787.787 0 01.81.316.733.733 0 01-.031.893A8.349 8.349 0 018.344 16C3.734 16 0 12.286 0 7.71 0 4.266 2.114 1.312 5.124.06A.752.752 0 016 .278z" />
            </svg>
          )}
        </button>
      </div>
    </header>
  );
}
