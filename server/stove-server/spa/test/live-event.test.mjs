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

function spanEvent(status) {
  return {
    seq: 1,
    run_id: "run-1",
    event_type: "span_recorded",
    payload: {
      id: -1,
      test_id: "test-1",
      trace_id: "trace-1",
      span_id: "span-1",
      parent_span_id: null,
      operation_name: "GET /health",
      service_name: "app",
      start_time_nanos: 1,
      end_time_nanos: 2,
      status,
      attributes: null,
      exception_type: null,
      exception_message: null,
      exception_stack_trace: null,
    },
  };
}

test("live spans accept OpenTelemetry statuses and reject test statuses", () => {
  for (const status of ["OK", "UNSET", "ERROR"]) {
    assert.equal(parseLiveDashboardEvent(JSON.stringify(spanEvent(status)))?.payload.status, status);
  }
  for (const status of ["PASSED", "RUNNING", "FAILED", null, 200]) {
    assert.equal(parseLiveDashboardEvent(JSON.stringify(spanEvent(status))), undefined);
  }
});

test("live payload validation checks required fields and nullable field types", () => {
  for (const field of Object.keys(spanEvent("OK").payload)) {
    const event = spanEvent("OK");
    delete event.payload[field];
    assert.equal(parseLiveDashboardEvent(JSON.stringify(event)), undefined, field);
  }
  for (const value of [[], {}, 5, true]) {
    const event = spanEvent("OK");
    event.payload.test_id = value;
    assert.equal(parseLiveDashboardEvent(JSON.stringify(event)), undefined);
  }
});

test("parsing projects known fields without rejecting forward-compatible additions", () => {
  const event = spanEvent("OK");
  const extended = {
    ...event,
    future: true,
    payload: { ...event.payload, run_id: "unrelated-run", future: { value: 1 } },
  };
  assert.deepEqual(parseLiveDashboardEvent(JSON.stringify(extended)), event);
});
