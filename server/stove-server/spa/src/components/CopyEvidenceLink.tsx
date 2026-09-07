import { useState } from "react";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import { appPath, evidencePath } from "../utils/location";

export function CopyEvidenceLink({ runId, testId }: { runId?: string; testId?: string }) {
  const navigation = useEvidenceNavigation();
  const [status, setStatus] = useState("");
  const [manual, setManual] = useState<string>();
  if (!navigation && !runId) return null;
  const copy = async () => {
    const url =
      navigation?.href ?? `${window.location.origin}${appPath(evidencePath(runId ?? "", testId))}`;
    try {
      await navigator.clipboard.writeText(url);
      setStatus("Copied");
    } catch {
      setManual(url);
      setStatus("Select and copy the link");
    }
  };
  return (
    <>
      <button type="button" className="ledger-jump-button" onClick={() => void copy()}>
        Copy link
      </button>
      <span role="status">{status}</span>
      {manual && (
        <input
          aria-label="Evidence link"
          readOnly
          value={manual}
          onFocus={(event) => event.target.select()}
        />
      )}
    </>
  );
}
