import assert from "node:assert/strict";
import test from "node:test";
import initSqlJs from "sql.js";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { createDemoBackend } = await jiti.import("../src/demo/backend.ts");
const schema = await jiti.import("../src/api/response-schemas.ts");
const { parseLiveDashboardEvent } = await jiti.import("../src/api/live-event.ts");
const SQL = await initSqlJs();
function setup(t) {
  const backend = createDemoBackend(SQL, "1.0.0-demo");
  t.after(() => backend.reset());
  const request = (path, method = "GET", body) => backend.request(`/stove/dashboard-demo/api/v1${path}`, { method, body: body && JSON.stringify(body) });
  const get = async (path, validate, method, body) => {
    const response = await request(path, method, body);
    assert.equal(response.status, 200, path);
    const data = await response.json();
    assert.equal(validate(data), true, `API contract for ${path}: ${JSON.stringify(data).slice(0, 200)}`);
    return data;
  };
  const list = (check) => (data) => Array.isArray(data) && data.every(check);
  return { backend, request, get, list };
}

test("demo provides valid, linked evidence for every sample application and run", async (t) => {
  const { get, list } = setup(t);
  await get("/meta", schema.isMetaResponse);
  const apps = await get("/apps", list(schema.isAppSummary));
  assert.equal(apps.length, 3);
  for (const app of apps) {
    const runs = await get(`/runs?app=${app.app_name}`, list(schema.isRun));
    assert.equal(runs.length, 3);
    assert.ok(runs.every((run) => run.app_name === app.app_name));
    for (const run of runs) {
      await get(`/runs/${run.id}`, schema.isRun);
      const tests = await get(`/runs/${run.id}/tests`, list(schema.isTest));
      assert.equal(tests.length, 6);
      for (const test of tests) {
        const root = `/runs/${run.id}/tests/${encodeURIComponent(test.id)}`;
        await get(root, schema.isTest);
        const entries = await get(`${root}/entries`, list(schema.isEntry));
        const spans = await get(`${root}/spans`, list(schema.isSpan));
        assert.equal(entries.length, 6);
        assert.equal(spans.length, 8);
        assert.ok(entries.every((item) => item.run_id === run.id && item.test_id === test.id));
        const trace = await get(`/traces/${spans[0].trace_id}`, list(schema.isSpan));
        assert.deepEqual(trace, spans);
        await get(`${root}/snapshots`, list(schema.isSnapshot));
        await get(`${root}/mock-interactions`, list(schema.isMockInteraction));
        await get(`${root}/mock-warnings`, list(schema.isMockWarning));
      }
      const ambient = await get(`/runs/${run.id}/mock-interactions/ambient`, list(schema.isMockInteraction));
      assert.equal(ambient.length, 1);
      assert.equal(ambient[0].test_id, null);
      await get(`/runs/${run.id}/mock-warnings/ambient`, list(schema.isMockWarning));
    }
  }
});

test("focused links keep the exact record, bound context, and reject other scopes", async (t) => {
  const { get, request, list } = setup(t);
  const [run] = await get("/runs", list(schema.isRun));
  const [first, second] = await get(`/runs/${run.id}/tests`, list(schema.isTest));
  const root = `/runs/${run.id}/tests/${encodeURIComponent(first.id)}`;
  const entries = await get(`${root}/entries`, list(schema.isEntry));
  const focus = await get(`${root}/evidence/entry/${entries[3].id}?context=1`, schema.isFocusedEvidence);
  assert.deepEqual(focus.entries.map((entry) => entry.id), entries.slice(2, 5).map((entry) => entry.id));
  assert.equal(focus.has_more_before, true);
  assert.equal(focus.has_more_after, true);
  const exact = await get(`${root}/evidence/entry/${entries[3].id}?context=0`, schema.isFocusedEvidence);
  assert.deepEqual(exact.entries, [entries[3]]);
  for (const [path, kind, check] of [["spans", "span", schema.isSpan], ["snapshots", "snapshot", schema.isSnapshot], ["mock-interactions", "interaction", schema.isMockInteraction], ["mock-warnings", "warning", schema.isMockWarning]]) {
    const records = await get(`${root}/${path}`, list(check));
    const record = records[records.length - 1];
    const data = await get(`${root}/evidence/${kind}/${record.id}?context=0`, schema.isFocusedEvidence);
    assert.deepEqual(data.target, { kind, value: record });
    if (kind === "span") assert.equal(data.spans.length, 1);
    assert.equal((await request(`/runs/${run.id}/tests/${encodeURIComponent(second.id)}/evidence/${kind}/${record.id}`)).status, 404);
  }
  assert.equal((await request(`/runs/${run.id}/evidence/entry/${entries[0].id}`)).status, 404);
  assert.equal((await request(`/runs/missing/tests/${encodeURIComponent(first.id)}`)).status, 404);
});

test("SQL edits, retention, metadata purge, and reset operate on the same database", async (t) => {
  const { backend, get, list } = setup(t);
  const schemaData = await get("/admin/database/schema", schema.isDatabaseSchema);
  assert.equal(schemaData.tables.length, 7);
  const query = (sql, max_rows = 100) => get("/admin/database/query", schema.isDatabaseQueryResult, "POST", { sql, max_rows });
  const result = await query("SELECT app_name, status FROM runs ORDER BY started_at DESC", 2);
  assert.equal(result.rows.length, 2);
  assert.equal(result.truncated, true);
  const update = await query("UPDATE runs SET app_name = 'renamed-api' WHERE app_name = 'partner-orders-api'");
  assert.equal(update.affected_rows, 3);
  const apps = await get("/apps", list(schema.isAppSummary));
  assert.ok(apps.some((app) => app.app_name === "renamed-api"));
  const preview = await get("/admin/purge/preview", schema.isPurgePreview, "POST", { metadata: { branch: ["feature/payment-retry"] }, include_running: false });
  assert.equal(preview.run_count, 3);
  const purged = await get("/admin/purge", schema.isPurgeResult, "POST", { run_ids: preview.run_ids, include_running: false });
  assert.equal(purged.purged_runs, 3);
  const retained = await get("/admin/retention", schema.isAdminStatus, "PUT", { runs_per_app: 1 });
  assert.equal(retained.runs, 3);
  backend.reset();
  const restored = await get("/admin/status", schema.isAdminStatus);
  assert.equal(restored.runs, 9);
  assert.equal(restored.retention_runs_per_app, 0);
  assert.equal(restored.evidence.tests, 54);
});

test("replay streams valid events, finishes, and gives each replay a distinct trace", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const { backend, get, list } = setup(t);
  const events = [];
  backend.subscribe((batch) => events.push(...batch));
  const replay = backend.replay();
  assert.equal(backend.replay(), null);
  assert.equal(backend.replaying, true);
  assert.equal((await get(`/runs/${replay.runId}`, schema.isRun)).status, "RUNNING");
  for (let i = 0; i < 40; i++) t.mock.timers.tick(450);
  assert.equal(backend.replaying, false);
  assert.equal(events[events.length - 1].event_type, "run_ended");
  assert.ok(events.every((event, index) => parseLiveDashboardEvent(JSON.stringify(event)) && (index === 0 || event.seq > events[index - 1].seq)));
  const run = await get(`/runs/${replay.runId}`, schema.isRun);
  assert.equal(run.status, "FAILED");
  const root = `/runs/${replay.runId}/tests/${encodeURIComponent(replay.testId)}`;
  const spans = await get(`${root}/spans`, list(schema.isSpan));
  assert.equal(spans.length, 8);
  assert.equal((await get(`/traces/${spans[0].trace_id}`, list(schema.isSpan))).length, 8);
  backend.replay();
  for (let i = 0; i < 40; i++) t.mock.timers.tick(450);
  assert.equal((await get(`/traces/${spans[0].trace_id}`, list(schema.isSpan))).length, 8);
  backend.replay();
  backend.reset();
  t.mock.timers.tick(20_000);
  assert.equal(backend.replaying, false);
  assert.equal((await get("/runs", list(schema.isRun))).length, 9);
});

test("errors and aborted requests do not silently fall through to a real server", async (t) => {
  const { backend, request, get } = setup(t);
  assert.equal((await request("/admin/database/query", "POST", { sql: "invalid SQL" })).status, 400);
  assert.equal((await request("/admin/retention", "PUT", { runs_per_app: -1 })).status, 400);
  assert.equal((await request("/unknown")).status, 404);
  await assert.rejects(backend.request("/api/v1/runs", { signal: AbortSignal.abort() }), { name: "AbortError" });
  backend.replay();
  assert.equal((await request("/data", "DELETE")).status, 204);
  assert.equal(backend.replaying, false);
  assert.equal((await get("/admin/status", schema.isAdminStatus)).runs, 0);
});
