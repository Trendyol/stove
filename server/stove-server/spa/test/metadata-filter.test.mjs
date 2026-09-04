import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const {
  filterRunsByMetadata,
  isMetadataValueSelected,
  metadataSelections,
  toggleMetadataValue,
} = await jiti.import("../src/utils/metadata-filter.ts");

const runs = [
  { id: "one", metadata: { team: "checkout", region: "eu" } },
  { id: "two", metadata: { team: "payments", region: "eu" } },
  { id: "three", metadata: { team: "checkout", region: "us" } },
];

test("metadata filters match any selected value within a field and every selected field", () => {
  assert.deepEqual(
    filterRunsByMetadata(runs, { team: ["checkout", "payments"], region: ["eu"] }).map(
      ({ id }) => id,
    ),
    ["one", "two"],
  );
  assert.deepEqual(filterRunsByMetadata(runs, { team: ["checkout"] }).map(({ id }) => id), [
    "one",
    "three",
  ]);
});

test("metadata values toggle without leaving empty filter groups", () => {
  const selected = toggleMetadataValue({}, "team", "checkout");
  const multiSelected = toggleMetadataValue(selected, "team", "payments");
  const deselected = toggleMetadataValue(multiSelected, "team", "checkout");
  const cleared = toggleMetadataValue(deselected, "team", "payments");

  assert.deepEqual(selected, { team: ["checkout"] });
  assert.deepEqual(multiSelected, { team: ["checkout", "payments"] });
  assert.equal(isMetadataValueSelected(multiSelected, "team", "payments"), true);
  assert.deepEqual(metadataSelections(multiSelected), [
    { key: "team", value: "checkout" },
    { key: "team", value: "payments" },
  ]);
  assert.deepEqual(cleared, {});
});
