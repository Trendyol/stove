import type { AppSummary, Entry, MetaResponse, Run, Snapshot, Span, Test } from "./types";

const BASE = "/api/v1";
const encodePath = (value: string) => encodeURIComponent(value);

async function get<T>(url: string): Promise<T> {
  const res = await fetch(`${BASE}${url}`);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

async function del(url: string): Promise<void> {
  const res = await fetch(`${BASE}${url}`, { method: "DELETE" });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
}

export const api = {
  getMeta: () => get<MetaResponse>("/meta"),
  getApps: () => get<AppSummary[]>("/apps"),
  getRuns: (app?: string) => get<Run[]>(app ? `/runs?app=${encodeURIComponent(app)}` : "/runs"),
  getRun: (runId: string) => get<Run | null>(`/runs/${encodePath(runId)}`),
  getTests: (runId: string) => get<Test[]>(`/runs/${encodePath(runId)}/tests`),
  getEntries: (runId: string, testId: string) =>
    get<Entry[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/entries`),
  getSpans: (runId: string, testId: string) =>
    get<Span[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/spans`),
  getSnapshots: (runId: string, testId: string) =>
    get<Snapshot[]>(`/runs/${encodePath(runId)}/tests/${encodePath(testId)}/snapshots`),
  getTrace: (traceId: string) => get<Span[]>(`/traces/${encodePath(traceId)}`),
  clearAll: () => del("/data"),
};
