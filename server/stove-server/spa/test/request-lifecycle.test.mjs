import assert from "node:assert/strict";
import { test, afterEach } from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";
import {
  app,
  run,
  testRecord,
  meta,
  adminStatus,
  schema,
  databaseResult,
  counts,
  deferred,
} from "./helpers/fixtures.mjs";

const dom = new JSDOM("<!doctype html><html><body></body></html>", {
  url: "http://localhost",
  pretendToBeVisual: true,
});
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.HTMLElement = dom.window.HTMLElement;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
globalThis.EventSource = class {
  close() {}
};
globalThis.confirm = () => true;
const jiti = createJiti(import.meta.url);
const { createElement, StrictMode } = await jiti.import("react");
const { renderHook, act, waitFor, cleanup } = await jiti.import("@testing-library/react");
const { QueryClient, QueryClientProvider } = await jiti.import("@tanstack/react-query");
const { api } = await jiti.import("../src/api/client.ts");
const { useAppData } = await jiti.import("../src/hooks/useAppData.ts");
const { useAdminController } = await jiti.import("../src/pages/admin/useAdminController.ts");
const { useDatabaseExplorer } = await jiti.import("../src/pages/admin/useDatabaseExplorer.ts");
const clients = [];
afterEach(() => {
  cleanup();
  clients.splice(0).forEach((client) => client.clear());
});

function mount(hook) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  clients.push(client);
  const wrapper = ({ children }) =>
    createElement(StrictMode, null, createElement(QueryClientProvider, { client }, children));
  return { ...renderHook(hook, { wrapper }), client };
}

function dashboardApi(t) {
  t.mock.method(api, "getApps", async () => [app]);
  t.mock.method(api, "getMeta", async () => meta);
  t.mock.method(api, "getRuns", async () => [run, { ...run, id: "run-2" }]);
  t.mock.method(api, "getTests", async (runId) => [
    { ...testRecord, run_id: runId },
    { ...testRecord, id: "test-2", run_id: runId },
  ]);
}

test("dashboard selections survive pending and failed requests and recover after retry", async (t) => {
  dashboardApi(t);
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.tests.length, 2));
  act(() => result.current.selectRun("run-2"));
  await waitFor(() => assert.equal(result.current.latestRun.id, "run-2"));
  await waitFor(() => assert.equal(result.current.tests[0]?.run_id, "run-2"));
  act(() => result.current.selectTest("test-2"));
  const pending = deferred();
  api.getRuns.mock.mockImplementation(() => pending.promise);
  act(() => {
    void client.resetQueries({ queryKey: ["runs"] });
  });
  assert.equal(result.current.selectedRunId, "run-2");
  await act(async () => pending.reject(new Error("offline")));
  await waitFor(() => assert.equal(result.current.error?.message, "offline"));
  assert.equal(result.current.selectedRunId, "run-2");
  api.getRuns.mock.mockImplementation(async () => [run, { ...run, id: "run-2" }]);
  await act(async () => {
    await result.current.retry();
  });
  await waitFor(() => assert.equal(result.current.selectedTest?.id, "test-2"));
  assert.equal(result.current.latestRun.id, "run-2");
});

test("an explicit app survives an unsuccessful refresh, but a successful empty list clears it", async (t) => {
  dashboardApi(t);
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.apps.length, 1));
  act(() => result.current.selectApp("demo"));
  const pending = deferred();
  api.getApps.mock.mockImplementation(() => pending.promise);
  act(() => {
    void client.resetQueries({ queryKey: ["apps"] });
  });
  await act(async () => pending.reject(new Error("unavailable")));
  await waitFor(() => assert.equal(result.current.error?.message, "unavailable"));
  assert.equal(result.current.activeApp, "demo");
  api.getApps.mock.mockImplementation(async () => []);
  await act(async () => {
    await result.current.retry();
  });
  await waitFor(() => assert.equal(result.current.activeApp, undefined));
});

test("admin preserves a retention draft during refetch and discards late purge previews", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => adminStatus);
  const preview = deferred();
  t.mock.method(api, "previewPurge", () => preview.promise);
  const { result, client } = mount(useAdminController);
  await waitFor(() => assert.equal(result.current.retention, 5));
  act(() => result.current.setRetention(8));
  await act(async () => {
    await client.invalidateQueries({ queryKey: ["admin", "status"] });
  });
  assert.equal(result.current.retention, 8);
  act(() => result.current.previewPurge());
  await waitFor(() => assert.equal(result.current.busy, true));
  act(() => result.current.setAppName("another-app"));
  await act(async () => preview.resolve({ run_ids: ["run-1"], run_count: 1, evidence: counts }));
  assert.equal(result.current.preview, null);
});

test("retention saves keep the submitted value visible during refresh and preserve later edits", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => adminStatus);
  t.mock.method(api, "updateRetention", async () => ({
    ...adminStatus,
    retention_runs_per_app: 8,
  }));
  const { result } = mount(useAdminController);
  await waitFor(() => assert.equal(result.current.retention, 5));
  act(() => result.current.setRetention(8));
  const refresh = deferred();
  api.getAdminStatus.mock.mockImplementation(() => refresh.promise);
  act(() => result.current.updateRetention());
  await waitFor(() => assert.equal(api.getAdminStatus.mock.callCount(), 2));
  assert.equal(result.current.retention, 8);
  assert.equal(result.current.busy, true);
  act(() => result.current.setRetention(9));
  await act(async () => refresh.resolve({ ...adminStatus, retention_runs_per_app: 8 }));
  await waitFor(() => assert.equal(result.current.busy, false));
  assert.equal(result.current.status.retention_runs_per_app, 8);
  assert.equal(result.current.retention, 9);
});

test("admin command errors are visible and a successful retry clears stale dashboard data", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => adminStatus);
  t.mock.method(api, "clearAll", async () => {
    throw new Error("write failed");
  });
  const { result, client } = mount(useAdminController);
  await waitFor(() => assert.ok(result.current.status));
  client.setQueryData(["tests", "run-1"], [testRecord]);
  act(() => result.current.clearAll());
  await waitFor(() => assert.equal(result.current.error, "write failed"));
  assert.deepEqual(client.getQueryData(["tests", "run-1"]), [testRecord]);
  api.clearAll.mock.mockImplementation(async () => undefined);
  act(() => result.current.clearAll());
  await waitFor(() => assert.equal(result.current.error, null));
  await waitFor(() => assert.equal(result.current.busy, false));
  assert.equal(client.getQueryData(["tests", "run-1"]), undefined);
});

test("database schema refetch preserves SQL drafts and late results cannot replace another table", async (t) => {
  t.mock.method(api, "getDatabaseSchema", async () => schema);
  const response = deferred();
  t.mock.method(api, "executeDatabaseQuery", () => response.promise);
  const { result, client } = mount(() => useDatabaseExplorer(async () => undefined));
  await waitFor(() => assert.equal(result.current.selectedTable?.name, "runs"));
  act(() => result.current.setSql("SELECT id FROM runs"));
  await act(async () => {
    await client.invalidateQueries({ queryKey: ["admin", "schema"] });
  });
  assert.equal(result.current.sql, "SELECT id FROM runs");
  act(() => result.current.execute());
  await waitFor(() => assert.equal(result.current.busy, true));
  act(() => result.current.selectTable(schema.tables[1]));
  assert.equal(result.current.busy, true);
  await act(async () => response.resolve(databaseResult));
  await waitFor(() => assert.equal(result.current.busy, false));
  assert.equal(result.current.result, null);
  assert.match(result.current.sql, /"tests"/);
});

test("database failures can be retried and changing statements refresh the dashboard", async (t) => {
  t.mock.method(api, "getDatabaseSchema", async () => schema);
  t.mock.method(api, "executeDatabaseQuery", async () => {
    throw new Error("invalid SQL");
  });
  let refreshed = 0;
  const { result } = mount(() =>
    useDatabaseExplorer(async () => {
      refreshed += 1;
    }),
  );
  await waitFor(() => assert.ok(result.current.selectedTable));
  act(() => result.current.execute("DELETE FROM runs"));
  await waitFor(() => assert.equal(result.current.error, "invalid SQL"));
  api.executeDatabaseQuery.mock.mockImplementation(async () => databaseResult);
  act(() => result.current.execute());
  await waitFor(() => assert.ok(result.current.result));
  assert.equal(result.current.error, null);
  assert.equal(refreshed, 1);
});
