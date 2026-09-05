import { type Key, type ReactNode, useLayoutEffect, useMemo, useRef, useState } from "react";

import { fixedRange, variableRange } from "../utils/virtual-window";

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
    if (typeof getItemSize === "number") {
      return { layout: null, totalSize: items.length * getItemSize };
    }
    let start = 0;
    const nextLayout = items.map((item) => {
      const size = getItemSize(item);
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

  const minimum = Math.max(0, viewport.scrollTop - overscanPx);
  const maximum = viewport.scrollTop + viewport.height + overscanPx;
  const range =
    typeof getItemSize === "number"
      ? fixedRange(items.length, getItemSize, minimum, maximum)
      : variableRange(layout ?? [], minimum, maximum);
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
        const position =
          typeof getItemSize === "number"
            ? { start: index * getItemSize, size: getItemSize }
            : layout![index];
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
