import { testPages } from "./helpers/fixtures.mjs";
import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";
import { QueryClient } from "@tanstack/react-query";

const jiti = createJiti(import.meta.url);
const {
  applyLiveDashboardEvents,
  loadAndReconcileDashboardData,
  reconcileDashboardData,
} = await jiti.import("../src/api/live-cache.ts");
const { dashboardKeys } = await jiti.import("../src/api/query-keys.ts");
const { runPagesOptions } = await jiti.import("../src/api/run-queries.ts");
const { api } = await jiti.import("../src/api/client.ts");
const { dashboardQueries } = await jiti.import("../src/api/dashboard-queries.ts");


function pages(runs) { return { pages: [{ runs, next_cursor: null }], pageParams: [undefined] }; }
function setRuns(client, app, runs) { client.setQueryData(dashboardKeys.runs({ app }), pages(runs)); }
function getRuns(client, app) { return client.getQueryData(dashboardKeys.runs({ app })).pages.flatMap(p => p.runs); }

function applyLiveDashboardEvent(client, event) {
  applyLiveDashboardEvents(client, [event]);
}

test("new runs update only matching loaded pages without inventing a cursor for pending queries", () => {
  const client = new QueryClient();
  const matching = dashboardKeys.runs({ app: "live-app", metadata: { team: ["checkout"] } });
  const other = dashboardKeys.runs({ app: "live-app", metadata: { team: ["payments"] } });
  client.setQueryData(matching, pages([]));
  const pending = dashboardKeys.runs({ app: "live-app", metadata: {} });
  client.getQueryCache().build(client, { queryKey: pending });
  client.setQueryData(other, pages([]));
  applyLiveDashboardEvent(client, {
    seq: 1, run_id: "new", event_type: "run_started",
    payload: { app_name: "live-app", started_at: "2024-06-01T10:00:00Z", stove_version: null,
      systems: [], metadata: { team: "checkout" }, last_activity_at: "2024-06-01T10:00:00Z" },
  });
  assert.deepEqual(client.getQueryData(matching).pages[0].runs.map((run) => run.id), ["new"]);
  assert.deepEqual(client.getQueryData(other), pages([]));
  assert.equal(client.getQueryData(pending), undefined);
  client.clear();
});

test("applyLiveDashboardEvent updates run, test, and detail caches from live SSE payloads", () => {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["apps"], []);
  setRuns(queryClient, "live-app", []);
  queryClient.setQueryData(["tests", "run-live"], testPages());
  queryClient.setQueryData(["entries", "run-live", "test-1"], []);
  queryClient.setQueryData(["spans", "run-live", "test-1"], []);
  queryClient.setQueryData(["mock-interactions", "run-live", "test-1"], []);
  queryClient.setQueryData(["mock-interactions", "run-live"], []);
  queryClient.setQueryData(["mock-warnings", "run-live", "test-1"], []);
  queryClient.setQueryData(["mock-warnings", "run-live"], []);

  applyLiveDashboardEvent(queryClient, {
    seq: 1,
    run_id: "run-live",
    event_type: "run_started",
    payload: {
      app_name: "live-app",
      started_at: "2024-06-01T10:00:00Z",
      stove_version: "0.23.2",
      systems: ["HTTP"],
      metadata: { team: "checkout" },
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
  const runs = getRuns(queryClient, "live-app");
  const tests = queryClient.getQueryData(["tests", "run-live"]).pages.flatMap(p => p.tests);
  const entries = queryClient.getQueryData(["entries", "run-live", "test-1"]);
  const spans = queryClient.getQueryData(["spans", "run-live", "test-1"]);
  const testInteractions = queryClient.getQueryData(["mock-interactions", "run-live", "test-1"]);
  const runInteractions = queryClient.getQueryData(["mock-interactions", "run-live"]);
  const testWarnings = queryClient.getQueryData(["mock-warnings", "run-live", "test-1"]);
  const runWarnings = queryClient.getQueryData(["mock-warnings", "run-live"]);

  assert.equal(apps.length, 1);
  assert.equal(apps[0].latest_run_id, "run-live");
  assert.equal(apps[0].stove_version, "0.23.2");

  assert.equal(runs.length, 1);
  assert.equal(runs[0].status, "RUNNING");
  assert.equal(runs[0].stove_version, "0.23.2");
  assert.deepEqual(runs[0].metadata, { team: "checkout" });

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

test("a live run start updates its app cache without touching another app", () => {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["apps"], [
    {
      app_name: "live-app",
      latest_run_id: "old-run",
      latest_run_started_at: "2024-05-31T10:00:00Z",
      latest_status: "PASSED",
      stove_version: "0.23.1",
      metadata: { team: "checkout" },
    },
  ]);
  setRuns(queryClient, "live-app", [
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
      metadata: { team: "checkout" },
    },
  ]);
  setRuns(queryClient, "another-app", []);

  applyLiveDashboardEvent(queryClient, {
    seq: 1,
    run_id: "new-run",
    event_type: "run_started",
    payload: {
      app_name: "live-app",
      started_at: "2024-06-01T10:00:00Z",
      stove_version: "0.23.2",
      systems: ["HTTP"],
      metadata: { team: "checkout", "gitlab.pipeline_id": "42" },
    },
  });

  const apps = queryClient.getQueryData(["apps"]);
  const runs = getRuns(queryClient, "live-app");
  assert.equal(apps[0].latest_run_id, "new-run");
  assert.equal("total_runs" in apps[0], false);
  assert.deepEqual(runs.map((run) => run.id), ["new-run", "old-run"]);
  assert.deepEqual(getRuns(queryClient, "another-app"), []);
});

test("app reconciliation retains a newer persisted run when the cache contains an older run", () => {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["apps"], [
    {
      app_name: "live-app",
      latest_run_id: "old-run",
      latest_run_started_at: "2024-05-31T10:00:00Z",
      latest_status: "RUNNING",
      stove_version: "0.23.1",
    },
  ]);

  const reconciledApps = reconcileDashboardData(queryClient, dashboardQueries.apps(), [
    {
      app_name: "live-app",
      latest_run_id: "new-run",
      latest_run_started_at: "2024-06-01T10:00:00Z",
      latest_status: "PASSED",
      stove_version: "0.23.2",
    },
  ]);
  assert.equal(reconciledApps[0].latest_run_id, "new-run");
  assert.equal(reconciledApps[0].latest_status, "PASSED");
});

test("a newer live app survives a stale in-flight apps response", async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const queryKey = ["apps"];
  const response = deferredPromise();
  queryClient.setQueryData(queryKey, [
    {
      app_name: "live-app",
      latest_run_id: "old-run",
      latest_run_started_at: "2024-05-31T10:00:00Z",
      latest_status: "PASSED",
      stove_version: "0.23.1",
      metadata: {},
    },
  ]);

  const request = queryClient.fetchQuery({
    queryKey,
    queryFn: () => loadAndReconcileDashboardData(queryClient, { ...dashboardQueries.apps(), load: () => response.promise }),
  });
  applyLiveDashboardEvent(queryClient, runStartedEvent("new-run", "2024-06-01T10:00:00Z"));
  response.resolve([
    {
      app_name: "live-app",
      latest_run_id: "old-run",
      latest_run_started_at: "2024-05-31T10:00:00Z",
      latest_status: "PASSED",
      stove_version: "0.23.1",
      metadata: {},
    },
  ]);
  await request;

  assert.equal(queryClient.getQueryData(queryKey)[0].latest_run_id, "new-run");
});

test("a newer apps response wins over an older live event received in flight", async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const queryKey = ["apps"];
  const response = deferredPromise();
  queryClient.setQueryData(queryKey, queryKey[0] === "runs" ? pages([]) : queryKey[0] === "tests" ? testPages() : []);

  const request = queryClient.fetchQuery({
    queryKey,
    queryFn: () => loadAndReconcileDashboardData(queryClient, { ...dashboardQueries.apps(), load: () => response.promise }),
  });
  applyLiveDashboardEvent(queryClient, runStartedEvent("old-run", "2024-05-31T10:00:00Z"));
  response.resolve([
    {
      app_name: "live-app",
      latest_run_id: "new-run",
      latest_run_started_at: "2024-06-01T10:00:00Z",
      latest_status: "RUNNING",
      stove_version: "0.23.2",
      metadata: {},
    },
  ]);
  await request;

  assert.equal(queryClient.getQueryData(queryKey)[0].latest_run_id, "new-run");
});

test("live assertion retries collapse to the latest attempt and retain failure history", () => {
  const queryClient = new QueryClient();
  const queryKey = ["entries", "run-retry", "test-retry"];
  queryClient.setQueryData(queryKey, queryKey[0] === "runs" ? pages([]) : queryKey[0] === "tests" ? testPages() : []);

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

  const reconciled = reconcileDashboardData(queryClient, dashboardQueries.entries("run-retry", "test-retry"), [
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

test("test events cancel stale page reads and preserve cached live data", async () => {
  const client = new QueryClient({defaultOptions:{queries:{retry:false}}});
  const key = ["tests", "run-race"];
  client.setQueryData(key, testPages());
  let aborted = false;
  const request = client.fetchQuery({queryKey:key, queryFn: ({signal}) => new Promise((resolve, reject) => {
    signal.addEventListener("abort", () => { aborted=true; reject(new Error("aborted")); });
  })}).catch(error => error);
  applyLiveDashboardEvent(client, {seq:1, run_id:"run-race", event_type:"test_started", payload:{test_id:"test-live", test_name:"live", spec_name:"Spec", test_path:[], started_at:"2024-06-01T00:00:00Z", status:"RUNNING"}});
  await request;
  assert.equal(aborted,true);
  assert.equal(client.getQueryData(key).pages[0].tests[0].id,"test-live");
  assert.equal(client.getQueryState(key).isInvalidated,true);
  client.clear();
});

test("batched test starts update one summary query without creating detail caches", () => {
  const queryClient = new QueryClient();
  queryClient.setQueryData(["tests", "run-batch"], testPages());
  let testQueryUpdates = 0;
  const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
    if (event.type === "updated" && event.query.queryHash.includes("run-batch")) {
      testQueryUpdates += 1;
    }
  });

  applyLiveDashboardEvents(
    queryClient,
    Array.from({ length: 1_000 }, (_, index) => ({
      seq: index + 1,
      run_id: "run-batch",
      event_type: "test_started",
      payload: {
        test_id: `test-${index}`,
        test_name: `test ${index}`,
        spec_name: "BatchSpec",
        test_path: ["BatchSpec", `test ${index}`],
        started_at: `2024-06-01T10:${String(Math.floor(index / 60)).padStart(2, "0")}:${String(index % 60).padStart(2, "0")}Z`,
        status: "RUNNING",
      },
    })),
  );
  unsubscribe();

  assert.equal(queryClient.getQueryData(["tests", "run-batch"]).pages.flatMap(p => p.tests).length, 100);
  assert.equal(queryClient.getQueryCache().getAll().length, 1);
  assert.equal(testQueryUpdates, 2); // One data write and one invalidation.
});

test("a frame batch produces the same cache state as sequential live events", () => {
  const sequential = new QueryClient();
  const batched = new QueryClient();
  const queryKeys = [
    ["apps"],
    dashboardKeys.runs({ app: "live-app" }),
    ["tests", "run-batch-equivalence"],
    ["entries", "run-batch-equivalence", "test-1"],
  ];
  for (const queryClient of [sequential, batched]) {
    for (const queryKey of queryKeys) queryClient.setQueryData(queryKey, queryKey[0] === "runs" ? pages([]) : queryKey[0] === "tests" ? testPages() : []);
  }

  const events = [
    runStartedEvent("run-batch-equivalence", "2024-06-01T10:00:00Z"),
    {
      seq: 2,
      run_id: "run-batch-equivalence",
      event_type: "test_started",
      payload: {
        test_id: "test-1",
        test_name: "keeps equivalent state",
        spec_name: "BatchSpec",
        test_path: ["BatchSpec", "keeps equivalent state"],
        started_at: "2024-06-01T10:00:01Z",
        status: "RUNNING",
      },
    },
    {
      seq: 3,
      run_id: "run-batch-equivalence",
      event_type: "entry_recorded",
      payload: {
        id: -3,
        test_id: "test-1",
        timestamp: "2024-06-01T10:00:02Z",
        system: "HTTP",
        action: "GET /ready",
        result: "PASSED",
        input: null,
        output: null,
        metadata: "{}",
        expected: null,
        actual: null,
        error: null,
        trace_id: null,
        assertion_id: "assertion-ready",
        attempt_count: 1,
        failure_count: 0,
      },
    },
    {
      seq: 4,
      run_id: "run-batch-equivalence",
      event_type: "test_ended",
      payload: {
        test_id: "test-1",
        status: "PASSED",
        duration_ms: 1_000,
        error: null,
        ended_at: "2024-06-01T10:00:03Z",
      },
    },
    {
      seq: 5,
      run_id: "run-batch-equivalence",
      event_type: "run_ended",
      payload: {
        status: "PASSED",
        total_tests: 1,
        passed: 1,
        failed: 0,
        duration_ms: 3_000,
        ended_at: "2024-06-01T10:00:03Z",
      },
    },
  ];

  for (const event of events) applyLiveDashboardEvent(sequential, event);
  applyLiveDashboardEvents(batched, events);

  for (const queryKey of queryKeys) {
    assert.deepEqual(batched.getQueryData(queryKey), sequential.getQueryData(queryKey));
  }
});

test("persisted evidence replaces its temporary live duplicate during reconciliation", () => {
  const queryClient = new QueryClient();
  const queryKey = ["mock-interactions", "run-race", "test-live"];
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

  const reconciled = reconcileDashboardData(queryClient, dashboardQueries.testMockInteractions("run-race", "test-live"), [{ ...live, id: 42 }]);

  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].id, 42);
});

function runStartedEvent(runId, startedAt) {
  return {
    seq: 1,
    run_id: runId,
    event_type: "run_started",
    payload: {
      app_name: "live-app",
      started_at: startedAt,
      stove_version: "0.23.2",
      systems: [],
      metadata: {},
    },
  };
}

function deferredPromise() {
  let resolve;
  const promise = new Promise((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

test("evidence reconciliation preserves persisted and cached multiplicity", () => {
  const queryClient = new QueryClient();
  const queryKey = ["mock-interactions", "run-race", "test-live"];
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

  const reconciled = reconcileDashboardData(queryClient, dashboardQueries.testMockInteractions("run-race", "test-live"), [
    { ...interaction, id: 42 },
  ]);

  assert.equal(reconciled.length, 2);
  assert.deepEqual(
    reconciled.map((record) => record.id).sort((left, right) => left - right),
    [-8, 42],
  );
});

for (const apply of [applyLiveDashboardEvent, (client, event) => applyLiveDashboardEvents(client, [event, event])]) {
  test(`${apply.name || "batch"} preserves published records and arrays during retries`, () => {
    const queryClient = new QueryClient();
    const key = ["entries", "immutable-run", "test-1"];
    const previous = Object.freeze({
      id: 7,
      run_id: "immutable-run",
      test_id: "test-1",
      timestamp: "2024-06-01T10:00:00Z",
      assertion_id: "assertion-1",
      attempt_count: 1,
      failure_count: 1,
      result: "FAILED",
    });
    queryClient.setQueryData(key, Object.freeze([previous]));
    const published = queryClient.getQueryData(key);
    Object.freeze(published);
    Object.freeze(published[0]);
    const payload = Object.freeze({
      ...previous,
      id: -8,
      timestamp: "2024-06-01T10:00:01Z",
      attempt_count: 2,
      result: "PASSED",
    });

    apply(queryClient, { seq: 8, run_id: "immutable-run", event_type: "entry_recorded", payload });

    assert.equal(published.length, 1);
    assert.equal(published[0].result, "FAILED");
    assert.equal(published[0].attempt_count, 1);
    assert.equal(payload.id, -8);
    const updated = queryClient.getQueryData(key);
    assert.notEqual(updated, published);
    assert.deepEqual(updated.map(({ id, result, attempt_count, failure_count }) =>
      ({ id, result, attempt_count, failure_count })),
    [{ id: 7, result: "PASSED", attempt_count: 2, failure_count: 1 }]);
  });
}

test("reconciliation preserves both input snapshots when a retry arrives during a read", () => {
  const queryClient = new QueryClient();
  const key = ["entries", "run-1", "test-1"];
  const persisted = Object.freeze([Object.freeze({
    id: 7, assertion_id: "a", attempt_count: 1, failure_count: 1,
    result: "FAILED", timestamp: "2024-06-01T10:00:00Z",
  })]);
  const cached = Object.freeze([Object.freeze({
    ...persisted[0], id: -8, attempt_count: 2, result: "PASSED",
    timestamp: "2024-06-01T10:00:01Z",
  })]);
  queryClient.setQueryData(key, cached);
  const result = reconcileDashboardData(queryClient, dashboardQueries.entries("run-1", "test-1"), persisted);
  assert.equal(result[0].id, 7);
  assert.equal(result[0].result, "PASSED");
  assert.equal(result[0].failure_count, 1);
  assert.equal(persisted[0].result, "FAILED");
  assert.equal(cached[0].id, -8);
});

test("deletion events remove runs and evidence despite older REST requests", async () => {
  const { run, testRecord, app } = await import("./helpers/fixtures.mjs");
  const client = new QueryClient();
  client.setQueryData(["apps"], [{ ...app, latest_run_id: run.id }]);
  setRuns(client, "demo", [run]);
  client.setQueryData(["tests", run.id], testPages([testRecord]));
  client.setQueryData(["linked", run.id, "run"], run);
  client.setQueryData(["entries", run.id, testRecord.id], [{ id: 1 }]);
  client.setQueryData(["trace", "shared-trace"], [{run_id: run.id}, {run_id: "other-run"}]);
  let resolve;
  const pending = new Promise((done) => { resolve = done; });
  const query = { ...dashboardQueries.entries(run.id, testRecord.id), load: () => pending };
  const request = loadAndReconcileDashboardData(client, query);
  applyLiveDashboardEvent(client, { seq: 10, run_id: run.id, event_type: "run_deleted", payload: {} });
  resolve([testRecord]);
  assert.deepEqual(await request, []);
  assert.deepEqual(getRuns(client, "demo"), []);
  assert.deepEqual(client.getQueryData(["apps"]), []);
  assert.deepEqual(client.getQueryData(["tests", run.id]).pages[0].tests, []);
  assert.deepEqual(client.getQueryData(["entries", run.id, testRecord.id]), []);
  assert.equal(client.getQueryData(["linked", run.id, "run"]), null);
  assert.deepEqual(client.getQueryData(["trace", "shared-trace"]), [{run_id: "other-run"}]);
  client.clear();
});
