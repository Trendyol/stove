import { useCallback, useEffect, useMemo, useState } from "react";
import type { Test } from "../api/types";
import { ErrorDialog } from "../components/ErrorDialog";
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

export function TestDetail({ runId, test, liveConnected }: TestDetailProps) {
  const [tab, setTab] = useState<Tab>("timeline");
  const [errorDialogOpen, setErrorDialogOpen] = useState(false);
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

  // biome-ignore lint/correctness/useExhaustiveDependencies: reset local view state for a new test
  useEffect(() => {
    setTab("timeline");
    setErrorDialogOpen(false);
    setSummaries({});
  }, [runId, test.id]);

  const testError = test.error;

  return (
    <main className="test-detail">
      <div className="test-detail-header">
        <TestHeader test={test} liveConnected={liveConnected} />
        {testError && (
          <button
            type="button"
            onClick={() => setErrorDialogOpen(true)}
            className="test-error-strip"
            title="Click to view full error"
          >
            <span>Failure</span>
            {testError}
          </button>
        )}
        {errorDialogOpen && testError && (
          <ErrorDialog error={testError} onClose={() => setErrorDialogOpen(false)} />
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
