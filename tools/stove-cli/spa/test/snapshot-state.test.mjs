import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const {
  getKafkaSnapshotMetrics,
  hasDetailedSnapshotState,
  partitionSnapshotsByDetail,
} = await jiti.import("../src/utils/snapshot-state.ts");

test("hasDetailedSnapshotState returns false for empty object payloads", () => {
  const snapshot = {
    state_json: "{}",
  };

  assert.equal(hasDetailedSnapshotState(snapshot), false);
});

test("hasDetailedSnapshotState returns true when structured state exists", () => {
  const snapshot = {
    state_json: JSON.stringify({
      consumed: [{ topic: "orders" }, { topic: "payments" }, { topic: "orders-retry" }],
      published: [{ topic: "events" }],
      failed: [],
    }),
  };

  assert.equal(hasDetailedSnapshotState(snapshot), true);
});

test("hasDetailedSnapshotState returns false when nested collections are all empty", () => {
  const snapshot = {
    state_json: JSON.stringify({
      registeredStubs: [],
      servedRequests: [],
      unmatchedRequests: [],
    }),
  };

  assert.equal(hasDetailedSnapshotState(snapshot), false);
});

test("hasDetailedSnapshotState treats scalar counters as meaningful values", () => {
  const snapshot = {
    state_json: JSON.stringify({
      consumed: 0,
      published: 0,
      failed: 0,
    }),
  };

  assert.equal(hasDetailedSnapshotState(snapshot), true);
});

test("getKafkaSnapshotMetrics derives counts from kafka snapshot payloads", () => {
  const snapshot = {
    state_json: JSON.stringify({
      consumed: [{ topic: "orders" }, { topic: "payments" }],
      published: [{ topic: "events" }],
      committed: [],
      failed: [{ topic: "orders.failed" }],
    }),
  };

  assert.deepEqual(getKafkaSnapshotMetrics(snapshot), [
    { key: "consumed", label: "Consumed", value: 2, tone: "info" },
    { key: "published", label: "Published", value: 1, tone: "success" },
    { key: "committed", label: "Committed", value: 0, tone: "neutral" },
    { key: "failed", label: "Failed", value: 1, tone: "danger" },
  ]);
});

test("partitionSnapshotsByDetail separates hidden summary-only snapshots from detailed ones", () => {
  const snapshots = [
    {
      id: "http",
      state_json: "{}",
    },
    {
      id: "wiremock",
      state_json: JSON.stringify({
        registeredStubs: [],
        servedRequests: [],
        unmatchedRequests: [],
      }),
    },
    {
      id: "kafka",
      state_json: JSON.stringify({
        consumed: 0,
        published: 1,
      }),
    },
  ];

  const result = partitionSnapshotsByDetail(snapshots);

  assert.deepEqual(result.detailedSnapshots, [snapshots[2]]);
  assert.equal(result.hiddenCount, 2);
});
