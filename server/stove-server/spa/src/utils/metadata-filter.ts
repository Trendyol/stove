import type { Run } from "../api/types";

export type MetadataFilter = Readonly<Record<string, readonly string[]>>;

interface MetadataSelection {
  key: string;
  value: string;
}

export function filterRunsByMetadata(runs: readonly Run[], filter: MetadataFilter): Run[] {
  const selections = Object.entries(filter);
  if (selections.length === 0) return [...runs];

  const activeFilter = Object.fromEntries(selections.filter(([, values]) => values.length > 0));
  return runs.filter((run) => matchesMetadata(run.metadata, activeFilter));
}

/** Exact matches: any selected value within a key, all selected keys. */
export function matchesMetadata(
  metadata: Readonly<Record<string, string>>,
  filter: MetadataFilter,
): boolean {
  return Object.entries(filter).every(([key, values]) => {
    // biome-ignore lint/suspicious/noPrototypeBuiltins: Object.hasOwn requires ES2022; this app targets ES2020.
    const ownsKey = Object.prototype.hasOwnProperty.call(metadata, key);
    return ownsKey && values.includes(metadata[key]);
  });
}

export function toggleMetadataValue(
  filter: MetadataFilter,
  key: string,
  value: string,
): MetadataFilter {
  // biome-ignore lint/suspicious/noPrototypeBuiltins: Object.hasOwn requires ES2022; this app targets ES2020.
  const currentValues = Object.prototype.hasOwnProperty.call(filter, key) ? filter[key] : [];
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
  // biome-ignore lint/suspicious/noPrototypeBuiltins: Object.hasOwn requires ES2022; this app targets ES2020.
  return Object.prototype.hasOwnProperty.call(filter, key) && filter[key].includes(value);
}

export function metadataSelections(filter: MetadataFilter): MetadataSelection[] {
  return Object.entries(filter).flatMap(([key, values]) => values.map((value) => ({ key, value })));
}
