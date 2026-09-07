import { appPath, type EvidenceFocus } from "../utils/location";
import * as schema from "./response-schemas";
import type {
  DatabaseQueryRequest,
  PurgePreviewRequest,
  PurgeRequest,
  RetentionRequest,
} from "./types";
import { arrayOf, nullable, type Validator } from "./validation";

const BASE = "/api/v1";
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
const encodePath = (value: string) => encodeURIComponent(value);

async function get<T>(url: string, validate: Validator<T>, signal?: AbortSignal): Promise<T> {
  const res = await fetch(appPath(`${BASE}${url}`), { signal });
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`);
  return readResponse(res, url, validate);
}

async function del(url: string): Promise<void> {
  const res = await fetch(appPath(`${BASE}${url}`), { method: "DELETE" });
  if (!res.ok) throw new ApiError(res.status, `${res.status} ${res.statusText}`);
}

async function send<T>(
  url: string,
  method: "POST" | "PUT",
  body: unknown,
  validate: Validator<T>,
): Promise<T> {
  const res = await fetch(appPath(`${BASE}${url}`), {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return readResponse(res, url, validate);
}

async function readResponse<T>(
  response: Response,
  url: string,
  validate: Validator<T>,
): Promise<T> {
  const value: unknown = await response.json();
  if (!validate(value)) throw new Error(`Invalid response from ${BASE}${url}`);
  return value;
}

export const api = {
  getRun: async (runId: string, signal?: AbortSignal) => {
    const run = await get(`/runs/${encodePath(runId)}`, nullable(schema.isRun), signal);
    if (run === null) throw new ApiError(404, "Run unavailable");
    return run;
  },
  getTest: (runId: string, testId: string, signal?: AbortSignal) =>
    get(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}`, schema.isTest, signal),
  getFocusedEvidence: (
    runId: string,
    testId: string | undefined,
    focus: EvidenceFocus,
    context: number,
    signal?: AbortSignal,
  ) =>
    get(
      `/runs/${encodePath(runId)}${testId === undefined ? "" : `/tests/${encodePath(testId)}`}/evidence/${focus.kind}/${focus.id}?context=${context}`,
      schema.isFocusedEvidence,
      signal,
    ),
  getMeta: (signal?: AbortSignal) => get("/meta", schema.isMetaResponse, signal),
  getApps: (signal?: AbortSignal) => get("/apps", arrayOf(schema.isAppSummary), signal),
  getRuns: (app?: string, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (app) params.set("app", app);
    const query = params.toString();
    return get(query ? `/runs?${query}` : "/runs", arrayOf(schema.isRun), signal);
  },
  getTests: (runId: string, signal?: AbortSignal) =>
    get(`/runs/${encodePath(runId)}/tests`, arrayOf(schema.isTest), signal),
  getEntries: (runId: string, testId: string, signal?: AbortSignal) =>
    get(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/entries`,
      arrayOf(schema.isEntry),
      signal,
    ),
  getSpans: (runId: string, testId: string, signal?: AbortSignal) =>
    get(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/spans`,
      arrayOf(schema.isSpan),
      signal,
    ),
  getSnapshots: (runId: string, testId: string, signal?: AbortSignal) =>
    get(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/snapshots`,
      arrayOf(schema.isSnapshot),
      signal,
    ),
  getTestMockInteractions: (runId: string, testId: string, signal?: AbortSignal) =>
    get(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/mock-interactions`,
      arrayOf(schema.isMockInteraction),
      signal,
    ),
  getAmbientMockInteractions: (runId: string, signal?: AbortSignal) =>
    get(
      `/runs/${encodePath(runId)}/mock-interactions/ambient`,
      arrayOf(schema.isMockInteraction),
      signal,
    ),
  getTestMockWarnings: (runId: string, testId: string, signal?: AbortSignal) =>
    get(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/mock-warnings`,
      arrayOf(schema.isMockWarning),
      signal,
    ),
  getAmbientMockWarnings: (runId: string, signal?: AbortSignal) =>
    get(`/runs/${encodePath(runId)}/mock-warnings/ambient`, arrayOf(schema.isMockWarning), signal),
  getTrace: (traceId: string, signal?: AbortSignal) =>
    get(`/traces/${encodePath(traceId)}`, arrayOf(schema.isSpan), signal),
  clearAll: () => del("/data"),
  getAdminStatus: (signal?: AbortSignal) => get("/admin/status", schema.isAdminStatus, signal),
  getDatabaseSchema: (signal?: AbortSignal) =>
    get("/admin/database/schema", schema.isDatabaseSchema, signal),
  executeDatabaseQuery: (sql: string, maxRows: number) =>
    send(
      "/admin/database/query",
      "POST",
      {
        sql,
        max_rows: maxRows,
      } satisfies DatabaseQueryRequest,
      schema.isDatabaseQueryResult,
    ),
  updateRetention: (runsPerApp: number) =>
    send(
      "/admin/retention",
      "PUT",
      { runs_per_app: runsPerApp } satisfies RetentionRequest,
      schema.isAdminStatus,
    ),
  previewPurge: (selector: PurgePreviewRequest) =>
    send("/admin/purge/preview", "POST", selector, schema.isPurgePreview),
  purgeRuns: (runIds: string[], includeRunning: boolean) =>
    send(
      "/admin/purge",
      "POST",
      {
        run_ids: runIds,
        include_running: includeRunning,
      } satisfies PurgeRequest,
      schema.isPurgeResult,
    ),
};
