import type { Status } from "../utils/status";
import { EVENT_TYPE, type EventType, type LiveDashboardEvent } from "./types";

type JsonRecord = Record<string, unknown>;
type Validator = (value: unknown) => boolean;
type PayloadSchema = Record<string, Validator>;

const isString: Validator = (value) => typeof value === "string";
const isNumber: Validator = (value) => typeof value === "number" && Number.isFinite(value);
const isBoolean: Validator = (value) => typeof value === "boolean";
const isNullableString: Validator = (value) => value === null || typeof value === "string";
const isNullableNumber: Validator = (value) => value === null || isNumber(value);
const isRecordId: Validator = (value) => typeof value === "string" || isNumber(value);
const isStringArray: Validator = (value) =>
  Array.isArray(value) && value.every((item) => typeof item === "string");
const isStringRecord: Validator = (value) =>
  isRecord(value) && Object.values(value).every((item) => typeof item === "string");
const isStatus: Validator = (value): value is Status =>
  value === "RUNNING" || value === "PASSED" || value === "FAILED" || value === "ERROR";

const payloadSchemas = {
  [EVENT_TYPE.RUN_STARTED]: {
    app_name: isString,
    started_at: isString,
    stove_version: isNullableString,
    systems: isStringArray,
    metadata: isStringRecord,
  },
  [EVENT_TYPE.RUN_ENDED]: {
    ended_at: isString,
    status: isStatus,
    total_tests: isNumber,
    passed: isNumber,
    failed: isNumber,
    duration_ms: isNumber,
  },
  [EVENT_TYPE.TEST_STARTED]: {
    test_id: isString,
    test_name: isString,
    spec_name: isString,
    test_path: isStringArray,
    started_at: isString,
    status: isStatus,
  },
  [EVENT_TYPE.TEST_ENDED]: {
    test_id: isString,
    status: isStatus,
    duration_ms: isNumber,
    error: isNullableString,
    ended_at: isString,
  },
  [EVENT_TYPE.ENTRY_RECORDED]: {
    id: isRecordId,
    test_id: isString,
    timestamp: isString,
    system: isString,
    action: isString,
    result: isString,
    input: isNullableString,
    output: isNullableString,
    metadata: isNullableString,
    expected: isNullableString,
    actual: isNullableString,
    error: isNullableString,
    trace_id: isNullableString,
    assertion_id: isString,
    attempt_count: isNumber,
    failure_count: isNumber,
  },
  [EVENT_TYPE.SPAN_RECORDED]: {
    id: isRecordId,
    test_id: isNullableString,
    trace_id: isString,
    span_id: isString,
    parent_span_id: isNullableString,
    operation_name: isString,
    service_name: isString,
    start_time_nanos: isNumber,
    end_time_nanos: isNumber,
    status: isStatus,
    attributes: isNullableString,
    exception_type: isNullableString,
    exception_message: isNullableString,
    exception_stack_trace: isNullableString,
  },
  [EVENT_TYPE.SNAPSHOT]: {
    id: isRecordId,
    test_id: isString,
    system: isString,
    state_json: isString,
    summary: isString,
    captured_at: isNullableString,
    trigger: isString,
  },
  [EVENT_TYPE.MOCK_INTERACTION]: {
    id: isRecordId,
    test_id: isNullableString,
    timestamp: isString,
    system: isString,
    protocol: isString,
    method: isString,
    target: isString,
    matched: isBoolean,
    stub_id: isNullableString,
    attribution: isString,
    request_body: isNullableString,
    request_body_truncated: isBoolean,
    response_body: isNullableString,
    response_body_truncated: isBoolean,
    status: isString,
    latency_ms: isNullableNumber,
    near_misses: isStringArray,
    trace_id: isNullableString,
    scenario_name: isNullableString,
    scenario_state: isNullableString,
    next_scenario_state: isNullableString,
    configured_delay_ms: isNullableNumber,
    fault: isNullableString,
    client_deadline_ms: isNullableNumber,
  },
  [EVENT_TYPE.MOCK_WARNING]: {
    id: isRecordId,
    test_id: isNullableString,
    timestamp: isString,
    system: isString,
    kind: isString,
    message: isString,
    stub_id: isNullableString,
    target: isNullableString,
  },
} satisfies Record<EventType, PayloadSchema>;

export function parseLiveDashboardEvent(json: string): LiveDashboardEvent | undefined {
  let value: unknown;
  try {
    value = JSON.parse(json) as unknown;
  } catch {
    return undefined;
  }
  if (!isRecord(value) || !isNumber(value.seq) || !isString(value.run_id)) return undefined;
  if (!isEventType(value.event_type)) return undefined;
  const payload = value.payload;
  if (!isRecord(payload)) return undefined;

  const schema = payloadSchemas[value.event_type];
  const validPayload = Object.entries(schema).every(([field, validate]) =>
    validate(payload[field]),
  );
  return validPayload ? (value as unknown as LiveDashboardEvent) : undefined;
}

function isEventType(value: unknown): value is EventType {
  return typeof value === "string" && Object.values(EVENT_TYPE).includes(value as EventType);
}

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
