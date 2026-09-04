import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { parseLiveDashboardEvent } = await jiti.import("../src/api/live-event.ts");

test("parseLiveDashboardEvent accepts a complete typed event", () => {
  const event = parseLiveDashboardEvent(
    JSON.stringify({
      seq: 1,
      run_id: "run-1",
      event_type: "test_started",
      payload: {
        test_id: "test-1",
        test_name: "works",
        spec_name: "LiveSpec",
        test_path: ["LiveSpec", "works"],
        started_at: "2024-06-01T10:00:00Z",
        status: "RUNNING",
      },
    }),
  );

  assert.equal(event?.event_type, "test_started");
  assert.equal(event?.payload.test_id, "test-1");
});

test("parseLiveDashboardEvent rejects malformed and incomplete events", () => {
  assert.equal(parseLiveDashboardEvent("not-json"), undefined);
  assert.equal(
    parseLiveDashboardEvent(
      JSON.stringify({ seq: 1, run_id: "run-1", event_type: "unknown", payload: {} }),
    ),
    undefined,
  );
  assert.equal(
    parseLiveDashboardEvent(
      JSON.stringify({
        seq: 1,
        run_id: "run-1",
        event_type: "test_started",
        payload: { test_id: "test-1" },
      }),
    ),
    undefined,
  );
});
