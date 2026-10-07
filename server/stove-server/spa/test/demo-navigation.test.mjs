import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";
import createJiti from "jiti";

const dom = new JSDOM("<!doctype html><html><body></body></html>", {
  url: "https://trendyol.github.io/stove/dashboard-demo/",
});
Object.assign(globalThis, {
  window: dom.window, document: dom.window.document,
  PopStateEvent: dom.window.PopStateEvent,
  IS_REACT_ACT_ENVIRONMENT: true, __STOVE_DEMO__: true,
});
const jiti = createJiti(import.meta.url, { jsx: { runtime: "automatic" } });
const { createElement: h } = await jiti.import("react");
const { EvidenceNavigationProvider, useEvidenceNavigation } = await jiti.import("../src/hooks/useEvidenceNavigation.tsx");
const { renderHook, act, waitFor, cleanup } = await jiti.import("@testing-library/react");
const { appPath, evidencePath, navigateTo, useLocation } = await jiti.import("../src/utils/location.ts");

test("demo evidence links stay under the Pages path and survive navigation and history", async (t) => {
  t.after(cleanup);
  const { result } = renderHook(useLocation);
  assert.equal(result.current.kind, "home");
  const route = `${evidencePath("run / 1", "spec::test / example")}?focus=entry:6&context=0`;
  act(() => navigateTo(route));
  assert.equal(window.location.pathname, "/stove/dashboard-demo/");
  assert.equal(window.location.hash, `#${route}`);
  assert.equal(result.current.value.testId, "spec::test / example");
  assert.deepEqual(result.current.value.focus, { kind: "entry", id: 6 });
  assert.equal(result.current.value.context, 0);
  assert.equal(appPath("/api/v1/runs"), "/stove/dashboard-demo/api/v1/runs");
  assert.equal(appPath("/admin"), "/stove/dashboard-demo/#/admin");
  act(() => navigateTo("/admin"));
  assert.equal(result.current.kind, "admin");
  await act(async () => {
    window.history.back();
    await new Promise((resolve) => window.addEventListener("popstate", resolve, { once: true }));
  });
  await waitFor(() => assert.equal(result.current.value.testId, "spec::test / example"));
  // A fresh mount reads the same route, as a page reload would.
  cleanup();
  const fresh = renderHook(useLocation);
  assert.deepEqual(fresh.result.current.value.focus, { kind: "entry", id: 6 });
});


test("demo context, full view, tab history, and copied URLs retain the focused record", (t) => {
  t.after(cleanup);
  const path = evidencePath("run-1", "test-1");
  navigateTo(`${path}?focus=entry:6&context=0`);
  const { result } = renderHook(useEvidenceNavigation, {
    wrapper: ({ children }) => h(EvidenceNavigationProvider, { runId: "run-1", testId: "test-1" }, children),
  });
  act(() => result.current.moreContext());
  assert.deepEqual(result.current.focus, { kind: "entry", id: 6 });
  assert.equal(result.current.context, 10);
  assert.equal(result.current.href, `https://trendyol.github.io/stove/dashboard-demo/#${path}?focus=entry%3A6&context=10`);
  act(() => result.current.showFull());
  assert.equal(result.current.full, true);
  assert.deepEqual(result.current.focus, { kind: "entry", id: 6 });
  act(() => result.current.selectTab("trace"));
  act(() => result.current.selectTab("timeline"));
  assert.deepEqual(result.current.focus, { kind: "entry", id: 6 });
  assert.equal(result.current.full, true);
});
