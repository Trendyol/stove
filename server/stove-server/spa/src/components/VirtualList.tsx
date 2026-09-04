import { type Key, type ReactNode, useLayoutEffect, useMemo, useRef, useState } from "react";

const DEFAULT_OVERSCAN_PX = 320;
const DEFAULT_WINDOW_THRESHOLD = 160;

interface VirtualListProps<T> {
  items: readonly T[];
  getKey: (item: T) => Key;
  getItemSize: number | ((item: T) => number);
  renderItem: (item: T, index: number) => ReactNode;
  className: string;
  ariaLabel: string;
  windowThreshold?: number;
  overscanPx?: number;
}

interface ItemLayout {
  start: number;
  size: number;
}

/** Keeps large ledgers bounded while retaining ordinary document flow for small lists. */
export function VirtualList<T>({
  items,
  getKey,
  getItemSize,
  renderItem,
  className,
  ariaLabel,
  windowThreshold = DEFAULT_WINDOW_THRESHOLD,
  overscanPx = DEFAULT_OVERSCAN_PX,
}: VirtualListProps<T>) {
  const viewportRef = useRef<HTMLUListElement>(null);
  const [viewport, setViewport] = useState({ height: 0, scrollTop: 0 });
  const windowed = items.length > windowThreshold;
  const { layout, totalSize } = useMemo(() => {
    let start = 0;
    const nextLayout = items.map((item) => {
      const size = typeof getItemSize === "number" ? getItemSize : getItemSize(item);
      const position = { start, size };
      start += size;
      return position;
    });
    return { layout: nextLayout, totalSize: start };
  }, [getItemSize, items]);

  useLayoutEffect(() => {
    const element = viewportRef.current;
    if (!element || !windowed) return;
    const measure = () =>
      setViewport({ height: element.clientHeight, scrollTop: element.scrollTop });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [windowed]);

  if (!windowed) {
    return (
      <ul className={className} aria-label={ariaLabel}>
        {items.map((item, index) => (
          <li key={getKey(item)}>{renderItem(item, index)}</li>
        ))}
      </ul>
    );
  }

  const range = visibleRange(layout, viewport.scrollTop, viewport.height, overscanPx);
  return (
    <ul
      ref={viewportRef}
      className={`${className} virtual-list-window`}
      aria-label={ariaLabel}
      onScroll={(event) =>
        setViewport((current) => ({
          ...current,
          scrollTop: event.currentTarget.scrollTop,
        }))
      }
    >
      <li className="virtual-list-space" style={{ height: totalSize }} aria-hidden="true" />
      {items.slice(range.start, range.end).map((item, relativeIndex) => {
        const index = range.start + relativeIndex;
        const position = layout[index];
        return (
          <li
            key={getKey(item)}
            className="virtual-list-row"
            style={{ height: position.size, transform: `translateY(${position.start}px)` }}
          >
            {renderItem(item, index)}
          </li>
        );
      })}
    </ul>
  );
}

function visibleRange(
  layout: readonly ItemLayout[],
  scrollTop: number,
  viewportHeight: number,
  overscanPx: number,
): { start: number; end: number } {
  const minimum = Math.max(0, scrollTop - overscanPx);
  const maximum = scrollTop + viewportHeight + overscanPx;
  let start = 0;
  while (start < layout.length && layout[start].start + layout[start].size < minimum) start += 1;
  let end = start;
  while (end < layout.length && layout[end].start < maximum) end += 1;
  return { start, end };
}
