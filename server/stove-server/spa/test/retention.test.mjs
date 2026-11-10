import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { expiredRunIds, parseRetentionPolicy } = await jiti.import("../src/utils/retention.ts");
const { createRetentionDraft, retentionPolicyFromDraft } = await jiti.import("../src/pages/admin/retention-draft.ts");
const fixtures = JSON.parse(await readFile(new URL("../../tests/fixtures/retention.json", import.meta.url), "utf8"));

for (const fixture of fixtures.valid) {
  test(`retention contract: ${fixture.name}`, () => {
    const policy = parseRetentionPolicy(fixture.policy);
    assert.ok(policy);
    assert.deepEqual(expiredRunIds(policy, fixture.runs, fixture.protected_run).sort(), [...fixture.expired].sort());
    assert.deepEqual(retentionPolicyFromDraft(createRetentionDraft(policy)), {
      ...policy, rules: policy.rules.map(rule => ({app_name: null, passed_runs_per_app: null, failed_runs_per_app: null, ...rule})),
    });
  });
}
for (const fixture of fixtures.invalid) {
  test(`retention rejects: ${fixture.name}`, () => {
    const policy = structuredClone(fixture.policy);
    if (fixture.repeat_rules) policy.rules = Array(fixture.repeat_rules).fill(policy.rules[0]);
    assert.equal(parseRetentionPolicy(policy), null);
  });
}

test("incomplete and unsafe limits remain drafts, and IDs never enter the policy", () => {
  const draft = createRetentionDraft({ passed_runs_per_app: 3, failed_runs_per_app: 5, rules: [] });
  for (const value of ["", "-", "1.", "1.5", "-1", "Infinity", "9007199254740992"]) {
    assert.equal(retentionPolicyFromDraft({...draft, passed_runs_per_app: value}), null, value);
  }
  assert.deepEqual(retentionPolicyFromDraft({...draft, passed_runs_per_app: "0"}), {passed_runs_per_app: 0, failed_runs_per_app: 5, rules: []});
});
