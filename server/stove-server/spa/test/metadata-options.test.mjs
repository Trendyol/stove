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
    {
      key: "gitlab.pipeline_id",
      values: [
        { value: "10", count: 1 },
        { value: "20", count: 1 },
      ],
    },
    {
      key: "team",
      values: [
        { value: "checkout", count: 2 },
        { value: "payments", count: 1 },
      ],
    },
    { key: "tribe", values: [{ value: "commerce", count: 1 }] },
  ]);
});

test("metadata filter options handle runs without metadata", () => {
  assert.deepEqual(metadataOptionsForRuns([{ metadata: {} }]), []);
});
