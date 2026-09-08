import { useCallback, useMemo, useState } from "react";
import type { Test } from "../api/types";
import { ErrorDialog } from "../components/ErrorDialog";
import { EvidenceActions } from "../components/EvidenceActions";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import { isRunning } from "../utils/status";
import { type Tab, TabBar, type TabDef } from "./detail/TabBar";
import { type TabSummary, TestDetailTab } from "./detail/TestDetailTabs";
import { TestHeader } from "./detail/TestHeader";

const TAB_DEFINITIONS: readonly TabDef[] = [
  { id: "timeline", label: "Evidence", icon: "activity" },
  { id: "mocks", label: "Mock journal", icon: "mock" },
  { id: "trace", label: "Trace", icon: "trace" },
  { id: "snapshots", label: "State", icon: "snapshot" },
  { id: "flow", label: "Flow", icon: "flow" },
];

interface TestDetailProps {
  runId: string;
  test: Test;
  liveConnected: boolean;
}

export function TestDetail(props: TestDetailProps) {
  return <TestDetailContent key={JSON.stringify([props.runId, props.test.id])} {...props} />;
}
function TestDetailContent({ runId, test, liveConnected }: TestDetailProps) {
  const navigation = useEvidenceNavigation();
  const [localTab, setLocalTab] = useState<Tab>("timeline");
  const [localErrorOpen, setLocalErrorOpen] = useState(false);
  const tab = navigation?.tab ?? localTab;
  const setTab = navigation?.selectTab ?? setLocalTab;
  const errorOpen = navigation?.errorOpen ?? localErrorOpen;
  const openError = navigation?.openError ?? (() => setLocalErrorOpen(true));
  const closeError = navigation?.clear ?? (() => setLocalErrorOpen(false));
  const [summaries, setSummaries] = useState<Partial<Record<Tab, TabSummary>>>({});

  const updateSummary = useCallback((summaryTab: Tab, summary: TabSummary) => {
    setSummaries((current) => {
      const previous = current[summaryTab];
      if (previous?.count === summary.count && previous.attention === summary.attention) {
        return current;
      }
      return { ...current, [summaryTab]: summary };
    });
  }, []);

  const tabs = useMemo(
    () => TAB_DEFINITIONS.map((definition) => ({ ...definition, ...summaries[definition.id] })),
    [summaries],
  );

  const testError = test.error;

  return (
    <main className="test-detail">
      <div className="test-detail-header">
        <TestHeader test={test} liveConnected={liveConnected} />
        <EvidenceActions />
        {testError && (
          <button
            type="button"
            onClick={openError}
            className="test-error-strip"
            title="Click to view full error"
          >
            <span>Failure</span>
            {testError}
          </button>
        )}
        {errorOpen && testError && <ErrorDialog error={testError} onClose={closeError} />}
        {errorOpen && !testError && (
          <div role="alert" className="evidence-scope-note">
            The cited test error is unavailable. This test has no recorded error.
            <button type="button" onClick={closeError}>
              Show test
            </button>
          </div>
        )}
        <TabBar tabs={tabs} active={tab} onSelect={setTab} />
      </div>

      <div className={`test-detail-body ${tab === "flow" ? "is-flow" : ""}`}>
        <TestDetailTab
          tab={tab}
          runId={runId}
          testId={test.id}
          testRunning={isRunning(test.status)}
          liveConnected={liveConnected}
          onSelectTab={setTab}
          onSummary={updateSummary}
        />
      </div>
    </main>
  );
}
