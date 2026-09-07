import type { PointerResult } from "../utils/json-pointer";
import type { SnapshotMetric } from "../utils/snapshot-state";

export type SnapshotWorkerRequest =
  | { kind: "load"; stateJson: string; system: string; pointer?: string }
  | { kind: "search"; requestId: number; query: string };

export type SnapshotWorkerResponse =
  | {
      kind: "structured";
      value: unknown;
      selection?: PointerResult;
      description: string;
      detailed: boolean;
      metrics: SnapshotMetric[];
    }
  | {
      kind: "raw";
      value: string;
      selection?: PointerResult;
      detailed: boolean;
      metrics: SnapshotMetric[];
    }
  | { kind: "search-result"; requestId: number; filteredValue: unknown | null; matchCount: number };
