import { useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { adminKeys } from "./admin-queries";

export function useAdminMetadata(appName: string) {
  return useQuery({
    queryKey: adminKeys.metadata(appName),
    queryFn: ({ signal }) => api.getRunMetadata(appName, undefined, signal),
    enabled: Boolean(appName),
  });
}
