import type { MouseEventHandler } from "react";
import stoveMarkUrl from "../assets/stove-mark.svg";
import { VersionMismatchWarning } from "../components/VersionMismatchWarning";
import { useTheme } from "../hooks/useTheme";
import { appPath, basePath } from "../utils/location";
import type { StoveRoute } from "../utils/routes";
import type { VersionMismatchSummary } from "../utils/version-mismatch";

interface HeaderProps {
  activeRoute: StoveRoute;
  liveConnected: boolean;
  versionMismatchSummary: VersionMismatchSummary | null;
  onNavigateAdmin: MouseEventHandler<HTMLAnchorElement>;
}

export function Header({
  activeRoute,
  liveConnected,
  versionMismatchSummary,
  onNavigateAdmin,
}: HeaderProps) {
  const { theme, toggle } = useTheme();
  const swaggerUrl = appPath("/swagger-ui/");

  return (
    <header className="stove-topbar">
      <a className="stove-brand stove-focus-ring" href={appPath("/")} aria-label="Stove home">
        <img src={stoveMarkUrl} alt="" aria-hidden="true" />
        <span>
          <strong>Stove</strong>
          <small>Test workspace</small>
        </span>
      </a>

      <div className="stove-topbar-actions">
        {versionMismatchSummary ? (
          <VersionMismatchWarning summary={versionMismatchSummary} />
        ) : null}
        <span
          className={`stove-stream-status ${liveConnected ? "is-live" : "is-polling"}`}
          title={liveConnected ? "Live SSE stream connected" : "SSE disconnected; polling APIs"}
        >
          <span />
          {liveConnected ? "Connected" : "Reconnecting"}
        </span>
        <a
          aria-current={activeRoute === "admin" ? "page" : undefined}
          className={`stove-topbar-link stove-focus-ring ${activeRoute === "admin" ? "is-active" : ""}`}
          href={appPath("/admin")}
          onClick={onNavigateAdmin}
          title="Administration"
        >
          <svg aria-hidden="true" className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
            <path d="M6.7 1h2.6l.4 1.5 1.1.6 1.5-.5 1.3 2.2-1.1 1.1v1.2l1.1 1.1-1.3 2.2-1.5-.5-1.1.6-.4 1.5H6.7l-.4-1.5-1.1-.6-1.5.5-1.3-2.2 1.1-1.1V5.9L2.4 4.8l1.3-2.2 1.5.5 1.1-.6L6.7 1zM8 5a2 2 0 100 4 2 2 0 000-4z" />
          </svg>
          <span>Admin</span>
        </a>
        <details className="stove-help-menu">
          <summary className="stove-topbar-link stove-focus-ring">Help</summary>
          <div className="stove-help-popover">
            <strong>Stove v{__STOVE_VERSION__}</strong>
            <a href={swaggerUrl} target="_blank" rel="noopener noreferrer">
              API reference ↗
            </a>
            <a href="https://trendyol.github.io/stove/" target="_blank" rel="noopener noreferrer">
              Documentation ↗
            </a>
            <a href="https://github.com/Trendyol/stove" target="_blank" rel="noopener noreferrer">
              GitHub ↗
            </a>
            <span>MCP endpoint</span>
            <code>
              {window.location.origin}
              {basePath()}/mcp
            </code>
          </div>
        </details>
        <button
          type="button"
          onClick={toggle}
          className="stove-theme-toggle stove-focus-ring"
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
