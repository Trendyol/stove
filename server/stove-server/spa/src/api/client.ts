import type {
  AdminStatus,
  AppSummary,
  DatabaseQueryResult,
  DatabaseSchema,
  Entry,
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

const BASE = "/api/v1";
const encodePath = (value: string) => encodeURIComponent(value);

async function get<T>(url: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE}${url}`, { signal });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

async function del(url: string): Promise<void> {
  const res = await fetch(`${BASE}${url}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
}

async function send<T>(url: string, method: "POST" | "PUT", body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${url}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`);
  return res.json();
}

export const api = {
  getMeta: (signal?: AbortSignal) => get<MetaResponse>("/meta", signal),
  getApps: (signal?: AbortSignal) => get<AppSummary[]>("/apps", signal),
  getRuns: (app?: string, metadata: Record<string, string> = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams();
    if (app) params.set("app", app);
    if (Object.keys(metadata).length > 0) params.set("metadata", JSON.stringify(metadata));
    const query = params.toString();
    return get<Run[]>(query ? `/runs?${query}` : "/runs", signal);
  },
  getRun: (runId: string, signal?: AbortSignal) =>
    get<Run | null>(`/runs/${encodePath(runId)}`, signal),
  getTests: (runId: string, signal?: AbortSignal) =>
    get<Test[]>(`/runs/${encodePath(runId)}/tests`, signal),
  getEntries: (runId: string, testId: string, signal?: AbortSignal) =>
    get<Entry[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/entries`, signal),
  getSpans: (runId: string, testId: string, signal?: AbortSignal) =>
    get<Span[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/spans`, signal),
  getSnapshots: (runId: string, testId: string, signal?: AbortSignal) =>
    get<Snapshot[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/snapshots`, signal),
  getTestMockInteractions: (runId: string, testId: string, signal?: AbortSignal) =>
    get<MockInteraction[]>(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/mock-interactions`,
      signal,
    ),
  getRunMockInteractions: (runId: string, signal?: AbortSignal) =>
    get<MockInteraction[]>(`/runs/${encodePath(runId)}/mock-interactions`, signal),
  getAmbientMockInteractions: (runId: string, signal?: AbortSignal) =>
    get<MockInteraction[]>(`/runs/${encodePath(runId)}/mock-interactions/ambient`, signal),
  getTestMockWarnings: (runId: string, testId: string, signal?: AbortSignal) =>
    get<MockWarning[]>(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/mock-warnings`,
      signal,
    ),
  getRunMockWarnings: (runId: string, signal?: AbortSignal) =>
    get<MockWarning[]>(`/runs/${encodePath(runId)}/mock-warnings`, signal),
  getAmbientMockWarnings: (runId: string, signal?: AbortSignal) =>
    get<MockWarning[]>(`/runs/${encodePath(runId)}/mock-warnings/ambient`, signal),
  getTrace: (traceId: string, signal?: AbortSignal) =>
    get<Span[]>(`/traces/${encodePath(traceId)}`, signal),
  clearAll: () => del("/data"),
  getAdminStatus: (signal?: AbortSignal) => get<AdminStatus>("/admin/status", signal),
  getDatabaseSchema: (signal?: AbortSignal) =>
    get<DatabaseSchema>("/admin/database/schema", signal),
  executeDatabaseQuery: (sql: string, maxRows: number) =>
    send<DatabaseQueryResult>("/admin/database/query", "POST", {
      sql,
      max_rows: maxRows,
    }),
  updateRetention: (runsPerApp: number) =>
    send<AdminStatus>("/admin/retention", "PUT", { runs_per_app: runsPerApp }),
  previewPurge: (selector: { app_name?: string; older_than?: string; include_running: boolean }) =>
    send<PurgePreview>("/admin/purge/preview", "POST", selector),
  purgeRuns: (runIds: string[], includeRunning: boolean) =>
    send<PurgeResult>("/admin/purge", "POST", {
      run_ids: runIds,
      include_running: includeRunning,
    }),
};
