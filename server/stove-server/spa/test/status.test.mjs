import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { aggregateStatus } = await jiti.import("../src/utils/status.ts");

test("failure takes precedence over running regardless of test order", () => {
  for (const failed of ["FAILED", "ERROR"]) {
    for (const statuses of [
      ["RUNNING", failed, "PASSED"],
      [failed, "PASSED", "RUNNING"],
      ["PASSED", "RUNNING", failed],
    ]) {
      assert.equal(aggregateStatus(statuses), "FAILED");
    }
  }
});

test("an unfinished group stays running and a completed group passes", () => {
  assert.equal(aggregateStatus([]), "RUNNING");
  assert.equal(aggregateStatus(["PASSED", "RUNNING"]), "RUNNING");
  assert.equal(aggregateStatus(["RUNNING", "PASSED"]), "RUNNING");
  assert.equal(aggregateStatus(["PASSED", "PASSED"]), "PASSED");
});
