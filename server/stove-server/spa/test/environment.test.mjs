import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";
import { QueryClient } from "@tanstack/react-query";

const jiti = createJiti(import.meta.url);
const { isRunEnvironment, environmentKey } = await jiti.import("../src/api/environment.ts");
const { parseLiveDashboardEvent } = await jiti.import("../src/api/live-event.ts");
const { applyLiveDashboardEvents } = await jiti.import("../src/api/live-cache.ts");

const environment = {
  components: [
    { id: "postgres", name: "PostgreSQL", type: "PostgresqlSystem", key: "analytics", role: "SYSTEM",
      phases: [{ name: "START", status: "FAILED", started_at: "2026-11-17T00:00:00Z", duration_ms: 14, error: "connection failed" }] },
    { id: "aut", name: "SpringApplication", type: "SpringApplication", key: "", role: "APPLICATION", phases: [] },
  ],
};

test("environment validates nested records and preserves systems that never started", () => {
  assert.equal(isRunEnvironment(environment), true);
  assert.equal(isRunEnvironment({ components: [{ ...environment.components[0], phases: [{}] }] }), false);
  const event = parseLiveDashboardEvent(JSON.stringify({
    seq: 2, run_id: "run", event_type: "run_environment", payload: environment,
  }));
  assert.deepEqual(event.payload, environment);
});

test("live environment cancels a stale read and preserves the newer snapshot", async () => {
  const client = new QueryClient();
  let complete;
  const pending = client.fetchQuery({
    queryKey: environmentKey("run"),
    queryFn: () => new Promise((resolve) => { complete = resolve; }),
  }).catch(() => {});
  applyLiveDashboardEvents(client, [{
    seq: 2, run_id: "run", event_type: "run_environment", payload: environment,
  }]);
  complete(null);
  await pending;
  assert.deepEqual(client.getQueryData(environmentKey("run")), environment);
  client.clear();
});

test("keyed display names are preserved and registration keys appear separately", async () => {
  const jsx = createJiti(import.meta.url, { jsx: { runtime: "automatic" } });
  const { createElement: h } = await jsx.import("react");
  const { renderToStaticMarkup } = await jsx.import("react-dom/server");
  const { QueryClient: Client, QueryClientProvider } = await jsx.import("@tanstack/react-query");
  const { RunEnvironment } = await jsx.import("../src/layout/sidebar/RunEnvironment.tsx");
  const client = new Client({ defaultOptions: { queries: { staleTime: Infinity } } });
  client.setQueryData(environmentKey("run"), {
    components: [{ ...environment.components[0], name: "PostgreSQL [analytics]" }],
  });
  try {
    const html = renderToStaticMarkup(h(QueryClientProvider, { client }, h(RunEnvironment, {
      run: { id: "run", systems: [] },
    })));
    assert.match(html, /<strong>PostgreSQL \[analytics\]<\/strong>/);
    assert.match(html, /Registration key: analytics/);
    assert.doesNotMatch(html, /\[analytics\] \[analytics\]/);
  } finally {
    client.clear();
  }
});
