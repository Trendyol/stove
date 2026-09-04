import type { SnapshotMetric } from "../utils/snapshot-state";

export type SnapshotWorkerRequest =
  | { kind: "load"; stateJson: string; system: string }
  | { kind: "search"; requestId: number; query: string };

export type SnapshotWorkerResponse =
  | {
      kind: "structured";
      value: unknown;
      description: string;
      detailed: boolean;
      metrics: SnapshotMetric[];
    }
  | { kind: "raw"; value: string; detailed: boolean; metrics: SnapshotMetric[] }
  | { kind: "search-result"; requestId: number; filteredValue: unknown | null; matchCount: number };
