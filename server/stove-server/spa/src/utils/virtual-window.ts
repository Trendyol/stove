export interface ItemLayout {
  start: number;
  size: number;
}

export function fixedRange(count: number, size: number, minimum: number, maximum: number) {
  return {
    start: Math.min(count, Math.max(0, Math.floor(minimum / size))),
    end: Math.min(count, Math.max(0, Math.ceil(maximum / size))),
  };
}

export function variableRange(layout: readonly ItemLayout[], minimum: number, maximum: number) {
  function lowerBound(after: (item: ItemLayout) => boolean) {
    let low = 0;
    let high = layout.length;
    while (low < high) {
      const middle = (low + high) >>> 1;
      if (after(layout[middle])) low = middle + 1;
      else high = middle;
    }
    return low;
  }
  const start = lowerBound((item) => item.start + item.size <= minimum);
  const end = Math.max(
    start,
    lowerBound((item) => item.start < maximum),
  );
  return { start, end };
}
