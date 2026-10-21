import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../api/client";
import { topFilterQueryOptions } from "../../api/dashboard-queries";

export function useTopFilterSettings() {
  const client = useQueryClient();
  const query = useQuery(topFilterQueryOptions);
  const savedKey = query.data?.settings.metadata_key ?? "";
  const [draft, setDraft] = useState<{ original: string; value: string } | null>(null);
  const value = draft?.original === savedKey ? draft.value : savedKey;
  const mutation = useMutation({
    mutationFn: api.updateTopFilter,
    onSuccess: async (settings) => {
      // The write response is authoritative even if the subsequent catalog refresh fails.
      client.setQueryData(
        topFilterQueryOptions.queryKey,
        (catalog) =>
          catalog && {
            ...catalog,
            settings,
            values: catalog.settings.metadata_key === settings.metadata_key ? catalog.values : [],
          },
      );
      setDraft(null);
      await client.invalidateQueries(topFilterQueryOptions);
    },
  });

  return {
    value,
    savedKey,
    keys: query.data?.available_keys.filter((key) => key.trim()) ?? [],
    loading: query.isPending,
    saving: mutation.isPending,
    saved: mutation.isSuccess,
    disabled: !query.data || mutation.isPending,
    error: mutation.error ?? query.error,
    retry: query.error ? () => void query.refetch() : undefined,
    select: (value: string) => {
      setDraft({ original: savedKey, value });
      mutation.reset();
    },
    save: () => mutation.mutate({ metadata_key: value || null }),
  };
}
