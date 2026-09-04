import type { CSSProperties } from "react";
import type { MockInteraction } from "../../api/types";
import { formatDuration, formatTimestamp } from "../../utils/format";
import { getSystemInfo } from "../../utils/systems";
import { Icon } from "../Icon";
import { hasInteractionIssue, humanize } from "./model";

interface InteractionRowProps {
  interaction: MockInteraction;
  sequence: number;
  selected: boolean;
  onSelect: () => void;
}

export function InteractionRow({ interaction, sequence, selected, onSelect }: InteractionRowProps) {
  const system = getSystemInfo(interaction.system);
  const issue = hasInteractionIssue(interaction);
  const ambient = interaction.test_id === null;
  const tone = !interaction.matched ? "unmatched" : issue ? "issue" : "matched";

  return (
    <button
      type="button"
      className={`mock-ledger-row is-${tone} ${ambient ? "is-ambient" : ""} ${
        selected ? "is-selected" : ""
      }`}
      aria-pressed={selected}
      onClick={onSelect}
    >
      <span className="ledger-rail-point" />
      <span className="mock-ledger-sequence">{String(sequence).padStart(2, "0")}</span>
      <time>{formatTimestamp(interaction.timestamp)}</time>
      <span
        className="ledger-system-glyph"
        style={{ "--system-color": system.color } as CSSProperties}
      >
        {system.icon}
      </span>
      <span className="mock-ledger-request">
        <span>
          <code>{interaction.method}</code>
          <em>{interaction.protocol}</em>
          {ambient && <b>unattributed</b>}
        </span>
        <strong>{interaction.target}</strong>
        {(interaction.scenario_name ||
          interaction.scenario_state ||
          interaction.next_scenario_state) && (
          <span className="scenario-inline">
            <i>{interaction.scenario_name ?? "Scenario"}</i>
            <code>{interaction.scenario_state ?? "STARTED"}</code>
            <span>→</span>
            <code>{interaction.next_scenario_state ?? "stable"}</code>
          </span>
        )}
      </span>
      <span className="mock-ledger-signals">
        {interaction.configured_delay_ms !== null && (
          <EvidenceTag label={`delay ${formatDuration(interaction.configured_delay_ms)}`} />
        )}
        {interaction.client_deadline_ms !== null && (
          <EvidenceTag label={`deadline ${formatDuration(interaction.client_deadline_ms)}`} />
        )}
        {interaction.fault && <EvidenceTag label={humanize(interaction.fault)} tone="bad" />}
      </span>
      <span className={`mock-ledger-status is-${tone}`}>
        <strong>{interaction.status || (interaction.matched ? "MATCHED" : "UNMATCHED")}</strong>
        <small>
          {interaction.latency_ms === null ? "latency —" : formatDuration(interaction.latency_ms)}
        </small>
      </span>
      <Icon name="chevron" className="h-4 w-4" />
    </button>
  );
}

function EvidenceTag({ label, tone = "neutral" }: { label: string; tone?: "neutral" | "bad" }) {
  return <span className={`mock-evidence-tag is-${tone}`}>{label}</span>;
}
