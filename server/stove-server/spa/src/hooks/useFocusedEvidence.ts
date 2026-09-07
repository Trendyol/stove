import { skipToken, useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { evidenceKeys } from "../api/evidence-queries";
import type { EvidenceFocus } from "../utils/location";

export function useFocusedEvidence(
  runId: string,
  testId: string | undefined,
  focus: EvidenceFocus | undefined,
  context: number,
  running: boolean,
) {
  return useQuery({
    queryKey: evidenceKeys.focus(runId, testId, focus, context),
    queryFn: focus
      ? ({ signal }) => api.getFocusedEvidence(runId, testId, focus, context, signal)
      : skipToken,
    retry: false,
    staleTime: 5000,
    refetchInterval: running ? 5000 : false,
  });
}
