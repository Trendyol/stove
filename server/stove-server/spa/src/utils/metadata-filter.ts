import type { Run } from "../api/types";

export type MetadataFilter = Readonly<Record<string, readonly string[]>>;

export interface MetadataSelection {
  key: string;
  value: string;
}

export function filterRunsByMetadata(runs: readonly Run[], filter: MetadataFilter): Run[] {
  const selections = Object.entries(filter);
  if (selections.length === 0) return [...runs];

  return runs.filter((run) =>
    selections.every(
      ([key, acceptedValues]) =>
        acceptedValues.length === 0 || acceptedValues.includes(run.metadata[key]),
    ),
  );
}

export function toggleMetadataValue(
  filter: MetadataFilter,
  key: string,
  value: string,
): MetadataFilter {
  const currentValues = filter[key] ?? [];
  const nextValues = currentValues.includes(value)
    ? currentValues.filter((candidate) => candidate !== value)
    : [...currentValues, value].sort((left, right) => left.localeCompare(right));

  return Object.fromEntries(
    Object.entries({ ...filter, [key]: nextValues })
      .filter(([, values]) => values.length > 0)
      .sort(([left], [right]) => left.localeCompare(right)),
  );
}

export function isMetadataValueSelected(
  filter: MetadataFilter,
  key: string,
  value: string,
): boolean {
  return filter[key]?.includes(value) ?? false;
}

export function metadataSelections(filter: MetadataFilter): MetadataSelection[] {
  return Object.entries(filter).flatMap(([key, values]) => values.map((value) => ({ key, value })));
}
