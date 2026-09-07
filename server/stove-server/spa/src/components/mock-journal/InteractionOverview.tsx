import type { MockInteraction } from "../../api/types";
import { formatDuration } from "../../utils/format";
import { InspectorDatum } from "./InspectorDatum";
import { attributionLabel, humanize } from "./model";

export function InteractionOverview({ interaction }: { interaction: MockInteraction }) {
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
