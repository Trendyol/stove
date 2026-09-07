import type { MockInteraction, MockWarning } from "../../api/types";

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

export function findRelatedInteraction(
  warning: MockWarning,
  interactions: readonly MockInteraction[],
): MockInteraction | undefined {
  return interactions.find(
    (interaction) =>
      warning.stub_id !== null &&
      interaction.stub_id === warning.stub_id &&
      interaction.run_id === warning.run_id &&
      interaction.test_id === warning.test_id,
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

export function selectedInteraction(state: JournalInspectorState): MockInteraction | undefined {
  if (state.kind === "interaction") return state.interaction;
  if (state.kind === "warning" && state.related.kind === "interaction") {
    return state.related.interaction;
  }
  return undefined;
}
