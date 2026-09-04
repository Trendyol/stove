import { useEffect, useRef, useState } from "react";
import type { Snapshot } from "../api/types";
import type { SnapshotMetric } from "../utils/snapshot-state";
import type {
  SnapshotWorkerRequest,
  SnapshotWorkerResponse,
} from "../workers/snapshot-state.protocol";

export type SnapshotExplorerState =
  | { kind: "loading" }
  | { kind: "raw"; value: string; detailed: boolean; metrics: SnapshotMetric[] }
  | {
      kind: "structured";
      value: unknown;
      filteredValue: unknown | null;
      description: string;
      detailed: boolean;
      metrics: SnapshotMetric[];
      matchCount: number;
      filtering: boolean;
    };

export function useSnapshotExplorer(
  snapshot: Pick<Snapshot, "state_json" | "system">,
  searchQuery: string,
): SnapshotExplorerState {
  const [state, setState] = useState<SnapshotExplorerState>({ kind: "loading" });
  const workerRef = useRef<Worker | undefined>(undefined);
  const requestIdRef = useRef(0);

  useEffect(() => {
    setState({ kind: "loading" });
    requestIdRef.current = 0;
    const worker = new Worker(new URL("../workers/snapshot-state.worker.ts", import.meta.url), {
      type: "module",
    });
    workerRef.current = worker;
    worker.onmessage = (message: MessageEvent<SnapshotWorkerResponse>) => {
      const response = message.data;
      if (response.kind === "search-result") {
        if (response.requestId !== requestIdRef.current) return;
        setState((current) =>
          current.kind === "structured"
            ? {
                ...current,
                filteredValue: response.filteredValue,
                matchCount: response.matchCount,
                filtering: false,
              }
            : current,
        );
        return;
      }
      setState(
        response.kind === "structured"
          ? {
              ...response,
              filteredValue: response.value,
              matchCount: 0,
              filtering: false,
            }
          : response,
      );
    };
    const request: SnapshotWorkerRequest = {
      kind: "load",
      stateJson: snapshot.state_json,
      system: snapshot.system,
    };
    worker.postMessage(request);

    return () => {
      worker.terminate();
      if (workerRef.current === worker) workerRef.current = undefined;
    };
  }, [snapshot.state_json, snapshot.system]);

  const sourceValue = state.kind === "structured" ? state.value : undefined;
  useEffect(() => {
    if (sourceValue === undefined) return;
    const query = searchQuery.trim();
    if (!query) {
      // Invalidate a search already posted to the worker so a late response
      // cannot replace the restored, unfiltered tree.
      requestIdRef.current += 1;
      setState((current) =>
        current.kind === "structured" &&
        (current.filteredValue !== current.value || current.matchCount !== 0 || current.filtering)
          ? { ...current, filteredValue: current.value, matchCount: 0, filtering: false }
          : current,
      );
      return;
    }

    setState((current) =>
      current.kind === "structured" && !current.filtering
        ? { ...current, filtering: true }
        : current,
    );
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;
    const timer = window.setTimeout(() => {
      const request: SnapshotWorkerRequest = { kind: "search", requestId, query };
      workerRef.current?.postMessage(request);
    }, 100);
    return () => window.clearTimeout(timer);
  }, [searchQuery, sourceValue]);

  return state;
}
