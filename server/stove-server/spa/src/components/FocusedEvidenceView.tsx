import { ApiError } from "../api/client";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import type { useFocusedEvidence } from "../hooks/useFocusedEvidence";
import { appPath, evidencePath } from "../utils/location";
import { EvidenceWorkbench } from "./EvidenceWorkbench";
import { MockJournal } from "./MockJournal";
import { SnapshotCards } from "./SnapshotCards";
import { SpanTree } from "./SpanTree";

export function FocusedEvidenceView({
  query,
  runId,
  testId,
}: {
  query: ReturnType<typeof useFocusedEvidence>;
  runId: string;
  testId?: string;
}) {
  const navigation = useEvidenceNavigation();
  if (query.error)
    return (
      <div role="alert" className="stove-empty-state m-4">
        <strong>
          {query.error instanceof ApiError && query.error.status === 404
            ? "Evidence is unavailable in this scope."
            : "Could not load this evidence."}
        </strong>
        <p>The requested record has not been replaced with another result.</p>
        <button type="button" onClick={() => void query.refetch()}>
          Retry
        </button>{" "}
        <a href={appPath(evidencePath(runId, testId))}>Open {testId ? "test" : "run"}</a>
      </div>
    );
  if (!query.data)
    return (
      <div role="status" className="stove-empty-state m-4">
        Loading cited evidence…
      </div>
    );
  const { target, entries, spans, interactions, warnings, has_more_before, has_more_after } =
    query.data;
  const openTrace = () => navigation?.selectTab("trace");
  return (
    <>
      <div className="evidence-scope-note" role="status">
        Focused on the cited record.{" "}
        {target.kind === "entry" && "Showing recorded attempts, including retries. "}
        {has_more_before || has_more_after
          ? "More context is available."
          : "All available context is shown."}
        {!testId && " Run evidence; no test attribution is implied."}
      </div>
      {target.kind === "entry" ? (
        <EvidenceWorkbench entries={entries} onOpenTrace={openTrace} />
      ) : target.kind === "span" ? (
        <SpanTree spans={spans} />
      ) : target.kind === "snapshot" ? (
        <SnapshotCards snapshots={[target.value]} />
      ) : (
        <MockJournal
          interactions={interactions}
          warnings={warnings}
          ambientInteractions={[]}
          ambientWarnings={[]}
          onOpenTrace={openTrace}
        />
      )}
    </>
  );
}
