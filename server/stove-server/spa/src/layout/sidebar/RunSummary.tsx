import type { Run, TestSummary } from "../../api/types";
import { formatDuration } from "../../utils/format";
import {
  isMetadataValueSelected,
  type MetadataFilter,
  toggleMetadataValue,
} from "../../utils/metadata-filter";

interface RunSummaryProps {
  run: Run;
  summary?: TestSummary;
  metadataFilter: MetadataFilter;
  onMetadataFilterChange: (metadata: MetadataFilter) => void;
}

export function RunSummary({
  run,
  summary,
  metadataFilter,
  onMetadataFilterChange,
}: RunSummaryProps) {
  const total = summary?.total ?? run.total_tests;
  const passed = summary?.passed ?? run.passed;
  const failed = summary?.failed ?? run.failed;
  const running = summary?.running ?? 0;
  return (
    <div className="stove-run-summary">
      <section className="stove-run-overview" aria-label="Run results">
        {failed > 0 && <strong className="is-failed">{failed} failed</strong>}
        <span>{passed} passed</span>
        {running > 0 && <span>{running} running</span>}
        <span>
          {total} tests · {formatDuration(run.duration_ms)}
        </span>
      </section>
      <details className="stove-run-details">
        <summary>Run details</summary>
        <dl>
          <dt>Run ID</dt>
          <dd>{run.id}</dd>
          <dt>Status</dt>
          <dd>{run.status}</dd>
          <dt>Stove version</dt>
          <dd>{run.stove_version ?? "not reported"}</dd>
        </dl>
        {Object.keys(run.metadata).length > 0 ? (
          <section className="stove-run-metadata" aria-label="Run metadata">
            <div className="stove-run-metadata-heading">
              <span>Metadata</span>
              <small>Click to filter</small>
            </div>
            {Object.entries(run.metadata).map(([key, value]) => (
              <button
                type="button"
                className={`stove-run-metadata-chip stove-focus-ring ${
                  isMetadataValueSelected(metadataFilter, key, value) ? "is-selected" : ""
                }`}
                key={key}
                title={`${key}=${value}`}
                aria-pressed={isMetadataValueSelected(metadataFilter, key, value)}
                onClick={() =>
                  onMetadataFilterChange(toggleMetadataValue(metadataFilter, key, value))
                }
              >
                <span>{key}</span>
                <strong>{value}</strong>
              </button>
            ))}
          </section>
        ) : null}
      </details>
    </div>
  );
}
