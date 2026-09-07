import type { MockInteraction, MockWarning } from "../../api/types";
import { tryFormatJson } from "../../utils/json";
import type { InspectorTab } from "./InspectorTabs";
import { InteractionDiagnostics } from "./InteractionDiagnostics";
import { InteractionOverview } from "./InteractionOverview";

export function InspectorBody({
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
