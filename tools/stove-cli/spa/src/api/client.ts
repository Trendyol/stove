import type { AppSummary, Entry, Run, Snapshot, Span, Test } from "./types";

const BASE = "/api/v1";

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
  getApps: () => get<AppSummary[]>("/apps"),
  getRuns: (app?: string) => get<Run[]>(app ? `/runs?app=${encodeURIComponent(app)}` : "/runs"),
  getRun: (runId: string) => get<Run | null>(`/runs/${runId}`),
  getTests: (runId: string) => get<Test[]>(`/runs/${runId}/tests`),
  getEntries: (runId: string, testId: string) =>
    get<Entry[]>(`/runs/${runId}/tests/${testId}/entries`),
  getSpans: (runId: string, testId: string) => get<Span[]>(`/runs/${runId}/tests/${testId}/spans`),
  getSnapshots: (runId: string, testId: string) =>
    get<Snapshot[]>(`/runs/${runId}/tests/${testId}/snapshots`),
  getTrace: (traceId: string) => get<Span[]>(`/traces/${traceId}`),
  clearAll: () => del("/data"),
};
