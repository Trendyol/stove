import type { AppSummary } from "../../api/types";

interface AppPickerProps {
  apps: AppSummary[];
  mismatchedApps: string[];
  selectedApp: string | null;
  onSelectApp: (name: string) => void;
}

export function AppPicker({ apps, mismatchedApps, selectedApp, onSelectApp }: AppPickerProps) {
  const mismatchedAppSet = new Set(mismatchedApps);

  return (
    <div className="stove-app-picker">
      <label htmlFor="stove-app-picker">Workspace</label>
      <div className="stove-app-select-wrap">
        <span className="stove-app-glyph">{selectedApp?.slice(0, 1).toUpperCase() || "—"}</span>
        <select
          id="stove-app-picker"
          className="stove-focus-ring"
          value={selectedApp ?? ""}
          onChange={(e) => onSelectApp(e.target.value)}
        >
          {apps.map((app) => (
            <option key={app.app_name} value={app.app_name}>
              {app.app_name}
              {mismatchedAppSet.has(app.app_name) ? " [mismatch]" : ""}
              {` · ${app.total_runs} runs`}
            </option>
          ))}
        </select>
        <svg aria-hidden="true" viewBox="0 0 16 16" fill="currentColor">
          <path d="m4 6 4 4 4-4z" />
        </svg>
      </div>
    </div>
  );
}
