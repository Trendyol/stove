import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { topFilterQueryOptions } from "../api/dashboard-queries";
import { isRecord } from "../api/validation";

const STORAGE_KEY = "stove-top-filter";
type Selection = { key: string; value: string } | null;

function readSelection(): Selection {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
    return isRecord(value) && typeof value.key === "string" && typeof value.value === "string"
      ? { key: value.key, value: value.value }
      : null;
  } catch {
    return null;
  }
}

export function useTopFilter() {
  const query = useQuery({
    ...topFilterQueryOptions,
    refetchInterval: 5000,
  });
  const [selection, setSelection] = useState(readSelection);
  const key = query.data?.settings.metadata_key ?? null;
  const value = key !== null && selection?.key === key ? selection.value : null;

  useEffect(() => {
    if (query.data && selection && selection.key !== key) setSelection(null);
  }, [query.data, key, selection]);
  useEffect(() => {
    try {
      if (selection) localStorage.setItem(STORAGE_KEY, JSON.stringify(selection));
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Filtering remains usable when browser storage is unavailable.
    }
  }, [selection]);

  return {
    query,
    key,
    value,
    values: query.data?.values ?? [],
    select: (value: string | null) =>
      setSelection(key !== null && value !== null ? { key, value } : null),
  };
}
