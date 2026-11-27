import assert from "node:assert/strict";
import { test, afterEach } from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";
import { run, testRecord, span } from "./helpers/fixtures.mjs";

const dom = new JSDOM("<html><body></body></html>", { url: "http://localhost" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document,
  HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, fsCache: false });
const { createElement: h, useState } = await jiti.import("react");
const { render, fireEvent, waitFor, cleanup, within } = await jiti.import("@testing-library/react");
const { QueryClient, QueryClientProvider } = await jiti.import("@tanstack/react-query");
const { api } = await jiti.import("../src/api/client.ts");
const { DrillDownPanel } = await jiti.import("../src/components/DrillDownPanel.tsx");
const clients = [];
afterEach(() => { cleanup(); clients.splice(0).forEach(c => c.clear()); });
const frame = name => ({ name, file: "App.java", line: 1 });
function report(value) {
  return { run_id: run.id, app_name: run.app_name, truncated: false, interpretation: "Sampled evidence",
    series: [{ service: "checkout", metric: "process_cpu:cpu", unit: "nanoseconds", total: value,
      sample_records: 50, from_ms: 0, until_ms: 1000, sample_rates: [100], profile_ids: ["profile-1"],
      hotspots: [{frame:frame("Leaf"),self_value:value,inclusive_value:value,self_percent:100}],
      stacks: [{ frames:[frame("Root"),frame("Leaf")],value }], omitted_stack_value:0, findings:[] }] };
}
function mount(props = {}) {
  const client = new QueryClient({ defaultOptions: {queries:{retry:false,gcTime:Infinity}} });
  clients.push(client);
  function Panel() {
    const [view, onViewChange] = useState({ kind: "run" });
    return h(DrillDownPanel, { appName: run.app_name, run, tests: [testRecord],
      view, onViewChange, onClose: () => {}, ...props });
  }
  return render(h(QueryClientProvider, { client }, h(Panel)));
}
test("profile zoom refreshes with new evidence and test selection requests an overlapping window",async()=>{
  let value=10;
  const calls=[];
  api.profiles=async(id,params)=>{calls.push([id,params.toString()]);return report(value);};
  const screen=mount();
  await waitFor(()=>assert.ok(screen.getByRole("button",{name:"Leaf",exact:true})));
  fireEvent.click(screen.getByRole("button",{name:"Leaf",exact:true}));
  assert.equal(screen.getByRole("button",{name:"Reset zoom"}).disabled,false);
  value=30;
  fireEvent.click(screen.getByRole("button",{name:"Refresh"}));
  await waitFor(()=>assert.equal(screen.getByRole("button",{name:"Leaf",exact:true}).title,"Leaf: 30 nanoseconds"));
  assert.equal(screen.getByRole("button",{name:"Reset zoom"}).disabled,false);
  fireEvent.click(screen.getByRole("button",{name:"Zoom out"}));
  assert.ok(screen.getByRole("button",{name:"Root",exact:true}));
  fireEvent.click(screen.getByRole("button",{name:"Reset zoom"}));
  fireEvent.change(screen.getByLabelText("Recording window"),{target:{value:testRecord.id}});
  await waitFor(()=>assert.ok(calls.at(-1)[1].includes("from_ms=")));
  assert.equal(calls.at(-1)[0],run.id);
});
test("failed profiling query is visible and refresh can recover",async()=>{
  api.profiles=async()=>{throw new Error("Profiles unavailable");};
  const screen=mount();
  await waitFor(()=>assert.equal(screen.getByRole("alert").textContent,"Profiles unavailable"));
  api.profiles=async()=>report(10);
  fireEvent.click(screen.getByRole("button",{name:"Refresh"}));
  await waitFor(()=>assert.ok(screen.getByRole("button",{name:"Leaf",exact:true})));
  assert.equal(screen.queryByRole("alert"),null);
});

test("run history opens the exact profile and restores the series, hotspot, and row focus", async () => {
  const metadata = { branch: ["main"], environment: ["ci"] };
  const calls = [];
  const profileCalls = [];
  const cpu = report(100).series[0];
  api.profiles = async (id, params) => {
    profileCalls.push([id, Object.fromEntries(params)]);
    return id === run.id ? report(10) : {
      ...report(0), run_id: id,
      series: [{ ...cpu, service: "worker", unit: "count" }, { ...cpu, service: "worker" }],
    };
  };
  const worker = { service: "worker", metric: cpu.metric, unit: cpu.unit };
  api.profileTrends = async (appName, filter) => {
    calls.push([appName, filter]);
    const runs = ["older-pass", "newer-pass"].map((run_id, i) => ({
      run_id, started_at: `2024-06-0${i + 1}T10:00:00Z`, status: "PASSED", total: 100, sample_records: 50,
    }));
    return {
      app_name: appName, runs_considered: 3, min_sample_records: 20,
      hotspot_threshold_percent: 20, run_limit: 10, interpretation: "Sampled evidence",
      truncated: false,
      series: [
        { ...worker, service: "checkout", runs, bottlenecks: [] },
        { ...worker, runs, bottlenecks: [{
          method: "app.Worker.process", file: "Worker.kt", hot_runs: 2, median_self_percent: 35,
          change_percentage_points: 20, self_percent_by_run: [25, 45],
        }] },
      ],
    };
  };
  const screen = mount({ metadata });
  assert.deepEqual(calls, []);
  fireEvent.click(screen.getByRole("button", { name: "Run history" }));
  const selector = await screen.findByLabelText("Service and profile metric");
  const workerKey = JSON.stringify(Object.values(worker));
  fireEvent.change(selector, { target: { value: workerKey } });
  const summary = screen.getByText("app.Worker.process");
  const hotspot = summary.closest("details");
  assert.deepEqual(calls, [[run.app_name, metadata]]);
  assert.match(screen.getByText(/of 3 recent runs/).textContent, /2 of 3/);
  assert.ok(screen.getByText("25.0% → 45.0% of sampled CPU"));
  assert.ok(screen.getByText(/Hot in 2 of 2 runs/));
  fireEvent.click(summary.closest("summary"));
  assert.equal(hotspot.open, true);
  assert.match(screen.getByRole("row", { name: /older-pass/ }).textContent, /25\.0%/);
  assert.match(screen.getByRole("row", { name: /newer-pass/ }).textContent, /45\.0%/);
  const inspect = within(screen.getByRole("row", { name: /older-pass/ }))
    .getByRole("button", { name: /Inspect profile/ });
  inspect.focus();
  fireEvent.click(inspect);
  assert.ok(document.activeElement === screen.getByRole("heading", { name: "Historical run profile" }));
  const metric = await screen.findByRole("button", { name: "CPU time 100 ns" });
  assert.equal(metric.getAttribute("aria-pressed"), "true");
  assert.equal(screen.queryByRole("button", { name: "CPU time 100" }), null);
  assert.deepEqual(profileCalls.at(-1), ["older-pass", { service: "worker", metric: cpu.metric }]);

  fireEvent.click(screen.getByRole("button", { name: "Back to run history" }));
  assert.ok(document.activeElement === inspect);
  assert.equal(selector.value, workerKey);
  assert.equal(hotspot.open, true);
  fireEvent.click(screen.getByRole("button", { name: "This run" }));
  await screen.findByRole("button", { name: "Leaf", exact: true });
  fireEvent.click(screen.getByRole("button", { name: "Run history" }));
  assert.equal(selector.value, workerKey);
  assert.equal(hotspot.open, true);
  assert.ok(screen.getByRole("button", { name: "Inspect profile for run older-pass" }));
});

test("CPU is selected first and metric selection falls back when the service changes", async () => {
  const cpu = report(100).series[0];
  const allocation = { ...cpu, metric: "memory:alloc_in_new_tlab_bytes", unit: "bytes", total: 2048 };
  api.profiles = async () => ({ ...report(100), series: [allocation, cpu, { ...cpu, service: "worker" }] });
  const screen = mount();
  await waitFor(() => assert.equal(screen.getByRole("button", { name: "CPU time 100 ns" }).getAttribute("aria-pressed"), "true"));
  assert.equal(screen.getByLabelText("Service").value, "checkout");
  fireEvent.click(screen.getByRole("button", { name: "Allocated bytes · TLAB 2 KiB" }));
  assert.equal(screen.getByRole("button", { name: "Allocated bytes · TLAB 2 KiB" }).getAttribute("aria-pressed"), "true");
  fireEvent.change(screen.getByLabelText("Service"), { target: { value: "worker" } });
  assert.equal(screen.getByRole("button", { name: "CPU time 100 ns" }).getAttribute("aria-pressed"), "true");
  assert.equal(screen.queryByRole("button", { name: /Allocated bytes/ }), null);
});

test("search connects methods and frames, ranking and selection preserve exact method identity", async () => {
  const data = report(100);
  data.series[0].hotspots = [
    { frame: frame("Root"), self_value: 10, inclusive_value: 100, self_percent: 10 },
    { frame: frame("Leaf"), self_value: 90, inclusive_value: 90, self_percent: 90 },
  ];
  api.profiles = async () => data;
  const screen = mount();
  await waitFor(() => assert.ok(screen.getByRole("table")));
  const methods = () => [...screen.container.querySelectorAll(".profile-method")];
  assert.equal(methods()[0].title, "Leaf");
  fireEvent.change(screen.getByLabelText("Rank by"), { target: { value: "inclusive" } });
  assert.equal(methods()[0].title, "Root");
  fireEvent.change(screen.getByRole("searchbox"), { target: { value: "leaf" } });
  assert.equal(methods().length, 1);
  assert.match(screen.getByRole("status").textContent, /1 matching frames/);
  assert.equal(screen.getByRole("button", { name: "Root", exact: true }).dataset.dimmed, "true");
  fireEvent.click(screen.getByRole("button", { name: "Clear selection" }));
  fireEvent.click(methods()[0]);
  assert.equal(methods()[0].getAttribute("aria-pressed"), "true");
  assert.equal(screen.getByRole("button", { name: "Root", exact: true }).dataset.match, "true");
  fireEvent.change(screen.getByRole("searchbox"), { target: { value: "missing" } });
  assert.equal(methods().length, 0);
  assert.match(screen.getByRole("status").textContent, /0 matching frames/);
  fireEvent.click(screen.getByRole("button", { name: "Clear selection" }));
  assert.equal(methods().length, 2);
});

test("an empty test window offers a return to the entire run", async () => {
  api.profiles = async (_id, params) => params.has("from_ms") ? { ...report(10), series: [] } : report(10);
  const screen = mount();
  await waitFor(() => assert.ok(screen.getByRole("button", { name: "Leaf", exact: true })));
  fireEvent.change(screen.getByLabelText("Recording window"), { target: { value: testRecord.id } });
  await waitFor(() => assert.ok(screen.getByRole("heading", { name: "No recordings overlap this test" })));
  fireEvent.click(screen.getByRole("button", { name: "Show entire run" }));
  await waitFor(() => assert.ok(screen.getByRole("button", { name: "Leaf", exact: true })));
  assert.equal(screen.getByLabelText("Recording window").value, "");
});

test("zoom returns to all stacks when a refreshed recording no longer contains the selected path", async () => {
  api.profiles = async () => report(10);
  const screen = mount();
  await waitFor(() => assert.ok(screen.getByRole("button", { name: "Leaf", exact: true })));
  fireEvent.click(screen.getByRole("button", { name: "Leaf", exact: true }));
  const updated = report(20);
  updated.series[0].stacks = [{ frames: [frame("Other")], value: 20 }];
  api.profiles = async () => updated;
  fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
  await waitFor(() => assert.ok(screen.getByRole("button", { name: "Other", exact: true })));
  assert.equal(screen.getByRole("button", { name: "Reset zoom" }).disabled, true);
});

test("span correlation sends exact selectors and empty results can return to the entire run", async () => {
  const calls = [];
  api.profiles = async (id, params) => { calls.push([id, new URLSearchParams(params)]); return {...report(0), series: []}; };
  let cleared = false;
  const screen = mount({correlation: {traceId: "trace-1", spanId: "span-1", name: "checkout"}, onClearCorrelation: () => {cleared = true;}});
  await waitFor(() => assert.ok(screen.getByText("No correlated samples found")));
  assert.equal(calls[0][0], run.id);
  assert.equal(calls[0][1].get("trace_id"), "trace-1");
  assert.equal(calls[0][1].get("span_id"), "span-1");
  assert.equal(calls[0][1].has("from_ms"), false);
  assert.equal(screen.queryByLabelText("Recording window"), null);
  fireEvent.click(screen.getByRole("button", {name: "Show entire run"}));
  assert.equal(cleared, true);
});

test("trace selection does not invent a profile id for uncorrelated child spans", async () => {
  const calls = [];
  api.profiles = async (id, params) => { calls.push(new URLSearchParams(params)); return report(10); };
  const screen = mount({correlation: {traceId: "trace-2", name: "child"}});
  await waitFor(() => assert.ok(screen.getByRole("button", {name: "Leaf", exact: true})));
  assert.equal(calls[0].get("trace_id"), "trace-2");
  assert.equal(calls[0].has("span_id"), false);
});

test("span inspector opens the exact run and profile id and falls back to trace scope", async () => {
  const { SpanInspector } = await jiti.import("../src/components/span-tree/SpanInspector.tsx");
  const { DrillDownNavigationProvider } = await jiti.import("../src/hooks/useDrillDownNavigation.tsx");
  const calls = [];
  let closed = 0;
  const view = value => h(DrillDownNavigationProvider, {value: (...args) => calls.push(args)},
    h(SpanInspector, {span: value, onClose: () => closed++}));
  const screen = render(view({...span, attributes: JSON.stringify({"pyroscope.profile.id": "profile-root"})}));
  fireEvent.click(screen.getByRole("button", {name: "Drill Down · this span"}));
  assert.deepEqual(calls[0], [span.run_id, {traceId: span.trace_id, spanId: "profile-root", name: span.operation_name}]);
  assert.equal(closed, 1);
  screen.rerender(view({...span, attributes: null}));
  fireEvent.click(screen.getByRole("button", {name: "Drill Down · this trace"}));
  assert.equal(calls[1][1].spanId, undefined);
  assert.equal(calls[1][1].traceId, span.trace_id);
});
