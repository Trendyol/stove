import type { Status } from "../utils/status";
import type { components } from "./generated/schema";

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
} as const satisfies { [Type in EventType as Uppercase<Type>]: Type };

export type EventType = LiveDashboardEvent["event_type"];

// REST shapes are owned by the Rust OpenAPI contract.
export type AppSummary = components["schemas"]["AppSummary"];
export type MetaResponse = components["schemas"]["MetaResponse"];
export type McpMeta = components["schemas"]["McpMeta"];
export type Run = components["schemas"]["Run"];
export type Test = components["schemas"]["Test"];
export type EvidenceCounts = components["schemas"]["EvidenceCounts"];
export type AdminStatus = components["schemas"]["StorageStats"];
export type PurgePreview = components["schemas"]["PurgePreview"];
export type PurgeResult = components["schemas"]["PurgeResult"];
export type DatabaseSchema = components["schemas"]["DatabaseSchema"];
export type DatabaseTable = components["schemas"]["DatabaseTable"];
export type DatabaseColumn = components["schemas"]["DatabaseColumn"];
export type DatabaseQueryResult = components["schemas"]["DatabaseQueryResult"];
export type Entry = components["schemas"]["Entry"];
export type Span = components["schemas"]["Span"];
export type Snapshot = components["schemas"]["Snapshot"];
export type MockInteraction = components["schemas"]["MockInteraction"];
export type MockWarning = components["schemas"]["MockWarning"];
export type RunStatus = components["schemas"]["RunStatus"];
export type SpanStatus = components["schemas"]["SpanStatus"];
export type PurgePreviewRequest = components["schemas"]["PurgePreviewRequest"];
export type PurgeRequest = components["schemas"]["PurgeRequest"];
export type RetentionRequest = components["schemas"]["RetentionRequest"];
export type DatabaseQueryRequest = components["schemas"]["DatabaseQueryRequest"];

// Preserve each generated union variant while making its envelope and payload readonly.
type ReadonlyEvent<Event> = { readonly [Field in keyof Event]: Readonly<Event[Field]> };

export type LiveDashboardEvent = ReadonlyEvent<components["schemas"]["LiveDashboardEvent"]>;
export type LiveEventOf<Type extends EventType> = Extract<LiveDashboardEvent, { event_type: Type }>;
export type LivePayloads = { [Type in EventType]: LiveEventOf<Type>["payload"] };

export type FocusedEvidence = components["schemas"]["FocusedEvidence"];
export type EvidenceTarget = components["schemas"]["EvidenceTarget"];
