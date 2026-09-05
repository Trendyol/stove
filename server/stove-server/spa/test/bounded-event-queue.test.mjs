import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";
const jiti = createJiti(import.meta.url);
const { BoundedEventQueue } = await jiti.import("../src/utils/bounded-event-queue.ts");

test("byte overflow replaces the entire batch with one recovery signal", () => {
  const queue = new BoundedEventQueue(10, 8);
  queue.push("first", 4);
  queue.push("second", 4);
  queue.push("overflow", 1);
  for (let i = 0; i < 10_000; i++) queue.push("ignored", 1);
  assert.deepEqual(queue.drain(), { items: [], overflowed: true });
  assert.deepEqual(queue.drain(), { items: [], overflowed: false });
  queue.push("recovered", 8);
  assert.deepEqual(queue.drain(), { items: ["recovered"], overflowed: false });
});

test("event-count overflow and explicit reset release pending evidence", () => {
  const queue = new BoundedEventQueue(2, 100);
  for (const item of [1, 2, 3]) queue.push(item, 1);
  assert.deepEqual(queue.drain(), { items: [], overflowed: true });
  queue.push(4, 100);
  queue.clear();
  queue.push(5, 100);
  assert.deepEqual(queue.drain(), { items: [5], overflowed: false });
});
