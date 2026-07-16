import type { Status } from "../utils/status";

export type { Status };

export const EVENT_TYPE = {
  RUN_STARTED: "run_started",
  RUN_ENDED: "run_ended",
  TEST_STARTED: "test_started",
  TEST_ENDED: "test_ended",
  ENTRY_RECORDED: "entry_recorded",
  SPAN_RECORDED: "span_recorded",
  SNAPSHOT: "snapshot",
  MOCK_INTERACTION: "mock_interaction",
  MOCK_WARNING: "mock_warning",
} as const;

export type EventType = (typeof EVENT_TYPE)[keyof typeof EVENT_TYPE];

export interface AppSummary {
  app_name: string;
  latest_run_id: string;
  latest_status: Status;
  stove_version: string | null;
  total_runs: number;
}

export interface MetaResponse {
  stove_cli_version: string;
}

export type LiveRecordId = number | string;

export interface Run {
  id: string;
  app_name: string;
  started_at: string;
  ended_at: string | null;
  status: Status;
  total_tests: number;
  passed: number;
  failed: number;
  duration_ms: number | null;
  stove_version: string | null;
  systems: string[];
}

export interface Test {
  id: string;
  run_id: string;
  test_name: string;
  spec_name: string;
  test_path: string[];
  started_at: string;
  ended_at: string | null;
  status: Status;
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
  status: Status;
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
  captured_at: string | null;
  trigger: string;
}

export interface MockInteraction {
  id: LiveRecordId;
  run_id: string;
  test_id: string | null;
  timestamp: string;
  system: string;
  protocol: string;
  method: string;
  target: string;
  matched: boolean;
  stub_id: string | null;
  attribution: string;
  request_body: string | null;
  request_body_truncated: boolean;
  response_body: string | null;
  response_body_truncated: boolean;
  status: string;
  latency_ms: number | null;
  near_misses: string[];
  trace_id: string | null;
  scenario_name: string | null;
  scenario_state: string | null;
  next_scenario_state: string | null;
  configured_delay_ms: number | null;
  fault: string | null;
  client_deadline_ms: number | null;
}

export interface MockWarning {
  id: LiveRecordId;
  run_id: string;
  test_id: string | null;
  timestamp: string;
  system: string;
  kind: string;
  message: string;
  stub_id: string | null;
  target: string | null;
}

export interface LiveRunStartedPayload {
  app_name: string;
  started_at: string;
  stove_version: string | null;
  systems: string[];
}

export interface LiveRunEndedPayload {
  ended_at: string;
  status: Status;
  total_tests: number;
  passed: number;
  failed: number;
  duration_ms: number;
}

export interface LiveTestStartedPayload {
  test_id: string;
  test_name: string;
  spec_name: string;
  test_path: string[];
  started_at: string;
  status: Status;
}

export interface LiveTestEndedPayload {
  test_id: string;
  status: Status;
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
  status: Status;
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
  captured_at: string | null;
  trigger: string;
}

export interface LiveMockInteractionPayload {
  id: LiveRecordId;
  test_id: string | null;
  timestamp: string;
  system: string;
  protocol: string;
  method: string;
  target: string;
  matched: boolean;
  stub_id: string | null;
  attribution: string;
  request_body: string | null;
  request_body_truncated: boolean;
  response_body: string | null;
  response_body_truncated: boolean;
  status: string;
  latency_ms: number | null;
  near_misses: string[];
  trace_id: string | null;
  scenario_name: string | null;
  scenario_state: string | null;
  next_scenario_state: string | null;
  configured_delay_ms: number | null;
  fault: string | null;
  client_deadline_ms: number | null;
}

export interface LiveMockWarningPayload {
  id: LiveRecordId;
  test_id: string | null;
  timestamp: string;
  system: string;
  kind: string;
  message: string;
  stub_id: string | null;
  target: string | null;
}

interface LiveEventBase {
  seq: number;
  run_id: string;
}

export type LiveDashboardEvent =
  | (LiveEventBase & { event_type: typeof EVENT_TYPE.RUN_STARTED; payload: LiveRunStartedPayload })
  | (LiveEventBase & { event_type: typeof EVENT_TYPE.RUN_ENDED; payload: LiveRunEndedPayload })
  | (LiveEventBase & {
      event_type: typeof EVENT_TYPE.TEST_STARTED;
      payload: LiveTestStartedPayload;
    })
  | (LiveEventBase & { event_type: typeof EVENT_TYPE.TEST_ENDED; payload: LiveTestEndedPayload })
  | (LiveEventBase & {
      event_type: typeof EVENT_TYPE.ENTRY_RECORDED;
      payload: LiveEntryRecordedPayload;
    })
  | (LiveEventBase & {
      event_type: typeof EVENT_TYPE.SPAN_RECORDED;
      payload: LiveSpanRecordedPayload;
    })
  | (LiveEventBase & { event_type: typeof EVENT_TYPE.SNAPSHOT; payload: LiveSnapshotPayload })
  | (LiveEventBase & {
      event_type: typeof EVENT_TYPE.MOCK_INTERACTION;
      payload: LiveMockInteractionPayload;
    })
  | (LiveEventBase & {
      event_type: typeof EVENT_TYPE.MOCK_WARNING;
      payload: LiveMockWarningPayload;
    });
