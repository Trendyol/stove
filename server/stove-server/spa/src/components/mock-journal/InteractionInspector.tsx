import { useCallback } from "react";
import type { MockInteraction } from "../../api/types";
import { formatDuration } from "../../utils/format";
import { EvidenceActions } from "../EvidenceActions";
import { useRememberedState, useRevealTarget } from "../evidence/EvidenceViewMemory";
import { Icon } from "../Icon";
import { InspectorBody } from "./InspectorBody";
import { InspectorHeader, WarningBrief } from "./InspectorHeader";
import { type InspectorTab, InspectorTabs } from "./InspectorTabs";
import { hasInteractionIssue } from "./model";
import { type JournalInspectorState, selectedInteraction } from "./selection";

interface InteractionInspectorProps {
  state: JournalInspectorState;
  interactions: readonly MockInteraction[];
  onSelect: (id: MockInteraction["id"]) => void;
  onClose: () => void;
  onOpenTrace: (traceId: string) => void;
}

export function InteractionInspector({
  state,
  interactions,
  onSelect,
  onClose,
  onOpenTrace,
}: InteractionInspectorProps) {
  const [tab, setTab] = useRememberedState<InspectorTab>("mocks.inspectorTab", "overview");
  const interaction = selectedInteraction(state);
  const warning = state.kind === "warning" ? state.warning : undefined;
  const selectionKey =
    state.kind === "empty"
      ? "empty"
      : state.kind === "interaction"
        ? `interaction:${state.interaction.id}`
        : `warning:${state.warning.id}`;

  useRevealTarget(
    "mocks.inspectorTarget",
    selectionKey,
    useCallback(() => {
      setTab(state.kind === "warning" ? "diagnostics" : "overview");
    }, [state.kind]),
  );

  if (state.kind === "empty") {
    return (
      <aside className="ledger-inspector is-empty">
        <Icon name="mock" className="h-5 w-5" />
        <strong>Select an exchange</strong>
        <p>Request, response and diagnostics stay pinned while you compare retries.</p>
      </aside>
    );
  }

  const position = interaction
    ? interactions.findIndex((candidate) => candidate.id === interaction.id)
    : -1;
  const previous = position > 0 ? interactions[position - 1] : undefined;
  const next =
    position >= 0 && position < interactions.length - 1 ? interactions[position + 1] : undefined;

  return (
    <aside
      className="ledger-inspector mock-inspector"
      aria-label={
        interaction ? `Exchange details for ${interaction.target}` : "Mock warning details"
      }
    >
      <InspectorHeader state={state} interaction={interaction} onClose={onClose} />
      <EvidenceActions />
      {warning && <WarningBrief warning={warning} />}

      {interaction && (
        <>
          <div className="inspector-status-line">
            <span className={hasInteractionIssue(interaction) ? "is-issue" : "is-success"}>
              {interaction.status || (interaction.matched ? "MATCHED" : "UNMATCHED")}
            </span>
            <strong>
              {interaction.latency_ms === null
                ? "Latency unknown"
                : formatDuration(interaction.latency_ms)}
            </strong>
            {interaction.trace_id && (
              <button
                type="button"
                onClick={() => interaction.trace_id && onOpenTrace(interaction.trace_id)}
              >
                Related trace
                <Icon name="chevron" className="h-3.5 w-3.5" />
              </button>
            )}
          </div>

          <InspectorTabs
            active={tab}
            diagnosticCount={interaction.near_misses.length + (warning ? 1 : 0)}
            onSelect={setTab}
          />
          <InspectorBody tab={tab} interaction={interaction} warning={warning} />
          <footer className="ledger-inspector-nav">
            <button
              type="button"
              disabled={previous === undefined}
              onClick={() => previous && onSelect(previous.id)}
            >
              ← Previous
            </button>
            <span>
              {position + 1} / {interactions.length}
            </span>
            <button
              type="button"
              disabled={next === undefined}
              onClick={() => next && onSelect(next.id)}
            >
              Next →
            </button>
          </footer>
        </>
      )}
    </aside>
  );
}
