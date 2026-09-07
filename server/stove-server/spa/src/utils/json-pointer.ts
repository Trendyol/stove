export type PointerResult = { found: true; value: unknown } | { found: false };

/** RFC 6901 lookup; distinguish JSON null from a missing value. */
export function resolveJsonPointer(value: unknown, pointer: string): PointerResult {
  if (pointer === "") return { found: true, value };
  if (!pointer.startsWith("/") || /~(?![01])/u.test(pointer)) return { found: false };
  let selected = value;
  for (const part of pointer.slice(1).split("/")) {
    const key = part.replace(/~1/g, "/").replace(/~0/g, "~");
    if (
      typeof selected !== "object" ||
      selected === null ||
      Object.getOwnPropertyDescriptor(selected, key) === undefined
    )
      return { found: false };
    if (Array.isArray(selected) && !/^(0|[1-9]\d*)$/.test(key)) return { found: false };
    selected = (selected as Record<string, unknown>)[key];
  }
  return { found: true, value: selected };
}

/** Resolve against recorded JSON before the explorer expands JSON-encoded strings. */
export function resolveSnapshotPointer(raw: string, pointer: string): PointerResult {
  try {
    return resolveJsonPointer(JSON.parse(raw), pointer);
  } catch {
    return { found: false };
  }
}
