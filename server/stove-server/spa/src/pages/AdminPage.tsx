import type { MouseEventHandler } from "react";
import type { AppSummary } from "../api/types";
import { ClearAllCard, PurgeCard, RetentionCard, StorageCard } from "./admin/AdminSections";
import { DatabaseExplorer } from "./admin/DatabaseExplorer";
import { useAdminController } from "./admin/useAdminController";

interface AdminPageProps {
  apps: AppSummary[];
  onNavigateDashboard: MouseEventHandler<HTMLAnchorElement>;
}

export function AdminPage({ apps, onNavigateDashboard }: AdminPageProps) {
  const admin = useAdminController();
  return (
    <main className="stove-admin-page" aria-labelledby="stove-admin-title">
      <div className="stove-admin-page-content">
        <header className="stove-admin-header">
          <div>
            <div className="stove-kicker">Runtime controls</div>
            <h2 id="stove-admin-title">Dashboard administration</h2>
          </div>
          <a className="stove-admin-back stove-focus-ring" href="/" onClick={onNavigateDashboard}>
            <span aria-hidden="true">←</span>
            Back to dashboard
          </a>
        </header>

        {admin.error ? <div className="stove-admin-error">{admin.error}</div> : null}
        <div className="stove-admin-grid">
          <StorageCard status={admin.status} />
          <RetentionCard
            retention={admin.retention}
            busy={admin.busy}
            onRetentionChange={admin.setRetention}
            onApply={admin.updateRetention}
          />
        </div>
        <PurgeCard
          apps={apps}
          appName={admin.appName}
          olderThan={admin.olderThan}
          includeRunning={admin.includeRunning}
          preview={admin.preview}
          busy={admin.busy}
          onAppNameChange={admin.setAppName}
          onOlderThanChange={admin.setOlderThan}
          onIncludeRunningChange={admin.setIncludeRunning}
          onPreview={admin.previewPurge}
          onPurge={admin.purge}
        />
        <DatabaseExplorer onDatabaseChange={admin.refresh} />
        <ClearAllCard busy={admin.busy} onClear={admin.clearAll} />
      </div>
    </main>
  );
}
