export const counts = {
  tests: 1,
  entries: 1,
  spans: 1,
  snapshots: 0,
  mock_interactions: 0,
  mock_warnings: 0,
};
export const app = {
  app_name: "demo",
  latest_run_id: "run-1",
  latest_run_started_at: "2026-09-07T10:00:00Z",
  latest_status: "PASSED",
  stove_version: "0.24.0",
  metadata: {},
};
export const run = {
  id: "run-1",
  app_name: "demo",
  started_at: app.latest_run_started_at,
  ended_at: "2026-09-07T10:00:05Z",
  status: "PASSED",
  total_tests: 1,
  passed: 1,
  failed: 0,
  duration_ms: 5000,
  stove_version: "0.24.0",
  systems: ["HTTP"],
  metadata: {},
};
export const testRecord = {
  id: "test-1",
  run_id: run.id,
  test_name: "creates a product",
  spec_name: "ProductSpec",
  test_path: ["ProductSpec", "creates a product"],
  started_at: run.started_at,
  ended_at: run.ended_at,
  status: "PASSED",
  duration_ms: 5000,
  error: null,
};
export const entry = {
  id: 1,
  run_id: run.id,
  test_id: testRecord.id,
  timestamp: run.started_at,
  system: "HTTP",
  action: "POST /products",
  result: "PASSED",
  input: '{"name":"Stove"}',
  output: '{"id":42}',
  metadata: null,
  expected: "201",
  actual: "201",
  error: null,
  trace_id: "trace-1",
  assertion_id: "assertion-1",
  attempt_count: 1,
  failure_count: 0,
};
export const span = {
  id: 1,
  run_id: run.id,
  trace_id: "trace-1",
  span_id: "span-1",
  parent_span_id: null,
  operation_name: "POST /products",
  service_name: "product-api",
  start_time_nanos: 1000000000,
  end_time_nanos: 1050000000,
  status: "OK",
  attributes: '{"http.method":"POST"}',
  exception_type: null,
  exception_message: null,
  exception_stack_trace: null,
};
export const adminStatus = {
  backend: "sqlite",
  retention_runs_per_app: 5,
  runs: 1,
  running_runs: 0,
  evidence: counts,
};
export const schema = {
  backend: "sqlite",
  tables: [
    {
      name: "runs",
      columns: [{ name: "id", data_type: "TEXT", nullable: false, primary_key: true }],
    },
    {
      name: "tests",
      columns: [{ name: "id", data_type: "TEXT", nullable: false, primary_key: true }],
    },
  ],
};
export const databaseResult = {
  columns: ["id"],
  rows: [["run-1"]],
  affected_rows: 0,
  truncated: false,
};
export function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

export const meta = {
  stove_server_version: "0.24.0",
  mcp: { enabled: true, endpoint: "/mcp", scope: "read", transport: "http" },
};
