import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { filterEvidence, hasEntryDetail, isEntryIssue } = await jiti.import(
  "../src/components/evidence/model.ts",
);

test("evidence filtering combines issue and text predicates", () => {
  const passing = entry({ id: 1, action: "publish order", result: "PASSED" });
  const failing = entry({ id: 2, action: "charge card", result: "FAILED" });
  const errored = entry({ id: 3, action: "load profile", error: "timeout" });

  assert.deepEqual(filterEvidence([passing, failing, errored], "issues", "charge"), [failing]);
  assert.deepEqual(filterEvidence([passing, failing, errored], "all", "timeout"), [errored]);
  assert.equal(isEntryIssue(errored), true);
  assert.equal(hasEntryDetail(passing), false);
  assert.equal(hasEntryDetail(errored), true);
});

function entry(overrides) {
  return {
    id: 1,
    run_id: "run",
    test_id: "test",
    timestamp: "2026-01-01T00:00:00Z",
    system: "http",
    action: "request",
    result: "PASSED",
    input: null,
    output: null,
    metadata: null,
    expected: null,
    actual: null,
    error: null,
    trace_id: null,
    assertion_id: "assertion",
    attempt_count: 1,
    failure_count: 0,
    ...overrides,
  };
}
