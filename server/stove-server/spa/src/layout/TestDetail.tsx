import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Test } from "../api/types";
import { ErrorDialog } from "../components/ErrorDialog";
import { EvidenceActions } from "../components/EvidenceActions";
import { EvidenceViewMemoryProvider } from "../components/evidence/EvidenceViewMemory";
import { useEvidenceNavigation } from "../hooks/useEvidenceNavigation";
import { useIsPhone, usePhoneNavigation } from "../hooks/usePhoneNavigation";
import { useRememberedScroll } from "../hooks/useRememberedScroll";
import { isRunning } from "../utils/status";
import { type Tab, TabBar, type TabDef } from "./detail/TabBar";
import { type TabSummary, TestDetailTab } from "./detail/TestDetailTabs";
import { TestHeader } from "./detail/TestHeader";

const TAB_DEFINITIONS: readonly TabDef[] = [
  { id: "timeline", label: "Timeline", icon: "activity" },
  { id: "mocks", label: "Mocks", icon: "mock" },
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
    <EvidenceViewMemoryProvider key={JSON.stringify([props.runId, props.test.id])}>
      <TestDetailContent {...props} />
    </EvidenceViewMemoryProvider>
  );
}
function TestDetailContent({ runId, test, liveConnected }: TestDetailProps) {
  const navigation = useEvidenceNavigation();
  const phone = usePhoneNavigation();
  const isPhone = useIsPhone();
  const [localTab, setLocalTab] = useState<Tab>("timeline");
  const [localTraceId, setLocalTraceId] = useState<string>();
  const [localErrorOpen, setLocalErrorOpen] = useState(false);
  const tab = navigation?.tab ?? (isPhone && phone ? (phone.tab ?? "timeline") : localTab);
  const setTab = (next: Tab) => {
    if (navigation) navigation.selectTab(next);
    else if (isPhone && phone)
      phone.go(
        {
          level: "test",
          testId: test.id,
          tab: next,
          traceId: phone.traceId,
          backLabel: phone.backLabel,
        },
        true,
      );
    else setLocalTab(next);
  };
  const previousTab = useRef(tab);
  useLayoutEffect(() => {
    if (isPhone && previousTab.current !== tab)
      document.querySelector<HTMLElement>(".test-detail h1")?.focus();
    previousTab.current = tab;
  }, [isPhone, tab]);
  const panelRef = useRef<HTMLDivElement>(null);
  useRememberedScroll(panelRef, `panel.${tab}`);
  const openTrace = (traceId: string) => {
    if (navigation) navigation.openTrace(traceId);
    else if (isPhone && phone)
      phone.go({ level: "test", testId: test.id, tab: "trace", traceId, backLabel: "Event" });
    else {
      setLocalTraceId(traceId);
      setLocalTab("trace");
    }
  };
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
        {phone && (
          <button type="button" className="phone-back" onClick={phone.back}>
            ← {phone.backLabel ?? "Tests"}
          </button>
        )}
        <TestHeader test={test} liveConnected={liveConnected} />
        <EvidenceActions />
        {testError && (
          <button
            type="button"
            onClick={openError}
            className="test-error-strip"
            title="Click to view full error"
          >
            <strong>Test failed</strong>
            <span>{testError.split("\n").find((line) => line.trim())}</span>
            <small>View full error →</small>
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

      {TAB_DEFINITIONS.filter((item) => item.id !== tab).map((item) => (
        <div
          key={item.id}
          hidden
          role="tabpanel"
          id={`panel-${item.id}`}
          aria-labelledby={`tab-${item.id}`}
        />
      ))}
      <div
        ref={panelRef}
        role="tabpanel"
        id={`panel-${tab}`}
        aria-labelledby={`tab-${tab}`}
        // biome-ignore lint/a11y/noNoninteractiveTabindex: Tabpanels are keyboard destinations, including empty results.
        tabIndex={0}
        className={`test-detail-body ${tab === "flow" ? "is-flow" : ""}`}
      >
        <TestDetailTab
          tab={tab}
          runId={runId}
          testId={test.id}
          testRunning={isRunning(test.status)}
          liveConnected={liveConnected}
          onClearTrace={() => {
            if (navigation) navigation.clear();
            else if (isPhone && phone)
              phone.go(
                { level: "test", tab: "trace", testId: test.id, backLabel: phone.backLabel },
                true,
              );
            else setLocalTraceId(undefined);
          }}
          onOpenTrace={openTrace}
          traceId={
            navigation ? navigation.traceId : isPhone && phone ? phone.traceId : localTraceId
          }
          onSelectTab={setTab}
          onSummary={updateSummary}
        />
      </div>
    </main>
  );
}
