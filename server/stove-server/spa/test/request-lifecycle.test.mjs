import { testPage, entry } from "./helpers/fixtures.mjs";
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
  t.mock.method(api, "getRuns", async () => ({ runs: [run, { ...run, id: "run-2" }], next_cursor: null }));
  t.mock.method(api, "getRunMetadata", async () => []);
  t.mock.method(api, "getRun", async id => ({ ...run, id }));
  t.mock.method(api, "getTest", async (runId, id) => ({...testRecord, run_id: runId, id}));
  t.mock.method(api, "getTests", async (runId) => testPage([
    { ...testRecord, run_id: runId },
    { ...testRecord, id: "test-2", run_id: runId },
  ]));
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
  api.getRuns.mock.mockImplementation(async () => ({ runs: [run, { ...run, id: "run-2" }], next_cursor: null }));
  await act(async () => {
    await result.current.retry();
  });
  await waitFor(() => assert.equal(result.current.selectedTest?.id, "test-2"));
  assert.equal(result.current.latestRun.id, "run-2");
});

test("dashboard loads older pages and applies metadata filters before paging", async (t) => {
  dashboardApi(t);
  api.getRunMetadata.mock.mockImplementation(async () => [{ key: "team", values: [{ value: "older", count: 1 }] }]);
  api.getRuns.mock.mockImplementation(async ({ metadata, cursor }) => {
    if (metadata.team) return { runs: [{ ...run, id: "older", metadata: { team: "older" } }], next_cursor: null };
    return cursor
      ? { runs: [{ ...run, id: "older", metadata: { team: "older" } }], next_cursor: null }
      : { runs: [run], next_cursor: "older-page" };
  });
  api.getRun.mock.mockImplementation(async id => ({ ...run, id, metadata: { team: "older" } }));
  const { result } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.hasOlderRuns, true));
  assert.equal(result.current.metadataOptions[0].values[0].value, "older");
  act(() => result.current.loadOlderRuns());
  await waitFor(() => assert.equal(result.current.runs.length, 2));
  act(() => result.current.selectRun("older"));
  await waitFor(() => assert.equal(result.current.latestRun.id, "older"));
  act(() => result.current.filterRunsByMetadata({ team: ["older"] }));
  await waitFor(() => assert.deepEqual(result.current.runs.map((item) => item.id), ["older"]));
  assert.equal(result.current.latestRun.id, "older");
  assert.equal(result.current.hasOlderRuns, false);
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
  t.mock.method(api, "getRunMetadata", async () => []);
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
  t.mock.method(api, "getRunMetadata", async (appName) => [{ key: appName, values: [{ value: "main", count: 1 }] }]);
  const latePreview = deferred();
  t.mock.method(api, "previewPurge", () => latePreview.promise);
  t.mock.method(api, "purgeRuns", async () => ({}));
  const { result } = mount(useAdminController);
  await waitFor(() => assert.equal(result.current.busy, false));
  assert.equal(api.getRunMetadata.mock.callCount(), 0);
  act(() => result.current.setAppName("checkout"));
  await waitFor(() => assert.equal(result.current.metadataLoading, false));
  assert.equal(result.current.metadataFacets[0].key, "checkout");
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
  await waitFor(() => assert.equal(result.current.metadataFacets[0]?.key, "catalog"));
  act(() => result.current.setAppName(""));
  assert.deepEqual(result.current.metadataFacets, []);
});

test("switching the purge app ignores metadata that arrives late for the old app", async (t) => {
  t.mock.method(api, "getAdminStatus", async () => adminStatus);
  const oldRuns = deferred();
  t.mock.method(api, "getRunMetadata", (appName) => appName === "old-app"
    ? oldRuns.promise : Promise.resolve([{ key: "team", values: [{ value: "new", count: 1 }] }]));
  const { result } = mount(useAdminController);
  act(() => result.current.setAppName("old-app"));
  await waitFor(() => assert.equal(api.getRunMetadata.mock.callCount(), 1));
  act(() => result.current.setAppName("new-app"));
  await waitFor(() => assert.equal(result.current.metadataFacets[0]?.values[0].value, "new"));
  await act(async () => oldRuns.resolve([{ key: "team", values: [{ value: "old", count: 1 }] }]));
  assert.equal(result.current.metadataFacets[0].values[0].value, "new");
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
      options: [{ key: "branch", values: Array.from({ length: 8 }, (_, index) => ({ value: `release-${index}`, count: 1 })) }, { key: "team", values: [{ value: "checkout", count: 8 }] }],
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
  api.getRuns.mock.mockImplementation(async ({ app: name, metadata }) => ({ runs: [
    { ...run, id: `${name}-payments`, app_name: name, metadata: { "gitlab.team": "payments", region: "eu" } },
    { ...run, id: `${name}-checkout`, app_name: name, metadata: { "gitlab.team": "checkout", region: "eu" } },
  ].filter(r => Object.entries(metadata ?? {}).every(([k, values]) => values.includes(r.metadata[k]))), next_cursor: null }));
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.apps.length, 3));
  act(() => result.current.topFilter.onChange("checkout"));
  await waitFor(() => assert.equal(result.current.selectedTest?.run_id, "demo-checkout"));
  assert.deepEqual(result.current.apps.map(a => a.app_name), ["demo", "second"]);
  assert.deepEqual(result.current.runs.map(r => r.id), ["demo-checkout"]);
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
  t.mock.method(api, "getEntries", async () => { throw new Error("Evidence unavailable"); });
  const { result } = mount(() => useDashboardListQuery({
    query: dashboardQueries.entries("run-1", "test-1"), liveConnected: true, pollWhileDisconnected: false,
  }));
  await waitFor(() => assert.equal(result.current.kind, "error"));
  api.getEntries.mock.mockImplementation(async () => [entry]);
  act(() => result.current.retry());
  await waitFor(() => assert.equal(result.current.kind, "ready"));
  assert.deepEqual(result.current.data, [entry]);
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

test("admin idle timeout defaults to one hour, preserves drafts and saves zero", async (t) => {
  let saved = { ...adminStatus, run_idle_timeout_seconds: 3600 };
  t.mock.method(api, "getAdminStatus", async () => saved);
  t.mock.method(api, "updateIdleTimeout", async (seconds) => { saved = { ...saved, run_idle_timeout_seconds: seconds }; return saved; });
  const { result, client } = mount(useAdminController);
  assert.equal(result.current.idleTimeout, 3600);
  await waitFor(() => assert.equal(result.current.busy, false));
  act(() => result.current.setIdleTimeout(7200));
  await act(async () => client.invalidateQueries({ queryKey: ["admin"] }));
  assert.equal(result.current.idleTimeout, 7200);
  act(() => result.current.setIdleTimeout(0));
  act(() => result.current.updateIdleTimeout());
  await waitFor(() => assert.equal(api.updateIdleTimeout.mock.callCount(), 1));
  await waitFor(() => assert.equal(result.current.busy, false));
  assert.equal(saved.run_idle_timeout_seconds, 0);
  assert.equal(result.current.idleTimeout, 0);
  act(() => result.current.setIdleTimeout(-1));
  assert.equal(result.current.idleTimeoutValid, false);
  act(() => result.current.updateIdleTimeout());
  assert.equal(api.updateIdleTimeout.mock.callCount(), 1);
});

test("refreshed first page restores pagination after previously reaching the end", async (t) => {
  dashboardApi(t);
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.runs.length, 2));
  assert.equal(result.current.hasOlderRuns, false);
  api.getRuns.mock.mockImplementation(async () => ({ runs: [{ ...run, id: "newest" }], next_cursor: "more-runs" }));
  await act(async () => { await client.invalidateQueries({ queryKey: ["runs"] }); });
  await waitFor(() => assert.equal(result.current.hasOlderRuns, true));
});

test("older-page errors recover locally and do not poison another filter", async (t) => {
  dashboardApi(t);
  api.getRuns.mock.mockImplementation(async ({ cursor }) => {
    if (cursor) throw new Error("older page offline");
    return { runs: [run], next_cursor: "older" };
  });
  const { result } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.hasOlderRuns, true));
  act(() => result.current.loadOlderRuns());
  await waitFor(() => assert.equal(result.current.runPickerFeedback.pageError?.message, "older page offline"));
  assert.equal(result.current.error, null);
  api.getRuns.mock.mockImplementation(async ({ cursor }) => ({ runs: [{ ...run, id: cursor ? "older-run" : run.id }], next_cursor: cursor ? null : "older" }));
  act(() => result.current.loadOlderRuns());
  await waitFor(() => assert.equal(result.current.runs.length, 2));
  assert.equal(result.current.runPickerFeedback.pageError, null);
  act(() => result.current.filterRunsByMetadata({ team: ["other"] }));
  assert.equal(result.current.runPickerFeedback.pageError, null);
});

test("metadata errors recover independently of run pages", async (t) => {
  dashboardApi(t);
  api.getRunMetadata.mock.mockImplementation(async () => { throw new Error("facets offline"); });
  const { result } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.runPickerFeedback.metadataError?.message, "facets offline"));
  assert.equal(result.current.error, null);
  api.getRunMetadata.mock.mockImplementation(async () => []);
  act(() => result.current.runPickerFeedback.retryMetadata());
  await waitFor(() => assert.equal(result.current.runPickerFeedback.metadataError, null));
});

test("clearing a filter preserves a selected run outside the first page", async (t) => {
  dashboardApi(t);
  const old = { ...run, id: "selected-old-run", metadata: { team: "older" } };
  api.getRun.mock.mockImplementation(async () => old);
  api.getRuns.mock.mockImplementation(async ({ metadata }) => metadata.team ? { runs: [old], next_cursor: null } : { runs: [run], next_cursor: "older-page" });
  const { result } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.runs.length, 1));
  act(() => result.current.filterRunsByMetadata({ team: ["older"] }));
  await waitFor(() => assert.equal(result.current.latestRun.id, old.id));
  act(() => result.current.selectRun(old.id));
  act(() => result.current.filterRunsByMetadata({}));
  await waitFor(() => assert.equal(result.current.runs.length, 2));
  assert.equal(result.current.selectedRunId, old.id);
  assert.equal(result.current.latestRun.id, old.id);
});

test("refetch updates older pages after missed completion and deletion events", async (t) => {
  dashboardApi(t);
  let refreshed = false;
  api.getRuns.mock.mockImplementation(async ({ cursor }) => cursor ? {
    runs: refreshed ? [{ ...run, id: "old-running", status: "PASSED" }] : [
      { ...run, id: "old-running", status: "RUNNING" }, { ...run, id: "deleted" }
    ], next_cursor: null,
  } : { runs: [run], next_cursor: "older" });
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.hasOlderRuns, true));
  act(() => result.current.loadOlderRuns());
  await waitFor(() => assert.equal(result.current.runs.length, 3));
  refreshed = true;
  await act(async () => { await client.invalidateQueries(); });
  await waitFor(() => assert.equal(result.current.runs.find(r => r.id === "old-running").status, "PASSED"));
  assert.equal(result.current.runs.some(r => r.id === "deleted"), false);
});

test("a late older page cannot overwrite a run completion event", async (t) => {
  dashboardApi(t);
  const { applyLiveDashboardEvents } = await jiti.import("../src/api/live-cache.ts");
  const pending = deferred();
  api.getRuns.mock.mockImplementation(async ({ cursor }) => cursor ? pending.promise : { runs: [{ ...run, status: "RUNNING" }], next_cursor: "older" });
  const { result, client } = mount(useAppData);
  await waitFor(() => assert.equal(result.current.hasOlderRuns, true));
  act(() => result.current.loadOlderRuns());
  await waitFor(() => assert.equal(result.current.loadingOlderRuns, true));
  api.getRuns.mock.mockImplementation(async () => ({ runs: [{ ...run, status: "PASSED" }], next_cursor: null }));
  await act(async () => {
    applyLiveDashboardEvents(client, [{ seq: 1, event_type: "run_ended", run_id: run.id, payload: { status: "PASSED", ended_at: run.started_at, total_tests: 1, passed: 1, failed: 0, duration_ms: 100 } }]);
    pending.resolve({ runs: [{ ...run, status: "RUNNING" }], next_cursor: null });
  });
  await waitFor(() => assert.equal(result.current.loadingOlderRuns, false));
  assert.equal(result.current.runs.find(r => r.id === run.id).status, "PASSED");
});

test("test pages retry, refresh cursors, and query filters on the server", async (t) => {
  const { useTestPages } = await jiti.import("../src/hooks/useTestPages.ts");
  let failOlder = true;
  let refreshed = false;
  const requests = [];
  t.mock.method(api, "getTests", async (runId, options) => {
    requests.push({runId, ...options});
    if (options.search) return testPage([{...testRecord, id:"outside-first-page"}]);
    if (!options.cursor) return testPage([testRecord], refreshed ? "fresh-cursor" : "old-cursor", 2);
    if (failOlder) throw new Error("Older tests unavailable");
    return testPage([{...testRecord, id:refreshed ? "fresh-test" : "old-test"}], null, 2);
  });
  const { result, client } = mount(() => {
    const [search, setSearch] = useState("");
    return {...useTestPages("run-1", {search}, true), setSearch};
  });
  await waitFor(() => assert.equal(result.current.tests.length, 1));
  act(() => result.current.loadMore());
  await waitFor(() => assert.equal(result.current.isFetchNextPageError, true));
  assert.equal(result.current.tests.length, 1);
  failOlder = false;
  act(() => result.current.loadMore());
  await waitFor(() => assert.equal(result.current.tests.length, 2));
  refreshed = true;
  await act(async () => client.invalidateQueries({queryKey:["tests", "run-1"]}));
  await waitFor(() => assert.ok(result.current.tests.some(test => test.id === "fresh-test")));
  assert.equal(requests[requests.length - 1].cursor, "fresh-cursor");
  act(() => result.current.setSearch("outside"));
  await waitFor(() => assert.equal(result.current.tests[0]?.id, "outside-first-page"));
  assert.equal(requests[requests.length - 1].search, "outside");
  assert.equal(requests[requests.length - 1].cursor, undefined);
});

test("selected tests remain available outside the loaded page", async (t) => {
  dashboardApi(t);
  const {result} = mount(useAppData);
  await waitFor(() => assert.equal(result.current.tests.length, 2));
  act(() => result.current.selectTest("test-1999"));
  await waitFor(() => assert.equal(result.current.selectedTest?.id, "test-1999"));
  assert.equal(result.current.tests.length, 2);
  assert.equal(api.getTest.mock.calls.at(-1).arguments[1], "test-1999");
});
