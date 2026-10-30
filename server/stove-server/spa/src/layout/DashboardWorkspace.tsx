import { useLayoutEffect, useRef, useState } from "react";
import type { AppSummary, Run, Test } from "../api/types";
import { ConnectAgentButton } from "../components/agent-connection/AgentConnectionProvider";
import { DrillDownPanel } from "../components/DrillDownPanel";
import type { TopFilterPickerProps } from "../components/TopFilterPicker";
import {
  DrillDownNavigationProvider,
  type ProfileCorrelation,
} from "../hooks/useDrillDownNavigation";
import {
  PhoneNavigationProvider,
  useIsPhone,
  usePhoneNavigation,
} from "../hooks/usePhoneNavigation";
import type { MetadataFilter } from "../utils/metadata-filter";
import { Sidebar } from "./Sidebar";
import { TestDetail } from "./TestDetail";

interface DashboardWorkspaceProps {
  topFilter: TopFilterPickerProps;
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

export function DashboardWorkspace(props: DashboardWorkspaceProps) {
  return (
    <PhoneNavigationProvider scope={props.selectedRunId ?? "empty"}>
      <DashboardWorkspaceContent {...props} />
    </PhoneNavigationProvider>
  );
}
function DashboardWorkspaceContent({
  topFilter,
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
  const [profilesRun, setProfilesRun] = useState<string>();
  const [correlation, setCorrelation] = useState<ProfileCorrelation>();
  const showProfiles = profilesRun !== undefined && profilesRun === latestRun?.id;
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
  return (
    <DrillDownNavigationProvider
      value={(runId, selection) => {
        if (runId !== latestRun?.id) return;
        setCorrelation(selection);
        setProfilesRun(runId);
        if (isPhone) phone?.go({ level: "test" });
      }}
    >
      <div ref={workspaceRef} className="stove-workspace" data-phone-screen={phone?.level}>
        <Sidebar
          topFilter={topFilter}
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
          profilesSelected={showProfiles}
          onSelectProfiles={() => {
            setCorrelation(undefined);
            setProfilesRun(latestRun?.id);
            if (isPhone) phone?.go({ level: "test" });
          }}
          onSelectTest={(testId) => {
            setProfilesRun(undefined);
            onSelectTest(testId);
            if (isPhone) phone?.go({ level: "test", testId });
          }}
        />
        {latestRun && showProfiles && (
          <DrillDownPanel
            key={latestRun.id}
            run={latestRun}
            tests={tests}
            correlation={correlation}
            onClearCorrelation={() => setCorrelation(undefined)}
            onClose={() => {
              setProfilesRun(undefined);
              if (isPhone) phone?.go({ level: "tests" });
            }}
          />
        )}
        {!showProfiles && latestRun && selectedTest && (
          <TestDetail runId={latestRun.id} test={selectedTest} liveConnected={liveConnected} />
        )}
        {!showProfiles && !(latestRun && selectedTest) && (
          <WorkspaceEmpty
            waitingForFirstRun={apps.length === 0}
            filtered={topFilter.value !== null}
          />
        )}
      </div>
    </DrillDownNavigationProvider>
  );
}

function WorkspaceEmpty({
  waitingForFirstRun,
  filtered,
}: {
  waitingForFirstRun: boolean;
  filtered: boolean;
}) {
  const { title, description } = workspaceEmptyMessage(waitingForFirstRun, filtered);
  let label = "Evidence workspace";
  if (filtered) label = "Filtered workspace";
  else if (waitingForFirstRun) label = "Listening for a run";

  return (
    <div className="workspace-empty">
      <div className="workspace-empty-mark">
        <span />
        <span />
        <span />
      </div>
      <div className="stove-kicker">{label}</div>
      <h1>{title}</h1>
      <p>{description}</p>
      {waitingForFirstRun && !filtered && (
        <ConnectAgentButton className="agent-primary stove-focus-ring" />
      )}
    </div>
  );
}

function workspaceEmptyMessage(waitingForFirstRun: boolean, filtered: boolean) {
  if (!waitingForFirstRun) {
    return {
      title: "Choose a test to inspect",
      description: "Select a test from the run navigator to open its evidence dossier.",
    };
  }
  if (filtered) {
    return {
      title: "No applications match this value",
      description: "Choose another value or All values to explore other applications.",
    };
  }
  return {
    title: "Waiting for the first signal",
    description:
      "The dashboard will assemble the run as test, trace, state and mock events arrive.",
  };
}
