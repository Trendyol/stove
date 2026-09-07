import { payloadSchemas } from "./live-schemas";
import type {
  AdminStatus,
  AppSummary,
  DatabaseColumn,
  DatabaseQueryResult,
  DatabaseSchema,
  DatabaseTable,
  Entry,
  EvidenceCounts,
  McpMeta,
  MetaResponse,
  MockInteraction,
  MockWarning,
  PurgePreview,
  PurgeResult,
  Run,
  Snapshot,
  Span,
  Test,
} from "./types";
import {
  arrayOf,
  isBoolean,
  isNullableNumber,
  isNullableString,
  isNumber,
  isRunStatus,
  isStatus,
  isString,
  isStringArray,
  isStringRecord,
  object,
} from "./validation";

export const isAppSummary = object<AppSummary>({
  app_name: isString,
  latest_run_id: isString,
  latest_run_started_at: isString,
  latest_status: isRunStatus,
  stove_version: isNullableString,
  metadata: isStringRecord,
});

const isMcpMeta = object<McpMeta>({
  enabled: isBoolean,
  endpoint: isString,
  scope: isString,
  transport: isString,
});

export const isMetaResponse = object<MetaResponse>({
  stove_server_version: isString,
  mcp: isMcpMeta,
});

export const isRun = object<Run>({
  id: isString,
  app_name: isString,
  started_at: isString,
  ended_at: isNullableString,
  status: isRunStatus,
  total_tests: isNumber,
  passed: isNumber,
  failed: isNumber,
  duration_ms: isNullableNumber,
  stove_version: isNullableString,
  systems: isStringArray,
  metadata: isStringRecord,
});

export const isTest = object<Test>({
  id: isString,
  run_id: isString,
  test_name: isString,
  spec_name: isString,
  test_path: isStringArray,
  started_at: isString,
  ended_at: isNullableString,
  status: isStatus,
  duration_ms: isNullableNumber,
  error: isNullableString,
});

const isEvidenceCounts = object<EvidenceCounts>({
  tests: isNumber,
  entries: isNumber,
  spans: isNumber,
  snapshots: isNumber,
  mock_interactions: isNumber,
  mock_warnings: isNumber,
});

export const isAdminStatus = object<AdminStatus>({
  backend: isString,
  retention_runs_per_app: isNumber,
  runs: isNumber,
  running_runs: isNumber,
  evidence: isEvidenceCounts,
});

export const isPurgePreview = object<PurgePreview>({
  run_ids: isStringArray,
  run_count: isNumber,
  evidence: isEvidenceCounts,
});

export const isPurgeResult = object<PurgeResult>({
  purged_run_ids: isStringArray,
  purged_runs: isNumber,
  evidence: isEvidenceCounts,
});

const isDatabaseColumn = object<DatabaseColumn>({
  name: isString,
  data_type: isString,
  nullable: isBoolean,
  primary_key: isBoolean,
});

const isDatabaseTable = object<DatabaseTable>({
  name: isString,
  columns: arrayOf(isDatabaseColumn),
});

export const isDatabaseSchema = object<DatabaseSchema>({
  backend: isString,
  tables: arrayOf(isDatabaseTable),
});

export const isDatabaseQueryResult = object<DatabaseQueryResult>({
  columns: isStringArray,
  rows: arrayOf(arrayOf(isNullableString)),
  affected_rows: isNumber,
  truncated: isBoolean,
});

export const isEntry = object<Entry>({ ...payloadSchemas.entry_recorded, run_id: isString });
const { test_id: _testId, ...spanFields } = payloadSchemas.span_recorded;
export const isSpan = object<Span>({ ...spanFields, run_id: isString });
export const isSnapshot = object<Snapshot>({ ...payloadSchemas.snapshot, run_id: isString });
export const isMockInteraction = object<MockInteraction>({
  ...payloadSchemas.mock_interaction,
  run_id: isString,
});
export const isMockWarning = object<MockWarning>({
  ...payloadSchemas.mock_warning,
  run_id: isString,
});
