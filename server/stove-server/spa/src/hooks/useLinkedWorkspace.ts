import { skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { evidenceKeys, invalidateEvidenceQueries } from "../api/evidence-queries";
import { applyLiveDashboardEvents, invalidateDashboardQueries } from "../api/live-cache";
import { useSSE } from "../api/sse";
import type { EvidenceLocation } from "../utils/location";

export function useLinkedWorkspace(location: EvidenceLocation) {
  const { runId, testId } = location;
  const client = useQueryClient();
  const invalidate = () => {
    invalidateDashboardQueries(client, runId);
    invalidateEvidenceQueries(client, runId);
  };
  const { connected } = useSSE({
    onEvents: (events) => {
      applyLiveDashboardEvents(client, events);
      invalidateEvidenceQueries(client, runId, events);
      // Entry/span correlation can change after either record arrives.
      if (
        events.some(
          (event) =>
            event.run_id === runId &&
            (event.event_type === "entry_recorded" || event.event_type === "span_recorded"),
        )
      ) {
        void client.invalidateQueries({ queryKey: ["spans", runId] });
      }
    },
    onConnect: invalidate,
    onGap: invalidate,
    onOverflow: invalidate,
  });
  const run = useQuery({
    queryKey: evidenceKeys.run(runId),
    queryFn: ({ signal }) => api.getRun(runId, signal),
    retry: false,
    refetchInterval: connected ? false : 5000,
  });
  const test = useQuery({
    queryKey: evidenceKeys.test(runId, testId),
    queryFn: testId ? ({ signal }) => api.getTest(runId, testId, signal) : skipToken,
    retry: false,
    refetchInterval: connected ? false : 5000,
  });
  const tests = useQuery({
    queryKey: evidenceKeys.tests(runId),
    queryFn: !testId && !location.focus ? ({ signal }) => api.getTests(runId, signal) : skipToken,
    retry: false,
    refetchInterval: connected ? false : 5000,
  });
  return { run, test, tests, connected };
}
