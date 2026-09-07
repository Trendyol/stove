import type { MockInteraction, MockWarning } from "../../api/types";
import { InspectorDatum } from "./InspectorDatum";
import { humanize } from "./model";

export function InteractionDiagnostics({
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
