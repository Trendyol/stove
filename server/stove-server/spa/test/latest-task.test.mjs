import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { LatestTask } = await jiti.import("../src/utils/latest-task.ts");
const { calculateFlow, FLOW_NODE_LIMIT } = await jiti.import("../src/utils/flow-work.ts");

test("continuous updates publish completed work and retain only the latest pending input", () => {
  const started = [];
  const published = [];
  const queue = new LatestTask((input) => started.push(input), (output, input) => published.push([output, input]));
  queue.submit("selected-test", 0);
  for (let input = 1; input <= 10_000; input++) queue.submit("selected-test", input);
  assert.deepEqual(started, [0]);
  queue.complete("first result");
  assert.deepEqual(started, [0, 10_000]);
  assert.deepEqual(published, [["first result", 0]]);
  for (let input = 10_001; input <= 20_000; input++) queue.submit("selected-test", input);
  queue.complete("second result");
  assert.deepEqual(started, [0, 10_000, 20_000]);
  assert.deepEqual(published, [["first result", 0], ["second result", 10_000]]);
});

test("selection changes discard obsolete results and disposal stops pending work", () => {
  const started = [];
  const published = [];
  const queue = new LatestTask((input) => started.push(input), (output) => published.push(output));
  queue.submit("old-test", 1);
  queue.submit("old-test", 2);
  queue.submit("new-test", 3);
  queue.complete("obsolete");
  assert.deepEqual(started, [1, 3]);
  assert.deepEqual(published, []);
  queue.complete("current");
  assert.deepEqual(published, ["current"]);
  queue.submit("new-test", 4);
  queue.submit("new-test", 5);
  queue.dispose();
  queue.complete("disposed");
  queue.submit("new-test", 6);
  assert.deepEqual(started, [1, 3, 4]);
});

test("timeline node bound includes generated idle gaps", () => {
  const records = Array.from({ length: 1200 }, (_, index) => ({
    id: index + 1, run_id: "run", test_id: "test", timestamp: new Date(1704067200000 + index * 3000).toISOString(),
    system: "HTTP", action: `request-${index}`, result: "PASSED", input: null, output: null, metadata: null,
    expected: null, actual: null, error: null, trace_id: null, assertion_id: `assertion-${index}`, attempt_count: 1, failure_count: 0,
  }));
  const graph = calculateFlow({ mode: "timeline", records });
  assert.ok(graph.nodes.length <= FLOW_NODE_LIMIT);
  assert.equal(graph.nodes.filter((node) => node.type === "gapNode").length, 499);
  const ids = new Set(graph.nodes.map((node) => node.id));
  assert.ok(graph.edges.every((edge) => ids.has(edge.source) && ids.has(edge.target)));
});

test("trace construction caps nodes and removes links to evidence outside the window", () => {
  const records = Array.from({ length: 1001 }, (_, index) => ({
    id: index + 1, run_id: "run", trace_id: "trace", span_id: `span-${index}`, parent_span_id: index === 0 ? null : "span-0",
    operation_name: "request", service_name: "service", start_time_nanos: index * 1000000, end_time_nanos: (index + 1) * 1000000,
    status: "OK", attributes: null, exception_type: null, exception_message: null, exception_stack_trace: null,
  }));
  const graph = calculateFlow({ mode: "trace", records });
  assert.equal(graph.nodes.length, FLOW_NODE_LIMIT);
  assert.equal(graph.edges.length, 0);
  assert.ok(!graph.nodes.some((node) => node.id === "span-0"));
});
