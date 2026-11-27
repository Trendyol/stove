import { testPage } from "./helpers/fixtures.mjs";
import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";
import {
  app,
  run,
  testRecord,
  entry,
  span,
  schema,
  databaseResult,
  adminStatus,
  counts,
} from "./helpers/fixtures.mjs";
const jiti = createJiti(import.meta.url);
const { api, ApiError } = await jiti.import("../src/api/client.ts");
const meta = {
  stove_server_version: "0.24.0",
  mcp: { enabled: true, endpoint: "/mcp", scope: "read", transport: "http" },
};
const cases = [
  [() => api.getTopFilter(), { settings: { metadata_key: null }, available_keys: [], values: [] }],
  [() => api.updateTopFilter({ metadata_key: "team" }), { metadata_key: "team" }],
  [() => api.getMeta(), meta],
  [() => api.getApps(), [app]],
  [() => api.getRuns(), { runs: [run], next_cursor: null }],
  [() => api.getRunMetadata(), [{ key: "team", values: [{ value: "one", count: 1 }] }]],
  [() => api.getTests("run-1"), testPage([testRecord])],
  [() => api.getEntries("run-1", "test-1"), [entry]],
  [() => api.getSpans("run-1", "test-1"), [span]],
  [() => api.getTrace("trace-1"), [span]],
  [() => api.getSnapshots("run-1", "test-1"), []],
  [() => api.getTestMockInteractions("run-1", "test-1"), []],
  [() => api.getAmbientMockInteractions("run-1"), []],
  [() => api.getTestMockWarnings("run-1", "test-1"), []],
  [() => api.getAmbientMockWarnings("run-1"), []],
  [() => api.getAdminStatus(), adminStatus],
  [() => api.getDatabaseSchema(), schema],
  [() => api.executeDatabaseQuery("SELECT 1", 100), databaseResult],
  [() => api.updateRetention(adminStatus.retention), adminStatus],
  [
    () => api.previewPurge({ include_running: false }),
    { run_ids: ["run-1"], run_count: 1, evidence: counts },
  ],
  [
    () => api.purgeRuns(["run-1"], false),
    { purged_run_ids: ["run-1"], purged_runs: 1, evidence: counts },
  ],
];

test("every JSON endpoint accepts its contract and rejects malformed responses", async (t) => {
  let body;
  t.mock.method(globalThis, "fetch", async () => Response.json(body));
  for (const [request, valid] of cases) {
    body = valid;
    assert.deepEqual(await request(), valid);
    body = { unexpected: true };
    await assert.rejects(request(), /Invalid response from \/api\/v1\//);
  }
});

test("HTTP errors preserve status and server messages for reads, writes, deletes and health", async (t) => {
  const requests = [api.getApps, () => api.updateTopFilter({ metadata_key: "team" }), api.clearAll, api.getHealth];
  let response;
  t.mock.method(globalThis, "fetch", async () => response());
  for (const [body, message] of [
    ['{"error":"Database unavailable"}', "Database unavailable"],
    ["Upstream unavailable", "Upstream unavailable"],
    ["<html>proxy failure</html>", "Bad Gateway"],
    ["", "Bad Gateway"],
  ]) {
    response = () => new Response(body, { status: 502, statusText: "Bad Gateway" });
    for (const request of requests) {
      await assert.rejects(request(), (error) =>
        error instanceof ApiError && error.status === 502 && error.message === `502 ${message}`,
      );
    }
  }
});

test("invalid JSON reports the endpoint and aborted response bodies stay cancelled", async (t) => {
  t.mock.method(globalThis, "fetch", async () => new Response("not json"));
  await assert.rejects(api.getTopFilter(), /Invalid response from \/api\/v1\/top-filter/);
  const aborted = new DOMException("Cancelled", "AbortError");
  globalThis.fetch.mock.mockImplementation(async () => ({ ok: true, json: async () => { throw aborted; } }));
  await assert.rejects(api.getApps(), error => error === aborted);
});

test("REST rejects bad nested data, missing nullable fields, and unknown statuses", async (t) => {
  let body;
  t.mock.method(globalThis, "fetch", async () => Response.json(body));
  for (const invalid of [
    { ...run, status: "UNKNOWN" },
    { ...run, status: "ERROR" },
    { ...run, metadata: { count: 1 } },
    { ...run, systems: [42] },
  ]) {
    body = [invalid];
    await assert.rejects(api.getRuns(), /Invalid response/);
  }
  const { ended_at: _, ...missingNullable } = run;
  body = [missingNullable];
  await assert.rejects(api.getRuns(), /Invalid response/);
  body = [{ ...span, status: "PASSED" }];
  await assert.rejects(api.getTrace("trace-1"), /Invalid response/);
  body = { ...databaseResult, rows: [[42]] };
  await assert.rejects(api.executeDatabaseQuery("SELECT 1", 100), /Invalid response/);
  body = { ...meta, mcp: { ...meta.mcp, enabled: "yes" } };
  await assert.rejects(api.getMeta(), /Invalid response/);
});

test("REST accepts additional server fields", async (t) => {
  const body = { ...meta, another_field: "new" };
  t.mock.method(globalThis, "fetch", async () => Response.json(body));
  assert.equal((await api.getMeta()).stove_server_version, meta.stove_server_version);
});

test("profile history rejects a hotspot missing a share for a listed run", async (t) => {
  t.mock.method(globalThis, "fetch", async () => Response.json({
    app_name: "checkout", runs_considered: 2, run_limit: 10,
    hotspot_threshold_percent: 20, min_sample_records: 20,
    truncated: false, interpretation: "Sampled evidence",
    series: [{
      service: "checkout", metric: "go:cpu", unit: "nanoseconds",
      runs: ["older", "newer"].map((run_id, index) => ({
        run_id, started_at: `2024-06-0${index + 1}T10:00:00Z`, status: "PASSED", total: 100, sample_records: 20,
      })),
      bottlenecks: [{
        method: "Checkout", file: "checkout.go", hot_runs: 2,
        median_self_percent: 40, change_percentage_points: 20, self_percent_by_run: [30],
      }],
    }],
  }));
  await assert.rejects(api.profileTrends("checkout", {}), /Invalid response.*profile-trends/);
});

test("failed and cancelled requests do not produce successful empty data", async (t) => {
  t.mock.method(
    globalThis,
    "fetch",
    async () => new Response("unavailable", { status: 503, statusText: "Service Unavailable" }),
  );
  await assert.rejects(api.getApps(), /503/);
  globalThis.fetch.mock.mockImplementation(async (_, { signal }) => {
    signal.throwIfAborted();
  });
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(api.getApps(controller.signal), { name: "AbortError" });
});
