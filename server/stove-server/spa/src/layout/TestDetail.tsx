import { useCallback, useMemo, useState } from "react";
import type { Test } from "../api/types";
import { ErrorDialog } from "../components/ErrorDialog";
import { EvidenceActions } from "../components/EvidenceActions";
import { FocusedEvidenceProvider } from "../hooks/FocusedEvidenceProvider";
import {
  EvidenceNavigationProvider,
  useRequiredEvidenceNavigation,
} from "../hooks/useEvidenceNavigation";
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
  return (
    <EvidenceNavigationProvider runId={props.runId} testId={props.test.id}>
      <FocusedEvidenceProvider
        running={isRunning(props.test.status)}
        liveConnected={props.liveConnected}
      >
        <TestDetailContent key={JSON.stringify([props.runId, props.test.id])} {...props} />
      </FocusedEvidenceProvider>
    </EvidenceNavigationProvider>
  );
}
function TestDetailContent({ runId, test, liveConnected }: TestDetailProps) {
  const navigation = useRequiredEvidenceNavigation();
  const tab = navigation.tab;
  const setTab = navigation.selectTab;
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
            onClick={navigation.openError}
            className="test-error-strip"
            title="Click to view full error"
          >
            <span>Failure</span>
            {testError}
          </button>
        )}
        {navigation.errorOpen && testError && (
          <ErrorDialog error={testError} onClose={navigation.clear} />
        )}
        {navigation.errorOpen && !testError && (
          <div role="alert" className="evidence-scope-note">
            The cited test error is unavailable. This test has no recorded error.
            <button type="button" onClick={navigation.clear}>
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
