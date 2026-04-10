import type { Snapshot } from "../api/types";
import { parseJsonDeep } from "./json";

export interface SnapshotMetric {
  key: string;
  label: string;
  value: number;
  tone: "info" | "success" | "warning" | "danger" | "neutral";
}

export interface SnapshotPartition<TSnapshot> {
  detailedSnapshots: TSnapshot[];
  hiddenCount: number;
}

export function hasDetailedSnapshotState(
  snapshot: Pick<Snapshot, "state_json">,
  parsedState: unknown | null = parseJsonDeep(snapshot.state_json),
): boolean {
  if (parsedState !== null) {
    return hasInspectableValue(parsedState);
  }

  const rawState = snapshot.state_json.trim();
  return rawState.length > 0 && rawState !== "{}" && rawState !== "[]";
}

export function getKafkaSnapshotMetrics(
  snapshot: Pick<Snapshot, "state_json">,
  parsedState: unknown | null = parseJsonDeep(snapshot.state_json),
): SnapshotMetric[] {
  if (typeof parsedState !== "object" || parsedState === null || Array.isArray(parsedState)) {
    return [];
  }

  const state = parsedState as Record<string, unknown>;
  const metricDefs = [
    { key: "consumed", label: "Consumed" },
    { key: "published", label: "Published" },
    { key: "produced", label: "Produced" },
    { key: "committed", label: "Committed" },
    { key: "failed", label: "Failed" },
  ];

  return metricDefs.flatMap(({ key, label }) => {
    if (!(key in state)) {
      return [];
    }

    const value = countMetricValue(state[key]);
    if (value === null) {
      return [];
    }

    return [
      {
        key,
        label,
        value,
        tone: metricTone(key, value),
      } satisfies SnapshotMetric,
    ];
  });
}

export function partitionSnapshotsByDetail<TSnapshot extends Pick<Snapshot, "state_json">>(
  snapshots: TSnapshot[],
): SnapshotPartition<TSnapshot> {
  const detailedSnapshots = snapshots.filter((snapshot) => hasDetailedSnapshotState(snapshot));

  return {
    detailedSnapshots,
    hiddenCount: snapshots.length - detailedSnapshots.length,
  };
}

function hasInspectableValue(value: unknown): boolean {
  if (Array.isArray(value)) {
    return value.some(hasInspectableValue);
  }

  if (typeof value === "object" && value !== null) {
    const entries = Object.values(value);
    return entries.length > 0 && entries.some(hasInspectableValue);
  }

  if (typeof value === "string") {
    return value.trim().length > 0;
  }

  return typeof value === "number" || typeof value === "boolean";
}

function countMetricValue(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  if (Array.isArray(value)) {
    return value.length;
  }

  if (typeof value === "object" && value !== null) {
    return Object.keys(value).length;
  }

  return null;
}

function metricTone(key: string, value: number): SnapshotMetric["tone"] {
  if (key === "failed") {
    return value > 0 ? "danger" : "neutral";
  }

  if (key === "published" || key === "produced") {
    return value > 0 ? "success" : "neutral";
  }

  if (key === "consumed" || key === "committed") {
    return value > 0 ? "info" : "neutral";
  }

  return "warning";
}
