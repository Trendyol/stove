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
globalThis.localStorage = dom.window.localStorage;
globalThis.HTMLElement = dom.window.HTMLElement;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
globalThis.EventSource = class {
  close() {}
};
globalThis.confirm = () => true;
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, fsCache: false });
const { createElement, StrictMode, useState } = await jiti.import("react");
const { render, fireEvent, renderHook, act, waitFor, cleanup } = await jiti.import("@testing-library/react");
const { QueryClient, QueryClientProvider } = await jiti.import("@tanstack/react-query");
const { api } = await jiti.import("../src/api/client.ts");
const { useAppData } = await jiti.import("../src/hooks/useAppData.ts");
const { useAdminController } = await jiti.import("../src/pages/admin/useAdminController.ts");
const { useDatabaseExplorer } = await jiti.import("../src/pages/admin/useDatabaseExplorer.ts");
const { PurgeMetadataFilters } = await jiti.import("../src/pages/admin/PurgeMetadataFilters.tsx");
const clients = [];
afterEach(() => {
  cleanup();
  localStorage.clear();
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
  t.mock.method(api, "getTopFilter", async () => ({ settings: { metadata_key: null }, available_keys: [], values: [] }));
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
  t.mock.method(api, "getRuns", async () => []);
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

test("metadata purge filters scope requests to the app and invalidate obsolete previews", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => adminStatus);
  t.mock.method(api, "getRuns", async (appName) => [{ ...run, app_name: appName, metadata: { branch: "main" } }]);
  const latePreview = deferred();
  t.mock.method(api, "previewPurge", () => latePreview.promise);
  t.mock.method(api, "purgeRuns", async () => ({}));
  const { result } = mount(useAdminController);
  await waitFor(() => assert.equal(result.current.busy, false));
  assert.equal(api.getRuns.mock.callCount(), 0);
  act(() => result.current.setAppName("checkout"));
  await waitFor(() => assert.equal(result.current.metadataLoading, false));
  assert.equal(result.current.metadataRuns[0].app_name, "checkout");
  act(() => result.current.setMetadata({ branch: ["main", "release"], pipeline: ["42"] }));
  act(() => result.current.previewPurge());
  await waitFor(() => assert.equal(api.previewPurge.mock.callCount(), 1));
  assert.deepEqual(api.previewPurge.mock.calls[0].arguments[0], {
    app_name: "checkout", include_running: false,
    metadata: { branch: ["main", "release"], pipeline: ["42"] },
  });
  act(() => result.current.setMetadata({ branch: ["feature"] }));
  await act(async () => latePreview.resolve({ run_ids: ["obsolete"], run_count: 1, evidence: counts }));
  assert.equal(result.current.preview, null);
  act(() => result.current.purge());
  assert.equal(api.purgeRuns.mock.callCount(), 0);

  api.previewPurge.mock.mockImplementation(async () => ({ run_ids: ["selected-run"], run_count: 1, evidence: counts }));
  act(() => result.current.previewPurge());
  await waitFor(() => assert.deepEqual(result.current.preview?.run_ids, ["selected-run"]));
  act(() => result.current.purge());
  await waitFor(() => assert.equal(api.purgeRuns.mock.callCount(), 1));
  assert.deepEqual(api.purgeRuns.mock.calls[0].arguments, [["selected-run"], false]);
  await waitFor(() => assert.equal(result.current.busy, false));
  act(() => result.current.setAppName("catalog"));
  assert.deepEqual(result.current.metadata, {});
  assert.equal(result.current.preview, null);
  await waitFor(() => assert.equal(result.current.metadataRuns[0]?.app_name, "catalog"));
  act(() => result.current.setAppName(""));
  assert.deepEqual(result.current.metadataRuns, []);
});

test("switching the purge app ignores metadata that arrives late for the old app", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => adminStatus);
  const oldRuns = deferred();
  t.mock.method(api, "getRuns", (appName) => appName === "old-app"
    ? oldRuns.promise : Promise.resolve([{ ...run, app_name: appName, metadata: { team: "new" } }]));
  const { result } = mount(useAdminController);
  act(() => result.current.setAppName("old-app"));
  await waitFor(() => assert.equal(api.getRuns.mock.callCount(), 1));
  act(() => result.current.setAppName("new-app"));
  await waitFor(() => assert.equal(result.current.metadataRuns[0]?.metadata.team, "new"));
  await act(async () => oldRuns.resolve([{ ...run, metadata: { team: "old" } }]));
  assert.equal(result.current.metadataRuns[0].metadata.team, "new");
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


test("searching a long metadata list preserves selections across values and fields", () => {
  let selection = {};
  function Filters() {
    const [value, setValue] = useState({});
    return createElement(PurgeMetadataFilters, {
      appName: "demo",
      runs: Array.from({ length: 8 }, (_, index) => ({
        ...run,
        id: `run-${index}`,
        metadata: { branch: `release-${index}`, team: "checkout" },
      })),
      value,
      loading: false,
      error: null,
      onRetry: () => {},
      onChange: (next) => { selection = next; setValue(next); },
    });
  }
  const view = render(createElement(Filters));
  fireEvent.click(view.getByRole("button", { name: "team: checkout" }));
  fireEvent.click(view.getByLabelText("branch: Any value"));
  const search = view.getByRole("searchbox", { name: "Find branch values" });
  fireEvent.change(search, { target: { value: "release-1" } });
  fireEvent.click(view.getByRole("checkbox", { name: "branch: release-1" }));
  fireEvent.change(search, { target: { value: "release-7" } });
  fireEvent.click(view.getByRole("checkbox", { name: "branch: release-7" }));
  fireEvent.change(search, { target: { value: "missing" } });
  assert.ok(view.getByText("No values found."));
  assert.deepEqual(selection, { branch: ["release-1", "release-7"], team: ["checkout"] });
  fireEvent.change(search, { target: { value: "" } });
  assert.equal(view.getByRole("checkbox", { name: "branch: release-1" }).checked, true);
  assert.equal(view.getByRole("checkbox", { name: "branch: release-7" }).checked, true);
  fireEvent.click(view.getByRole("checkbox", { name: "branch: release-1" }));
  assert.deepEqual(selection, { branch: ["release-7"], team: ["checkout"] });
  fireEvent.click(view.getByRole("button", { name: "Clear metadata filters" }));
  assert.deepEqual(selection, {});
});

test("top filter scopes applications, runs and tests, persists across apps, and resets for a new admin key", async (t) => {
  dashboardApi(t);
  const secondApp = { ...app, app_name: "second" };
  const otherApp = { ...app, app_name: "payments" };
  const catalog = {
    settings: { metadata_key: "gitlab.team" }, available_keys: ["gitlab.team", "region"],
    values: [ { value: "checkout", apps: [app, secondApp] }, { value: "payments", apps: [otherApp, app] } ],
  };
  api.getTopFilter.mock.mockImplementation(async () => catalog);
  api.getApps.mock.mockImplementation(async () => [app, secondApp, otherApp]);
  api.getRuns.mock.mockImplementation(async (name) => [
    { ...run, id: `${name}-payments`, app_name: name, metadata: { "gitlab.team": "payments", region: "eu" } },
    { ...run, id: `${name}-checkout`, app_name: name, metadata: { "gitlab.team": "checkout", region: "eu" } },
  ]);
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.apps.length, 3));
  act(() => result.current.topFilter.onChange("checkout"));
  await waitFor(() => assert.equal(result.current.selectedTest?.run_id, "demo-checkout"));
  assert.deepEqual(result.current.apps.map(a => a.app_name), ["demo", "second"]);
  assert.deepEqual(result.current.allRuns.map(r => r.id), ["demo-checkout"]);
  assert.equal(JSON.parse(localStorage.getItem("stove-top-filter")).value, "checkout");
  act(() => result.current.selectApp("second"));
  await waitFor(() => assert.equal(result.current.selectedTest?.run_id, "second-checkout"));
  assert.equal(result.current.topFilter.value, "checkout");
  act(() => result.current.topFilter.onChange("payments"));
  await waitFor(() => assert.equal(result.current.selectedTest?.run_id, "payments-payments"));
  assert.deepEqual(result.current.apps.map(a => a.app_name), ["payments", "demo"]);
  act(() => result.current.filterRunsByMetadata({ region: ["us"] }));
  await waitFor(() => assert.equal(result.current.runs.length, 0));
  assert.equal(result.current.selectedTest, undefined);
  await act(async () => {
    client.setQueryData(["top-filter"], { settings: { metadata_key: "region" }, available_keys: ["region"], values: [] });
  });
  await waitFor(() => assert.equal(result.current.topFilter.value, null));
  assert.equal(result.current.apps.length, 3);
  assert.deepEqual(result.current.metadataFilter, {});
  assert.equal(localStorage.getItem("stove-top-filter"), null);
});

test("a remembered top filter stays scoped when its value disappears and can be cleared", async (t) => {
  dashboardApi(t);
  localStorage.setItem("stove-top-filter", JSON.stringify({ key: "gitlab.team", value: "retired" }));
  api.getTopFilter.mock.mockImplementation(async () => ({ settings: { metadata_key: "gitlab.team" }, available_keys: [], values: [] }));
  const { result } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.loading, false));
  assert.equal(result.current.topFilter.value, "retired");
  assert.deepEqual(result.current.apps, []);
  assert.equal(result.current.latestRun, undefined);
  assert.equal(result.current.selectedTest, undefined);
  assert.equal(api.getRuns.mock.callCount(), 0);
  act(() => result.current.topFilter.onChange(null));
  await waitFor(() => assert.equal(result.current.apps.length, 1));
});

test("admin saves Browse by and the dashboard uses friendly labels without losing exact values", async (t) => {
  const { TopFilterCard } = await jiti.import("../src/pages/admin/TopFilterCard.tsx");
  const { TopFilterPicker } = await jiti.import("../src/components/TopFilterPicker.tsx");
  let saved = null;
  const catalog = () => ({ settings: { metadata_key: saved }, available_keys: ["gitlab.team", "environment"], values: [] });
  t.mock.method(api, "getTopFilter", async () => catalog());
  t.mock.method(api, "updateTopFilter", async (settings) => { saved = settings.metadata_key; return settings; });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { gcTime: 0 } } });
  clients.push(client);
  const view = render(createElement(QueryClientProvider, { client }, createElement(TopFilterCard)));
  await waitFor(() => assert.equal(view.getByRole("combobox", {name: "Metadata field"}).disabled, false));
  fireEvent.change(view.getByRole("combobox", {name: "Metadata field"}), {target: {value: "gitlab.team"}});
  assert.ok(view.getByText("Team"));
  fireEvent.click(view.getByRole("button", {name: "Save browsing field"}));
  await waitFor(() => assert.equal(saved, "gitlab.team"));
  await waitFor(() => assert.ok(view.getByText("Browsing field saved for everyone.")));
  assert.equal(client.getQueryData(["top-filter"]).settings.metadata_key, "gitlab.team");
  view.unmount();
  const selections = [];
  const picker = render(createElement(TopFilterPicker, {
    metadataKey: "gitlab.team", value: null,
    values: [{ value: "", apps: [app] }, { value: "all", apps: [app] }],
    onChange: value => selections.push(value),
  }));
  const select = picker.getByRole("combobox", {name: "Team"});
  assert.equal(picker.getByText("Team").title, "gitlab.team");
  fireEvent.change(select, {target: {value: "value:"}});
  fireEvent.change(select, {target: {value: "value:all"}});
  fireEvent.change(select, {target: {value: "all"}});
  assert.deepEqual(selections, ["", "all", null]);
});

test("browsing settings keep failed drafts and retain successful saves when refresh fails", async (t) => {
  const { useTopFilterSettings } = await jiti.import("../src/pages/admin/useTopFilterSettings.ts");
  let saved = null;
  t.mock.method(api, "getTopFilter", async () => ({ settings: { metadata_key: saved }, available_keys: ["team"], values: [] }));
  t.mock.method(api, "updateTopFilter", async () => { throw new Error("Save failed"); });
  const { result, client } = mount(useTopFilterSettings);
  await waitFor(() => assert.equal(result.current.disabled, false));
  act(() => result.current.select("team"));
  act(() => result.current.save());
  await waitFor(() => assert.equal(result.current.error?.message, "Save failed"));
  assert.equal(result.current.value, "team");
  assert.equal(result.current.savedKey, "");
  api.updateTopFilter.mock.mockImplementation(async (settings) => {
    saved = settings.metadata_key;
    return settings;
  });
  api.getTopFilter.mock.mockImplementation(async () => { throw new Error("Refresh failed"); });
  act(() => result.current.save());
  await waitFor(() => assert.equal(result.current.saved, true));
  assert.equal(result.current.value, "team");
  assert.equal(result.current.savedKey, "team");
  assert.equal(client.getQueryData(["top-filter"]).settings.metadata_key, "team");
  assert.equal(result.current.error.message, "Refresh failed");
  api.getTopFilter.mock.mockImplementation(async () => ({ settings: { metadata_key: saved }, available_keys: ["team"], values: [] }));
  act(() => result.current.retry());
  await waitFor(() => assert.equal(result.current.error, null));
  assert.equal(result.current.value, "team");
});

test("invalid persisted selections and blocked browser storage do not break filtering", async (t) => {
  const { useTopFilter } = await jiti.import("../src/hooks/useTopFilter.ts");
  t.mock.method(api, "getTopFilter", async () => ({ settings: { metadata_key: "team" }, available_keys: ["team"], values: [] }));
  for (const stored of ["broken json", "[]", "false", '{"key":"team","value":7}']) {
    localStorage.setItem("stove-top-filter", stored);
    const view = mount(useTopFilter);
    await waitFor(() => assert.equal(view.result.current.key, "team"));
    assert.equal(view.result.current.value, null);
    view.unmount();
  }
  t.mock.method(dom.window.Storage.prototype, "getItem", () => { throw new Error("Storage blocked"); });
  t.mock.method(dom.window.Storage.prototype, "setItem", () => { throw new Error("Storage blocked"); });
  const { result } = mount(useTopFilter);
  await waitFor(() => assert.equal(result.current.key, "team"));
  act(() => result.current.select("commerce"));
  assert.equal(result.current.value, "commerce");
});

test("admin and schema load failures can be retried without losing drafts", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => { throw new Error("Status unavailable"); });
  t.mock.method(api, "getDatabaseSchema", async () => { throw new Error("Schema unavailable"); });
  const admin = mount(useAdminController);
  const explorer = mount(() => useDatabaseExplorer(async () => undefined));
  await waitFor(() => assert.equal(admin.result.current.error, "Status unavailable"));
  await waitFor(() => assert.equal(explorer.result.current.error, "Schema unavailable"));
  act(() => {
    admin.result.current.setRetention(8);
    explorer.result.current.setSql("SELECT 1");
  });
  api.getAdminStatus.mock.mockImplementation(async () => adminStatus);
  api.getDatabaseSchema.mock.mockImplementation(async () => schema);
  act(() => {
    admin.result.current.retry();
    explorer.result.current.retry();
  });
  await waitFor(() => assert.equal(admin.result.current.error, null));
  await waitFor(() => assert.equal(explorer.result.current.error, null));
  assert.equal(admin.result.current.retention, 8);
  assert.equal(explorer.result.current.sql, "SELECT 1");
});

test("failed evidence lists expose a retry that recovers the same query", async (t) => {
  const { useDashboardListQuery } = await jiti.import("../src/hooks/useDashboardListQuery.ts");
  const { dashboardQueries } = await jiti.import("../src/api/dashboard-queries.ts");
  t.mock.method(api, "getTests", async () => { throw new Error("Evidence unavailable"); });
  const { result } = mount(() => useDashboardListQuery({
    query: dashboardQueries.tests("run-1"), liveConnected: true, pollWhileDisconnected: false,
  }));
  await waitFor(() => assert.equal(result.current.kind, "error"));
  api.getTests.mock.mockImplementation(async () => [testRecord]);
  act(() => result.current.retry());
  await waitFor(() => assert.equal(result.current.kind, "ready"));
  assert.deepEqual(result.current.data, [testRecord]);
});

test("database changes refresh catalog membership without clearing the browsing scope", async (t) => {
  dashboardApi(t);
  const { refreshDatabaseQueries } = await jiti.import("../src/pages/admin/admin-queries.ts");
  localStorage.setItem("stove-top-filter", JSON.stringify({ key: "team", value: "commerce" }));
  const catalog = { settings: { metadata_key: "team" }, available_keys: ["team"], values: [{ value: "commerce", apps: [app] }] };
  api.getTopFilter.mock.mockImplementation(async () => catalog);
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.apps.length, 1));
  const pending = deferred();
  api.getTopFilter.mock.mockImplementation(() => pending.promise);
  let refresh;
  act(() => { refresh = refreshDatabaseQueries(client); });
  await waitFor(() => assert.equal(api.getTopFilter.mock.callCount(), 2));
  assert.equal(result.current.topFilter.value, "commerce");
  await act(async () => {
    pending.resolve({ ...catalog, values: [] });
    await refresh;
  });
  await waitFor(() => assert.deepEqual(result.current.apps, []));
  assert.equal(result.current.topFilter.value, "commerce");
});

test("Browse by displays an accessible load error and Retry restores its labelled control", async (t) => {
  const { TopFilterCard } = await jiti.import("../src/pages/admin/TopFilterCard.tsx");
  t.mock.method(api, "getTopFilter", async () => { throw new Error("Catalog unavailable"); });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { gcTime: 0 } } });
  clients.push(client);
  const view = render(createElement(QueryClientProvider, { client }, createElement(TopFilterCard)));
  await waitFor(() => assert.match(view.getByRole("alert").textContent, /Catalog unavailable/));
  assert.equal(view.getByRole("combobox", { name: "Metadata field" }).disabled, true);
  assert.equal(view.getByRole("button", { name: "Save browsing field" }).disabled, true);
  api.getTopFilter.mock.mockImplementation(async () => ({ settings: { metadata_key: null }, available_keys: ["team"], values: [] }));
  fireEvent.click(view.getByRole("button", { name: "Retry" }));
  await waitFor(() => assert.ok(!view.queryByRole("alert")));
  fireEvent.change(view.getByRole("combobox", { name: "Metadata field" }), { target: { value: "team" } });
  assert.equal(view.getByRole("button", { name: "Save browsing field" }).disabled, false);
});


test("sidebar width tolerates invalid and unavailable browser storage", async (t) => {
  const { useSidebarWidth } = await jiti.import("../src/layout/sidebar/SidebarResizer.tsx");
  for (const [stored, expected] of [["invalid", 280], ["Infinity", 280], ["100", 240], ["900", 600], ["320", 320]]) {
    localStorage.setItem("stove-sidebar-width", stored);
    const view = renderHook(useSidebarWidth);
    assert.equal(view.result.current[0], expected);
    view.unmount();
  }
  t.mock.method(dom.window.Storage.prototype, "getItem", () => { throw new Error("Storage blocked"); });
  t.mock.method(dom.window.Storage.prototype, "setItem", () => { throw new Error("Storage blocked"); });
  const view = renderHook(useSidebarWidth);
  assert.equal(view.result.current[0], 280);
  act(() => view.result.current[1](360));
  assert.equal(view.result.current[0], 360);
});

test("sidebar dragging uses the navigator offset and stops on release or lost capture", async () => {
  const { SidebarResizer, useSidebarWidth } = await jiti.import("../src/layout/sidebar/SidebarResizer.tsx");
  function ResizableNavigator() {
    const [width, resize] = useSidebarWidth();
    return createElement("aside", null, createElement(SidebarResizer, { width, onResize: resize }));
  }
  const view = render(createElement(ResizableNavigator));
  const separator = view.getByRole("separator", { name: "Test navigator width" });
  separator.parentElement.getBoundingClientRect = () => ({ left: 50 });
  let captured;
  separator.setPointerCapture = (id) => { captured = id; };
  function pointer(type, clientX = 0) {
    const event = new dom.window.MouseEvent(type, { bubbles: true, clientX, cancelable: true });
    Object.defineProperty(event, "pointerId", { value: 7 });
    fireEvent(separator, event);
  }
  pointer("pointermove", 500);
  assert.equal(separator.getAttribute("aria-valuenow"), "280");
  pointer("pointerdown");
  assert.equal(captured, 7);
  assert.ok(document.activeElement === separator);
  pointer("pointermove", 400);
  assert.equal(separator.getAttribute("aria-valuenow"), "350");
  pointer("pointermove", 1000);
  assert.equal(separator.getAttribute("aria-valuenow"), "600");
  pointer("pointerup");
  pointer("pointermove", 450);
  assert.equal(separator.getAttribute("aria-valuenow"), "600");
  pointer("pointerdown");
  pointer("pointermove", 350);
  pointer("lostpointercapture");
  pointer("pointermove", 500);
  assert.equal(separator.getAttribute("aria-valuenow"), "300");
});
