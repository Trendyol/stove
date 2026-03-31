export interface AppSummary {
  app_name: string;
  latest_run_id: string;
  latest_status: string;
  total_runs: number;
}

export type LiveRecordId = number | string;

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
  id: LiveRecordId;
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
  id: LiveRecordId;
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
  id: LiveRecordId;
  run_id: string;
  test_id: string;
  system: string;
  state_json: string;
  summary: string;
}

export interface LiveRunStartedPayload {
  app_name: string;
  started_at: string;
  systems: string[];
}

export interface LiveRunEndedPayload {
  ended_at: string;
  status: string;
  total_tests: number;
  passed: number;
  failed: number;
  duration_ms: number;
}

export interface LiveTestStartedPayload {
  test_id: string;
  test_name: string;
  spec_name: string;
  started_at: string;
  status: string;
}

export interface LiveTestEndedPayload {
  test_id: string;
  status: string;
  duration_ms: number;
  error: string | null;
  ended_at: string;
}

export interface LiveEntryRecordedPayload {
  id: LiveRecordId;
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

export interface LiveSpanRecordedPayload {
  id: LiveRecordId;
  test_id: string | null;
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

export interface LiveSnapshotPayload {
  id: LiveRecordId;
  test_id: string;
  system: string;
  state_json: string;
  summary: string;
}

export type LiveDashboardEvent =
  | {
      seq: number;
      run_id: string;
      event_type: "run_started";
      payload: LiveRunStartedPayload;
    }
  | {
      seq: number;
      run_id: string;
      event_type: "run_ended";
      payload: LiveRunEndedPayload;
    }
  | {
      seq: number;
      run_id: string;
      event_type: "test_started";
      payload: LiveTestStartedPayload;
    }
  | {
      seq: number;
      run_id: string;
      event_type: "test_ended";
      payload: LiveTestEndedPayload;
    }
  | {
      seq: number;
      run_id: string;
      event_type: "entry_recorded";
      payload: LiveEntryRecordedPayload;
    }
  | {
      seq: number;
      run_id: string;
      event_type: "span_recorded";
      payload: LiveSpanRecordedPayload;
    }
  | {
      seq: number;
      run_id: string;
      event_type: "snapshot";
      payload: LiveSnapshotPayload;
    };
