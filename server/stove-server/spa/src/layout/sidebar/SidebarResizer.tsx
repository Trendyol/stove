import { type KeyboardEvent, type PointerEvent, useCallback, useRef, useState } from "react";

const MIN_WIDTH = 240;
const MAX_WIDTH = 600;
const DEFAULT_WIDTH = 280;
const RESIZE_STEP = 16;
const STORAGE_KEY = "stove-sidebar-width";

function clampWidth(width: number): number {
  return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
}

function loadWidth(): number {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return DEFAULT_WIDTH;
    const width = Number(stored);
    if (Number.isFinite(width)) return clampWidth(width);
  } catch {
    // Resizing still works when browser storage is unavailable.
  }
  return DEFAULT_WIDTH;
}

export function useSidebarWidth() {
  const [width, setWidth] = useState(loadWidth);
  const resize = useCallback((value: number) => {
    const next = clampWidth(value);
    setWidth(next);
    try {
      localStorage.setItem(STORAGE_KEY, String(next));
    } catch {
      // Keep the width for this session when it cannot be persisted.
    }
  }, []);
  return [width, resize] as const;
}

function widthForKey(key: string, width: number): number | undefined {
  switch (key) {
    case "ArrowLeft":
      return width - RESIZE_STEP;
    case "ArrowRight":
      return width + RESIZE_STEP;
    case "Home":
      return MIN_WIDTH;
    case "End":
      return MAX_WIDTH;
    case "Enter":
      return DEFAULT_WIDTH;
    default:
      return undefined;
  }
}

export function SidebarResizer({
  width,
  onResize,
}: {
  width: number;
  onResize: (width: number) => void;
}) {
  const dragging = useRef(false);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const next = widthForKey(event.key, width);
    if (next === undefined) return;
    event.preventDefault();
    onResize(next);
  }

  function startDragging(event: PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    dragging.current = true;
    event.currentTarget.setPointerCapture(event.pointerId);
    event.currentTarget.focus();
  }

  function resizeWhileDragging(event: PointerEvent<HTMLDivElement>) {
    if (!dragging.current) return;
    const left = event.currentTarget.parentElement?.getBoundingClientRect().left ?? 0;
    onResize(event.clientX - left);
  }

  function stopDragging() {
    dragging.current = false;
  }

  return (
    // biome-ignore lint/a11y/useSemanticElements: An adjustable window splitter exposes width, unlike a thematic break.
    <div
      className="stove-sidebar-resizer"
      role="separator"
      tabIndex={0}
      aria-label="Test navigator width"
      aria-orientation="vertical"
      aria-valuemin={MIN_WIDTH}
      aria-valuemax={MAX_WIDTH}
      aria-valuenow={width}
      aria-valuetext={`${width} pixels`}
      onKeyDown={handleKeyDown}
      onPointerDown={startDragging}
      onPointerMove={resizeWhileDragging}
      onPointerUp={stopDragging}
      onLostPointerCapture={stopDragging}
    />
  );
}
