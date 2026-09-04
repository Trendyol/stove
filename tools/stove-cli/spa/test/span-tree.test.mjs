import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { buildSpanTreeRows } = await jiti.import("../src/components/span-tree/model.ts");

test("buildSpanTreeRows orders a trace and hides collapsed descendants", () => {
  const spans = [
    span("child-late", "root", 30),
    span("root", null, 10),
    span("grandchild", "child-early", 25),
    span("child-early", "root", 20),
  ];

  const expanded = buildSpanTreeRows(spans, new Set());
  assert.deepEqual(
    expanded.map((row) => [row.span.span_id, row.depth]),
    [
      ["root", 0],
      ["child-early", 1],
      ["grandchild", 2],
      ["child-late", 1],
    ],
  );

  const collapsed = buildSpanTreeRows(spans, new Set(["child-early"]));
  assert.deepEqual(
    collapsed.map((row) => row.span.span_id),
    ["root", "child-early", "child-late"],
  );
  assert.equal(collapsed[1].collapsed, true);
});

test("buildSpanTreeRows keeps malformed cyclic traces finite and inspectable", () => {
  const rows = buildSpanTreeRows([span("one", "two", 1), span("two", "one", 2)], new Set());
  assert.deepEqual(
    rows.map((row) => row.span.span_id).sort(),
    ["one", "two"],
  );
});

function span(spanId, parentSpanId, startTimeNanos) {
  return {
    id: spanId,
    run_id: "run-1",
    trace_id: "trace-1",
    span_id: spanId,
    parent_span_id: parentSpanId,
    operation_name: spanId,
    service_name: "service",
    start_time_nanos: startTimeNanos,
    end_time_nanos: startTimeNanos + 10,
    status: "OK",
    attributes: "{}",
    exception_type: null,
    exception_message: null,
    exception_stack_trace: null,
  };
}
