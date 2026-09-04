import { useEffect, useState } from "react";
import type { MockInteraction, MockWarning } from "../../api/types";
import { formatDuration, formatTimestamp } from "../../utils/format";
import { tryFormatJson } from "../../utils/json";
import { getSystemInfo } from "../../utils/systems";
import { Icon } from "../Icon";
import {
  attributionLabel,
  hasInteractionIssue,
  humanize,
  type InspectorTab,
  type JournalInspectorState,
} from "./model";

interface InteractionInspectorProps {
  state: JournalInspectorState;
  interactions: readonly MockInteraction[];
  onSelect: (id: MockInteraction["id"]) => void;
  onClose: () => void;
  onOpenTrace: () => void;
}

export function InteractionInspector({
  state,
  interactions,
  onSelect,
  onClose,
  onOpenTrace,
}: InteractionInspectorProps) {
  const [tab, setTab] = useState<InspectorTab>("overview");
  const interaction = selectedInteraction(state);
  const warning = state.kind === "warning" ? state.warning : undefined;
  const selectionKey =
    state.kind === "empty"
      ? "empty"
      : state.kind === "interaction"
        ? `interaction:${state.interaction.id}`
        : `warning:${state.warning.id}`;

  // biome-ignore lint/correctness/useExhaustiveDependencies: selection key deliberately controls inspector reset
  useEffect(() => {
    setTab(state.kind === "warning" ? "diagnostics" : "overview");
  }, [selectionKey]);

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
              <button type="button" onClick={onOpenTrace}>
                Open trace
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

function selectedInteraction(state: JournalInspectorState): MockInteraction | undefined {
  if (state.kind === "interaction") return state.interaction;
  if (state.kind === "warning" && state.related.kind === "interaction") {
    return state.related.interaction;
  }
  return undefined;
}

function InspectorHeader({
  state,
  interaction,
  onClose,
}: {
  state: Exclude<JournalInspectorState, { kind: "empty" }>;
  interaction: MockInteraction | undefined;
  onClose: () => void;
}) {
  const warning = state.kind === "warning" ? state.warning : undefined;
  const system = interaction ? getSystemInfo(interaction.system) : undefined;
  const title = interaction?.target ?? warning?.target ?? (warning ? humanize(warning.kind) : "");

  return (
    <header className="ledger-inspector-header">
      <div>
        <strong>{title}</strong>
        <p>
          {interaction && system ? (
            <>
              <span style={{ color: system.color }}>{system.icon}</span> {interaction.system} ·{" "}
              {interaction.method} · {formatTimestamp(interaction.timestamp)}
            </>
          ) : (
            warning && `${warning.system} · ${formatTimestamp(warning.timestamp)}`
          )}
        </p>
      </div>
      <button
        type="button"
        className="inspector-close"
        onClick={onClose}
        aria-label="Close inspector"
      >
        ×
      </button>
    </header>
  );
}

function WarningBrief({ warning }: { warning: MockWarning }) {
  return (
    <div className="inspector-warning-brief">
      <Icon name="warning" className="h-4 w-4" />
      <div>
        <strong>{humanize(warning.kind)}</strong>
        <p>{warning.message}</p>
      </div>
    </div>
  );
}

function InspectorTabs({
  active,
  diagnosticCount,
  onSelect,
}: {
  active: InspectorTab;
  diagnosticCount: number;
  onSelect: (tab: InspectorTab) => void;
}) {
  const tabs: readonly InspectorTab[] = ["overview", "request", "response", "diagnostics"];
  return (
    <nav className="inspector-tabs" aria-label="Exchange detail sections">
      {tabs.map((tab) => (
        <button
          type="button"
          key={tab}
          className={active === tab ? "is-active" : ""}
          onClick={() => onSelect(tab)}
        >
          {tab}
          {tab === "diagnostics" && diagnosticCount > 0 && <span>{diagnosticCount}</span>}
        </button>
      ))}
    </nav>
  );
}

function InspectorBody({
  tab,
  interaction,
  warning,
}: {
  tab: InspectorTab;
  interaction: MockInteraction;
  warning: MockWarning | undefined;
}) {
  return (
    <div className="ledger-inspector-body">
      {tab === "overview" && <InteractionOverview interaction={interaction} />}
      {tab === "request" && (
        <ExchangeBody
          label="Request body"
          body={interaction.request_body}
          truncated={interaction.request_body_truncated}
        />
      )}
      {tab === "response" && (
        <ExchangeBody
          label="Response body"
          body={interaction.response_body}
          truncated={interaction.response_body_truncated}
        />
      )}
      {tab === "diagnostics" && (
        <InteractionDiagnostics interaction={interaction} warning={warning} />
      )}
    </div>
  );
}

function InteractionOverview({ interaction }: { interaction: MockInteraction }) {
  return (
    <div className="inspector-overview-grid">
      <InspectorDatum label="Attribution" value={attributionLabel(interaction.attribution)} />
      <InspectorDatum label="Protocol" value={interaction.protocol} />
      <InspectorDatum label="Matched" value={interaction.matched ? "Yes" : "No"} />
      <InspectorDatum
        label="Observed latency"
        value={interaction.latency_ms === null ? "Unknown" : formatDuration(interaction.latency_ms)}
      />
      {interaction.configured_delay_ms !== null && (
        <InspectorDatum
          label="Configured delay"
          value={formatDuration(interaction.configured_delay_ms)}
          tone="warn"
        />
      )}
      {interaction.client_deadline_ms !== null && (
        <InspectorDatum
          label="Client deadline"
          value={formatDuration(interaction.client_deadline_ms)}
          tone="warn"
        />
      )}
      {interaction.fault && (
        <InspectorDatum label="Injected fault" value={humanize(interaction.fault)} tone="bad" />
      )}
      {interaction.stub_id && <InspectorDatum label="Stub" value={interaction.stub_id} mono />}
      {(interaction.scenario_name ||
        interaction.scenario_state ||
        interaction.next_scenario_state) && (
        <div className="inspector-scenario">
          <span>{interaction.scenario_name ?? "Scenario transition"}</span>
          <code>{interaction.scenario_state ?? "STARTED"}</code>
          <span className="inspector-scenario-arrow">→</span>
          <code>{interaction.next_scenario_state ?? "stable"}</code>
        </div>
      )}
    </div>
  );
}

function InteractionDiagnostics({
  interaction,
  warning,
}: {
  interaction: MockInteraction;
  warning: MockWarning | undefined;
}) {
  return (
    <div className="inspector-diagnostics">
      {warning && (
        <div className="diagnostic-block is-warning">
          <strong>{humanize(warning.kind)}</strong>
          <p>{warning.message}</p>
        </div>
      )}
      {interaction.near_misses.map((nearMiss, index) => (
        // biome-ignore lint/suspicious/noArrayIndexKey: duplicate diagnostic candidates are meaningful and have no stable id
        <div className="diagnostic-block" key={`${index}-${nearMiss}`}>
          <span>Candidate {index + 1}</span>
          <pre>{nearMiss}</pre>
        </div>
      ))}
      {interaction.near_misses.length === 0 && warning === undefined && (
        <div className="inspector-no-detail">
          No near-miss or warning diagnostics were recorded.
        </div>
      )}
      {interaction.trace_id && (
        <InspectorDatum label="Trace ID" value={interaction.trace_id} mono />
      )}
    </div>
  );
}

function ExchangeBody({
  label,
  body,
  truncated,
}: {
  label: string;
  body: string | null;
  truncated: boolean;
}) {
  return (
    <div className="inspector-exchange-body">
      <div>
        <span>{label}</span>
        {truncated && <span className="inspector-truncated">truncated</span>}
      </div>
      {body ? <pre>{tryFormatJson(body)}</pre> : <p>No body captured</p>}
    </div>
  );
}

function InspectorDatum({
  label,
  value,
  tone = "neutral",
  mono = false,
}: {
  label: string;
  value: string;
  tone?: "neutral" | "warn" | "bad";
  mono?: boolean;
}) {
  return (
    <div className={`inspector-datum is-${tone}`}>
      <span>{label}</span>
      <strong className={mono ? "is-mono" : ""}>{value}</strong>
    </div>
  );
}
