import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";
import { entry } from "./helpers/fixtures.mjs";

const dom = new JSDOM("<!doctype html><html><body></body></html>", {
  url: "http://localhost", pretendToBeVisual: true,
});
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  HTMLElement: dom.window.HTMLElement, HTMLInputElement: dom.window.HTMLInputElement,
  HTMLTextAreaElement: dom.window.HTMLTextAreaElement, HTMLSelectElement: dom.window.HTMLSelectElement,
  PopStateEvent: dom.window.PopStateEvent, IS_REACT_ACT_ENVIRONMENT: true,
  ResizeObserver: class { observe() {} disconnect() {} },
});
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, fsCache: false });
const { createElement: h } = await jiti.import("react");
const { render, fireEvent, within, act, cleanup } = await jiti.import("@testing-library/react");
const { EvidenceWorkbench } = await jiti.import("../src/components/EvidenceWorkbench.tsx");
const { EvidenceViewMemoryProvider } = await jiti.import("../src/components/evidence/EvidenceViewMemory.tsx");
const { MockJournal } = await jiti.import("../src/components/MockJournal.tsx");
const { EvidenceNavigationProvider } = await jiti.import("../src/hooks/useEvidenceNavigation.tsx");
const { navigateTo } = await jiti.import("../src/utils/location.ts");

afterEach(() => {
  cleanup();
  window.history.replaceState(null, "", "/");
});

test("evidence controls coordinate search, issue selection, inspector navigation and removal", () => {
  const entries = [entry, { ...entry, id: 2, action: "charge card", result: "FAILED", error: "declined" },
    { ...entry, id: 3, action: "load profile" }];
  let traceOpened = 0;
  const props = { entries, onOpenTrace: () => traceOpened++ };
  const view = render(h(EvidenceWorkbench, props));
  const search = view.getByRole("textbox", { name: /Search evidence/ });
  fireEvent.keyDown(window, { key: "/" });
  assert.ok(document.activeElement === search);
  fireEvent.change(search, { target: { value: "missing" } });
  assert.ok(view.getByText("No matching evidence"));
  fireEvent.click(view.getByRole("button", { name: "Jump to first issue" }));
  assert.equal(search.value, "");
  let inspector = view.getByRole("complementary", { name: "Evidence details for charge card" });
  assert.match(inspector.textContent, /declined/);
  fireEvent.click(within(inspector).getByRole("button", { name: "Related trace" }));
  assert.equal(traceOpened, 1);
  fireEvent.click(within(inspector).getByRole("button", { name: "Next →" }));
  inspector = view.getByRole("complementary", { name: "Evidence details for load profile" });
  assert.equal(within(inspector).getByRole("button", { name: "Next →" }).disabled, true);
  view.rerender(h(EvidenceWorkbench, { ...props, entries: entries.slice(0, 2) }));
  assert.equal(view.queryByRole("complementary"), null);
  fireEvent.click(view.getByRole("button", { name: /Needs attention/ }));
  assert.equal(view.getAllByRole("listitem").length, 1);
  fireEvent.click(view.getByRole("button", { name: /charge card/ }));
  fireEvent.click(view.getByRole("button", { name: "View details" }));
  fireEvent.keyDown(document.activeElement, { key: "Escape" });
  assert.equal(view.queryByRole("complementary"), null);
});


test("new evidence preserves selection and a dismissed inspector stays closed", () => {
  const entries = [entry, {...entry, id: 2, action: "charge card", result: "FAILED", error: "declined"}];
  const props = {entries, onOpenTrace() {}};
  const view = render(h(EvidenceWorkbench, props));
  assert.ok(view.getByRole("complementary", {name: "Evidence details for charge card"}));
  const row = view.getByRole("button", {name: /POST \/products/});
  fireEvent.click(row);
  fireEvent.click(view.getByRole("button", {name: "Raw", exact: true}));
  assert.match(view.getByRole("complementary").textContent, /"assertion_id"/);
  view.rerender(h(EvidenceWorkbench, {...props, entries: [...entries, {...entry, id: 3}]}));
  assert.ok(view.getByRole("complementary", {name: "Evidence details for POST /products"}));
  fireEvent.click(view.getByRole("button", {name: "Close inspector"}));
  assert.ok(document.activeElement === row);
  view.rerender(h(EvidenceWorkbench, {...props, entries: [...entries, {...entry, id: 4}]}));
  assert.equal(view.queryByRole("complementary"), null);
});

test("timeline search and selection survive visiting another tab within the same test", () => {
  const entries = [entry, {...entry, id: 2, action: "charge card"}];
  const content = () => h(EvidenceWorkbench, {entries, onOpenTrace() {}});
  const view = render(h(EvidenceViewMemoryProvider, {}, content()));
  fireEvent.change(view.getByRole("textbox", {name: /Search evidence/}), {target: {value: "charge"}});
  fireEvent.click(view.getByRole("button", {name: /charge card/}));
  view.rerender(h(EvidenceViewMemoryProvider, {}, null));
  view.rerender(h(EvidenceViewMemoryProvider, {}, content()));
  assert.equal(view.getByRole("textbox", {name: /Search evidence/}).value, "charge");
  assert.ok(view.getByRole("complementary", {name: "Evidence details for charge card"}));
  fireEvent.click(view.getByRole("button", {name: "Close inspector"}));
  view.rerender(h(EvidenceViewMemoryProvider, {}, null));
  view.rerender(h(EvidenceViewMemoryProvider, {}, content()));
  assert.equal(view.queryByRole("complementary"), null);
});

test("expected HTTP errors remain successful and comparison comes before payloads", () => {
  const view = render(h(EvidenceWorkbench, {entries: [{...entry, expected: "404", actual: "404", output: "Not found"}], onOpenTrace() {}}));
  assert.equal(view.queryByRole("button", {name: "Jump to first issue"}), null);
  assert.equal(view.queryByRole("complementary"), null);
  fireEvent.click(view.getByRole("button", {name: /POST \/products/}));
  const inspector = view.getByRole("complementary");
  const comparison = within(inspector).getByRole("region", {name: "Expected and actual values"});
  assert.equal(within(comparison).getAllByText("404").length, 2);
  assert.ok(inspector.textContent.indexOf("Expected") < inspector.textContent.indexOf("Output"));
});

test("mock controls preserve warnings, payload tabs, ambient scope and dismissed selection", () => {
  const records = journalRecords();
  const view = render(h(MockJournal, { ...records, onOpenTrace() {} }));
  const inspector = () => view.getByRole("complementary", { name: "Exchange details for /payments" });
  assert.ok(inspector()); // The first issue is selected in standalone mode.
  fireEvent.click(within(inspector()).getByRole("button", { name: "request" }));
  assert.match(inspector().textContent, /payment-request/);
  fireEvent.click(within(inspector()).getByRole("button", { name: "response" }));
  assert.match(inspector().textContent, /payment-response/);
  assert.match(inspector().textContent, /truncated/);

  const search = view.getByRole("textbox", { name: /Search mock exchanges/ });
  fireEvent.change(search, { target: { value: "missing" } });
  assert.ok(view.getByText("No exchanges match this lens"));
  assert.ok(view.getByText("Select an exchange"));
  fireEvent.click(view.getByRole("button", { name: /Near Miss.*check payment stub/ }));
  assert.equal(search.value, "");
  assert.ok(within(inspector()).getByText("Candidate 1"));
  assert.match(inspector().textContent, /stub mismatch/);
  fireEvent.click(view.getByRole("button", { name: "Collapse" }));
  assert.equal(view.queryByRole("button", { name: /Near Miss.*check payment stub/ }), null);
  fireEvent.click(view.getByRole("button", { name: "Review" }));

  fireEvent.click(view.getByRole("checkbox", { name: /Include ambient/ }));
  assert.equal(view.getAllByRole("listitem").length, 3);
  fireEvent.click(view.getByRole("button", { name: /unattributed.*\/ambient/ }));
  assert.ok(view.getByRole("complementary", { name: "Exchange details for /ambient" }));
  fireEvent.click(view.getByRole("checkbox", { name: /Include ambient/ }));
  assert.ok(view.getByText("Select an exchange"));
  fireEvent.click(view.getByRole("button", { name: "Jump to first issue" }));
  assert.ok(within(inspector()).getByRole("button", { name: "overview" }).classList.contains("is-active"));
  fireEvent.click(within(inspector()).getByRole("button", { name: "Close inspector" }));
  view.rerender(h(MockJournal, { ...records, interactions: [...records.interactions], onOpenTrace() {} }));
  assert.ok(view.getByText("Select an exchange"));
});

test("URL selection distinguishes warning and exchange IDs and retains ambient ownership", () => {
  const records = journalRecords();
  window.history.replaceState(null, "", "/runs/run-1/tests/test-1?tab=mocks&focus=interaction:2");
  const wrapper = ({ children }) => h(EvidenceNavigationProvider, { runId: "run-1", testId: "test-1" }, children);
  const view = render(h(MockJournal, { ...records, onOpenTrace() {} }), { wrapper });
  const search = view.getByRole("textbox", { name: /Search mock exchanges/ });
  fireEvent.change(search, { target: { value: "missing" } });
  act(() => navigateTo("/runs/run-1/tests/test-1?tab=mocks&focus=warning:2"));
  assert.equal(search.value, "");
  assert.ok(view.getByRole("button", { name: /Near Miss.*check payment stub/, pressed: true }));
  assert.ok(view.getByText("Candidate 1"));
  act(() => navigateTo("/runs/run-1/tests/test-1?tab=mocks&focus=interaction:999"));
  assert.ok(view.getByText("Select an exchange"));
  assert.match(window.location.search, /interaction:999/);
  fireEvent.click(view.getByRole("checkbox", { name: /Include ambient/ }));
  fireEvent.click(view.getByRole("button", { name: /unattributed.*\/ambient/ }));
  assert.equal(window.location.pathname, "/runs/run-1");
  assert.match(window.location.search, /focus=interaction:3/);
});

function journalRecords() {
  const passing = interaction({ id: 1, target: "/orders" });
  const failing = interaction({ id: 2, target: "/payments", matched: false, status: "503", stub_id: "payment-stub",
    latency_ms: 700, near_misses: ["stub mismatch"], request_body: "payment-request",
    response_body: "payment-response", response_body_truncated: true });
  return {
    interactions: [passing, failing],
    warnings: [{ id: 2, run_id: "run-1", test_id: "test-1", timestamp: failing.timestamp,
      system: "HTTP", kind: "NEAR_MISS", target: "/payments", stub_id: "payment-stub", message: "check payment stub" }],
    ambientInteractions: [interaction({ id: 3, test_id: null, target: "/ambient" })],
    ambientWarnings: [],
  };
}

function interaction(overrides) {
  return { id: 1, run_id: "run-1", test_id: "test-1", timestamp: "2026-01-01T00:00:00Z",
    system: "HTTP", protocol: "HTTP", method: "GET", target: "/", status: "200", matched: true,
    attribution: "PROVEN_STUB", latency_ms: 20, stub_id: null, fault: null, near_misses: [],
    configured_delay_ms: null, client_deadline_ms: null, trace_id: null,
    scenario_name: null, scenario_state: null, next_scenario_state: null,
    request_body: null, request_body_truncated: false, response_body: null, response_body_truncated: false,
    ...overrides };
}

test("healthy evidence stays full width; later failures never move selection or focus", () => {
  const props = {entries: [entry], onOpenTrace() {}};
  const view = render(h(EvidenceWorkbench, props));
  assert.equal(view.queryByRole("complementary"), null);
  const search = view.getByRole("textbox", {name: /Search evidence/});
  search.focus();
  view.rerender(h(EvidenceWorkbench, {...props, entries: [entry, {...entry, id: 2, action: "new failure", result: "FAILED"}]}));
  assert.equal(view.queryByRole("complementary"), null);
  assert.ok(document.activeElement === search);
  fireEvent.click(view.getByRole("button", {name: "New failure · View event"}));
  assert.ok(view.getByRole("complementary", {name: "Evidence details for new failure"}));
});

test("inspector focus is explicit, Escape is scoped, and Next announces only the event summary", () => {
  const view = render(h(EvidenceWorkbench, {entries: [entry, {...entry, id: 2, action: "second event", output: "private payload"}, {...entry, id: 3, action: "third event"}], onOpenTrace() {}}));
  const row = view.getByRole("button", {name: /POST \/products/});
  row.focus();
  fireEvent.click(row);
  assert.ok(document.activeElement === row);
  assert.equal(row.getAttribute("aria-controls"), view.getByRole("complementary").id);
  const search = view.getByRole("textbox", {name: /Search evidence/});
  search.focus();
  fireEvent.keyDown(search, {key: "Escape"});
  assert.ok(view.getByRole("complementary"));
  assert.ok(document.activeElement === search);
  fireEvent.click(view.getByRole("button", {name: "View details"}));
  assert.ok(document.activeElement === view.getByRole("heading", {name: entry.action}));
  const next = view.getByRole("button", {name: "Next →"});
  next.focus();
  fireEvent.click(next);
  assert.ok(document.activeElement === next);
  assert.equal(view.getByRole("status").textContent, "second event. Event 2 of 3.");
  fireEvent.keyDown(next, {key: "Escape"});
  assert.equal(view.queryByRole("complementary"), null);
  assert.ok(document.activeElement === view.getByRole("button", {name: /second event/}));
});

test("Mocks retains search, scope, disclosure, payload mode, and intentional dismissal after unmount", () => {
  const content = () => h(MockJournal, {...journalRecords(), onOpenTrace() {}});
  const view = render(h(EvidenceViewMemoryProvider, {}, content()));
  fireEvent.click(view.getByRole("checkbox", {name: /Include ambient/}));
  fireEvent.change(view.getByRole("textbox", {name: /Search mock exchanges/}), {target: {value: "payments"}});
  fireEvent.click(view.getByRole("button", {name: "Collapse"}));
  fireEvent.click(view.getByRole("button", {name: "response", exact: true}));
  view.rerender(h(EvidenceViewMemoryProvider, {}, null));
  view.rerender(h(EvidenceViewMemoryProvider, {}, content()));
  assert.equal(view.getByRole("checkbox", {name: /Include ambient/}).checked, true);
  assert.equal(view.getByRole("textbox", {name: /Search mock exchanges/}).value, "payments");
  assert.ok(view.getByRole("button", {name: "Review"}));
  assert.ok(view.getByRole("button", {name: "response", exact: true}).classList.contains("is-active"));
  fireEvent.click(view.getByRole("button", {name: "Close inspector"}));
  view.rerender(h(EvidenceViewMemoryProvider, {}, null));
  view.rerender(h(EvidenceViewMemoryProvider, {}, content()));
  assert.ok(view.getByText("Select an exchange"));
});

test("Trace remembers collapsed branches and Timeline remembers Raw on return", async () => {
  const { SpanTree } = await jiti.import("../src/components/SpanTree.tsx");
  const { span } = await import("./helpers/fixtures.mjs");
  const spans = [span, {...span, id: 2, span_id: "child", parent_span_id: span.span_id, operation_name: "child operation"}];
  const trace = () => h(SpanTree, {spans});
  const timeline = () => h(EvidenceWorkbench, {entries: [entry], onOpenTrace() {}});
  const view = render(h(EvidenceViewMemoryProvider, {}, trace()));
  fireEvent.click(view.getByRole("button", {name: `Collapse ${span.operation_name}`}));
  view.rerender(h(EvidenceViewMemoryProvider, {}, timeline()));
  fireEvent.click(view.getByRole("button", {name: /POST \/products/}));
  fireEvent.click(view.getByRole("button", {name: "Raw", exact: true}));
  view.rerender(h(EvidenceViewMemoryProvider, {}, trace()));
  assert.equal(view.queryByRole("button", {name: /Inspect child operation/}), null);
  view.rerender(h(EvidenceViewMemoryProvider, {}, timeline()));
  assert.equal(view.getByRole("button", {name: "Raw", exact: true}).getAttribute("aria-pressed"), "true");
});
