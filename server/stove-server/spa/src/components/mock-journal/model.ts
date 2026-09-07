import type { MockInteraction, MockWarning } from "../../api/types";

export interface JournalRecords {
  interactions: MockInteraction[];
  warnings: MockWarning[];
  ambientInteractions: MockInteraction[];
  ambientWarnings: MockWarning[];
}

export type InteractionFilter = "all" | "issues" | "unmatched" | "slow";

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

export function hasInteractionIssue(interaction: MockInteraction): boolean {
  if (!interaction.matched || interaction.fault || interaction.near_misses.length > 0) return true;
  if (/^[45]\d\d$/.test(interaction.status)) return true;
  return ["ERROR", "DEADLINE_EXCEEDED", "CANCELLED", "INTERNAL", "UNKNOWN"].includes(
    interaction.status.toUpperCase(),
  );
}

function isSlowInteraction(interaction: MockInteraction): boolean {
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
