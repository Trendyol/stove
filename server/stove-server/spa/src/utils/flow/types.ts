import type { Entry } from "../../api/types";

export interface SystemNodeData extends Record<string, unknown> {
  kind: "step" | "trace" | "arrange";
  system: string;
  action: string;
  result: string;
  count: number;
  error: string | null;
  entries: Entry[];
  traceId: string | null;
  startedAt: string | null;
  endedAt: string | null;
  durationMs: number | null;
  inspectable: boolean;
}

export interface GapNodeData extends Record<string, unknown> {
  kind: "gap";
  label: string;
  durationMs: number;
  startedAt: string;
  endedAt: string;
  inspectable: false;
}

export type FlowNodeData = SystemNodeData | GapNodeData;

export interface DurationEdgeData extends Record<string, unknown> {
  durationMs: number;
  label?: string;
}
