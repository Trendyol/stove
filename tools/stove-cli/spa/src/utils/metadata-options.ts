import type { Run } from "../api/types";

export interface MetadataOption {
  key: string;
  values: string[];
}

export function metadataOptionsForRuns(runs: Run[]): MetadataOption[] {
  const valuesByKey = new Map<string, Set<string>>();
  for (const run of runs) {
    for (const [key, value] of Object.entries(run.metadata)) {
      const values = valuesByKey.get(key) ?? new Set<string>();
      values.add(value);
      valuesByKey.set(key, values);
    }
  }

  return [...valuesByKey.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, values]) => ({
      key,
      values: [...values].sort((left, right) => left.localeCompare(right)),
    }));
}
