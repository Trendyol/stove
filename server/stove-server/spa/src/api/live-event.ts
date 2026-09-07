import { payloadSchemas } from "./live-schemas";
import { EVENT_TYPE, type EventType, type LiveDashboardEvent } from "./types";
import { isNumber, isRecord, isString } from "./validation";

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
  if (!validPayload) return undefined;

  // Project only validated fields: a newer server may send extra properties,
  // which must not overwrite unrelated cache fields when payloads are spread.
  // The mapped schema enforces completeness and field types at compile time.
  return {
    seq: value.seq,
    run_id: value.run_id,
    event_type: value.event_type,
    payload: Object.fromEntries(Object.keys(schema).map((field) => [field, payload[field]])),
  } as LiveDashboardEvent;
}

function isEventType(value: unknown): value is EventType {
  return Object.values(EVENT_TYPE).some((eventType) => eventType === value);
}
