export function tryFormatJson(s: string): string {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

export function tryFormatJsonDeep(s: string): string {
  const parsed = parseJsonDeep(s);
  if (parsed === null) {
    return s;
  }
  return JSON.stringify(parsed, null, 2);
}

export function parseJsonDeep(s: string): unknown | null {
  try {
    return normalizeEmbeddedJson(JSON.parse(s));
  } catch {
    return null;
  }
}

export interface JsonSearchResult {
  filteredValue: unknown | null;
  matchCount: number;
}

export function filterJsonByQuery(value: unknown, query: string): JsonSearchResult {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return {
      filteredValue: value,
      matchCount: 0,
    };
  }

  return filterJsonValue(value, normalizedQuery);
}

export function describeJsonValue(value: unknown): string {
  if (Array.isArray(value)) {
    return `${value.length} item${value.length === 1 ? "" : "s"}`;
  }

  if (typeof value === "object" && value !== null) {
    const keys = Object.keys(value);
    return `${keys.length} key${keys.length === 1 ? "" : "s"}`;
  }

  if (value === null) {
    return "null";
  }

  return typeof value;
}

export function getJsonPreviewKeys(value: unknown, limit = 4): string[] {
  if (Array.isArray(value)) {
    return value.slice(0, limit).map((_, index) => `[${index}]`);
  }

  if (typeof value === "object" && value !== null) {
    return Object.keys(value).slice(0, limit);
  }

  return [];
}

function normalizeEmbeddedJson(value: unknown): unknown {
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (
      (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
      (trimmed.startsWith("[") && trimmed.endsWith("]"))
    ) {
      try {
        return normalizeEmbeddedJson(JSON.parse(trimmed));
      } catch {
        return value;
      }
    }
    return value;
  }

  if (Array.isArray(value)) {
    return value.map(normalizeEmbeddedJson);
  }

  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(
      Object.entries(value).map(([key, nested]) => [key, normalizeEmbeddedJson(nested)]),
    );
  }

  return value;
}

function filterJsonValue(value: unknown, normalizedQuery: string): JsonSearchResult {
  if (Array.isArray(value)) {
    const filteredItems: unknown[] = [];
    let matchCount = 0;

    value.forEach((item) => {
      const nested = filterJsonValue(item, normalizedQuery);
      if (nested.filteredValue !== null) {
        filteredItems.push(nested.filteredValue);
        matchCount += nested.matchCount;
      }
    });

    return filteredItems.length > 0
      ? { filteredValue: filteredItems, matchCount }
      : { filteredValue: null, matchCount: 0 };
  }

  if (typeof value === "object" && value !== null) {
    const filteredEntries: Array<[string, unknown]> = [];
    let matchCount = 0;

    for (const [key, nestedValue] of Object.entries(value)) {
      if (key.toLowerCase().includes(normalizedQuery)) {
        filteredEntries.push([key, nestedValue]);
        matchCount += 1;
        continue;
      }

      const nested = filterJsonValue(nestedValue, normalizedQuery);
      if (nested.filteredValue !== null) {
        filteredEntries.push([key, nested.filteredValue]);
        matchCount += nested.matchCount;
      }
    }

    return filteredEntries.length > 0
      ? { filteredValue: Object.fromEntries(filteredEntries), matchCount }
      : { filteredValue: null, matchCount: 0 };
  }

  return primitiveIncludes(value, normalizedQuery)
    ? { filteredValue: value, matchCount: 1 }
    : { filteredValue: null, matchCount: 0 };
}

function primitiveIncludes(value: unknown, normalizedQuery: string): boolean {
  if (value == null) {
    return "null".includes(normalizedQuery);
  }

  return String(value).toLowerCase().includes(normalizedQuery);
}

export function parseAttrs(json: string | null): Record<string, string> {
  if (!json) return {};
  try {
    const parsed: unknown = JSON.parse(json);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
    const result: Record<string, string> = {};
    for (const [k, v] of Object.entries(parsed)) {
      result[k] = String(v);
    }
    return result;
  } catch {
    return {};
  }
}
