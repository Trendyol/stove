import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { api } from "../../api/client";
import type { AgentSetupProvider, AgentSetupResponse, AgentSetupScope } from "../../api/types";
import { isDemo } from "../../utils/demo-mode";
import { appPath } from "../../utils/location";
import { ChoiceGroup } from "./ChoiceGroup";
import { CopyBlock } from "./CopyBlock";

const setupDocs = "https://trendyol.github.io/stove/agent-setup/";
const scopes = [
  { id: "project", label: "This project" },
  { id: "global", label: "All projects" },
] as const;

export function AgentConnectionDialog({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    const element = dialog.current;
    const trigger = document.activeElement;
    element?.showModal();
    return () => {
      element?.close();
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus();
    };
  }, []);
  return (
    <dialog
      ref={dialog}
      className="agent-dialog"
      aria-labelledby={`${id}-title`}
      aria-describedby={`${id}-description`}
      onCancel={onClose}
    >
      <div className="agent-dialog-heading">
        <div>
          <div className="stove-kicker">Stove + your coding agent</div>
          <h2 id={`${id}-title`}>Connect your agent</h2>
        </div>
        <button
          type="button"
          className="agent-close stove-focus-ring"
          aria-label="Close agent setup"
          onClick={onClose}
        >
          ×
        </button>
      </div>
      <p id={`${id}-description`} className="agent-intro">
        Give your agent Stove skills and read-only access to test failures, traces, and snapshots.
      </p>
      <ConnectionContent />
    </dialog>
  );
}

function ConnectionContent() {
  const browserEndpoint = new URL(appPath("/mcp"), window.location.origin).href;
  const setup = useQuery({
    queryKey: ["agent-setup", browserEndpoint],
    queryFn: ({ signal }) => api.getAgentSetup(browserEndpoint, signal),
    enabled: !isDemo,
    retry: false,
  });

  if (isDemo)
    return (
      <p className="agent-message">
        This demo has no MCP server.{" "}
        <a href={setupDocs} target="_blank" rel="noopener noreferrer">
          Set up your own Stove server ↗
        </a>{" "}
        to connect an agent.
      </p>
    );
  if (setup.isError)
    return (
      <div className="agent-message" role="alert">
        <p>
          Could not load connection details. Check your connection and that your Stove server
          supports agent setup.
        </p>
        <button
          type="button"
          className="stove-topbar-link stove-focus-ring"
          onClick={() => void setup.refetch()}
        >
          Try again
        </button>
        <a href={setupDocs} target="_blank" rel="noopener noreferrer">
          Setup guide ↗
        </a>
      </div>
    );
  if (setup.data) return <ConnectionOptions setup={setup.data} />;
  return (
    <p role="status" className="agent-message">
      Loading connection details…
    </p>
  );
}

function ConnectionOptions({ setup }: { setup: AgentSetupResponse }) {
  const [agentId, setAgentId] = useState("");
  const [global, setGlobal] = useState(false);
  const agent = setup.providers.find((item) => item.id === agentId) ?? setup.providers[0];
  if (!agent) return <p>No agent providers are available on this server.</p>;
  const scope = global ? agent.global : agent.project;
  return (
    <>
      <ChoiceGroup
        legend="Choose your agent"
        choices={setup.providers}
        value={agent.id}
        onChange={setAgentId}
      />
      <ChoiceGroup
        legend="Install for"
        choices={scopes}
        value={global ? "global" : "project"}
        onChange={(value) => setGlobal(value === "global")}
        className="agent-scope"
      />
      <InstallCommand command={scope.command} global={global} />
      <ManualConfiguration agent={agent} scope={scope} />
      <div className="agent-endpoint">
        <CopyBlock label="MCP endpoint" text={setup.endpoint} />
        <p>
          Connection approval happens in your agent. This page does not track its connection status.
        </p>
      </div>
    </>
  );
}

function InstallCommand({ command, global }: Readonly<{ command: string; global: boolean }>) {
  return (
    <section className="agent-install" aria-label="Install skills and MCP">
      <div className="agent-section-heading">
        <h3>One command. Skills + MCP.</h3>
        <span className="agent-badge">Recommended</span>
      </div>
      <p>
        {global
          ? "Run on the machine where your agent runs."
          : "Run from your application’s Git repository."}{" "}
        Use a macOS, Linux, or WSL terminal.
      </p>
      <CopyBlock label="Setup command" text={command} />
      <p className="agent-next">
        Restart your agent session and approve the Stove connection when prompted.
      </p>
      <a href={setupDocs} target="_blank" rel="noopener noreferrer">
        Need to install or update the Stove CLI? ↗
      </a>
    </section>
  );
}

function ManualConfiguration({
  agent,
  scope,
}: Readonly<{ agent: AgentSetupProvider; scope: AgentSetupScope }>) {
  const installLink = agent.install_link?.startsWith(
    "cursor://anysphere.cursor-deeplink/mcp/install?",
  )
    ? agent.install_link
    : null;
  return (
    <details className="agent-manual">
      <summary className="stove-focus-ring">Manual configuration & MCP-only setup</summary>
      <p>
        Merge this entry into <code>{scope.config_path}</code>, keeping your other settings. This
        connects MCP; the command above also installs the skill.
      </p>
      {installLink && (
        <div className="agent-shortcut">
          <a className="agent-primary stove-focus-ring" href={installLink}>
            Open in {agent.label} ↗
          </a>
          <span className="agent-shortcut-note">
            Review the MCP installation in {agent.label}. If the link does not open, use the
            configuration below.
          </span>
        </div>
      )}
      <CopyBlock label="MCP configuration" text={scope.configuration} />
      <dl className="agent-paths">
        <dt>Skill directory</dt>
        <dd>
          <code>{scope.skill_path}</code>
        </dd>
      </dl>
      {agent.note && <p>{agent.note}</p>}
    </details>
  );
}
