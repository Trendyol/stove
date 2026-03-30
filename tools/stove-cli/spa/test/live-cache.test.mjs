import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";
import { QueryClient } from "@tanstack/react-query";

const jiti = createJiti(import.meta.url);
const { applyLivePortalEvent } = await jiti.import("../src/api/live-cache.ts");

test("applyLivePortalEvent updates run, test, and detail caches from live SSE payloads", () => {
  const queryClient = new QueryClient();

  applyLivePortalEvent(queryClient, {
    seq: 1,
    run_id: "run-live",
    event_type: "run_started",
    payload: {
      app_name: "live-app",
      started_at: "2024-06-01T10:00:00Z",
      systems: ["HTTP"],
    },
  });

  applyLivePortalEvent(queryClient, {
    seq: 2,
    run_id: "run-live",
    event_type: "test_started",
    payload: {
      test_id: "test-1",
      test_name: "streams immediately",
      spec_name: "LiveSpec",
      started_at: "2024-06-01T10:00:01Z",
      status: "RUNNING",
    },
  });

  applyLivePortalEvent(queryClient, {
    seq: 3,
    run_id: "run-live",
    event_type: "entry_recorded",
    payload: {
      id: -3,
      test_id: "test-1",
      timestamp: "2024-06-01T10:00:02Z",
      system: "HTTP",
      action: "GET /health",
      result: "PASSED",
      input: null,
      output: null,
      metadata: "{}",
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
  });

  applyLivePortalEvent(queryClient, {
    seq: 4,
    run_id: "run-live",
    event_type: "span_recorded",
    payload: {
      id: -4,
      test_id: null,
      trace_id: "trace-1",
      span_id: "span-1",
      parent_span_id: null,
      operation_name: "GET /health",
      service_name: "live-app",
      start_time_nanos: 1_000_000,
      end_time_nanos: 2_000_000,
      status: "OK",
      attributes: "{}",
      exception_type: null,
      exception_message: null,
      exception_stack_trace: null,
    },
  });

  applyLivePortalEvent(queryClient, {
    seq: 5,
    run_id: "run-live",
    event_type: "test_ended",
    payload: {
      test_id: "test-1",
      status: "PASSED",
      duration_ms: 1200,
      error: null,
      ended_at: "2024-06-01T10:00:03Z",
    },
  });

  const apps = queryClient.getQueryData(["apps"]);
  const runs = queryClient.getQueryData(["runs", "live-app"]);
  const tests = queryClient.getQueryData(["tests", "run-live"]);
  const entries = queryClient.getQueryData(["entries", "run-live", "test-1"]);
  const spans = queryClient.getQueryData(["spans", "run-live", "test-1"]);

  assert.equal(apps.length, 1);
  assert.equal(apps[0].latest_run_id, "run-live");

  assert.equal(runs.length, 1);
  assert.equal(runs[0].status, "RUNNING");

  assert.equal(tests.length, 1);
  assert.equal(tests[0].status, "PASSED");
  assert.equal(tests[0].duration_ms, 1200);

  assert.equal(entries.length, 1);
  assert.equal(entries[0].action, "GET /health");

  assert.equal(spans.length, 1);
  assert.equal(spans[0].span_id, "span-1");
});
