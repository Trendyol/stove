import type { Run } from "../api/types";

export interface MetadataOption {
  key: string;
  values: MetadataValueOption[];
}

export interface MetadataValueOption {
  value: string;
  count: number;
}

export function metadataOptionsForRuns(runs: Run[]): MetadataOption[] {
  const valuesByKey = new Map<string, Map<string, number>>();
  for (const run of runs) {
    for (const [key, value] of Object.entries(run.metadata)) {
      const values = valuesByKey.get(key) ?? new Map<string, number>();
      values.set(value, (values.get(value) ?? 0) + 1);
      valuesByKey.set(key, values);
    }
  }

  return [...valuesByKey.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, values]) => ({
      key,
      values: [...values.entries()]
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([value, count]) => ({ value, count })),
    }));
}
