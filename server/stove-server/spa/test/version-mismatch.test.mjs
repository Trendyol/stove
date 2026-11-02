import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const {
  buildVersionMismatchWarningModel,
  compareVersions,
  summarizeVersionMismatches,
} = await jiti.import(
  "../src/utils/version-mismatch.ts",
);

test("compareVersions detects exact matches, directional mismatches, and unknown cases", () => {
  assert.equal(compareVersions("0.23.2", "0.23.2"), null);
  assert.equal(compareVersions("0.23.0", "0.23.2"), "runtime_older");
  assert.equal(compareVersions("0.23.3", "0.23.2"), "server_older");
  assert.equal(compareVersions(null, "0.23.2"), "unknown");
  assert.equal(compareVersions("0.23.2-SNAPSHOT", "0.23.2"), null);
  assert.equal(compareVersions("1.0.0.566-SNAPSHOT", "1.0.0.566-SNAPSHOT"), null);
  assert.equal(compareVersions("1.0.0.565-SNAPSHOT", "1.0.0.566-SNAPSHOT"), "runtime_older");
  assert.equal(compareVersions("1.0.0.567-SNAPSHOT", "1.0.0.566-SNAPSHOT"), "server_older");
  assert.equal(compareVersions("0.27.6", "0.28.0"), "runtime_older");
});

test("summarizeVersionMismatches accepts a same-build 566 snapshot pair", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "ecomm-java",
        latest_run_id: "run-566",
        latest_run_started_at: "2024-06-01T10:00:00Z",
        latest_status: "PASSED",
        stove_version: "1.0.0.566-SNAPSHOT",
      },
    ],
    "1.0.0.566-SNAPSHOT",
    "ecomm-java",
  );

  assert.equal(summary, null);
});

test("summarizeVersionMismatches still warns for a genuinely older runtime", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "ecomm-java",
        latest_run_id: "run-old",
        latest_run_started_at: "2024-06-01T10:00:00Z",
        latest_status: "PASSED",
        stove_version: "0.27.6",
      },
    ],
    "0.28.0",
    "ecomm-java",
  );

  assert.equal(summary.serverVersion, "0.28.0");
  assert.equal(summary.mismatches.length, 1);
  assert.equal(summary.selectedAppMismatch.kind, "runtime_older");
});

test("summarizeVersionMismatches falls back to release-only version strings", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "release-api",
        latest_run_id: "run-release",
        latest_run_started_at: "2024-06-01T10:00:00Z",
        latest_status: "PASSED",
        stove_version: "0.28.0",
      },
    ],
    "0.28.0",
    "release-api",
  );

  assert.equal(summary, null);
});

test("summarizeVersionMismatches reports a missing version without crashing", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "legacy-api",
        latest_run_id: "run-legacy",
        latest_run_started_at: "2024-06-01T10:00:00Z",
        latest_status: "PASSED",
        stove_version: null,
      },
    ],
    "0.28.0",
    "legacy-api",
  );

  assert.equal(summary.mismatches.length, 1);
  assert.equal(summary.selectedAppMismatch.kind, "unknown");
  assert.equal(summary.selectedAppMismatch.runtimeVersion, null);
});


test("summarizeVersionMismatches returns null when every latest app matches the server", () => {
  const summary = summarizeVersionMismatches(
    [
      {
        app_name: "alpha-api",
        latest_run_id: "run-1",
        latest_run_started_at: "2024-06-01T10:00:00Z",
        latest_status: "PASSED",
        stove_version: "0.23.2",
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
        latest_run_started_at: "2024-06-01T10:00:00Z",
        latest_status: "PASSED",
        stove_version: "0.23.0",
      },
      {
        app_name: "beta-api",
        latest_run_id: "run-2",
        latest_run_started_at: "2024-06-01T11:00:00Z",
        latest_status: "FAILED",
        stove_version: "0.23.5",
      },
    ],
    "0.23.2",
    "alpha-api",
  );

  assert.equal(summary.serverVersion, "0.23.2");
  assert.equal(summary.mismatches.length, 2);
  assert.deepEqual(summary.affectedAppNames, ["alpha-api", "beta-api"]);
  assert.equal(summary.selectedAppMismatch.appName, "alpha-api");
  assert.equal(summary.selectedAppMismatch.kind, "runtime_older");
});

test("buildVersionMismatchWarningModel returns dependency alignment guidance for older runtimes", () => {
  const model = buildVersionMismatchWarningModel({
    serverVersion: "0.23.2",
    mismatches: [
      {
        appName: "alpha-api",
        serverVersion: "0.23.2",
        runtimeVersion: "0.23.0",
        kind: "runtime_older",
      },
    ],
    affectedAppNames: ["alpha-api"],
    selectedAppMismatch: {
      appName: "alpha-api",
      serverVersion: "0.23.2",
      runtimeVersion: "0.23.0",
      kind: "runtime_older",
    },
  });

  assert.equal(model.details[0].appName, "alpha-api");
  assert.deepEqual(model.details[0].remediationSteps, [
    {
      kind: "text",
      value: "Align the Stove BOM or all Stove test dependencies to 0.23.2.",
    },
  ]);
});

test("buildVersionMismatchWarningModel returns server upgrade commands when the runtime is newer", () => {
  const model = buildVersionMismatchWarningModel({
    serverVersion: "0.23.2",
    mismatches: [
      {
        appName: "beta-api",
        serverVersion: "0.23.2",
        runtimeVersion: "0.23.5",
        kind: "server_older",
      },
    ],
    affectedAppNames: ["beta-api"],
    selectedAppMismatch: {
      appName: "beta-api",
      serverVersion: "0.23.2",
      runtimeVersion: "0.23.5",
      kind: "server_older",
    },
  });

  assert.equal(model.details[0].remediationSteps[0].kind, "text");
  assert.equal(model.details[0].remediationSteps[1].kind, "command");
  assert.equal(
    model.details[0].remediationSteps[1].value,
    "brew upgrade Trendyol/trendyol-tap/stove",
  );
  assert.equal(
    model.details[0].remediationSteps[2].value,
    "curl -fsSL https://raw.githubusercontent.com/Trendyol/stove/main/server/stove-server/install.sh | sh -s -- --version 0.23.5",
  );
});

test("buildVersionMismatchWarningModel keeps details when the selected app matches", () => {
  const model = buildVersionMismatchWarningModel({
    serverVersion: "0.23.2",
    mismatches: [
      {
        appName: "alpha-api",
        serverVersion: "0.23.2",
        runtimeVersion: "0.23.0",
        kind: "runtime_older",
      },
      {
        appName: "beta-api",
        serverVersion: "0.23.2",
        runtimeVersion: "0.23.5",
        kind: "server_older",
      },
    ],
    affectedAppNames: ["alpha-api", "beta-api"],
    selectedAppMismatch: null,
  });

  assert.deepEqual(
    model.details.map((detail) => detail.appName),
    ["alpha-api", "beta-api"],
  );
  assert.ok(model.details.every((detail) => !detail.selected));
  assert.ok(model.details.every((detail) => detail.remediationSteps.length > 0));
});

test("buildVersionMismatchWarningModel returns legacy guidance for missing runtime versions", () => {
  const model = buildVersionMismatchWarningModel({
    serverVersion: "0.23.2",
    mismatches: [
      {
        appName: "legacy-api",
        serverVersion: "0.23.2",
        runtimeVersion: null,
        kind: "unknown",
      },
    ],
    affectedAppNames: ["legacy-api"],
    selectedAppMismatch: {
      appName: "legacy-api",
      serverVersion: "0.23.2",
      runtimeVersion: null,
      kind: "unknown",
    },
  });

  assert.equal(model.details[0].runtimeVersion, null);
  assert.equal(model.details[0].remediationSteps[0].kind, "text");
  assert.match(
    model.details[0].remediationSteps[0].value,
    /older or non-standard Stove runtime/,
  );
});

test("buildVersionMismatchWarningModel includes details for every mismatch and puts the selected app first", () => {
  const model = buildVersionMismatchWarningModel({
    serverVersion: "0.23.2",
    mismatches: [
      {
        appName: "alpha-api",
        serverVersion: "0.23.2",
        runtimeVersion: "0.23.0",
        kind: "runtime_older",
      },
      {
        appName: "beta-api",
        serverVersion: "0.23.2",
        runtimeVersion: "0.23.5",
        kind: "server_older",
      },
    ],
    affectedAppNames: ["alpha-api", "beta-api"],
    selectedAppMismatch: {
      appName: "beta-api",
      serverVersion: "0.23.2",
      runtimeVersion: "0.23.5",
      kind: "server_older",
    },
  });

  assert.equal(model.mismatchCount, 2);
  assert.deepEqual(
    model.details.map((detail) => detail.appName),
    ["beta-api", "alpha-api"],
  );
  assert.equal(model.details[0].selected, true);
  assert.match(model.details[0].problem, /server is older/);
  assert.match(model.details[1].problem, /runtime is older/);
  assert.ok(model.details.every((detail) => detail.remediationSteps.length > 0));
});
