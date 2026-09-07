import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const {
  parseJsonDeep,
  filterJsonByQuery,
  describeJsonValue,
} = await jiti.import("../src/utils/json.ts");

test("parseJsonDeep expands embedded JSON strings inside structured snapshot payloads", () => {
  const parsed = parseJsonDeep(
    JSON.stringify({
      outboxEvents: [
        JSON.stringify({
          type: "ProductCreated",
          payload: {
            productId: 42,
            sellerId: 99,
          },
        }),
      ],
      metadata: {
        count: 1,
      },
    }),
  );

  assert.deepEqual(parsed.outboxEvents, [
    { type: "ProductCreated", payload: { productId: 42, sellerId: 99 } },
  ]);
  assert.deepEqual(parsed.metadata, { count: 1 });
});

test("parseJsonDeep returns structured nested values for snapshot state rendering", () => {
  const parsed = parseJsonDeep(
    JSON.stringify({
      counts: {
        succeeded: 4,
      },
      outboxEvents: [
        JSON.stringify({
          eventType: "ProductUpdated",
        }),
      ],
    }),
  );

  assert.deepEqual(parsed, {
    counts: {
      succeeded: 4,
    },
    outboxEvents: [
      {
        eventType: "ProductUpdated",
      },
    ],
  });
});

test("snapshot json helpers describe object roots for compact cards", () => {
  const parsed = parseJsonDeep(
    JSON.stringify({
      registeredStubs: [],
      servedRequests: [],
      unmatchedRequests: [],
      metadata: {
        matched: 0,
      },
    }),
  );

  assert.equal(describeJsonValue(parsed), "4 keys");
});

test("filterJsonByQuery narrows state by matching property names while preserving subtree context", () => {
  const parsed = parseJsonDeep(
    JSON.stringify({
      servedRequests: [
        {
          method: "GET",
          url: "/inventory",
          matched: true,
        },
      ],
      unmatchedRequests: [],
    }),
  );

  const filtered = filterJsonByQuery(parsed, "servedRequests");

  assert.equal(filtered.matchCount, 1);
  assert.deepEqual(filtered.filteredValue, {
    servedRequests: [
      {
        method: "GET",
        url: "/inventory",
        matched: true,
      },
    ],
  });
});

test("filterJsonByQuery narrows state by matching primitive values", () => {
  const parsed = parseJsonDeep(
    JSON.stringify({
      servedRequests: [
        {
          method: "GET",
          url: "/inventory",
          matched: true,
        },
        {
          method: "POST",
          url: "/orders",
          matched: false,
        },
      ],
    }),
  );

  const filtered = filterJsonByQuery(parsed, "/orders");

  assert.equal(filtered.matchCount, 1);
  assert.deepEqual(filtered.filteredValue, {
    servedRequests: [
      {
        url: "/orders",
      },
    ],
  });
});

test("filterJsonByQuery returns no matches when the query is absent from the state", () => {
  const parsed = parseJsonDeep(
    JSON.stringify({
      servedRequests: [],
      unmatchedRequests: [],
    }),
  );

  const filtered = filterJsonByQuery(parsed, "kafka");

  assert.equal(filtered.matchCount, 0);
  assert.equal(filtered.filteredValue, null);
});
