import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { applyDagreLayout, entriesToDag, getNodeLayoutSize, spansToTraceDag } = await jiti.import(
  "../src/utils/flow.ts",
);

test("spansToTraceDag preserves UNSET span status instead of marking it as passed", () => {
  const { nodes } = spansToTraceDag([
    {
      id: 1,
      run_id: "run-1",
      trace_id: "trace-1",
      span_id: "span-1",
      parent_span_id: null,
      operation_name: "GET /health",
      service_name: "my-api",
      start_time_nanos: 0,
      end_time_nanos: 1_000_000,
      status: "UNSET",
      attributes: null,
      exception_type: null,
      exception_message: null,
      exception_stack_trace: null,
    },
  ]);

  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].data.result, "UNSET");
});

test("entriesToDag keeps distinct actions separate even when they belong to the same system", () => {
  const { nodes } = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.000Z",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
    {
      id: 2,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.050Z",
      system: "HTTP",
      action: "GET /products/42",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-2",
    },
  ]);

  assert.equal(nodes.length, 2);
  assert.equal(nodes[0].data.action, "POST /products");
  assert.equal(nodes[1].data.action, "GET /products/42");
});

test("entriesToDag inserts explicit gap nodes for long idle periods", () => {
  const { nodes, edges } = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.000Z",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
    {
      id: 2,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:03.250Z",
      system: "Kafka",
      action: "consume ProductCreated",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
  ]);

  assert.equal(nodes.length, 3);
  assert.equal(nodes[1].type, "gapNode");
  assert.equal(nodes[1].data.kind, "gap");
  assert.equal(nodes[1].data.durationMs, 3250);
  assert.equal(edges.length, 2);
});

test("entriesToDag keeps captured snapshots out of the execution graph layout", () => {
  const { nodes, edges } = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.000Z",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
  ]);

  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].type, "systemNode");
  assert.equal(edges.length, 0);
});

test("entriesToDag lifts leading mock registration steps into an arrange branch", () => {
  const { nodes, edges } = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.000Z",
      system: "WireMock",
      action: "Register stub: GET /inventory",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 2,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.100Z",
      system: "gRPC Mock",
      action: "Register unary stub: inventory.StockService/GetStock",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 3,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.250Z",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
  ]);

  assert.equal(nodes.length, 3);
  const arrangeNodes = nodes.filter(
    (node) => node.type === "systemNode" && node.data.kind === "arrange",
  );
  assert.equal(arrangeNodes.length, 2);
  const executionNode = nodes.find(
    (node) => node.type === "systemNode" && node.data.kind === "step",
  );
  assert.ok(executionNode);
  assert.equal(executionNode.data.system, "HTTP");
  assert.equal(edges.filter((edge) => edge.data.label === "ready").length, 2);
});

test("entriesToDag groups leading registrations by mock system", () => {
  const { nodes } = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.000Z",
      system: "WireMock",
      action: "Register stub: GET /inventory",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 2,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.100Z",
      system: "WireMock",
      action: "Register stub: GET /prices",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 3,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.150Z",
      system: "gRPC Mock",
      action: "Register unary stub: inventory.StockService/GetStock",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 4,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.250Z",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
  ]);

  const arrangeNodes = nodes.filter(
    (node) => node.type === "systemNode" && node.data.kind === "arrange",
  );

  assert.equal(arrangeNodes.length, 2);
  const wireMockNode = arrangeNodes.find((node) => node.data.system === "WireMock");
  assert.ok(wireMockNode);
  assert.equal(wireMockNode.data.count, 2);
  assert.equal(wireMockNode.data.action, "Registered 2 stubs");
});

test("entriesToDag groups consecutive WireMock registrations in the middle of the timeline", () => {
  const { nodes } = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-31T09:00:00.000Z",
      system: "SpringJdbc",
      action: "Select business unit",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 2,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-31T09:00:00.050Z",
      system: "WireMock",
      action: "Register stub: GET /first",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 3,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-31T09:00:00.100Z",
      system: "WireMock",
      action: "Register stub: GET /second",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 4,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-31T09:00:00.150Z",
      system: "Kafka",
      action: "Publish order result",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
  ]);

  const flowNodes = nodes.filter((node) => node.type === "systemNode");
  assert.equal(flowNodes.length, 3);
  assert.equal(flowNodes[1].data.system, "WireMock");
  assert.equal(flowNodes[1].data.kind, "arrange");
  assert.equal(flowNodes[1].data.count, 2);
  assert.equal(flowNodes[1].data.action, "Registered 2 stubs");
});

test("applyDagreLayout keeps arrange siblings on distinct coordinates", () => {
  const dag = entriesToDag([
    {
      id: 1,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.000Z",
      system: "WireMock",
      action: "Register stub: GET /inventory",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 2,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.050Z",
      system: "WireMock",
      action: "Register stub: GET /prices",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 3,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.100Z",
      system: "gRPC Mock",
      action: "Register unary stub: inventory.StockService/GetStock",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: null,
    },
    {
      id: 4,
      run_id: "run-1",
      test_id: "test-1",
      timestamp: "2026-03-30T10:00:00.300Z",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      input: null,
      output: null,
      metadata: null,
      expected: null,
      actual: null,
      error: null,
      trace_id: "trace-1",
    },
  ]);

  const laidOut = applyDagreLayout(dag.nodes, dag.edges);
  const arrangeNodes = laidOut.filter(
    (node) => node.type === "systemNode" && node.data.kind === "arrange",
  );

  assert.equal(arrangeNodes.length, 2);
  assert.notDeepEqual(
    arrangeNodes.map((node) => node.position),
    [{ x: arrangeNodes[0].position.x, y: arrangeNodes[0].position.y }, { x: arrangeNodes[0].position.x, y: arrangeNodes[0].position.y }],
  );
});

test("applyDagreLayout keeps trace siblings on distinct coordinates", () => {
  const dag = spansToTraceDag([
    {
      id: 1,
      run_id: "run-1",
      trace_id: "trace-1",
      span_id: "root",
      parent_span_id: null,
      operation_name: "request",
      service_name: "api",
      start_time_nanos: 0,
      end_time_nanos: 4_000_000,
      status: "OK",
      attributes: null,
      exception_type: null,
      exception_message: null,
      exception_stack_trace: null,
    },
    {
      id: 2,
      run_id: "run-1",
      trace_id: "trace-1",
      span_id: "child-a",
      parent_span_id: "root",
      operation_name: "db query",
      service_name: "postgres",
      start_time_nanos: 500_000,
      end_time_nanos: 2_000_000,
      status: "OK",
      attributes: '{"db.system":"postgresql"}',
      exception_type: null,
      exception_message: null,
      exception_stack_trace: null,
    },
    {
      id: 3,
      run_id: "run-1",
      trace_id: "trace-1",
      span_id: "child-b",
      parent_span_id: "root",
      operation_name: "http call",
      service_name: "inventory",
      start_time_nanos: 1_000_000,
      end_time_nanos: 3_000_000,
      status: "OK",
      attributes: '{"http.method":"GET"}',
      exception_type: null,
      exception_message: null,
      exception_stack_trace: null,
    },
  ]);

  const laidOut = applyDagreLayout(dag.nodes, dag.edges);
  const traceChildren = laidOut.filter((node) => node.id === "child-a" || node.id === "child-b");

  assert.equal(traceChildren.length, 2);
  assert.notDeepEqual(traceChildren[0].position, traceChildren[1].position);
});

test("getNodeLayoutSize keeps stable spacing for regular graph nodes", () => {
  const stepNodeSize = getNodeLayoutSize({
    id: "step-1",
    type: "systemNode",
    position: { x: 0, y: 0 },
    data: {
      kind: "step",
      system: "HTTP",
      action: "POST /products",
      result: "PASSED",
      count: 1,
      error: null,
      entries: [],
      traceId: null,
      startedAt: "2026-03-30T10:00:00.000Z",
      endedAt: "2026-03-30T10:00:00.200Z",
      durationMs: 200,
      inspectable: true,
    },
  });
  const gapNodeSize = getNodeLayoutSize({
    id: "gap-1",
    type: "gapNode",
    position: { x: 0, y: 0 },
    data: {
      kind: "gap",
      label: "Idle gap",
      durationMs: 1200,
      startedAt: "2026-03-30T10:00:00.000Z",
      endedAt: "2026-03-30T10:00:01.200Z",
      inspectable: false,
    },
  });

  assert.equal(stepNodeSize.width, 240);
  assert.equal(gapNodeSize.width, 208);
  assert.ok(stepNodeSize.height > gapNodeSize.height);
});
