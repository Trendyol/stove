import { useEffect, useMemo, useRef, useState } from "react";
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
  onOpenTrace: () => void;
}

type InteractionFilter = "all" | "issues" | "unmatched" | "slow";
type InspectorTab = "overview" | "request" | "response" | "diagnostics";

export function MockJournal({
  interactions,
  warnings,
  ambientInteractions,
  ambientWarnings,
  onOpenTrace,
}: MockJournalProps) {
  const [includeAmbient, setIncludeAmbient] = useState(false);
  const [filter, setFilter] = useState<InteractionFilter>("all");
  const [search, setSearch] = useState("");
  const [selectedId, setSelectedId] = useState<MockInteraction["id"] | null>(null);
  const [selectedWarningId, setSelectedWarningId] = useState<MockWarning["id"] | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const allInteractions = useMemo(
    () =>
      (includeAmbient ? [...interactions, ...ambientInteractions] : interactions).sort(
        (left, right) => left.timestamp.localeCompare(right.timestamp),
      ),
    [ambientInteractions, includeAmbient, interactions],
  );
  const allWarnings = useMemo(
    () =>
      (includeAmbient ? [...warnings, ...ambientWarnings] : warnings).sort((left, right) =>
        left.timestamp.localeCompare(right.timestamp),
      ),
    [ambientWarnings, includeAmbient, warnings],
  );

  useEffect(() => {
    if (
      selectedId != null &&
      allInteractions.some((interaction) => interaction.id === selectedId)
    ) {
      return;
    }
    setSelectedId(allInteractions.find(hasIssue)?.id ?? allInteractions[0]?.id ?? null);
  }, [allInteractions, selectedId]);

  useEffect(() => {
    const focusSearch = (event: KeyboardEvent) => {
      if (event.key !== "/" || isEditableTarget(event.target)) return;
      event.preventDefault();
      searchRef.current?.focus();
    };
    window.addEventListener("keydown", focusSearch);
    return () => window.removeEventListener("keydown", focusSearch);
  }, []);

  const counts = {
    all: allInteractions.length,
    issues: allInteractions.filter(hasIssue).length,
    unmatched: allInteractions.filter((interaction) => !interaction.matched).length,
    slow: allInteractions.filter(isSlow).length,
  };
  const matchedCount = allInteractions.filter((interaction) => interaction.matched).length;
  const matchedRate =
    allInteractions.length === 0 ? null : Math.round((matchedCount / allInteractions.length) * 100);
  const slowest = allInteractions.reduce<number | null>((current, interaction) => {
    if (interaction.latency_ms == null) return current;
    return current == null ? interaction.latency_ms : Math.max(current, interaction.latency_ms);
  }, null);

  const sequenceById = useMemo(
    () => new Map(allInteractions.map((interaction, index) => [interaction.id, index + 1])),
    [allInteractions],
  );
  const visibleInteractions = useMemo(() => {
    const query = search.trim().toLowerCase();
    return allInteractions.filter((interaction) => {
      if (filter === "issues" && !hasIssue(interaction)) return false;
      if (filter === "unmatched" && interaction.matched) return false;
      if (filter === "slow" && !isSlow(interaction)) return false;
      if (!query) return true;
      return searchableInteractionText(interaction).includes(query);
    });
  }, [allInteractions, filter, search]);

  const selectedInteraction =
    allInteractions.find((interaction) => interaction.id === selectedId) ?? null;
  const selectedWarning = allWarnings.find((warning) => warning.id === selectedWarningId) ?? null;

  const selectWarning = (warning: MockWarning) => {
    setSelectedWarningId(warning.id);
    const related = findRelatedInteraction(warning, allInteractions);
    if (related) {
      setFilter("all");
      setSearch("");
      setSelectedId(related.id);
    } else {
      setSelectedId(null);
    }
  };

  const jumpToFirstIssue = () => {
    const issue = allInteractions.find(hasIssue);
    if (issue) {
      setFilter("all");
      setSearch("");
      setSelectedWarningId(null);
      setSelectedId(issue.id);
    }
  };

  return (
    <div className="mock-workbench">
      <header className="ledger-command-bar mock-command-bar">
        <div className="ledger-command-title">
          <div className="stove-kicker">Dependency evidence</div>
          <h2>Mock ledger</h2>
          <span>
            {allInteractions.length} exchanges · {counts.issues} need attention ·{" "}
            {matchedRate == null ? "—" : `${matchedRate}%`} matched
            {slowest == null ? "" : ` · ${formatDuration(slowest)} slowest`}
          </span>
        </div>
        <div className="ledger-command-actions">
          {counts.issues > 0 && (
            <button type="button" className="ledger-jump-button" onClick={jumpToFirstIssue}>
              <span />
              Jump to first issue
            </button>
          )}
          {ambientInteractions.length + ambientWarnings.length > 0 && (
            <label className="ambient-toggle">
              <input
                type="checkbox"
                checked={includeAmbient}
                onChange={(event) => setIncludeAmbient(event.target.checked)}
              />
              <span />
              Include ambient
              <strong>{ambientInteractions.length + ambientWarnings.length}</strong>
            </label>
          )}
          <label className="ledger-search">
            <Icon name="search" className="h-4 w-4" />
            <span className="sr-only">Search mock exchanges</span>
            <input
              ref={searchRef}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search exchanges"
            />
            <kbd>/</kbd>
          </label>
        </div>
      </header>

      {allWarnings.length > 0 && (
        <section className="warning-ribbon" aria-label="Mock diagnostic signals">
          <div className="warning-ribbon-label">
            <Icon name="warning" className="h-4 w-4" />
            <span>Diagnostic signals</span>
            <strong>{allWarnings.length}</strong>
          </div>
          <div className="warning-ribbon-list">
            {allWarnings.map((warning) => (
              <button
                type="button"
                key={warning.id}
                className={warning.id === selectedWarningId ? "is-selected" : ""}
                onClick={() => selectWarning(warning)}
              >
                <span>{humanize(warning.kind)}</span>
                <strong>{warning.target ?? warning.system}</strong>
                <p>{warning.message}</p>
                <Icon name="chevron" className="h-3.5 w-3.5" />
              </button>
            ))}
          </div>
        </section>
      )}

      <div className="mock-ledger-toolbar">
        <fieldset className="ledger-filter-group">
          <legend className="sr-only">Filter mock exchanges</legend>
          <FilterButton
            active={filter === "all"}
            count={counts.all}
            label="All"
            onClick={() => setFilter("all")}
          />
          <FilterButton
            active={filter === "issues"}
            count={counts.issues}
            label="Needs attention"
            onClick={() => setFilter("issues")}
          />
          <FilterButton
            active={filter === "unmatched"}
            count={counts.unmatched}
            label="Unmatched"
            onClick={() => setFilter("unmatched")}
          />
          <FilterButton
            active={filter === "slow"}
            count={counts.slow}
            label="Slow"
            onClick={() => setFilter("slow")}
          />
        </fieldset>
        <span>
          Showing {visibleInteractions.length} of {allInteractions.length}
        </span>
      </div>

      <div className="mock-ledger-layout">
        <section className="mock-ledger" aria-label="Mock exchanges">
          {visibleInteractions.length > 0 ? (
            visibleInteractions.map((interaction) => (
              <InteractionRow
                key={interaction.id}
                interaction={interaction}
                sequence={sequenceById.get(interaction.id) ?? 0}
                selected={interaction.id === selectedId}
                onSelect={() => {
                  setSelectedWarningId(null);
                  setSelectedId(interaction.id);
                }}
              />
            ))
          ) : (
            <MockEmptyState filtered={allInteractions.length > 0} />
          )}
        </section>

        <InteractionInspector
          interaction={selectedInteraction}
          warning={selectedWarning}
          interactions={allInteractions}
          onSelect={(id) => {
            setSelectedWarningId(null);
            setSelectedId(id);
          }}
          onClose={() => {
            setSelectedId(null);
            setSelectedWarningId(null);
          }}
          onOpenTrace={onOpenTrace}
        />
      </div>
    </div>
  );
}

function InteractionRow({
  interaction,
  sequence,
  selected,
  onSelect,
}: {
  interaction: MockInteraction;
  sequence: number;
  selected: boolean;
  onSelect: () => void;
}) {
  const system = getSystemInfo(interaction.system);
  const issue = hasIssue(interaction);
  const ambient = interaction.test_id == null;
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
        style={{ "--system-color": system.color } as React.CSSProperties}
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
        {interaction.configured_delay_ms != null && (
          <EvidenceTag label={`delay ${formatDuration(interaction.configured_delay_ms)}`} />
        )}
        {interaction.client_deadline_ms != null && (
          <EvidenceTag label={`deadline ${formatDuration(interaction.client_deadline_ms)}`} />
        )}
        {interaction.fault && <EvidenceTag label={humanize(interaction.fault)} tone="bad" />}
      </span>
      <span className={`mock-ledger-status is-${tone}`}>
        <strong>{interaction.status || (interaction.matched ? "MATCHED" : "UNMATCHED")}</strong>
        <small>
          {interaction.latency_ms == null ? "latency —" : formatDuration(interaction.latency_ms)}
        </small>
      </span>
      <Icon name="chevron" className="h-4 w-4" />
    </button>
  );
}

function InteractionInspector({
  interaction,
  warning,
  interactions,
  onSelect,
  onClose,
  onOpenTrace,
}: {
  interaction: MockInteraction | null;
  warning: MockWarning | null;
  interactions: MockInteraction[];
  onSelect: (id: MockInteraction["id"]) => void;
  onClose: () => void;
  onOpenTrace: () => void;
}) {
  const [tab, setTab] = useState<InspectorTab>("overview");

  useEffect(() => {
    setTab(warning ? "diagnostics" : "overview");
  }, [interaction?.id, warning]);

  if (!interaction && !warning) {
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
  const previous = position > 0 ? interactions[position - 1] : null;
  const next =
    position >= 0 && position < interactions.length - 1 ? interactions[position + 1] : null;
  const system = interaction ? getSystemInfo(interaction.system) : null;

  return (
    <aside
      className="ledger-inspector mock-inspector"
      aria-label={
        interaction ? `Exchange details for ${interaction.target}` : "Mock warning details"
      }
    >
      <header className="ledger-inspector-header">
        <div>
          <span className="stove-kicker">Exchange inspector</span>
          <strong>{interaction?.target ?? warning?.target ?? humanize(warning?.kind ?? "")}</strong>
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

      {warning && (
        <div className="inspector-warning-brief">
          <Icon name="warning" className="h-4 w-4" />
          <div>
            <strong>{humanize(warning.kind)}</strong>
            <p>{warning.message}</p>
          </div>
        </div>
      )}

      {interaction && (
        <>
          <div className="inspector-status-line">
            <span className={hasIssue(interaction) ? "is-issue" : "is-success"}>
              {interaction.status || (interaction.matched ? "MATCHED" : "UNMATCHED")}
            </span>
            <strong>
              {interaction.latency_ms == null
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

          <nav className="inspector-tabs" aria-label="Exchange detail sections">
            {(["overview", "request", "response", "diagnostics"] as const).map((value) => (
              <button
                type="button"
                key={value}
                className={tab === value ? "is-active" : ""}
                onClick={() => setTab(value)}
              >
                {value}
                {value === "diagnostics" &&
                  interaction.near_misses.length + (warning ? 1 : 0) > 0 && (
                    <span>{interaction.near_misses.length + (warning ? 1 : 0)}</span>
                  )}
              </button>
            ))}
          </nav>

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
        </>
      )}

      {interaction && (
        <footer className="ledger-inspector-nav">
          <button
            type="button"
            disabled={!previous}
            onClick={() => previous && onSelect(previous.id)}
          >
            ← Previous
          </button>
          <span>
            {position + 1} / {interactions.length}
          </span>
          <button type="button" disabled={!next} onClick={() => next && onSelect(next.id)}>
            Next →
          </button>
        </footer>
      )}
    </aside>
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
        value={interaction.latency_ms == null ? "Unknown" : formatDuration(interaction.latency_ms)}
      />
      {interaction.configured_delay_ms != null && (
        <InspectorDatum
          label="Configured delay"
          value={formatDuration(interaction.configured_delay_ms)}
          tone="warn"
        />
      )}
      {interaction.client_deadline_ms != null && (
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
  warning: MockWarning | null;
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
      {interaction.near_misses.length === 0 && !warning && (
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

function FilterButton({
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
    <button type="button" className={active ? "is-active" : ""} onClick={onClick}>
      {label}
      <span>{count}</span>
    </button>
  );
}

function EvidenceTag({ label, tone = "neutral" }: { label: string; tone?: "neutral" | "bad" }) {
  return <span className={`mock-evidence-tag is-${tone}`}>{label}</span>;
}

function MockEmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="ledger-empty">
      <span className="mock-empty-orbit">
        <Icon name="mock" className="h-5 w-5" />
      </span>
      <div>
        <strong>{filtered ? "No exchanges match this lens" : "No mock exchanges captured"}</strong>
        <p>
          {filtered
            ? "Broaden the filter or clear the search."
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

function findRelatedInteraction(
  warning: MockWarning,
  interactions: MockInteraction[],
): MockInteraction | null {
  return (
    interactions.find(
      (interaction) => warning.stub_id != null && interaction.stub_id === warning.stub_id,
    ) ??
    interactions.find(
      (interaction) =>
        warning.target != null &&
        interaction.target === warning.target &&
        interaction.system === warning.system,
    ) ??
    null
  );
}

function searchableInteractionText(interaction: MockInteraction): string {
  return [
    interaction.system,
    interaction.protocol,
    interaction.method,
    interaction.target,
    interaction.status,
    interaction.attribution,
    interaction.scenario_name,
    interaction.scenario_state,
    interaction.next_scenario_state,
    interaction.fault,
    interaction.request_body,
    interaction.response_body,
    ...interaction.near_misses,
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
}

function attributionLabel(attribution: string): string {
  const labels: Record<string, string> = {
    PROVEN_STUB: "Proven by stub",
    PROVEN_HEADER: "Proven by header",
    PROVEN_BAGGAGE: "Proven by baggage",
    UNATTRIBUTED: "Unattributed",
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

function isEditableTarget(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement ||
    (target instanceof HTMLElement && target.isContentEditable)
  );
}
