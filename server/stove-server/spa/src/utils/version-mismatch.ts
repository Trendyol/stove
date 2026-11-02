import type { AppSummary } from "../api/types";

// Published Stove versions are either a release (`0.28.0`) or a snapshot
// coordinate (`1.0.0.566-SNAPSHOT`). Compare the numeric core so 3-part and
// 4-part builds are both understood, and ignore pre-release/build suffixes.
const VERSION_PATTERN = /^(\d+(?:\.\d+)*)(?:[-+].*)?$/;
const SERVER_UPGRADE_COMMAND = "brew upgrade Trendyol/trendyol-tap/stove";

type VersionMismatchKind = "runtime_older" | "server_older" | "unknown";

interface VersionMismatch {
  appName: string;
  serverVersion: string;
  runtimeVersion: string | null;
  kind: VersionMismatchKind;
}

export interface VersionMismatchSummary {
  serverVersion: string;
  mismatches: VersionMismatch[];
  affectedAppNames: string[];
  selectedAppMismatch: VersionMismatch | null;
}

interface VersionMismatchRemediationStep {
  kind: "text" | "command";
  value: string;
}

interface VersionMismatchDetailModel extends VersionMismatch {
  selected: boolean;
  problem: string;
  remediationSteps: VersionMismatchRemediationStep[];
}

interface VersionMismatchWarningModel {
  title: string;
  mismatchCount: number;
  serverVersion: string;
  details: VersionMismatchDetailModel[];
}

export function compareVersions(
  runtimeVersion: string | null | undefined,
  serverVersion: string,
): VersionMismatchKind | null {
  const normalizedRuntime = normalizeVersion(runtimeVersion);
  const normalizedServer = normalizeVersion(serverVersion);
  if (!normalizedServer) {
    return "unknown";
  }
  if (normalizedRuntime === normalizedServer) {
    return null;
  }

  if (!normalizedRuntime) {
    return "unknown";
  }

  const runtimeParts = parseVersion(normalizedRuntime);
  const serverParts = parseVersion(normalizedServer);
  if (!runtimeParts || !serverParts) {
    return "unknown";
  }

  const order = compareVersionParts(runtimeParts, serverParts);
  if (order < 0) {
    return "runtime_older";
  }
  if (order > 0) {
    return "server_older";
  }

  // Same numeric core (for example a snapshot and its release base) is not a
  // mismatch; the pre-release suffix does not change compatibility.
  return null;
}

export function summarizeVersionMismatches(
  apps: AppSummary[],
  serverVersion: string | null,
  selectedApp: string | undefined,
): VersionMismatchSummary | null {
  if (!serverVersion) {
    return null;
  }

  const mismatches = apps
    .map((app) => createVersionMismatch(app, serverVersion))
    .filter((mismatch): mismatch is VersionMismatch => mismatch !== null);

  if (mismatches.length === 0) {
    return null;
  }

  const affectedAppNames = mismatches.map((mismatch) => mismatch.appName);

  return {
    serverVersion,
    mismatches,
    affectedAppNames,
    selectedAppMismatch: mismatches.find((mismatch) => mismatch.appName === selectedApp) ?? null,
  };
}

export function buildVersionMismatchWarningModel(
  summary: VersionMismatchSummary,
): VersionMismatchWarningModel {
  const mismatchCount = summary.mismatches.length;
  const selectedAppMismatch = summary.selectedAppMismatch;
  const details = summary.mismatches
    .map((mismatch) => ({
      ...mismatch,
      selected: mismatch.appName === selectedAppMismatch?.appName,
      problem: mismatchProblem(mismatch),
      remediationSteps: remediationStepsForMismatch(mismatch),
    }))
    .sort((left, right) => Number(right.selected) - Number(left.selected));

  return {
    title: warningTitle(mismatchCount),
    mismatchCount,
    serverVersion: summary.serverVersion,
    details,
  };
}

function createVersionMismatch(app: AppSummary, serverVersion: string): VersionMismatch | null {
  const kind = compareVersions(app.stove_version, serverVersion);
  if (!kind) {
    return null;
  }

  return {
    appName: app.app_name,
    serverVersion,
    runtimeVersion: normalizeVersion(app.stove_version),
    kind,
  };
}

function warningTitle(mismatchCount: number): string {
  return mismatchCount === 1
    ? "Version mismatch detected"
    : `${mismatchCount} version mismatches detected`;
}

function normalizeVersion(version: string | null | undefined): string | null {
  const normalized = version?.trim();
  return normalized ? normalized : null;
}

function parseVersion(version: string): number[] | null {
  const match = version.match(VERSION_PATTERN);
  if (!match) {
    return null;
  }

  return match[1].split(".").map(Number);
}

function compareVersionParts(left: number[], right: number[]): number {
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const leftPart = left[index] ?? 0;
    const rightPart = right[index] ?? 0;
    if (leftPart < rightPart) {
      return -1;
    }
    if (leftPart > rightPart) {
      return 1;
    }
  }

  return 0;
}

function remediationStepsForMismatch(mismatch: VersionMismatch): VersionMismatchRemediationStep[] {
  if (mismatch.kind === "runtime_older") {
    return [textStep(dependencyAlignmentMessage(mismatch.serverVersion))];
  }

  if (mismatch.kind === "server_older") {
    return [
      textStep("Update stove-server to match the runtime version:"),
      commandStep(SERVER_UPGRADE_COMMAND),
      commandStep(installScriptCommand(mismatch.runtimeVersion!)),
    ];
  }

  return [
    textStep(
      `This run comes from an older or non-standard Stove runtime. ${dependencyAlignmentMessage(mismatch.serverVersion)}`,
    ),
  ];
}

function mismatchProblem(mismatch: VersionMismatch): string {
  if (mismatch.kind === "runtime_older") {
    return "The app runtime is older than the dashboard server.";
  }

  if (mismatch.kind === "server_older") {
    return "The dashboard server is older than the app runtime.";
  }

  return "The app did not report a recognizable Stove version.";
}

function dependencyAlignmentMessage(serverVersion: string): string {
  return `Align the Stove BOM or all Stove test dependencies to ${serverVersion}.`;
}

function installScriptCommand(runtimeVersion: string): string {
  return `curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/server/stove-server/install.sh | sh -s -- --version ${runtimeVersion}`;
}

function textStep(value: string): VersionMismatchRemediationStep {
  return { kind: "text", value };
}

function commandStep(value: string): VersionMismatchRemediationStep {
  return { kind: "command", value };
}
