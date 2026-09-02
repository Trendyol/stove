import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { metadataOptionsForRuns } = await jiti.import("../src/utils/metadata-options.ts");

test("metadata filter options collect and sort dynamic keys and distinct values", () => {
  const runs = [
    { metadata: { team: "payments", "gitlab.pipeline_id": "20" } },
    { metadata: { team: "checkout", tribe: "commerce", "gitlab.pipeline_id": "10" } },
    { metadata: { team: "checkout" } },
  ];

  assert.deepEqual(metadataOptionsForRuns(runs), [
    { key: "gitlab.pipeline_id", values: ["10", "20"] },
    { key: "team", values: ["checkout", "payments"] },
    { key: "tribe", values: ["commerce"] },
  ]);
});

test("metadata filter options handle runs without metadata", () => {
  assert.deepEqual(metadataOptionsForRuns([{ metadata: {} }]), []);
});
