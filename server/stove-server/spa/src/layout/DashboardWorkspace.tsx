import { useLayoutEffect, useRef, useState } from "react";
import type { TestBrowserState } from "../api/test-queries";
import type { AppSummary, Run, RunMetadataFacet, Test } from "../api/types";
import { ConnectAgentButton } from "../components/agent-connection/AgentConnectionProvider";
import { DrillDownPanel, type ProfileView } from "../components/DrillDownPanel";
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
import type { RunPickerFeedback } from "./sidebar/RunPicker";
import { TestDetail } from "./TestDetail";

interface DashboardWorkspaceProps {
  topFilter: TopFilterPickerProps;
  apps: AppSummary[];
  activeApp: string | undefined;
  mismatchedApps: string[];
  runs: Run[];
  metadataOptions: RunMetadataFacet[];
  runPickerFeedback?: RunPickerFeedback;
  hasOlderRuns: boolean;
  loadingOlderRuns: boolean;
  onLoadOlderRuns: () => void;
  selectedRunId: string | undefined;
  metadataFilter: MetadataFilter;
  scopedMetadata: MetadataFilter | null;
  latestRun: Run | undefined;
  tests: Test[];
  testBrowser: TestBrowserState;
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
  metadataOptions,
  runPickerFeedback,
  hasOlderRuns,
  loadingOlderRuns,
  onLoadOlderRuns,
  selectedRunId,
  metadataFilter,
  scopedMetadata,
  latestRun,
  tests,
  testBrowser,
  selectedTest,
  liveConnected,
  onSelectApp,
  onSelectRun,
  onMetadataFilterChange,
  onSelectTest,
}: DashboardWorkspaceProps) {
  const [profiles, setProfiles] = useState<{
    appName: string;
    runId?: string;
    view: ProfileView;
    correlation?: ProfileCorrelation;
  }>();
  const selectedProfiles =
    profiles &&
    profiles.appName === activeApp &&
    (profiles.view.kind !== "run" || profiles.runId === latestRun?.id)
      ? profiles
      : undefined;
  const phone = usePhoneNavigation();
  const isPhone = useIsPhone();
  const phoneLevel = phone?.level === "profiles" && !selectedProfiles ? "tests" : phone?.level;
  const openProfilePane = !isPhone || phoneLevel === "profiles" ? selectedProfiles : undefined;
  const correlation =
    openProfilePane?.runId === latestRun?.id ? openProfilePane?.correlation : undefined;
  const profileTrigger = useRef<HTMLButtonElement | undefined>(undefined);
  const workspaceRef = useRef<HTMLDivElement>(null);
  const lastLevel = useRef(phoneLevel);
  useLayoutEffect(() => {
    if (!isPhone) return;
    if (phone?.testId && phone.testId !== selectedTest?.id) onSelectTest(phone.testId);
    if (lastLevel.current !== phoneLevel) {
      if (phoneLevel === "tests") {
        const trigger = lastLevel.current === "profiles" ? profileTrigger.current : undefined;
        if (trigger?.isConnected) trigger.focus();
        else
          workspaceRef.current
            ?.querySelector<HTMLElement>('.stove-test-item[aria-current="true"]')
            ?.focus({ preventScroll: true });
      }
      if (
        phoneLevel === "test" &&
        (lastLevel.current === "tests" || lastLevel.current === "profiles")
      )
        workspaceRef.current?.querySelector<HTMLElement>("h1")?.focus();
    }
    lastLevel.current = phoneLevel;
  }, [isPhone, phoneLevel, phone?.testId, selectedTest?.id, onSelectTest]);

  const openProfiles = (trigger?: HTMLButtonElement, correlation?: ProfileCorrelation) => {
    if (!activeApp || !latestRun) return;
    profileTrigger.current = trigger;
    setProfiles({ appName: activeApp, runId: latestRun.id, view: { kind: "run" }, correlation });
    if (isPhone && phoneLevel !== "profiles") phone?.go({ level: "profiles" });
  };
  return (
    <DrillDownNavigationProvider
      value={(runId, selection) => {
        if (runId !== latestRun?.id) return;
        openProfiles(undefined, selection);
      }}
    >
      <div ref={workspaceRef} className="stove-workspace" data-phone-screen={phoneLevel}>
        <Sidebar
          topFilter={topFilter}
          apps={apps}
          mismatchedApps={mismatchedApps}
          selectedApp={activeApp}
          onSelectApp={onSelectApp}
          runs={runs}
          metadataOptions={metadataOptions}
          runPickerFeedback={runPickerFeedback}
          hasOlderRuns={hasOlderRuns}
          loadingOlderRuns={loadingOlderRuns}
          onLoadOlderRuns={onLoadOlderRuns}
          selectedRunId={selectedRunId}
          onSelectRun={onSelectRun}
          metadataFilter={metadataFilter}
          onMetadataFilterChange={onMetadataFilterChange}
          run={latestRun}
          tests={tests}
          testBrowser={testBrowser}
          selectedTestId={selectedTest?.id}
          profilesSelected={Boolean(openProfilePane)}
          onSelectProfiles={openProfiles}
          onSelectTest={(testId) => {
            setProfiles(undefined);
            onSelectTest(testId);
            if (isPhone) phone?.go({ level: "test", testId });
          }}
        />
        {openProfilePane && (
          <DrillDownPanel
            key={openProfilePane.appName}
            appName={openProfilePane.appName}
            run={latestRun}
            metadata={scopedMetadata}
            tests={tests}
            view={openProfilePane.view}
            onViewChange={(view) =>
              setProfiles({
                ...openProfilePane,
                runId: latestRun?.id,
                view,
                correlation,
              })
            }
            correlation={correlation}
            onClearCorrelation={() => setProfiles({ ...openProfilePane, correlation: undefined })}
            onClose={() => {
              if (isPhone && phone && profileTrigger.current?.isConnected) {
                phone.back();
              } else {
                setProfiles(undefined);
                if (isPhone) phone?.go({ level: "tests" }, true);
                else profileTrigger.current?.focus();
              }
            }}
          />
        )}
        {!openProfilePane && latestRun && selectedTest && (
          <TestDetail runId={latestRun.id} test={selectedTest} liveConnected={liveConnected} />
        )}
        {!openProfilePane && !(latestRun && selectedTest) && (
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
