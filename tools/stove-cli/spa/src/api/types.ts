export interface AppSummary {
  app_name: string;
  latest_run_id: string;
  latest_status: string;
  total_runs: number;
}

export interface Run {
  id: string;
  app_name: string;
  started_at: string;
  ended_at: string | null;
  status: string;
  total_tests: number;
  passed: number;
  failed: number;
  duration_ms: number | null;
  systems: string[];
}

export interface Test {
  id: string;
  run_id: string;
  test_name: string;
  spec_name: string;
  started_at: string;
  ended_at: string | null;
  status: string;
  duration_ms: number | null;
  error: string | null;
}

export interface Entry {
  id: number;
  run_id: string;
  test_id: string;
  timestamp: string;
  system: string;
  action: string;
  result: string;
  input: string | null;
  output: string | null;
  metadata: string | null;
  expected: string | null;
  actual: string | null;
  error: string | null;
  trace_id: string | null;
}

export interface Span {
  id: number;
  run_id: string;
  trace_id: string;
  span_id: string;
  parent_span_id: string | null;
  operation_name: string;
  service_name: string;
  start_time_nanos: number;
  end_time_nanos: number;
  status: string;
  attributes: string | null;
  exception_type: string | null;
  exception_message: string | null;
  exception_stack_trace: string | null;
}

export interface Snapshot {
  id: number;
  run_id: string;
  test_id: string;
  system: string;
  state_json: string;
  summary: string;
}
