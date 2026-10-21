import { appPath, type EvidenceFocus } from "../utils/location";
import * as schema from "./response-schemas";
import { request } from "./transport";
import type {
  DatabaseQueryRequest,
  PurgePreviewRequest,
  PurgeRequest,
  RetentionRequest,
  TopFilterSettings,
} from "./types";
import { arrayOf, isRecord, nullable, type Validator } from "./validation";

const BASE = "/api/v1";
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}
const encodePath = (value: string) => encodeURIComponent(value);

async function checkResponse(response: Response): Promise<void> {
  if (response.ok) return;
  const text = (await response.text()).trim();
  let message = response.statusText || "Request failed";
  try {
    const body: unknown = JSON.parse(text);
    if (isRecord(body) && typeof body.error === "string" && body.error.trim()) message = body.error;
  } catch {
    // Proxies may return plain text or HTML instead of the server's JSON error.
    if (text && !text.startsWith("<")) message = text;
  }
  throw new ApiError(response.status, `${response.status} ${message}`);
}

async function get<T>(url: string, validate: Validator<T>, signal?: AbortSignal): Promise<T> {
  const res = await request(appPath(`${BASE}${url}`), { signal });
  await checkResponse(res);
  return readResponse(res, url, validate);
}

async function del(url: string): Promise<void> {
  const res = await request(appPath(`${BASE}${url}`), { method: "DELETE" });
  await checkResponse(res);
}

async function send<T>(
  url: string,
  method: "POST" | "PUT",
  body: unknown,
  validate: Validator<T>,
): Promise<T> {
  const res = await request(appPath(`${BASE}${url}`), {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  await checkResponse(res);
  return readResponse(res, url, validate);
}

async function readResponse<T>(
  response: Response,
  url: string,
  validate: Validator<T>,
): Promise<T> {
  try {
    const value: unknown = await response.json();
    if (validate(value)) return value;
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
  }
  throw new Error(`Invalid response from ${BASE}${url}`);
}

export const api = {
  getTopFilter: (signal?: AbortSignal) => get("/top-filter", schema.isTopFilterCatalog, signal),
  updateTopFilter: (settings: TopFilterSettings) =>
    send("/admin/top-filter", "PUT", settings, schema.isTopFilterSettings),
  getHealth: async (signal?: AbortSignal) => {
    const timeout = AbortSignal.timeout(5000);
    const res = await request(appPath(`${BASE}/health`), {
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      cache: "no-store",
    });
    if (res.status !== 503) await checkResponse(res);
    return readResponse(res, "/health", schema.isHealthResponse);
  },
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
