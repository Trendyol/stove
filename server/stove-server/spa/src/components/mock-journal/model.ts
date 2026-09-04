import type { MockInteraction, MockWarning } from "../../api/types";

export type InteractionFilter = "all" | "issues" | "unmatched" | "slow";
export type InspectorTab = "overview" | "request" | "response" | "diagnostics";

type RelatedInteractionSelection =
  | { kind: "none" }
  | { kind: "interaction"; interactionId: MockInteraction["id"] };

export type JournalSelection =
  | { kind: "none" }
  | { kind: "interaction"; interactionId: MockInteraction["id"] }
  | {
      kind: "warning";
      warningId: MockWarning["id"];
      related: RelatedInteractionSelection;
    };

export type JournalInspectorState =
  | { kind: "empty" }
  | { kind: "interaction"; interaction: MockInteraction }
  | {
      kind: "warning";
      warning: MockWarning;
      related: { kind: "none" } | { kind: "interaction"; interaction: MockInteraction };
    };

export interface JournalStats {
  all: number;
  issues: number;
  unmatched: number;
  slow: number;
  matchRate: { kind: "empty" } | { kind: "rate"; value: number };
  slowest: { kind: "none" } | { kind: "duration"; milliseconds: number };
}

export function journalStats(interactions: readonly MockInteraction[]): JournalStats {
  const matched = interactions.filter((interaction) => interaction.matched).length;
  const latencies = interactions.flatMap((interaction) =>
    interaction.latency_ms === null ? [] : [interaction.latency_ms],
  );
  return {
    all: interactions.length,
    issues: interactions.filter(hasInteractionIssue).length,
    unmatched: interactions.filter((interaction) => !interaction.matched).length,
    slow: interactions.filter(isSlowInteraction).length,
    matchRate:
      interactions.length === 0
        ? { kind: "empty" }
        : { kind: "rate", value: Math.round((matched / interactions.length) * 100) },
    slowest:
      latencies.length === 0
        ? { kind: "none" }
        : { kind: "duration", milliseconds: Math.max(...latencies) },
  };
}

export function filterInteractions(
  interactions: readonly MockInteraction[],
  filter: InteractionFilter,
  search: string,
): MockInteraction[] {
  const query = search.trim().toLowerCase();
  return interactions.filter((interaction) => {
    if (filter === "issues" && !hasInteractionIssue(interaction)) return false;
    if (filter === "unmatched" && interaction.matched) return false;
    if (filter === "slow" && !isSlowInteraction(interaction)) return false;
    return !query || searchableInteractionText(interaction).includes(query);
  });
}

export function findRelatedInteraction(
  warning: MockWarning,
  interactions: readonly MockInteraction[],
): MockInteraction | undefined {
  return (
    interactions.find(
      (interaction) => warning.stub_id !== null && interaction.stub_id === warning.stub_id,
    ) ??
    interactions.find(
      (interaction) =>
        warning.target !== null &&
        interaction.target === warning.target &&
        interaction.system === warning.system,
    )
  );
}

export function resolveJournalInspector(
  selection: JournalSelection,
  interactions: readonly MockInteraction[],
  warnings: readonly MockWarning[],
): JournalInspectorState {
  if (selection.kind === "none") return { kind: "empty" };
  if (selection.kind === "interaction") {
    const interaction = interactions.find((candidate) => candidate.id === selection.interactionId);
    return interaction ? { kind: "interaction", interaction } : { kind: "empty" };
  }

  const warning = warnings.find((candidate) => candidate.id === selection.warningId);
  if (!warning) return { kind: "empty" };
  if (selection.related.kind === "none") {
    return { kind: "warning", warning, related: { kind: "none" } };
  }
  const relatedInteractionId = selection.related.interactionId;
  const interaction = interactions.find((candidate) => candidate.id === relatedInteractionId);
  return {
    kind: "warning",
    warning,
    related: interaction ? { kind: "interaction", interaction } : { kind: "none" },
  };
}

export function hasInteractionIssue(interaction: MockInteraction): boolean {
  if (!interaction.matched || interaction.fault || interaction.near_misses.length > 0) return true;
  if (/^[45]\d\d$/.test(interaction.status)) return true;
  return ["ERROR", "DEADLINE_EXCEEDED", "CANCELLED", "INTERNAL", "UNKNOWN"].includes(
    interaction.status.toUpperCase(),
  );
}

export function isSlowInteraction(interaction: MockInteraction): boolean {
  return (interaction.latency_ms ?? 0) >= 500;
}

export function attributionLabel(attribution: string): string {
  const labels: Record<string, string> = {
    PROVEN_STUB: "Proven by stub",
    PROVEN_HEADER: "Proven by header",
    PROVEN_BAGGAGE: "Proven by baggage",
    UNATTRIBUTED: "Unattributed",
  };
  return labels[attribution] ?? humanize(attribution);
}

export function humanize(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
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
