import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { filterInteractions, journalStats, resolveJournalInspector } = await jiti.import(
  "../src/components/mock-journal/model.ts",
);

test("mock journal model derives filters and typed empty statistics", () => {
  const matched = interaction({ id: 1, target: "/orders", latency_ms: 20 });
  const unmatched = interaction({ id: 2, target: "/payments", matched: false, latency_ms: 700 });

  assert.deepEqual(filterInteractions([matched, unmatched], "issues", ""), [unmatched]);
  assert.deepEqual(filterInteractions([matched, unmatched], "slow", "payments"), [unmatched]);
  assert.deepEqual(journalStats([]).matchRate, { kind: "empty" });
  assert.deepEqual(journalStats([matched, unmatched]).slowest, {
    kind: "duration",
    milliseconds: 700,
  });
});

test("mock warning selection preserves whether a related interaction exists", () => {
  const related = interaction({ id: 4, stub_id: "stub-1" });
  const warning = { id: 8, stub_id: "stub-1" };
  const selected = {
    kind: "warning",
    warningId: 8,
    related: { kind: "interaction", interactionId: 4 },
  };

  assert.deepEqual(resolveJournalInspector(selected, [related], [warning]), {
    kind: "warning",
    warning,
    related: { kind: "interaction", interaction: related },
  });
  assert.deepEqual(resolveJournalInspector(selected, [], [warning]), {
    kind: "warning",
    warning,
    related: { kind: "none" },
  });
});

function interaction(overrides) {
  return {
    id: 1,
    matched: true,
    latency_ms: null,
    status: "200",
    fault: null,
    near_misses: [],
    stub_id: null,
    target: "/",
    system: "http",
    protocol: "HTTP",
    method: "GET",
    attribution: "PROVEN_STUB",
    scenario_name: null,
    scenario_state: null,
    next_scenario_state: null,
    request_body: null,
    response_body: null,
    ...overrides,
  };
}
