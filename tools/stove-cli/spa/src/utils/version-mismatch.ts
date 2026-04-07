import type { AppSummary } from "../api/types";

const RELEASE_VERSION_PATTERN = /^(\d+)\.(\d+)\.(\d+)$/;
const SWITCH_HINT = "Switch to a mismatched app to see exact remediation.";
const CLI_UPGRADE_COMMAND = "brew upgrade Trendyol/trendyol-tap/stove";

export type VersionMismatchKind = "runtime_older" | "cli_older" | "unknown";

export interface VersionMismatch {
  appName: string;
  cliVersion: string;
  runtimeVersion: string | null;
  kind: VersionMismatchKind;
}

export interface VersionMismatchSummary {
  cliVersion: string;
  mismatches: VersionMismatch[];
  affectedAppNames: string[];
  selectedAppMismatch: VersionMismatch | null;
}

export interface VersionMismatchRemediationStep {
  kind: "text" | "command";
  value: string;
}

export interface VersionMismatchBannerModel {
  title: string;
  affectedApps: string[];
  switchHint: string | null;
  selectedAppName: string | null;
  runtimeVersion: string | null;
  cliVersion: string;
  remediationSteps: VersionMismatchRemediationStep[];
}

export function compareVersions(
  runtimeVersion: string | null | undefined,
  cliVersion: string,
): VersionMismatchKind | null {
  const normalizedRuntime = normalizeVersion(runtimeVersion);
  if (normalizedRuntime === cliVersion) {
    return null;
  }

  if (!normalizedRuntime) {
    return "unknown";
  }

  const runtimeTriplet = parseReleaseVersion(normalizedRuntime);
  const cliTriplet = parseReleaseVersion(cliVersion);
  if (!runtimeTriplet || !cliTriplet) {
    return "unknown";
  }

  for (let index = 0; index < runtimeTriplet.length; index += 1) {
    if (runtimeTriplet[index] < cliTriplet[index]) {
      return "runtime_older";
    }
    if (runtimeTriplet[index] > cliTriplet[index]) {
      return "cli_older";
    }
  }

  return "unknown";
}

export function summarizeVersionMismatches(
  apps: AppSummary[],
  cliVersion: string | null,
  selectedApp: string | null,
): VersionMismatchSummary | null {
  if (!cliVersion) {
    return null;
  }

  const mismatches = apps
    .map((app) => createVersionMismatch(app, cliVersion))
    .filter((mismatch): mismatch is VersionMismatch => mismatch !== null);

  if (mismatches.length === 0) {
    return null;
  }

  const affectedAppNames = mismatches.map((mismatch) => mismatch.appName);

  return {
    cliVersion,
    mismatches,
    affectedAppNames,
    selectedAppMismatch: mismatches.find((mismatch) => mismatch.appName === selectedApp) ?? null,
  };
}

export function buildVersionMismatchBannerModel(
  summary: VersionMismatchSummary,
): VersionMismatchBannerModel {
  const mismatchCount = summary.mismatches.length;
  const selectedAppMismatch = summary.selectedAppMismatch;

  return {
    title: bannerTitle(mismatchCount),
    affectedApps: summary.affectedAppNames,
    switchHint: selectedAppMismatch ? null : SWITCH_HINT,
    selectedAppName: selectedAppMismatch?.appName ?? null,
    runtimeVersion: selectedAppMismatch?.runtimeVersion ?? null,
    cliVersion: summary.cliVersion,
    remediationSteps: selectedAppMismatch ? remediationStepsForMismatch(selectedAppMismatch) : [],
  };
}

function createVersionMismatch(app: AppSummary, cliVersion: string): VersionMismatch | null {
  const kind = compareVersions(app.stove_version, cliVersion);
  if (!kind) {
    return null;
  }

  return {
    appName: app.app_name,
    cliVersion,
    runtimeVersion: normalizeVersion(app.stove_version),
    kind,
  };
}

function bannerTitle(mismatchCount: number): string {
  return mismatchCount === 1
    ? "A version mismatch was detected in the latest Stove dashboard run."
    : `${mismatchCount} apps have a version mismatch in their latest Stove dashboard runs.`;
}

function normalizeVersion(version: string | null | undefined): string | null {
  const normalized = version?.trim();
  return normalized ? normalized : null;
}

function parseReleaseVersion(version: string): number[] | null {
  const match = version.match(RELEASE_VERSION_PATTERN);
  if (!match) {
    return null;
  }

  return match.slice(1).map(Number);
}

function remediationStepsForMismatch(mismatch: VersionMismatch): VersionMismatchRemediationStep[] {
  if (mismatch.kind === "runtime_older") {
    return [textStep(dependencyAlignmentMessage(mismatch.cliVersion))];
  }

  if (mismatch.kind === "cli_older") {
    return [
      textStep("Update stove-cli to match the runtime version:"),
      commandStep(CLI_UPGRADE_COMMAND),
      commandStep(installScriptCommand(mismatch.runtimeVersion!)),
    ];
  }

  return [
    textStep(
      `This run comes from an older or non-standard Stove runtime. ${dependencyAlignmentMessage(mismatch.cliVersion)}`,
    ),
  ];
}

function dependencyAlignmentMessage(cliVersion: string): string {
  return `Align the Stove BOM or all Stove test dependencies to ${cliVersion}.`;
}

function installScriptCommand(runtimeVersion: string): string {
  return `curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/tools/stove-cli/install.sh | sh -s -- --version ${runtimeVersion}`;
}

function textStep(value: string): VersionMismatchRemediationStep {
  return { kind: "text", value };
}

function commandStep(value: string): VersionMismatchRemediationStep {
  return { kind: "command", value };
}
