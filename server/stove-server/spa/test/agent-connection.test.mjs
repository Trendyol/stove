import assert from "node:assert/strict";
import { afterEach, test } from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";

const dom = new JSDOM('<!doctype html><html><head><meta name="stove-base" content="/observe"></head><body></body></html>', { url: "https://stove.example/observe/" });
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true,
});
Object.defineProperty(globalThis, "navigator", { configurable: true, value: dom.window.navigator });
// Native focus trapping and Escape are also verified in a browser.
dom.window.HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
dom.window.HTMLDialogElement.prototype.close = function () {
  this.removeAttribute("open");
  setTimeout(() => this.dispatchEvent(new dom.window.Event("close")), 0);
};
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" }, fsCache: false });
const { createElement: h, StrictMode } = await jiti.import("react");
const { render, fireEvent, waitFor, cleanup } = await jiti.import("@testing-library/react");
const { QueryClient, QueryClientProvider } = await jiti.import("@tanstack/react-query");
const { AgentConnectionProvider, ConnectAgentButton } = await jiti.import("../src/components/agent-connection/AgentConnectionProvider.tsx");
const originalFetch = globalThis.fetch;
let client;
afterEach(() => { cleanup(); client?.clear(); globalThis.fetch = originalFetch; });
const scope = (id, global) => ({
  command: `install ${id}${global ? " --global" : ""}`,
  skill_path: `${global ? "~/" : ""}.agents/skills/stove`,
  config_path: `${global ? "~/" : ""}.${id}/config`,
  configuration: JSON.stringify({ agent: id, global }),
});
const report = () => ({ endpoint: "https://stove.example/observe/mcp", providers: ["codex", "cursor"].map(id => ({
  id, label: id === "codex" ? "Codex" : "Cursor", note: "", install_link: id === "cursor" ? "cursor://anysphere.cursor-deeplink/mcp/install?name=stove&config=example" : null,
  project: scope(id, false), global: scope(id, true),
})) });
function mount() {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(h(StrictMode, null, h(QueryClientProvider, { client }, h(AgentConnectionProvider, null, h(ConnectAgentButton)))));
}

test("setup loads on demand using the HTTPS browser origin and gateway prefix; survives strict mode and reopening", async () => {
  let calls = 0;
  globalThis.fetch = async url => {
    calls++;
    const parsed = new URL(url, window.location.origin);
    assert.equal(parsed.pathname, "/observe/api/v1/agent-setup");
    assert.equal(parsed.searchParams.get("endpoint"), "https://stove.example/observe/mcp");
    return new Response(JSON.stringify(report()));
  };
  const view = mount();
  assert.equal(calls, 0);
  const trigger = view.getByRole("button", { name: "Connect agent" });
  trigger.focus();
  fireEvent.click(trigger);
  await waitFor(() => assert.ok(view.getByText("install codex")));
  assert.ok(view.getByRole("dialog", { name: "Connect your agent" }));
  fireEvent.click(view.getByRole("radio", { name: "Cursor" }));
  fireEvent.click(view.getByRole("radio", { name: "All projects" }));
  assert.ok(view.getByText("install cursor --global"));
  fireEvent.click(view.getByText("Manual configuration & MCP-only setup"));
  assert.ok(view.getByText("~/.cursor/config"));
  assert.match(view.getByText("Open in Cursor ↗").getAttribute("href"), /^cursor:/);
  view.getByRole("button", { name: "Close agent setup" }).focus();
  fireEvent.click(view.getByRole("button", { name: "Close agent setup" }));
  assert.equal(view.queryByRole("dialog"), null);
  assert.equal(document.activeElement, trigger);
  fireEvent.click(view.getByRole("button", { name: "Connect agent" }));
  await waitFor(() => assert.ok(view.getByRole("dialog")));
  fireEvent(view.getByRole("dialog"), new dom.window.Event("cancel", { bubbles: true }));
  assert.equal(view.queryByRole("dialog"), null);
});

test("copy uses the selected scope and clipboard failures leave selectable content", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify(report()));
  let copied;
  navigator.clipboard = { writeText: async text => { copied = text; } };
  const view = mount();
  fireEvent.click(view.getByRole("button", { name: "Connect agent" }));
  await waitFor(() => assert.ok(view.getByText("install codex")));
  fireEvent.click(view.getByRole("radio", { name: "All projects" }));
  fireEvent.click(view.getByRole("button", { name: "Copy Setup command" }));
  await waitFor(() => assert.equal(copied, "install codex --global"));
  await waitFor(() => assert.ok(view.getByText("Copied ✓")));
  fireEvent.click(view.getByRole("radio", { name: "Cursor" }));
  assert.equal(view.queryByText("Copied ✓"), null);
  navigator.clipboard.writeText = async () => { throw new Error("denied"); };
  fireEvent.click(view.getByRole("button", { name: "Copy Setup command" }));
  await waitFor(() => assert.ok(view.getByText("Clipboard unavailable. Select and copy the text below.")));
  assert.ok(view.getByText("install cursor --global"));
});

test("failed setup can retry and invalid payloads do not render commands", async () => {
  let failing = true;
  globalThis.fetch = async () => new Response(JSON.stringify(failing ? { endpoint: "bad" } : report()));
  const view = mount();
  fireEvent.click(view.getByRole("button", { name: "Connect agent" }));
  await waitFor(() => assert.ok(view.getByRole("alert")));
  assert.equal(view.queryByRole("button", { name: "Copy Setup command" }), null);
  failing = false;
  fireEvent.click(view.getByRole("button", { name: "Try again" }));
  await waitFor(() => assert.ok(view.getByText("install codex")));
});
