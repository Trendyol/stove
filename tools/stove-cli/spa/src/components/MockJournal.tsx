import { useMemo, useState } from "react";
import type { MockInteraction, MockWarning } from "../api/types";
import { formatDuration, formatTimestamp } from "../utils/format";
import { tryFormatJson } from "../utils/json";
import { getSystemInfo } from "../utils/systems";
import { Icon } from "./Icon";

interface MockJournalProps {
  interactions: MockInteraction[];
  warnings: MockWarning[];
  ambientInteractions: MockInteraction[];
  ambientWarnings: MockWarning[];
}

type Scope = "test" | "ambient";
type InteractionFilter = "all" | "issues" | "unmatched" | "slow";

export function MockJournal({
  interactions,
  warnings,
  ambientInteractions,
  ambientWarnings,
}: MockJournalProps) {
  const [scope, setScope] = useState<Scope>("test");
  const [filter, setFilter] = useState<InteractionFilter>("all");
  const [search, setSearch] = useState("");
  const scopedInteractions = scope === "test" ? interactions : ambientInteractions;
  const scopedWarnings = scope === "test" ? warnings : ambientWarnings;

  const visibleInteractions = useMemo(() => {
    const query = search.trim().toLowerCase();
    return scopedInteractions.filter((interaction) => {
      if (filter === "issues" && !hasIssue(interaction)) return false;
      if (filter === "unmatched" && interaction.matched) return false;
      if (filter === "slow" && !isSlow(interaction)) return false;
      if (!query) return true;
      return [
        interaction.system,
        interaction.protocol,
        interaction.method,
        interaction.target,
        interaction.status,
        interaction.scenario_name,
        interaction.fault,
      ].some((value) => value?.toLowerCase().includes(query));
    });
  }, [filter, scopedInteractions, search]);

  const issueCount = scopedInteractions.filter(hasIssue).length;
  const matchedCount = scopedInteractions.filter((interaction) => interaction.matched).length;
  const matchedRate =
    scopedInteractions.length === 0
      ? null
      : Math.round((matchedCount / scopedInteractions.length) * 100);
  const slowest = scopedInteractions.reduce<number | null>((current, interaction) => {
    if (interaction.latency_ms == null) return current;
    return current == null ? interaction.latency_ms : Math.max(current, interaction.latency_ms);
  }, null);

  return (
    <div className="mock-workbench">
      <section className="mock-overview">
        <div className="mock-overview-copy">
          <div className="stove-kicker">Mock exchange journal</div>
          <h2>Dependency evidence, without guessed attribution.</h2>
          <p>
            Requests, responses, scenario transitions and diagnostics are kept in the order the mock
            systems observed them.
          </p>
        </div>
        <LatencySparkline interactions={scopedInteractions} />
        <section className="mock-metrics" aria-label="Mock journal summary">
          <SignalMetric label="Exchanges" value={String(scopedInteractions.length)} />
          <SignalMetric
            label="Matched"
            value={matchedRate == null ? "—" : `${matchedRate}%`}
            tone={matchedRate != null && matchedRate < 100 ? "warn" : "good"}
          />
          <SignalMetric
            label="Issues"
            value={String(issueCount)}
            tone={issueCount > 0 ? "bad" : "good"}
          />
          <SignalMetric label="Slowest" value={slowest == null ? "—" : formatDuration(slowest)} />
        </section>
      </section>

      <div className="mock-scope-bar">
        <div className="mock-scope-tabs" role="tablist" aria-label="Mock evidence scope">
          <ScopeButton
            active={scope === "test"}
            count={interactions.length + warnings.length}
            label="This test"
            onClick={() => setScope("test")}
          />
          <ScopeButton
            active={scope === "ambient"}
            count={ambientInteractions.length + ambientWarnings.length}
            label="Run ambient"
            onClick={() => setScope("ambient")}
          />
        </div>
        <span className="mock-scope-note">
          {scope === "test"
            ? "Proven to belong to this test"
            : "Observed during the run, intentionally unattributed"}
        </span>
      </div>

      {scopedWarnings.length > 0 && (
        <section className="mock-warning-lane" aria-label="Mock warnings">
          <div className="mock-lane-label">
            <span>
              <Icon name="warning" className="h-3.5 w-3.5" />
              Diagnostic signals
            </span>
            <span>{scopedWarnings.length}</span>
          </div>
          <div className="mock-warning-grid">
            {scopedWarnings.map((warning) => (
              <WarningCard key={warning.id} warning={warning} />
            ))}
          </div>
        </section>
      )}

      <section className="mock-exchange-lane" aria-label="Mock interactions">
        <div className="mock-toolbar">
          <fieldset className="mock-filters">
            <legend className="sr-only">Filter exchanges</legend>
            {(["all", "issues", "unmatched", "slow"] as const).map((value) => (
              <button
                type="button"
                key={value}
                className={filter === value ? "is-active" : ""}
                onClick={() => setFilter(value)}
              >
                {value}
              </button>
            ))}
          </fieldset>
          <label className="mock-search">
            <Icon name="search" className="h-3.5 w-3.5" />
            <span className="sr-only">Search mock exchanges</span>
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search target, status, scenario…"
            />
          </label>
        </div>

        {visibleInteractions.length > 0 ? (
          <div className="mock-exchange-list">
            {visibleInteractions.map((interaction, index) => (
              <InteractionCard
                key={interaction.id}
                interaction={interaction}
                sequence={index + 1}
              />
            ))}
          </div>
        ) : (
          <MockEmptyState scope={scope} filtered={scopedInteractions.length > 0} />
        )}
      </section>
    </div>
  );
}

function ScopeButton({
  active,
  count,
  label,
  onClick,
}: {
  active: boolean;
  count: number;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      className={active ? "is-active" : ""}
      onClick={onClick}
    >
      {label}
      <span>{count}</span>
    </button>
  );
}

function SignalMetric({
  label,
  value,
  tone = "neutral",
}: {
  label: string;
  value: string;
  tone?: "neutral" | "good" | "warn" | "bad";
}) {
  return (
    <div className={`mock-metric is-${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function LatencySparkline({ interactions }: { interactions: MockInteraction[] }) {
  const values = interactions.slice(-18).map((interaction) => interaction.latency_ms ?? 0);
  const peak = Math.max(...values, 1);

  return (
    <div className="mock-pulse" role="img" aria-label="Recent mock interaction latency">
      <span className="mock-pulse-label">Latency pulse</span>
      <div className="mock-pulse-bars" aria-hidden="true">
        {values.length > 0 ? (
          values.map((value, index) => (
            <span
              // Position is stable for the ordered, bounded pulse window.
              key={`${index}-${value}`}
              className={value >= 500 ? "is-hot" : ""}
              style={{ height: `${Math.max(14, (value / peak) * 100)}%` }}
            />
          ))
        ) : (
          <span className="is-empty" />
        )}
      </div>
    </div>
  );
}

function WarningCard({ warning }: { warning: MockWarning }) {
  const system = getSystemInfo(warning.system);
  return (
    <article className="mock-warning-card">
      <div className="mock-warning-icon">
        <Icon name="warning" className="h-4 w-4" />
      </div>
      <div className="min-w-0">
        <div className="mock-warning-heading">
          <span>{humanize(warning.kind)}</span>
          <time>{formatTimestamp(warning.timestamp)}</time>
        </div>
        <p>{warning.message}</p>
        <div className="mock-warning-meta">
          <span style={{ color: system.color }}>{system.icon}</span>
          <span>{warning.system}</span>
          {warning.target && <code>{warning.target}</code>}
        </div>
      </div>
    </article>
  );
}

function InteractionCard({
  interaction,
  sequence,
}: {
  interaction: MockInteraction;
  sequence: number;
}) {
  const [expanded, setExpanded] = useState(false);
  const system = getSystemInfo(interaction.system);
  const issue = hasIssue(interaction);
  const tone = !interaction.matched ? "unmatched" : issue ? "issue" : "matched";
  const hasDetails =
    interaction.request_body != null ||
    interaction.response_body != null ||
    interaction.near_misses.length > 0 ||
    interaction.stub_id != null ||
    interaction.trace_id != null;

  return (
    <article className={`mock-interaction-card is-${tone}`}>
      <button
        type="button"
        className="mock-interaction-summary"
        aria-expanded={expanded}
        onClick={() => hasDetails && setExpanded((current) => !current)}
      >
        <span className="mock-sequence">{String(sequence).padStart(2, "0")}</span>
        <span
          className="mock-system-orbit"
          style={{ "--system-color": system.color } as React.CSSProperties}
        >
          <span>{system.icon}</span>
        </span>
        <span className="mock-request">
          <span className="mock-request-topline">
            <code>{interaction.method}</code>
            <span>{interaction.protocol}</span>
            <time>{formatTimestamp(interaction.timestamp)}</time>
          </span>
          <strong title={interaction.target}>{interaction.target}</strong>
          <span className="mock-evidence-tags">
            <EvidenceTag label={attributionLabel(interaction.attribution)} />
            {interaction.configured_delay_ms != null && (
              <EvidenceTag label={`delay ${formatDuration(interaction.configured_delay_ms)}`} />
            )}
            {interaction.client_deadline_ms != null && (
              <EvidenceTag label={`deadline ${formatDuration(interaction.client_deadline_ms)}`} />
            )}
            {interaction.fault && <EvidenceTag label={humanize(interaction.fault)} tone="bad" />}
          </span>
        </span>
        <span className="mock-route-line" aria-hidden="true">
          <span />
        </span>
        <span className="mock-response">
          <strong>{interaction.status || (interaction.matched ? "MATCHED" : "UNMATCHED")}</strong>
          <span>
            {interaction.latency_ms == null
              ? "latency unknown"
              : formatDuration(interaction.latency_ms)}
          </span>
        </span>
        <Icon
          name="chevron"
          className={`mock-expand-icon h-4 w-4 ${expanded ? "is-expanded" : ""} ${
            hasDetails ? "" : "is-hidden"
          }`}
        />
      </button>

      {(interaction.scenario_name ||
        interaction.scenario_state ||
        interaction.next_scenario_state) && (
        <div className="mock-scenario-rail">
          <span className="mock-scenario-name">{interaction.scenario_name ?? "Scenario"}</span>
          <ScenarioState value={interaction.scenario_state ?? "STARTED"} />
          <span className="mock-scenario-arrow">→</span>
          <ScenarioState value={interaction.next_scenario_state ?? "stable"} terminal />
        </div>
      )}

      {expanded && hasDetails && (
        <div className="mock-interaction-details">
          <div className="mock-detail-meta">
            {interaction.stub_id && (
              <span>
                Stub <code>{interaction.stub_id}</code>
              </span>
            )}
            {interaction.trace_id && (
              <span>
                Trace <code>{interaction.trace_id}</code>
              </span>
            )}
          </div>
          <div className="mock-body-grid">
            <ExchangeBody
              label="Request"
              body={interaction.request_body}
              truncated={interaction.request_body_truncated}
            />
            <ExchangeBody
              label="Response"
              body={interaction.response_body}
              truncated={interaction.response_body_truncated}
            />
          </div>
          {interaction.near_misses.length > 0 && (
            <div className="mock-near-misses">
              <div className="stove-kicker">Closest stub candidates</div>
              {interaction.near_misses.map((nearMiss, index) => (
                <pre key={`${index}-${nearMiss}`}>{nearMiss}</pre>
              ))}
            </div>
          )}
        </div>
      )}
    </article>
  );
}

function EvidenceTag({ label, tone = "neutral" }: { label: string; tone?: "neutral" | "bad" }) {
  return <span className={`mock-evidence-tag is-${tone}`}>{label}</span>;
}

function ScenarioState({ value, terminal = false }: { value: string; terminal?: boolean }) {
  return <code className={terminal ? "is-terminal" : ""}>{value}</code>;
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
    <div className="mock-body">
      <div>
        <span>{label}</span>
        {truncated && <em>truncated</em>}
      </div>
      {body ? <pre>{tryFormatJson(body)}</pre> : <p>No body captured</p>}
    </div>
  );
}

function MockEmptyState({ scope, filtered }: { scope: Scope; filtered: boolean }) {
  return (
    <div className="mock-empty">
      <span className="mock-empty-orbit">
        <Icon name="mock" className="h-5 w-5" />
      </span>
      <div>
        <strong>{filtered ? "No exchanges match this lens" : "No mock exchanges captured"}</strong>
        <p>
          {filtered
            ? "Try a broader filter or clear the search."
            : scope === "ambient"
              ? "There is no unattributed mock evidence in this run."
              : "This test did not communicate with a journal-enabled mock system."}
        </p>
      </div>
    </div>
  );
}

function hasIssue(interaction: MockInteraction): boolean {
  if (!interaction.matched || interaction.fault || interaction.near_misses.length > 0) return true;
  if (/^[45]\d\d$/.test(interaction.status)) return true;
  return ["ERROR", "DEADLINE_EXCEEDED", "CANCELLED", "INTERNAL", "UNKNOWN"].includes(
    interaction.status.toUpperCase(),
  );
}

function isSlow(interaction: MockInteraction): boolean {
  return (interaction.latency_ms ?? 0) >= 500;
}

function attributionLabel(attribution: string): string {
  const labels: Record<string, string> = {
    PROVEN_STUB: "proven by stub",
    PROVEN_HEADER: "proven by header",
    UNATTRIBUTED: "unattributed",
  };
  return labels[attribution] ?? humanize(attribution);
}

function humanize(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
