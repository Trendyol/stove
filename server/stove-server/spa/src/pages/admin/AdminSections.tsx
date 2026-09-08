import type { AdminStatus, AppSummary, PurgePreview, Run } from "../../api/types";
import type { MetadataFilter } from "../../utils/metadata-filter";
import { PurgeMetadataFilters } from "./PurgeMetadataFilters";

export function StorageCard({ status }: { status: AdminStatus | null }) {
  const stats = [
    ["Backend", status?.backend],
    ["Runs", status?.runs],
    ["Running", status?.running_runs],
    ["Tests", status?.evidence.tests],
    ["Entries", status?.evidence.entries],
    ["Spans", status?.evidence.spans],
  ];
  return (
    <section className="stove-admin-card">
      <h3>Storage</h3>
      <dl className="stove-admin-stats">
        {stats.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value ?? "…"}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

interface RetentionCardProps {
  retention: number;
  busy: boolean;
  onRetentionChange: (value: number) => void;
  onApply: () => void;
}

export function RetentionCard({ retention, busy, onRetentionChange, onApply }: RetentionCardProps) {
  return (
    <section className="stove-admin-card">
      <h3>Retention</h3>
      <p>Completed runs kept per application. Use 0 for unlimited history.</p>
      <label className="stove-admin-field">
        <span>Runs per app</span>
        <input
          min="0"
          type="number"
          value={retention}
          onChange={(event) => onRetentionChange(Number(event.target.value))}
        />
      </label>
      <button type="button" disabled={busy} onClick={onApply}>
        Apply retention
      </button>
    </section>
  );
}

interface PurgeCardProps {
  apps: AppSummary[];
  appName: string;
  olderThan: string;
  includeRunning: boolean;
  metadata: MetadataFilter;
  metadataRuns: Run[];
  metadataLoading: boolean;
  metadataError: string | null;
  onMetadataChange: (value: MetadataFilter) => void;
  onRetryMetadata: () => void;
  preview: PurgePreview | null;
  busy: boolean;
  onAppNameChange: (value: string) => void;
  onOlderThanChange: (value: string) => void;
  onIncludeRunningChange: (value: boolean) => void;
  onPreview: () => void;
  onPurge: () => void;
}

export function PurgeCard(props: PurgeCardProps) {
  return (
    <section className="stove-admin-card stove-admin-purge">
      <h3>Purge runs</h3>
      <p>Choose which runs to remove, then review before deleting.</p>
      <div className="stove-admin-controls">
        <label className="stove-admin-field">
          <span>Application</span>
          <select
            value={props.appName}
            onChange={(event) => props.onAppNameChange(event.target.value)}
          >
            <option value="">All applications</option>
            {props.apps.map((app) => (
              <option key={app.app_name} value={app.app_name}>
                {app.app_name}
              </option>
            ))}
          </select>
        </label>
        <label className="stove-admin-field">
          <span>Started before</span>
          <input
            type="datetime-local"
            value={props.olderThan}
            onChange={(event) => props.onOlderThanChange(event.target.value)}
          />
        </label>
        <label className="stove-admin-check">
          <input
            type="checkbox"
            checked={props.includeRunning}
            onChange={(event) => props.onIncludeRunningChange(event.target.checked)}
          />
          Include active runs
        </label>
      </div>
      <PurgeMetadataFilters
        appName={props.appName}
        runs={props.metadataRuns}
        value={props.metadata}
        loading={props.metadataLoading}
        error={props.metadataError}
        onChange={props.onMetadataChange}
        onRetry={props.onRetryMetadata}
      />
      <div className="stove-admin-actions">
        <button
          type="button"
          disabled={props.busy || props.metadataLoading || Boolean(props.metadataError)}
          onClick={props.onPreview}
        >
          Preview purge
        </button>
      </div>
      {props.preview ? (
        <PurgePreviewDetails preview={props.preview} busy={props.busy} onPurge={props.onPurge} />
      ) : null}
    </section>
  );
}

function PurgePreviewDetails({
  preview,
  busy,
  onPurge,
}: {
  preview: PurgePreview;
  busy: boolean;
  onPurge: () => void;
}) {
  const count = preview.run_count.toLocaleString();
  return (
    <section className="stove-admin-preview" aria-label="Purge preview">
      <div className="stove-admin-preview-overview">
        <div className="stove-admin-preview-summary" role="status">
          <strong>
            {preview.run_count === 0
              ? "No runs match these filters"
              : `${count} ${preview.run_count === 1 ? "run" : "runs"} will be deleted`}
          </strong>
          {preview.run_count > 0 && (
            <span>
              {preview.evidence.tests.toLocaleString()} tests ·{" "}
              {preview.evidence.entries.toLocaleString()} entries ·{" "}
              {preview.evidence.spans.toLocaleString()} spans
            </span>
          )}
        </div>
        {preview.run_count > 0 && (
          <button type="button" className="is-danger" disabled={busy} onClick={onPurge}>
            Delete {count} {preview.run_count === 1 ? "run" : "runs"}
          </button>
        )}
      </div>
      {preview.run_count > 0 && (
        <details className="stove-admin-preview-ids">
          <summary>View run IDs</summary>
          <code>{preview.run_ids.join("\n")}</code>
        </details>
      )}
    </section>
  );
}

export function ClearAllCard({ busy, onClear }: { busy: boolean; onClear: () => void }) {
  return (
    <section className="stove-admin-danger">
      <div>
        <strong>Clear all data</strong>
        <span>Deletes active and completed runs and all evidence.</span>
      </div>
      <button type="button" className="is-danger" disabled={busy} onClick={onClear}>
        Clear everything
      </button>
    </section>
  );
}
