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
    <div className="p-3 border-b border-stove-border">
      <select
        className="w-full bg-stove-card border border-stove-border rounded px-2 py-1.5 text-sm text-[var(--stove-text)] focus:outline-none focus:border-blue-500"
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
