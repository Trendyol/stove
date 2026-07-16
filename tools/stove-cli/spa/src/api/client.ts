import type {
  AppSummary,
  Entry,
  MetaResponse,
  MockInteraction,
  MockWarning,
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

export const api = {
  getMeta: (signal?: AbortSignal) => get<MetaResponse>("/meta", signal),
  getApps: (signal?: AbortSignal) => get<AppSummary[]>("/apps", signal),
  getRuns: (app?: string, signal?: AbortSignal) =>
    get<Run[]>(app ? `/runs?app=${encodeURIComponent(app)}` : "/runs", signal),
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
  getTestInteractions: (runId: string, testId: string, signal?: AbortSignal) =>
    get<MockInteraction[]>(
      `/runs/${encodePath(runId)}/tests/${encodePath(testId)}/interactions`,
      signal,
    ),
  getRunInteractions: (runId: string, signal?: AbortSignal) =>
    get<MockInteraction[]>(`/runs/${encodePath(runId)}/interactions`, signal),
  getAmbientInteractions: (runId: string, signal?: AbortSignal) =>
    get<MockInteraction[]>(`/runs/${encodePath(runId)}/interactions/ambient`, signal),
  getTestWarnings: (runId: string, testId: string, signal?: AbortSignal) =>
    get<MockWarning[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/warnings`, signal),
  getRunWarnings: (runId: string, signal?: AbortSignal) =>
    get<MockWarning[]>(`/runs/${encodePath(runId)}/warnings`, signal),
  getAmbientWarnings: (runId: string, signal?: AbortSignal) =>
    get<MockWarning[]>(`/runs/${encodePath(runId)}/warnings/ambient`, signal),
  getTrace: (traceId: string, signal?: AbortSignal) =>
    get<Span[]>(`/traces/${encodePath(traceId)}`, signal),
  clearAll: () => del("/data"),
};
