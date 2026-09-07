import type { QueryClient, QueryKey } from "@tanstack/react-query";
import type { EvidenceFocus } from "../utils/location";
import type { LiveDashboardEvent } from "./types";

export const evidenceKeys = {
  run: (runId: string) => ["linked", runId, "run"] as const,
  test: (runId: string, testId?: string) => ["linked", runId, "test", testId] as const,
  tests: (runId: string) => ["linked", runId, "tests"] as const,
  focus: (
    runId: string,
    testId: string | undefined,
    focus: EvidenceFocus | undefined,
    context: number,
  ) => ["focus", runId, testId, focus?.kind, focus?.id, context] as const,
};

export function invalidateEvidenceQueries(
  client: QueryClient,
  runId: string,
  events?: readonly LiveDashboardEvent[],
) {
  void client.invalidateQueries({
    predicate: ({ queryKey }) =>
      (queryKey[0] === "linked" || queryKey[0] === "focus") &&
      queryKey[1] === runId &&
      (!events || events.some((event) => affectsEvidenceQuery(queryKey, event))),
  });
}

function affectsEvidenceQuery(key: QueryKey, event: LiveDashboardEvent): boolean {
  if (event.run_id !== key[1]) return false;
  const type = event.event_type;
  if (type === "run_started" || type === "run_ended") return true;
  const owner = event.payload.test_id ?? undefined;
  if (key[0] === "linked") {
    return (
      (type === "test_started" || type === "test_ended") &&
      (key[2] === "tests" || (key[2] === "test" && key[3] === owner))
    );
  }
  if (type === "test_started" || type === "test_ended") return key[2] === owner;
  switch (key[3]) {
    case "entry":
      return type === "entry_recorded" && key[2] === owner;
    case "snapshot":
      return type === "snapshot" && key[2] === owner && key[4] === event.payload.id;
    case "span":
      return type === "span_recorded" || (type === "entry_recorded" && key[2] === owner);
    case "interaction":
    case "warning":
      return (type === "mock_interaction" || type === "mock_warning") && key[2] === owner;
    default:
      return false;
  }
}
