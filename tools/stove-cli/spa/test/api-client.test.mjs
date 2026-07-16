import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { api } = await jiti.import("../src/api/client.ts");

test("test evidence endpoints URL-encode run and test ids", async () => {
  const originalFetch = globalThis.fetch;
  const seen = [];
  const controller = new AbortController();

  globalThis.fetch = async (input, init) => {
    seen.push({ input: String(input), signal: init?.signal });
    return new Response("[]", {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  };

  try {
    const runId = "run:1";
    const testId =
      "AuditHeadersValidationTests::should not require audit headers for get endpoint";
    await api.getSnapshots(runId, testId, controller.signal);
    await api.getTestInteractions(runId, testId);
    await api.getTestWarnings(runId, testId);
    await api.getAmbientInteractions(runId);
    await api.getAmbientWarnings(runId);
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(
    seen[0].input,
    "/api/v1/runs/run%3A1/tests/AuditHeadersValidationTests%3A%3Ashould%20not%20require%20audit%20headers%20for%20get%20endpoint/snapshots",
  );
  assert.equal(seen[0].signal, controller.signal);
  assert.equal(
    seen[1].input,
    "/api/v1/runs/run%3A1/tests/AuditHeadersValidationTests%3A%3Ashould%20not%20require%20audit%20headers%20for%20get%20endpoint/interactions",
  );
  assert.equal(
    seen[2].input,
    "/api/v1/runs/run%3A1/tests/AuditHeadersValidationTests%3A%3Ashould%20not%20require%20audit%20headers%20for%20get%20endpoint/warnings",
  );
  assert.equal(seen[3].input, "/api/v1/runs/run%3A1/interactions/ambient");
  assert.equal(seen[4].input, "/api/v1/runs/run%3A1/warnings/ambient");
});
