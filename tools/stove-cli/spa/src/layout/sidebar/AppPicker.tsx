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
    <div className="border-b border-stove-border p-3">
      <label
        htmlFor="stove-app-picker"
        className="mb-1.5 block text-[10px] font-semibold uppercase tracking-[0.16em] text-[var(--stove-text-muted)]"
      >
        Application
      </label>
      <select
        id="stove-app-picker"
        className="stove-focus-ring w-full rounded-lg border border-stove-border bg-stove-card px-2.5 py-2 text-sm font-medium text-[var(--stove-text)] shadow-sm focus:border-blue-500"
        value={selectedApp ?? ""}
        onChange={(e) => onSelectApp(e.target.value)}
      >
        {apps.map((app) => (
          <option key={app.app_name} value={app.app_name}>
            {app.app_name}
            {mismatchedAppSet.has(app.app_name) ? " [mismatch]" : ""}
            {` (${app.total_runs} runs)`}
          </option>
        ))}
      </select>
    </div>
  );
}
