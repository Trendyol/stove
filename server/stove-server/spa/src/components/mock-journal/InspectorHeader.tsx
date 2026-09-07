import type { MockInteraction, MockWarning } from "../../api/types";
import { formatTimestamp } from "../../utils/format";
import { getSystemInfo } from "../../utils/systems";
import { Icon } from "../Icon";
import { humanize } from "./model";
import type { JournalInspectorState } from "./selection";

export function InspectorHeader({
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

export function WarningBrief({ warning }: { warning: MockWarning }) {
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
