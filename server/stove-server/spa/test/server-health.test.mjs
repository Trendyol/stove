import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/observe/" });
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true,
});
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, fsCache: false });
const { createElement: h } = await jiti.import("react");
const { render, fireEvent, waitFor, cleanup } = await jiti.import("@testing-library/react");
const { QueryClient, QueryClientProvider } = await jiti.import("@tanstack/react-query");
const { ServerHealth } = await jiti.import("../src/components/ServerHealth.tsx");
// JSDOM does not implement native popover opening; interaction is also checked in Chrome.
const originalFetch = globalThis.fetch;
let client;
afterEach(() => { cleanup(); client?.clear(); globalThis.fetch = originalFetch; });

const report = (healthy = true) => ({
  healthy, stove_server_version: "1.0.0", backend: "sqlite", checked_at: "2026-10-19T10:00:00Z",
  checks: [
    { name: "HTTP API", healthy: true, message: "Server is responding." },
    { name: "Storage", healthy, message: healthy ? "Database is responding." : "Database check failed." },
  ],
});
function mount(liveConnected = true) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(h(QueryClientProvider, { client }, h(ServerHealth, { liveConnected })));
}

test("a connected stream does not mask unhealthy storage; refresh recovers", async () => {
  let healthy = false;
  globalThis.fetch = async (url, init) => {
    assert.match(url, /\/api\/v1\/health$/);
    assert.equal(init.cache, "no-store");
    return new Response(JSON.stringify(report(healthy)), { status: healthy ? 200 : 503 });
  };
  const view = mount();
  await waitFor(() => assert.ok(view.getByRole("button", { name: "Needs attention" })));
  assert.ok(view.getByText("Database check failed."));
  const trigger = view.getByRole("button", { name: "Needs attention" });
  const popover = document.getElementById(trigger.getAttribute("popovertarget"));
  assert.equal(popover.getAttribute("popover"), "auto");
  fireEvent.click(trigger);
  healthy = true;
  fireEvent.click(view.getByRole("button", { name: "Check again", hidden: true }));
  await waitFor(() => assert.ok(view.getByRole("button", { name: "Connected" })));
  assert.ok(view.getByText("All checks passed."));
});

test("failed refresh removes stale healthy checks, and can recover", async () => {
  let failing = false;
  globalThis.fetch = async () => {
    if (failing) throw new TypeError("Failed to fetch");
    return new Response(JSON.stringify(report()));
  };
  const view = mount();
  await waitFor(() => assert.ok(view.getByRole("button", { name: "Connected" })));
  fireEvent.click(view.getByRole("button", { name: "Connected" }));
  failing = true;
  fireEvent.click(view.getByRole("button", { name: "Check again", hidden: true }));
  await waitFor(() => assert.ok(view.getByRole("button", { name: "Health unavailable" })));
  assert.equal(view.queryByText("Database is responding."), null);
  assert.equal(view.queryByText("All checks passed."), null);
  failing = false;
  fireEvent.click(view.getByRole("button", { name: "Check again", hidden: true }));
  await waitFor(() => assert.ok(view.getByRole("button", { name: "Connected" })));
});

test("healthy server with disconnected stream reports degraded live updates", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(report()));
  const view = mount(false);
  await waitFor(() => assert.ok(view.getByText("Database is responding.")));
  assert.ok(view.getByRole("button", { name: "Reconnecting" }));
  assert.ok(view.getByText(/Live event stream is disconnected/));
  assert.equal(view.queryByText("All checks passed."), null);
});

test("invalid health responses never become a green status", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({ healthy: true }));
  const view = mount();
  await waitFor(() => assert.ok(view.getByRole("button", { name: "Health unavailable" })));
  assert.equal(view.queryByText("All checks passed."), null);
});
