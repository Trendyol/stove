import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";
import { QueryClient } from "@tanstack/react-query";

const jiti = createJiti(import.meta.url);
const { applyLiveDashboardEvent, reconcileDashboardData } = await jiti.import(
  "../src/api/live-cache.ts",
);

test("applyLiveDashboardEvent updates run, test, and detail caches from live SSE payloads", () => {
  const queryClient = new QueryClient();

  applyLiveDashboardEvent(queryClient, {
    seq: 1,
    run_id: "run-live",
    event_type: "run_started",
    payload: {
      app_name: "live-app",
      started_at: "2024-06-01T10:00:00Z",
      stove_version: "0.23.2",
      systems: ["HTTP"],
    },
  });

  applyLiveDashboardEvent(queryClient, {
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

  applyLiveDashboardEvent(queryClient, {
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
      assertion_id: "assertion-health",
      attempt_count: 1,
      failure_count: 0,
    },
  });

  applyLiveDashboardEvent(queryClient, {
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

  applyLiveDashboardEvent(queryClient, {
    seq: 5,
    run_id: "run-live",
    event_type: "mock_interaction",
    payload: {
      id: -5,
      test_id: "test-1",
      timestamp: "2024-06-01T10:00:02.500Z",
      system: "WireMock",
      protocol: "HTTP",
      method: "POST",
      target: "/payments",
      matched: true,
      stub_id: "stub-1",
      attribution: "PROVEN_STUB",
      request_body: '{"amount":42}',
      request_body_truncated: false,
      response_body: '{"accepted":true}',
      response_body_truncated: false,
      status: "200",
      latency_ms: 18,
      near_misses: [],
      trace_id: "trace-1",
      scenario_name: "Payment retry",
      scenario_state: "STARTED",
      next_scenario_state: "attempt-1",
      configured_delay_ms: null,
      fault: null,
      client_deadline_ms: null,
    },
  });

  applyLiveDashboardEvent(queryClient, {
    seq: 6,
    run_id: "run-live",
    event_type: "mock_interaction",
    payload: {
      id: -6,
      test_id: null,
      timestamp: "2024-06-01T10:00:02.600Z",
      system: "gRPC Mock",
      protocol: "gRPC",
      method: "Charge",
      target: "payments.PaymentService",
      matched: false,
      stub_id: null,
      attribution: "UNATTRIBUTED",
      request_body: null,
      request_body_truncated: false,
      response_body: null,
      response_body_truncated: false,
      status: "UNIMPLEMENTED",
      latency_ms: 2,
      near_misses: ["PaymentService/Authorize"],
      trace_id: null,
      scenario_name: null,
      scenario_state: null,
      next_scenario_state: null,
      configured_delay_ms: null,
      fault: null,
      client_deadline_ms: null,
    },
  });

  applyLiveDashboardEvent(queryClient, {
    seq: 7,
    run_id: "run-live",
    event_type: "mock_warning",
    payload: {
      id: -7,
      test_id: "test-1",
      timestamp: "2024-06-01T10:00:02.700Z",
      system: "WireMock",
      kind: "UNUSED_STUB",
      message: "A payment fallback stub was never matched",
      stub_id: "stub-2",
      target: "/payments/fallback",
    },
  });

  applyLiveDashboardEvent(queryClient, {
    seq: 8,
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
  const testInteractions = queryClient.getQueryData(["interactions", "run-live", "test-1"]);
  const runInteractions = queryClient.getQueryData(["interactions", "run-live"]);
  const testWarnings = queryClient.getQueryData(["warnings", "run-live", "test-1"]);
  const runWarnings = queryClient.getQueryData(["warnings", "run-live"]);

  assert.equal(apps.length, 1);
  assert.equal(apps[0].latest_run_id, "run-live");
  assert.equal(apps[0].stove_version, "0.23.2");

  assert.equal(runs.length, 1);
  assert.equal(runs[0].status, "RUNNING");
  assert.equal(runs[0].stove_version, "0.23.2");

  assert.equal(tests.length, 1);
  assert.equal(tests[0].status, "PASSED");
  assert.equal(tests[0].duration_ms, 1200);

  assert.equal(entries.length, 1);
  assert.equal(entries[0].action, "GET /health");

  assert.equal(spans.length, 1);
  assert.equal(spans[0].span_id, "span-1");

  assert.equal(testInteractions.length, 1);
  assert.equal(testInteractions[0].scenario_name, "Payment retry");
  assert.equal(runInteractions.length, 1);
  assert.equal(runInteractions[0].test_id, null);
  assert.equal(testWarnings.length, 1);
  assert.equal(testWarnings[0].kind, "UNUSED_STUB");
  assert.equal(runWarnings.length, 0);
});

test("a live run start replaces the previous run for the app", () => {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["apps"], [
    {
      app_name: "live-app",
      latest_run_id: "old-run",
      latest_status: "PASSED",
      stove_version: "0.23.1",
      total_runs: 4,
    },
  ]);
  queryClient.setQueryData(["runs", "live-app"], [
    {
      id: "old-run",
      app_name: "live-app",
      started_at: "2024-05-31T10:00:00Z",
      ended_at: "2024-05-31T10:01:00Z",
      status: "PASSED",
      total_tests: 1,
      passed: 1,
      failed: 0,
      duration_ms: 60_000,
      stove_version: "0.23.1",
      systems: ["HTTP"],
    },
  ]);

  applyLiveDashboardEvent(queryClient, {
    seq: 1,
    run_id: "new-run",
    event_type: "run_started",
    payload: {
      app_name: "live-app",
      started_at: "2024-06-01T10:00:00Z",
      stove_version: "0.23.2",
      systems: ["HTTP"],
    },
  });

  const apps = queryClient.getQueryData(["apps"]);
  const runs = queryClient.getQueryData(["runs", "live-app"]);
  assert.equal(apps[0].latest_run_id, "new-run");
  assert.equal(apps[0].total_runs, 1);
  assert.deepEqual(
    runs.map((run) => run.id),
    ["new-run"],
  );
});

test("live assertion retries collapse to the latest attempt and retain failure history", () => {
  const queryClient = new QueryClient();
  const queryKey = ["entries", "run-retry", "test-retry"];

  for (let attempt = 1; attempt <= 5; attempt += 1) {
    const failed = attempt < 5;
    applyLiveDashboardEvent(queryClient, {
      seq: attempt,
      run_id: "run-retry",
      event_type: "entry_recorded",
      payload: {
        id: 0,
        test_id: "test-retry",
        timestamp: `2024-06-01T10:00:0${attempt}Z`,
        system: "PostgreSQL",
        action: "Query",
        result: failed ? "FAILED" : "PASSED",
        input: "select * from products",
        output: null,
        metadata: "{}",
        expected: "one row",
        actual: failed ? "no rows" : "one row",
        error: failed ? `not ready on attempt ${attempt}` : null,
        trace_id: null,
        assertion_id: "assertion-products",
        attempt_count: attempt,
        failure_count: failed ? attempt : attempt - 1,
      },
    });
  }

  applyLiveDashboardEvent(queryClient, {
    seq: 6,
    run_id: "run-retry",
    event_type: "entry_recorded",
    payload: {
      id: 0,
      test_id: "test-retry",
      timestamp: "2024-06-01T10:00:06Z",
      system: "PostgreSQL",
      action: "Query",
      result: "PASSED",
      input: "select * from orders",
      output: null,
      metadata: "{}",
      expected: "one row",
      actual: "one row",
      error: null,
      trace_id: null,
      assertion_id: "assertion-orders",
      attempt_count: 1,
      failure_count: 0,
    },
  });

  const liveEntries = queryClient.getQueryData(queryKey);
  assert.equal(liveEntries.length, 2);
  const retriedEntry = liveEntries.find(
    (entry) => entry.assertion_id === "assertion-products",
  );
  assert.equal(retriedEntry.result, "PASSED");
  assert.equal(retriedEntry.attempt_count, 5);
  assert.equal(retriedEntry.failure_count, 4);
  assert.equal(retriedEntry.actual, "one row");

  const reconciled = reconcileDashboardData(queryClient, queryKey, [
    {
      ...retriedEntry,
      id: 42,
      timestamp: "2024-06-01T10:00:04Z",
      result: "FAILED",
      actual: "no rows",
      error: "not ready on attempt 4",
      attempt_count: 4,
      failure_count: 4,
    },
  ]);
  assert.equal(reconciled.length, 2);
  const reconciledRetry = reconciled.find(
    (entry) => entry.assertion_id === "assertion-products",
  );
  assert.equal(reconciledRetry.result, "PASSED");
  assert.equal(reconciledRetry.attempt_count, 5);
  assert.equal(reconciledRetry.failure_count, 4);
});

test("live test data survives a stale persisted response", () => {
  const queryClient = new QueryClient();
  const queryKey = ["tests", "run-race"];

  queryClient.setQueryData(queryKey, [
    {
      id: "test-live",
      run_id: "run-race",
      test_name: "arrives over SSE",
      spec_name: "RealtimeSpec",
      test_path: ["RealtimeSpec", "arrives over SSE"],
      started_at: "2024-06-01T10:00:01Z",
      ended_at: null,
      status: "RUNNING",
      duration_ms: null,
      error: null,
    },
  ]);

  const reconciled = reconcileDashboardData(queryClient, queryKey, []);

  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].id, "test-live");
  assert.equal(reconciled[0].status, "RUNNING");
});

test("a live event cancels the conflicting REST request before updating its cache", async () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  let requestWasAborted = false;

  const staleRequest = queryClient
    .fetchQuery({
      queryKey: ["tests", "run-race"],
      queryFn: ({ signal }) =>
        new Promise((resolve, reject) => {
          signal.addEventListener(
            "abort",
            () => {
              requestWasAborted = true;
              reject(new DOMException("The operation was aborted", "AbortError"));
            },
            { once: true },
          );
          setTimeout(() => resolve([]), 100);
        }),
    })
    .catch((error) => error);

  applyLiveDashboardEvent(queryClient, {
    seq: 1,
    run_id: "run-race",
    event_type: "test_started",
    payload: {
      test_id: "test-live",
      test_name: "cannot be erased",
      spec_name: "RealtimeSpec",
      test_path: ["RealtimeSpec", "cannot be erased"],
      started_at: "2024-06-01T10:00:01Z",
      status: "RUNNING",
    },
  });

  await staleRequest;
  const tests = queryClient.getQueryData(["tests", "run-race"]);

  assert.equal(requestWasAborted, true);
  assert.equal(tests.length, 1);
  assert.equal(tests[0].id, "test-live");
});

test("persisted evidence replaces its temporary live duplicate during reconciliation", () => {
  const queryClient = new QueryClient();
  const queryKey = ["interactions", "run-race", "test-live"];
  const live = {
    id: -7,
    run_id: "run-race",
    test_id: "test-live",
    timestamp: "2024-06-01T10:00:02Z",
    system: "WireMock",
    protocol: "HTTP",
    method: "GET",
    target: "/health",
    matched: true,
    stub_id: "stub-1",
    attribution: "PROVEN_STUB",
    request_body: null,
    request_body_truncated: false,
    response_body: null,
    response_body_truncated: false,
    status: "200",
    latency_ms: 3,
    near_misses: [],
    trace_id: null,
    scenario_name: null,
    scenario_state: null,
    next_scenario_state: null,
    configured_delay_ms: null,
    fault: null,
    client_deadline_ms: null,
  };
  queryClient.setQueryData(queryKey, [live]);

  const reconciled = reconcileDashboardData(queryClient, queryKey, [{ ...live, id: 42 }]);

  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].id, 42);
});

test("evidence reconciliation preserves persisted and cached multiplicity", () => {
  const queryClient = new QueryClient();
  const queryKey = ["interactions", "run-race", "test-live"];
  const interaction = {
    id: -7,
    run_id: "run-race",
    test_id: "test-live",
    timestamp: "2024-06-01T10:00:02Z",
    system: "WireMock",
    protocol: "HTTP",
    method: "GET",
    target: "/health",
    matched: true,
    stub_id: "stub-1",
    attribution: "PROVEN_STUB",
    request_body: null,
    request_body_truncated: false,
    response_body: null,
    response_body_truncated: false,
    status: "200",
    latency_ms: 3,
    near_misses: [],
    trace_id: null,
    scenario_name: null,
    scenario_state: null,
    next_scenario_state: null,
    configured_delay_ms: null,
    fault: null,
    client_deadline_ms: null,
  };
  queryClient.setQueryData(queryKey, [interaction, { ...interaction, id: -8 }]);

  const reconciled = reconcileDashboardData(queryClient, queryKey, [
    { ...interaction, id: 42 },
  ]);

  assert.equal(reconciled.length, 2);
  assert.deepEqual(
    reconciled.map((record) => record.id).sort((left, right) => left - right),
    [-8, 42],
  );
});
