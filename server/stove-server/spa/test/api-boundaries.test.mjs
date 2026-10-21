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
  [() => api.getRuns(), [run]],
  [() => api.getTests("run-1"), [testRecord]],
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
  [() => api.updateRetention(5), adminStatus],
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
