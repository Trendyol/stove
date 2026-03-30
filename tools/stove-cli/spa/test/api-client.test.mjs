import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { api } = await jiti.import("../src/api/client.ts");

test("getSnapshots URL-encodes run and test ids before requesting snapshot data", async () => {
  const originalFetch = globalThis.fetch;
  const seen = [];

  globalThis.fetch = async (input) => {
    seen.push(String(input));
    return new Response("[]", {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  };

  try {
    await api.getSnapshots(
      "run:1",
      "AuditHeadersValidationTests::should not require audit headers for get endpoint",
    );
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(
    seen[0],
    "/api/v1/runs/run%3A1/tests/AuditHeadersValidationTests%3A%3Ashould%20not%20require%20audit%20headers%20for%20get%20endpoint/snapshots",
  );
});
