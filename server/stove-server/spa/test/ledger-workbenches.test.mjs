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
  assert.equal(document.activeElement, search);
  fireEvent.change(search, { target: { value: "missing" } });
  assert.ok(view.getByText("No matching evidence"));
  fireEvent.click(view.getByRole("button", { name: "Jump to first issue" }));
  assert.equal(search.value, "");
  let dialog = view.getByRole("dialog", { name: "Evidence details for charge card" });
  assert.match(dialog.textContent, /declined/);
  fireEvent.click(within(dialog).getByRole("button", { name: "Open trace" }));
  assert.equal(traceOpened, 1);
  fireEvent.click(within(dialog).getByRole("button", { name: "Next →" }));
  dialog = view.getByRole("dialog", { name: "Evidence details for load profile" });
  assert.equal(within(dialog).getByRole("button", { name: "Next →" }).disabled, true);
  view.rerender(h(EvidenceWorkbench, { ...props, entries: entries.slice(0, 2) }));
  assert.equal(view.queryByRole("dialog"), null);
  fireEvent.click(view.getByRole("button", { name: /Needs attention/ }));
  assert.equal(view.getAllByRole("listitem").length, 1);
  fireEvent.click(view.getByRole("button", { name: /charge card/ }));
  fireEvent.keyDown(window, { key: "Escape" });
  assert.equal(view.queryByRole("dialog"), null);
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
