import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const {
  buildVersionMismatchBannerModel,
  compareVersions,
  summarizeVersionMismatches,
} = await jiti.import(
  "../src/utils/version-mismatch.ts",
);

test("compareVersions detects exact matches, directional mismatches, and unknown cases", () => {
  assert.equal(compareVersions("0.23.2", "0.23.2"), null);
  assert.equal(compareVersions("0.23.0", "0.23.2"), "runtime_older");
  assert.equal(compareVersions("0.23.3", "0.23.2"), "cli_older");
  assert.equal(compareVersions(null, "0.23.2"), "unknown");
  assert.equal(compareVersions("0.23.2-SNAPSHOT", "0.23.2"), "unknown");
});

test("summarizeVersionMismatches returns null when every latest app matches the CLI", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "alpha-api",
        latest_run_id: "run-1",
        latest_status: "PASSED",
        stove_version: "0.23.2",
        total_runs: 1,
      },
    ],
    "0.23.2",
    "alpha-api",
  );

  assert.equal(summary, null);
});

test("summarizeVersionMismatches captures selected-app mismatch and all affected apps", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "alpha-api",
        latest_run_id: "run-1",
        latest_status: "PASSED",
        stove_version: "0.23.0",
        total_runs: 1,
      },
      {
        app_name: "beta-api",
        latest_run_id: "run-2",
        latest_status: "FAILED",
        stove_version: "0.23.5",
        total_runs: 1,
      },
    ],
    "0.23.2",
    "alpha-api",
  );

  assert.equal(summary.cliVersion, "0.23.2");
  assert.equal(summary.mismatches.length, 2);
  assert.deepEqual(summary.affectedAppNames, ["alpha-api", "beta-api"]);
  assert.equal(summary.selectedAppMismatch.appName, "alpha-api");
  assert.equal(summary.selectedAppMismatch.kind, "runtime_older");
});

test("buildVersionMismatchBannerModel returns dependency alignment guidance for older runtimes", () => {
  const model = buildVersionMismatchBannerModel({
    cliVersion: "0.23.2",
    mismatches: [
      {
        appName: "alpha-api",
        cliVersion: "0.23.2",
        runtimeVersion: "0.23.0",
        kind: "runtime_older",
      },
    ],
    affectedAppNames: ["alpha-api"],
    selectedAppMismatch: {
      appName: "alpha-api",
      cliVersion: "0.23.2",
      runtimeVersion: "0.23.0",
      kind: "runtime_older",
    },
  });

  assert.equal(model.selectedAppName, "alpha-api");
  assert.deepEqual(model.remediationSteps, [
    {
      kind: "text",
      value: "Align the Stove BOM or all Stove test dependencies to 0.23.2.",
    },
  ]);
});

test("buildVersionMismatchBannerModel returns CLI upgrade commands when the runtime is newer", () => {
  const model = buildVersionMismatchBannerModel({
    cliVersion: "0.23.2",
    mismatches: [
      {
        appName: "beta-api",
        cliVersion: "0.23.2",
        runtimeVersion: "0.23.5",
        kind: "cli_older",
      },
    ],
    affectedAppNames: ["beta-api"],
    selectedAppMismatch: {
      appName: "beta-api",
      cliVersion: "0.23.2",
      runtimeVersion: "0.23.5",
      kind: "cli_older",
    },
  });

  assert.equal(model.remediationSteps[0].kind, "text");
  assert.equal(model.remediationSteps[1].kind, "command");
  assert.equal(model.remediationSteps[1].value, "brew upgrade Trendyol/trendyol-tap/stove");
  assert.equal(
    model.remediationSteps[2].value,
    "curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/tools/stove-cli/install.sh | sh -s -- --version 0.23.5",
  );
});

test("buildVersionMismatchBannerModel stays summary-only when another app mismatches", () => {
  const model = buildVersionMismatchBannerModel({
    cliVersion: "0.23.2",
    mismatches: [
      {
        appName: "alpha-api",
        cliVersion: "0.23.2",
        runtimeVersion: "0.23.0",
        kind: "runtime_older",
      },
      {
        appName: "beta-api",
        cliVersion: "0.23.2",
        runtimeVersion: "0.23.5",
        kind: "cli_older",
      },
    ],
    affectedAppNames: ["alpha-api", "beta-api"],
    selectedAppMismatch: null,
  });

  assert.deepEqual(model.affectedApps, ["alpha-api", "beta-api"]);
  assert.equal(model.switchHint, "Switch to a mismatched app to see exact remediation.");
  assert.deepEqual(model.remediationSteps, []);
});

test("buildVersionMismatchBannerModel returns legacy guidance for missing runtime versions", () => {
  const model = buildVersionMismatchBannerModel({
    cliVersion: "0.23.2",
    mismatches: [
      {
        appName: "legacy-api",
        cliVersion: "0.23.2",
        runtimeVersion: null,
        kind: "unknown",
      },
    ],
    affectedAppNames: ["legacy-api"],
    selectedAppMismatch: {
      appName: "legacy-api",
      cliVersion: "0.23.2",
      runtimeVersion: null,
      kind: "unknown",
    },
  });

  assert.equal(model.runtimeVersion, null);
  assert.equal(model.remediationSteps[0].kind, "text");
  assert.match(model.remediationSteps[0].value, /older or non-standard Stove runtime/);
});
