import type { Run, RunMetadataFacet } from "../../api/types";
import { formatTimestamp } from "../../utils/format";
import type { MetadataFilter } from "../../utils/metadata-filter";
import { MetadataFilters } from "./MetadataFilters";

export interface RunPickerFeedback {
  pageError: Error | null;
  metadataError: Error | null;
  metadataLoading: boolean;
  retryMetadata: () => void;
}

interface RunPickerProps {
  feedback?: RunPickerFeedback;
  runs: Run[];
  metadataOptions: RunMetadataFacet[];
  hasOlderRuns: boolean;
  loadingOlderRuns: boolean;
  onLoadOlderRuns: () => void;
  selectedRunId: string | undefined;
  onSelectRun: (runId: string) => void;
  metadataFilter: MetadataFilter;
  onMetadataFilterChange: (metadata: MetadataFilter) => void;
}

export function RunPicker({
  runs,
  feedback,
  metadataOptions,
  hasOlderRuns,
  loadingOlderRuns,
  onLoadOlderRuns,
  selectedRunId,
  onSelectRun,
  metadataFilter,
  onMetadataFilterChange,
}: RunPickerProps) {
  const loadLabel = feedback?.pageError ? "Retry older runs" : "Load older runs";
  return (
    <section className="stove-run-picker" aria-label="Run selection">
      <RunSelect runs={runs} selectedRunId={selectedRunId} onSelectRun={onSelectRun} />
      {feedback?.pageError && (
        <p role="alert">Could not load older runs: {feedback.pageError.message}</p>
      )}
      {hasOlderRuns && (
        <button type="button" disabled={loadingOlderRuns} onClick={onLoadOlderRuns}>
          {loadingOlderRuns ? "Loading…" : loadLabel}
        </button>
      )}
      {feedback?.metadataError && (
        <div role="alert">
          Could not load metadata: {feedback.metadataError.message}
          <button
            type="button"
            disabled={feedback.metadataLoading}
            onClick={feedback.retryMetadata}
          >
            Retry metadata
          </button>
        </div>
      )}
      <MetadataFilters
        options={metadataOptions}
        visibleRunCount={runs.length}
        value={metadataFilter}
        onChange={onMetadataFilterChange}
      />
    </section>
  );
}

function RunSelect({
  runs,
  selectedRunId,
  onSelectRun,
}: Pick<RunPickerProps, "runs" | "selectedRunId" | "onSelectRun">) {
  return (
    <>
      <div className="stove-sidebar-section-label">
        <span>Runs</span>
        <span>{runs.length}</span>
      </div>
      <select
        aria-label="Run"
        className="stove-focus-ring"
        disabled={runs.length === 0}
        value={selectedRunId ?? ""}
        onChange={(event) => onSelectRun(event.target.value)}
      >
        {runs.length === 0 ? <option value="">No matching runs</option> : null}
        {runs.map((run, index) => (
          <option key={run.id} value={run.id}>
            {index === 0 ? "Latest · " : ""}
            {new Date(run.started_at).toLocaleDateString(undefined, {
              month: "short",
              day: "numeric",
            })}{" "}
            · {formatTimestamp(run.started_at).slice(0, 5)} · {run.status.toLowerCase()}
          </option>
        ))}
      </select>
    </>
  );
}
