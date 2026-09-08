import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { test, afterEach } from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";
import { app, meta, entry, span, run, testRecord, deferred } from "./helpers/fixtures.mjs";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {
  url: "http://localhost", pretendToBeVisual: true,
});
Object.assign(globalThis, { window: dom.window, document: dom.window.document,
  HTMLElement: dom.window.HTMLElement, PopStateEvent: dom.window.PopStateEvent,
  localStorage: dom.window.localStorage,
  IS_REACT_ACT_ENVIRONMENT: true, __STOVE_VERSION__: "test",
  ResizeObserver: class { observe() {} disconnect() {} },
});
window.matchMedia = () => ({matches: false, addEventListener() {}, removeEventListener() {}});
const sources = [];
globalThis.EventSource = class { constructor() { sources.push(this); } close() {} };
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, fsCache: false, alias: {
  "../assets/stove-mark.svg": fileURLToPath(new URL("./helpers/asset.mjs", import.meta.url)),
} });
const { createElement: h } = await jiti.import("react");
const { render, renderHook, act, waitFor, cleanup, fireEvent, within } = await jiti.import("@testing-library/react");
const { QueryClient, QueryClientProvider } = await jiti.import("@tanstack/react-query");
const { api, ApiError } = await jiti.import("../src/api/client.ts");
const { parseLocation, evidencePath, focusTab, navigateTo, useLocation } = await jiti.import("../src/utils/location.ts");
const { resolveJsonPointer, resolveSnapshotPointer } = await jiti.import("../src/utils/json-pointer.ts");
const { EvidenceNavigationProvider, useEvidenceNavigation } = await jiti.import("../src/hooks/useEvidenceNavigation.tsx");
const { CopyEvidenceLink } = await jiti.import("../src/components/CopyEvidenceLink.tsx");
const { VirtualList } = await jiti.import("../src/components/VirtualList.tsx");
const { LinkedWorkspace } = await jiti.import("../src/layout/LinkedWorkspace.tsx");
const { default: App } = await jiti.import("../src/App.tsx");
const { useFocusedEvidence } = await jiti.import("../src/hooks/useFocusedEvidence.ts");
const clients = [];
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.clear()); sources.length = 0;
  document.head.innerHTML = ""; window.history.replaceState(null, "", "/"); });
function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  clients.push(client);
  return { client, wrapper: ({children}) => h(QueryClientProvider, {client}, children) };
}
function response(item) { return { target: {kind: "entry", value: item}, entries: [item], spans: [],
  interactions: [], warnings: [], context_limit: 10, has_more_before: false, has_more_after: false }; }
function Router() { const location = useLocation();
  return location.kind === "evidence" ? h(LinkedWorkspace, { location: location.value }) : null; }

function dashboardApi(t) {
  const tests = [testRecord, {...testRecord, id: "test-2", test_name: "updates a product",
    test_path: ["updates a product"], status: "FAILED", error: "update failed"}];
  t.mock.method(api, "getApps", async () => [app]);
  t.mock.method(api, "getMeta", async () => meta);
  t.mock.method(api, "getRuns", async () => [{...run, total_tests: 2}, {...run, id: "run-2"}]);
  t.mock.method(api, "getTests", async () => tests);
  t.mock.method(api, "getEntries", async (_run, testId) => [{...entry, test_id: testId}]);
  t.mock.method(api, "getSpans", async () => [span]);
  for (const method of ["getRun", "getTest", "getFocusedEvidence"]) {
    t.mock.method(api, method, () => { throw new Error("unexpected linked evidence request"); });
  }
}

test("dashboard test, tab, error and evidence clicks stay in place without linked loading", async (t) => {
  dashboardApi(t);
  const view = render(h(App), wrapper());
  await waitFor(() => assert.ok(view.getByRole("button", {name: /POST \/products/})));
  const sidebar = view.getByRole("complementary", {name: "Test navigator"});
  const search = within(sidebar).getByRole("textbox", {name: "Search tests"});
  fireEvent.change(search, {target: {value: "product"}});
  const historyLength = window.history.length;
  const assertDashboard = () => {
    assert.equal(view.getByRole("complementary", {name: "Test navigator"}), sidebar);
    assert.equal(search.value, "product");
    assert.equal(window.location.pathname, "/");
    assert.equal(window.location.search, "");
    assert.equal(window.history.length, historyLength);
    assert.equal(view.queryByText("Loading requested test run…"), null);
    for (const method of ["getRun", "getTest", "getFocusedEvidence"]) {
      assert.equal(api[method].mock.callCount(), 0, method);
    }
  };

  fireEvent.click(within(sidebar).getByRole("button", {name: /updates a product/}));
  await waitFor(() => assert.ok(view.getByRole("button", {name: /POST \/products/})));
  assert.ok(view.getByRole("heading", {name: "updates a product"}));
  assertDashboard();
  fireEvent.click(view.getByRole("button", {name: /Test failed.*update failed/}));
  assert.match(view.getByRole("dialog").textContent, /update failed/);
  assertDashboard();
  fireEvent.keyDown(window, {key: "Escape"});
  assert.equal(view.queryByRole("dialog"), null);

  fireEvent.click(view.getByRole("button", {name: /POST \/products/}));
  const dialog = view.getByRole("complementary", {name: "Evidence details for POST /products"});
  assertDashboard();
  fireEvent.click(within(dialog).getByRole("button", {name: "Related trace"}));
  await waitFor(() => assert.ok(view.getByRole("list", {name: "Recorded trace spans"})));
  assert.ok(view.getByRole("tab", {name: /Trace/, selected: true}));
  assertDashboard();
  fireEvent.click(view.getByRole("tab", {name: /Timeline/}));
  await waitFor(() => assert.ok(view.getByRole("complementary", {name: "Evidence details for POST /products"})));
  assertDashboard();

  fireEvent.click(within(sidebar).getByRole("button", {name: /creates a product/}));
  await waitFor(() => assert.ok(view.getByRole("heading", {name: "creates a product"})));
  assert.ok(view.getByRole("tab", {name: /Timeline/, selected: true}));
  assert.equal(view.queryByRole("dialog"), null);
  assertDashboard();
  fireEvent.click(view.getByRole("tab", {name: /Trace/}));
  await waitFor(() => assert.ok(view.getByRole("list", {name: "Recorded trace spans"})));
  assertDashboard();
});

test("changing dashboard runs retains the navigator while tests load", async (t) => {
  dashboardApi(t);
  const pending = deferred();
  api.getTests.mock.mockImplementation(async (runId) => runId === "run-2" ? pending.promise : [testRecord]);
  const view = render(h(App), wrapper());
  await waitFor(() => assert.ok(view.getByRole("heading", {name: "creates a product"})));
  const sidebar = view.getByRole("complementary", {name: "Test navigator"});
  fireEvent.change(view.getByRole("combobox", {name: "Run"}), {target: {value: "run-2"}});
  assert.equal(view.getByRole("complementary", {name: "Test navigator"}), sidebar);
  assert.equal(window.location.pathname, "/");
  assert.equal(view.getByRole("combobox", {name: "Run"}).value, "run-2");
  assert.equal(view.queryByText("Loading requested test run…"), null);
  await act(async () => pending.resolve([{...testRecord, run_id: "run-2"}]));
  await waitFor(() => assert.ok(view.getByRole("heading", {name: "creates a product"})));
  assert.equal(view.getByRole("complementary", {name: "Test navigator"}), sidebar);
  assert.equal(api.getRun.mock.callCount(), 0);
  assert.equal(api.getTest.mock.callCount(), 0);
  assert.equal(sources.length, 1);
});

test("Rust and browser share canonical links, including encoded identifiers and run evidence", () => {
  const cases = JSON.parse(readFileSync(new URL("../../tests/fixtures/navigation.json", import.meta.url)));
  for (const c of cases) {
    assert.equal(`${evidencePath(c.run, c.test ?? undefined)}?tab=${focusTab(c.kind)}&focus=${c.kind}:${c.id}`, c.path);
    const url = new URL(c.path, "http://localhost"); const parsed = parseLocation(url.pathname, url.search);
    assert.equal(parsed.kind, "evidence"); assert.equal(parsed.value.runId, c.run);
    assert.equal(parsed.value.testId, c.test ?? undefined); assert.deepEqual(parsed.value.focus, {kind:c.kind,id:c.id});
  }
});

test("malformed or ambiguous citations fail explicitly", () => {
  for (const path of ["/runs/%ZZ", "/runs/r/tests/t?focus=", "/runs/r?focus=entry:1", "/runs/r?focus=error",
    "/runs/r/tests/t?focus=entry:9007199254740992", "/runs/r/tests/t?focus=unknown:1", "/runs/r/tests/t?focus=entry:0",
    "/runs/r/tests/t?focus=entry:1&focus=entry:2", "/runs/r/tests/t?full=0", "/runs/r/tests/t?context=",
    "/runs/r/tests/t?context=101", "/runs/r/tests/t?pointer=/x", "/runs/r/tests/t?focus=snapshot:1&pointer=/~2"] ) {
    const [pathname, search] = path.split("?"); assert.equal(parseLocation(pathname, search ?? "").kind, "invalid", path);
  }
});

test("snapshot paths preserve nulls, escaping, arrays and encoded JSON strings", () => {
  const raw = '{"a/b":{"~key":[null,"{\\"inner\\":1}"]},"__proto__":{"safe":true}}';
  assert.deepEqual(resolveSnapshotPointer(raw, "/a~1b/~0key/0"), {found:true,value:null});
  assert.deepEqual(resolveSnapshotPointer(raw, "/a~1b/~0key/1"), {found:true,value:'{"inner":1}'});
  assert.deepEqual(resolveSnapshotPointer(raw, "/a~1b/~0key/1/inner"), {found:false});
  assert.deepEqual(resolveSnapshotPointer(raw, "/__proto__/safe"), {found:true,value:true});
  assert.deepEqual(resolveJsonPointer({}, "/toString"), {found:false});
  assert.deepEqual(resolveSnapshotPointer("null", ""), {found:true,value:null});
  for (const p of ["/a~1b/~0key/00", "/a~1b/~0key/-", "/missing", "/~2"]) assert.deepEqual(resolveSnapshotPointer(raw,p), {found:false});
});

test("selection, copy, and browser history retain the configured prefix", async (t) => {
  document.head.innerHTML = '<meta name="stove-base" content="/observe">';
  window.history.replaceState(null,"","/observe/runs/run-1/tests/test-1?focus=entry:1");
  const copied = [];
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: {clipboard:{writeText:async value => copied.push(value)}} });
  const navWrapper = ({children}) => h(EvidenceNavigationProvider, {runId:"run-1", testId:"test-1"}, children);
  const hook = renderHook(useEvidenceNavigation,{wrapper:navWrapper});
  const view = render(h(CopyEvidenceLink),{wrapper:navWrapper});
  act(() => hook.result.current.select("span", 17));
  assert.equal(hook.result.current.tab,"trace");
  await act(async () => fireEvent.click(view.getByText("Copy link")));
  assert.equal(copied[0],"http://localhost/observe/runs/run-1/tests/test-1?tab=trace&focus=span:17");
  act(() => window.history.back());
  await waitFor(() => assert.equal(hook.result.current.focus.kind,"entry"));
  act(() => window.history.forward());
  await waitFor(() => assert.equal(hook.result.current.focus.kind,"span"));
  act(() => hook.result.current.showFull()); assert.equal(hook.result.current.full,true);
  assert.equal(hook.result.current.focus.id,17);
});

test("late evidence responses cannot replace a new selected record", async (t) => {
  const a = deferred(), b = deferred();
  t.mock.method(api,"getFocusedEvidence", (_r,_t,focus) => focus.id === 1 ? a.promise : b.promise);
  const mounted = renderHook(({id}) => useFocusedEvidence("run-1","test-1",{kind:"entry",id},10,false), { ...wrapper(), initialProps:{id:1} });
  mounted.rerender({id:2});
  await act(async () => b.resolve(response({...entry,id:2})));
  await waitFor(() => assert.equal(mounted.result.current.data.target.value.id,2));
  await act(async () => a.resolve(response(entry)));
  assert.equal(mounted.result.current.data.target.value.id,2);
});

test("direct links open an earlier retry, preserve it in full view, and never fetch global lists", async (t) => {
  const failed = {...entry, result:"FAILED", error:"earlier retry failed"};
  t.mock.method(api,"getRun", async () => run); t.mock.method(api,"getTest", async () => testRecord);
  t.mock.method(api,"getFocusedEvidence", async () => response(failed));
  t.mock.method(api,"getEntries", async () => [{...entry,id:2}]);
  for (const method of ["getApps","getRuns","getTests"]) t.mock.method(api,method,() => {throw new Error("unexpected global listing");});
  window.history.replaceState(null,"","/runs/run-1/tests/test-1?focus=entry:1");
  const setup = wrapper();
  const view = render(h(Router),setup);
  await waitFor(() => assert.ok(view.getByRole("complementary",{name:"Evidence details for POST /products"})));
  assert.equal(api.getFocusedEvidence.mock.callCount(), 1);
  assert.equal(setup.client.getQueryCache().find({queryKey:["focus","run-1","test-1","entry",1,10]}).getObserversCount(), 1);
  assert.equal(api.getEntries.mock.callCount(),0);
  act(() => fireEvent.click(view.getAllByText("Show full test")[0]));
  await waitFor(() => assert.equal(api.getEntries.mock.callCount(),1));
  await waitFor(() => assert.ok(view.getByRole("complementary",{name:"Evidence details for POST /products"})));
  assert.match(view.container.textContent,/earlier retry failed/);
  for (const method of ["getApps","getRuns","getTests"]) assert.equal(api[method].mock.callCount(),0);
  // A connected SSE stream must also refresh the full-view evidence queries.
  act(() => sources[0].onopen());
  await waitFor(() => assert.ok(api.getEntries.mock.callCount() > 1));
});

test("missing exact evidence shows an unavailable state with no fallback selection", async (t) => {
  t.mock.method(api,"getRun",async () => run); t.mock.method(api,"getTest",async () => testRecord);
  t.mock.method(api,"getFocusedEvidence",async () => {throw new ApiError(404,"gone");});
  t.mock.method(api,"getEntries",async () => [entry]);
  window.history.replaceState(null,"","/runs/run-1/tests/test-1?focus=entry:400");
  const view=render(h(Router),wrapper());
  await waitFor(() => assert.match(view.getByRole("alert").textContent,/unavailable in this scope/));
  assert.equal(view.queryByRole("dialog"),null); assert.equal(api.getEntries.mock.callCount(),0);
  assert.match(window.location.search,/entry:400/);
});

test("virtual ledgers render and scroll to a target outside the initial window", () => {
  const items=Array.from({length:1000},(_,id)=>({id}));
  const view=render(h(VirtualList,{items,getKey:item=>item.id,getItemSize:44,scrollToKey:950,
    className:"ledger",ariaLabel:"records",renderItem:item=>h("span",null,`record ${item.id}`)}));
  assert.ok(view.getByText("record 950")); assert.ok(view.getByRole("list").scrollTop>40000);
  assert.ok(view.queryAllByRole("listitem").length<40);
});

test("inserting live records before the selection does not move the ledger", () => {
  const items = Array.from({length: 200}, (_, id) => ({id}));
  const props = {items, getKey: item => item.id, getItemSize: 64, scrollToKey: 180,
    renderItem: item => h("button", {}, `record ${item.id}`), className: "ledger", ariaLabel: "Evidence"};
  const view = render(h(VirtualList, props));
  const list = view.getByRole("list");
  list.scrollTop = 640;
  fireEvent.scroll(list);
  view.rerender(h(VirtualList, {...props, items: [{id: -1}, ...items]}));
  assert.equal(list.scrollTop, 640);
  view.rerender(h(VirtualList, {...props, scrollToKey: 190}));
  assert.ok(list.scrollTop > 10000);
});

test("a test named tests cannot collide with the run's test-list cache", async (t) => {
  t.mock.method(api,"getRun",async () => run);
  t.mock.method(api,"getTest",async (_run,id) => ({...testRecord,id,error:"specific failure"}));
  t.mock.method(api,"getEntries",async () => []);
  const setup = wrapper();
  setup.client.setQueryData(["linked","run-1","tests"],[testRecord]);
  window.history.replaceState(null,"","/runs/run-1/tests/tests?focus=error");
  const view=render(h(Router),setup);
  await waitFor(() => assert.match(view.getByRole("dialog").textContent,/specific failure/));
  assert.equal(api.getTest.mock.callCount(),1);
});

test("a null run response is explicitly unavailable and never an empty successful run", async (t) => {
  t.mock.method(globalThis,"fetch",async () => new Response("null",{status:200}));
  await assert.rejects(api.getRun("missing"), error => error instanceof ApiError && error.status===404);
});


test("live evidence refresh is limited to the affected record kind and owner", async () => {
  const { evidenceKeys, invalidateEvidenceQueries } = await jiti.import("../src/api/evidence-queries.ts");
  const { client } = wrapper();
  const keys = {
    run: evidenceKeys.run("run-1"),
    test: evidenceKeys.test("run-1", "test-1"),
    tests: evidenceKeys.tests("run-1"),
    entry: evidenceKeys.focus("run-1", "test-1", {kind:"entry",id:1}, 10),
    otherTest: evidenceKeys.focus("run-1", "test-2", {kind:"entry",id:1}, 10),
    otherKind: evidenceKeys.focus("run-1", "test-1", {kind:"snapshot",id:1}, 10),
    otherRun: evidenceKeys.focus("run-2", "test-1", {kind:"entry",id:1}, 10),
  };
  for (const key of Object.values(keys)) client.setQueryData(key, {});
  invalidateEvidenceQueries(client, "run-1", [{run_id:"run-1",event_type:"entry_recorded",payload:entry}]);
  for (const [name,key] of Object.entries(keys)) {
    assert.equal(client.getQueryState(key).isInvalidated, name === "entry", name);
  }
  invalidateEvidenceQueries(client, "run-1", [{run_id:"run-1",event_type:"test_ended",payload:{test_id:"test-1"}}]);
  assert.equal(client.getQueryState(keys.test).isInvalidated, true);
  assert.equal(client.getQueryState(keys.tests).isInvalidated, true);
  assert.equal(client.getQueryState(keys.run).isInvalidated, false);
  // Reconnect/gap recovery still refreshes all queries in this run.
  invalidateEvidenceQueries(client, "run-1");
  assert.equal(client.getQueryState(keys.run).isInvalidated, true);
  assert.equal(client.getQueryState(keys.otherTest).isInvalidated, true);
  assert.equal(client.getQueryState(keys.otherRun).isInvalidated, false);
});

test("explicit tab visits restore their URL target; closing and browser history remain authoritative", async () => {
  window.history.replaceState(null, "", "/runs/run-1/tests/test-1?tab=timeline&focus=entry:1&full=1");
  const navWrapper = ({children}) => h(EvidenceNavigationProvider, {runId:"run-1", testId:"test-1"}, children);
  const hook = renderHook(useEvidenceNavigation, {wrapper: navWrapper});
  act(() => hook.result.current.openTrace("trace/with spaces"));
  assert.equal(hook.result.current.traceId, "trace/with spaces");
  assert.equal(hook.result.current.focus, undefined);
  act(() => hook.result.current.selectTab("timeline"));
  assert.deepEqual(hook.result.current.focus, {kind:"entry", id:1});
  assert.equal(hook.result.current.full, true);
  act(() => hook.result.current.clear());
  assert.equal(hook.result.current.focus, undefined);
  act(() => hook.result.current.selectTab("trace"));
  assert.equal(hook.result.current.traceId, "trace/with spaces");
  act(() => hook.result.current.selectTab("timeline"));
  assert.equal(hook.result.current.focus, undefined);
  act(() => window.history.back());
  await waitFor(() => assert.equal(hook.result.current.traceId, "trace/with spaces"));
  act(() => window.history.forward());
  await waitFor(() => assert.equal(hook.result.current.tab, "timeline"));
  assert.equal(hook.result.current.focus, undefined);
});

test("related trace displays the complete matching tree without selecting an arbitrary span", async (t) => {
  dashboardApi(t);
  api.getSpans.mock.mockImplementation(async () => [span, {...span, id: 2, span_id: "child", parent_span_id: span.span_id, operation_name: "child operation"}, {...span, id: 3, trace_id: "unrelated", operation_name: "unrelated operation"}]);
  const view = render(h(App), wrapper());
  await waitFor(() => assert.ok(view.getByRole("button", {name: /POST \/products/})));
  fireEvent.click(view.getByRole("button", {name: /POST \/products/}));
  fireEvent.click(view.getByRole("button", {name: "Related trace"}));
  await waitFor(() => assert.ok(view.getByRole("button", {name: /Inspect child operation/})));
  assert.equal(view.queryByRole("button", {name: /Inspect unrelated operation/}), null);
  assert.equal(view.queryByRole("dialog"), null);
  fireEvent.click(view.getByRole("button", {name: "Show all traces"}));
  await waitFor(() => assert.ok(view.getByRole("button", {name: /Inspect unrelated operation/})));
});

test("a trace citation without recorded spans has an explicit unavailable state", async (t) => {
  dashboardApi(t);
  api.getSpans.mock.mockImplementation(async () => [{...span, trace_id: "other"}]);
  const view = render(h(App), wrapper());
  await waitFor(() => assert.ok(view.getByRole("button", {name: /POST \/products/})));
  fireEvent.click(view.getByRole("button", {name: /POST \/products/}));
  fireEvent.click(view.getByRole("button", {name: "Related trace"}));
  await waitFor(() => assert.ok(view.getByText("No spans were recorded for this trace.")));
  assert.equal(view.queryByRole("list", {name: "Recorded trace spans"}), null);
});

test("tabs have one keyboard stop, directional navigation, and associated panels; navigator resizes by keyboard", async (t) => {
  dashboardApi(t);
  const view = render(h(App), wrapper());
  await waitFor(() => assert.ok(view.getByRole("tab", {name: /Timeline/})));
  const timeline = view.getByRole("tab", {name: /Timeline/});
  timeline.focus();
  fireEvent.keyDown(timeline, {key: "ArrowLeft"});
  assert.equal(document.activeElement.id, "tab-flow");
  fireEvent.keyDown(document.activeElement, {key: "Home"});
  assert.ok(document.activeElement === timeline);
  assert.equal(view.getAllByRole("tab").filter(tab => tab.tabIndex === 0).length, 1);
  assert.equal(timeline.getAttribute("aria-controls"), view.getByRole("tabpanel").id);
  const separator = view.getByRole("separator", {name: "Test navigator width"});
  separator.focus();
  fireEvent.keyDown(separator, {key: "Home"});
  assert.equal(separator.getAttribute("aria-valuenow"), "240");
  fireEvent.keyDown(separator, {key: "ArrowRight"});
  assert.equal(separator.getAttribute("aria-valuenow"), "256");
  fireEvent.keyDown(separator, {key: "Enter"});
  assert.equal(separator.getAttribute("aria-valuenow"), "280");
});

test("phone history drills into an event, restores its row on Back, and preserves desktop URL", async (t) => {
  t.mock.method(window, "matchMedia", () => ({matches: true, addEventListener() {}, removeEventListener() {}}));
  dashboardApi(t);
  const view = render(h(App), wrapper());
  await waitFor(() => assert.ok(view.getByRole("button", {name: /creates a product/})));
  const testRow = view.getByRole("button", {name: /creates a product/});
  fireEvent.click(testRow);
  await waitFor(() => assert.ok(document.activeElement === view.getByRole("heading", {name: "creates a product"})));
  const eventRow = view.getByRole("button", {name: /POST \/products/});
  fireEvent.click(eventRow);
  assert.equal(window.history.state.stovePhone.screen.level, "event");
  assert.ok(document.activeElement === view.getByRole("heading", {name: "POST /products"}));
  act(() => window.history.back());
  await waitFor(() => assert.ok(document.activeElement === eventRow));
  assert.equal(view.queryByRole("complementary", {name: "Evidence details for POST /products"}), null);
  act(() => window.history.back());
  await waitFor(() => assert.ok(document.activeElement === testRow));
  assert.equal(window.location.pathname, "/");
  assert.equal(window.location.search, "");
});

test("a manually scrolled ledger restores its anchor after a tab visit and earlier insertion", async () => {
  const { EvidenceViewMemoryProvider } = await jiti.import("../src/components/evidence/EvidenceViewMemory.tsx");
  let rows = Array.from({length: 220}, (_, index) => ({id: index + 1}));
  const content = () => h(VirtualList, {items: rows, getKey: item => item.id, getItemSize: 64, renderItem: item => h("button", {}, `event ${item.id}`), className: "test-list", ariaLabel: "Scroll memory test"});
  const view = render(h(EvidenceViewMemoryProvider, {}, content()));
  const list = view.getByRole("list");
  list.scrollTop = 3200;
  fireEvent.scroll(list);
  view.rerender(h(EvidenceViewMemoryProvider, {}, null));
  rows = [{id: 999}, ...rows];
  view.rerender(h(EvidenceViewMemoryProvider, {}, content()));
  assert.equal(view.getByRole("list").scrollTop, 3264);
  assert.ok(view.getByRole("button", {name: "event 51"}));
});
