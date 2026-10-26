import { payloadSchemas } from "./live-schemas";
import type {
  AdminStatus,
  AgentSetupProvider,
  AgentSetupResponse,
  AgentSetupScope,
  AppSummary,
  DatabaseColumn,
  DatabaseQueryResult,
  DatabaseSchema,
  DatabaseTable,
  Entry,
  EvidenceCounts,
  EvidenceTarget,
  FocusedEvidence,
  HealthCheck,
  HealthResponse,
  McpMeta,
  MetaResponse,
  MockInteraction,
  MockWarning,
  PurgePreview,
  PurgeResult,
  Snapshot,
  Span,
  TopFilterCatalog,
  TopFilterSettings,
  TopFilterValue,
} from "./types";
import {
  arrayOf,
  isBoolean,
  isNullableString,
  isNumber,
  isRecord,
  isRunStatus,
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

export const isTopFilterSettings = object<TopFilterSettings>({ metadata_key: isNullableString });
export const isTopFilterCatalog = object<TopFilterCatalog>({
  settings: isTopFilterSettings,
  available_keys: isStringArray,
  values: arrayOf(object<TopFilterValue>({ value: isString, apps: arrayOf(isAppSummary) })),
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

const isHealthCheck = object<HealthCheck>({
  name: isString,
  healthy: isBoolean,
  message: isString,
});

export const isHealthResponse = object<HealthResponse>({
  healthy: isBoolean,
  stove_server_version: isString,
  backend: isString,
  checked_at: isString,
  checks: arrayOf(isHealthCheck),
});

export { isRun, isTest } from "./run-schemas";

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
  run_idle_timeout_seconds: isNumber,
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

const isEvidenceTarget = (value: unknown): value is EvidenceTarget => {
  if (!isRecord(value)) return false;
  switch (value.kind) {
    case "entry":
      return isEntry(value.value);
    case "span":
      return isSpan(value.value);
    case "snapshot":
      return isSnapshot(value.value);
    case "interaction":
      return isMockInteraction(value.value);
    case "warning":
      return isMockWarning(value.value);
    default:
      return false;
  }
};
export const isFocusedEvidence = object<FocusedEvidence>({
  target: isEvidenceTarget,
  entries: arrayOf(isEntry),
  spans: arrayOf(isSpan),
  interactions: arrayOf(isMockInteraction),
  warnings: arrayOf(isMockWarning),
  has_more_before: isBoolean,
  has_more_after: isBoolean,
  context_limit: isNumber,
});

const isAgentSetupScope = object<AgentSetupScope>({
  command: isString,
  skill_path: isString,
  config_path: isString,
  configuration: isString,
});
const isAgentSetupProvider = object<AgentSetupProvider>({
  id: isString,
  label: isString,
  note: isString,
  install_link: isNullableString,
  project: isAgentSetupScope,
  global: isAgentSetupScope,
});
export const isAgentSetupResponse = object<AgentSetupResponse>({
  endpoint: isString,
  providers: arrayOf(isAgentSetupProvider),
});
