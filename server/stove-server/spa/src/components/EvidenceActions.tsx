import { useFocusedEvidenceState } from "../hooks/FocusedEvidenceProvider";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import { CopyEvidenceLink } from "./CopyEvidenceLink";

export function EvidenceActions() {
  const navigation = useEvidenceNavigation();
  const query = useFocusedEvidenceState();
  const hasMoreContext = query?.data?.has_more_before || query?.data?.has_more_after;
  if (!navigation) return null;
  return (
    <div className="evidence-link-actions">
      <CopyEvidenceLink />
      {navigation.focus && !navigation.full && (
        <>
          {navigation.focus.kind !== "snapshot" && (
            <button
              type="button"
              onClick={navigation.moreContext}
              disabled={!hasMoreContext || navigation.context >= 100}
            >
              Show more context
            </button>
          )}
          <button type="button" onClick={navigation.showFull}>
            {navigation.testId ? "Show full test" : "Show full run"}
          </button>
        </>
      )}
    </div>
  );
}
