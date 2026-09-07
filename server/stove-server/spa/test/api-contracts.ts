import type { LiveDashboardEvent, LiveEventOf } from "../src/api/types";

declare const event: LiveDashboardEvent;
declare const started: LiveEventOf<"run_started">;
declare const ended: LiveEventOf<"run_ended">;

// @ts-expect-error The generated discriminator rejects another variant's payload.
const wrongPayload: LiveEventOf<"run_ended"> = { ...ended, payload: started.payload };
void wrongPayload;
// @ts-expect-error Completed runs always carry a duration.
const missingDuration: LiveEventOf<"run_ended"> = { ...ended, payload: { ...ended.payload, duration_ms: null } };
void missingDuration;
// @ts-expect-error Callers cannot rewrite a received envelope.
event.seq = 2;
// @ts-expect-error Callers cannot rewrite a received payload.
started.payload.app_name = "changed";
if (event.event_type === "test_ended") {
  const testId: string = event.payload.test_id;
  void testId;
  // @ts-expect-error Narrowing by the generated tag excludes unrelated payload fields.
  event.payload.app_name;
}
