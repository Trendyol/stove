import { useLayoutEffect, useRef } from "react";
import type { AppSummary, Run, Test } from "../api/types";
import {
  PhoneNavigationProvider,
  useIsPhone,
  usePhoneNavigation,
} from "../hooks/usePhoneNavigation";
import type { MetadataFilter } from "../utils/metadata-filter";
import { Sidebar } from "./Sidebar";
import { TestDetail } from "./TestDetail";

interface DashboardWorkspaceProps {
  apps: AppSummary[];
  activeApp: string | undefined;
  mismatchedApps: string[];
  runs: Run[];
  allRuns: Run[];
  selectedRunId: string | undefined;
  metadataFilter: MetadataFilter;
  latestRun: Run | undefined;
  tests: Test[];
  selectedTest: Test | undefined;
  liveConnected: boolean;
  onSelectApp: (appName: string) => void;
  onSelectRun: (runId: string) => void;
  onMetadataFilterChange: (metadata: MetadataFilter) => void;
  onSelectTest: (testId: string) => void;
}

type WorkspaceContent =
  | { kind: "test"; run: Run; test: Test }
  | { kind: "empty"; waitingForFirstRun: boolean };

export function DashboardWorkspace(props: DashboardWorkspaceProps) {
  return (
    <PhoneNavigationProvider scope={props.selectedRunId ?? "empty"}>
      <DashboardWorkspaceContent {...props} />
    </PhoneNavigationProvider>
  );
}
function DashboardWorkspaceContent({
  apps,
  activeApp,
  mismatchedApps,
  runs,
  allRuns,
  selectedRunId,
  metadataFilter,
  latestRun,
  tests,
  selectedTest,
  liveConnected,
  onSelectApp,
  onSelectRun,
  onMetadataFilterChange,
  onSelectTest,
}: DashboardWorkspaceProps) {
  const phone = usePhoneNavigation();
  const isPhone = useIsPhone();
  const workspaceRef = useRef<HTMLDivElement>(null);
  const lastLevel = useRef(phone?.level);
  useLayoutEffect(() => {
    if (!isPhone) return;
    if (phone?.testId && phone.testId !== selectedTest?.id) onSelectTest(phone.testId);
    if (lastLevel.current !== phone?.level) {
      if (phone?.level === "tests")
        workspaceRef.current
          ?.querySelector<HTMLElement>('.stove-test-item[aria-current="true"]')
          ?.focus({ preventScroll: true });
      if (phone?.level === "test" && lastLevel.current === "tests")
        workspaceRef.current?.querySelector<HTMLElement>("h1")?.focus();
    }
    lastLevel.current = phone?.level;
  }, [isPhone, phone?.level, phone?.testId, selectedTest?.id, onSelectTest]);
  const content = resolveWorkspaceContent(apps, latestRun, selectedTest);

  return (
    <div ref={workspaceRef} className="stove-workspace" data-phone-screen={phone?.level}>
      <Sidebar
        apps={apps}
        mismatchedApps={mismatchedApps}
        selectedApp={activeApp}
        onSelectApp={onSelectApp}
        runs={runs}
        availableRuns={allRuns}
        selectedRunId={selectedRunId}
        onSelectRun={onSelectRun}
        metadataFilter={metadataFilter}
        onMetadataFilterChange={onMetadataFilterChange}
        run={latestRun}
        tests={tests}
        selectedTestId={selectedTest?.id}
        onSelectTest={(testId) => {
          onSelectTest(testId);
          if (isPhone) phone?.go({ level: "test", testId });
        }}
      />
      {content.kind === "test" ? (
        <TestDetail runId={content.run.id} test={content.test} liveConnected={liveConnected} />
      ) : (
        <WorkspaceEmpty waitingForFirstRun={content.waitingForFirstRun} />
      )}
    </div>
  );
}

function resolveWorkspaceContent(
  apps: readonly AppSummary[],
  run: Run | undefined,
  test: Test | undefined,
): WorkspaceContent {
  return run && test
    ? { kind: "test", run, test }
    : { kind: "empty", waitingForFirstRun: apps.length === 0 };
}

function WorkspaceEmpty({ waitingForFirstRun }: { waitingForFirstRun: boolean }) {
  return (
    <div className="workspace-empty">
      <div className="workspace-empty-mark">
        <span />
        <span />
        <span />
      </div>
      <div className="stove-kicker">
        {waitingForFirstRun ? "Listening for a run" : "Evidence workspace"}
      </div>
      <h1>{waitingForFirstRun ? "Waiting for the first signal" : "Choose a test to inspect"}</h1>
      <p>
        {waitingForFirstRun
          ? "The dashboard will assemble the run as test, trace, state and mock events arrive."
          : "Select a test from the run navigator to open its evidence dossier."}
      </p>
    </div>
  );
}
