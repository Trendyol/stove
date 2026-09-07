import { createContext, useContext } from "react";
import { useRequiredEvidenceNavigation } from "./useEvidenceNavigation";
import { useFocusedEvidence } from "./useFocusedEvidence";

const Context = createContext<ReturnType<typeof useFocusedEvidence> | undefined>(undefined);
export const useFocusedEvidenceState = () => useContext(Context);

/** One query observer serves the header, ledger and any open inspector. */
export function FocusedEvidenceProvider({
  running,
  liveConnected,
  children,
}: {
  running: boolean;
  liveConnected: boolean;
  children: React.ReactNode;
}) {
  const navigation = useRequiredEvidenceNavigation();
  const query = useFocusedEvidence(
    navigation.runId,
    navigation.testId,
    navigation.focus,
    navigation.context,
    running && !liveConnected,
  );
  return <Context.Provider value={query}>{children}</Context.Provider>;
}
