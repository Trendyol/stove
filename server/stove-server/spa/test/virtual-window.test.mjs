import assert from "node:assert/strict";
import test from "node:test";
import createJiti from "jiti";

const jiti = createJiti(import.meta.url);
const { fixedRange, variableRange } = await jiti.import("../src/utils/virtual-window.ts");

test("fixed rows position deep history arithmetically and clamp collection boundaries", () => {
  assert.deepEqual(fixedRange(1_000_000, 40, 20_000_000, 20_000_400), { start: 500_000, end: 500_010 });
  assert.deepEqual(fixedRange(10, 40, 380, 800), { start: 9, end: 10 });
  assert.deepEqual(fixedRange(0, 40, 0, 400), { start: 0, end: 0 });
});

test("binary search matches variable row intersections at gaps and exact boundaries", () => {
  const layout = [];
  let offset = 0;
  for (let index = 0; index < 10_000; index++) {
    const size = 20 + (index % 11) * 7;
    layout.push({ start: offset, size });
    offset += size;
  }
  for (const minimum of [0, 20, 40, 199, offset / 2, offset - 1, offset, offset + 1]) {
    const maximum = minimum + 333;
    const expected = layout.map((row, index) => ({ ...row, index }))
      .filter((row) => row.start + row.size > minimum && row.start < maximum)
      .map((row) => row.index);
    const { start, end } = variableRange(layout, minimum, maximum);
    assert.deepEqual(Array.from({ length: end - start }, (_, index) => index + start), expected);
  }
  assert.deepEqual(variableRange([], 0, 100), { start: 0, end: 0 });
});
