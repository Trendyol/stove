import type { Run } from "../../api/types";
import { formatTimestamp } from "../../utils/format";
import type { MetadataFilter } from "../../utils/metadata-filter";
import { MetadataFilters } from "./MetadataFilters";

interface RunPickerProps {
  runs: Run[];
  availableRuns: Run[];
  selectedRunId: string | undefined;
  onSelectRun: (runId: string) => void;
  metadataFilter: MetadataFilter;
  onMetadataFilterChange: (metadata: MetadataFilter) => void;
}

export function RunPicker({
  runs,
  availableRuns,
  selectedRunId,
  onSelectRun,
  metadataFilter,
  onMetadataFilterChange,
}: RunPickerProps) {
  return (
    <section className="stove-run-picker" aria-label="Run selection">
      <RunSelect runs={runs} selectedRunId={selectedRunId} onSelectRun={onSelectRun} />
      <MetadataFilters
        availableRuns={availableRuns}
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
        {runs.map((run) => (
          <option key={run.id} value={run.id}>
            {formatTimestamp(run.started_at)} · {run.status} · {run.id}
          </option>
        ))}
      </select>
    </>
  );
}
