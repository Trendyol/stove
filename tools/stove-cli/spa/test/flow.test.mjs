import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { spansToTraceDag } = await jiti.import("../src/utils/flow.ts");

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
