import dagre from "@dagrejs/dagre";
import type { Edge, Node } from "@xyflow/react";
import { MarkerType } from "@xyflow/react";
import type { Entry, Span } from "../api/types";
import { parseAttrs } from "./json";
import { isFailed } from "./result";

const EXECUTION_GAP_THRESHOLD_MS = 1000;
const ADJACENT_MERGE_WINDOW_MS = 250;
const STEP_NODE_SIZE = { width: 240, height: 128 };
const TRACE_NODE_SIZE = { width: 240, height: 120 };
const ARRANGE_NODE_SIZE = { width: 240, height: 128 };
const GAP_NODE_SIZE = { width: 208, height: 96 };

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

interface TimelineStepGroup {
  entries: Entry[];
  startedAtMs: number;
  endedAtMs: number;
  kind: "step" | "arrange";
  actionLabel: string;
  displayCount: number;
}

interface TimelineStepSpec {
  kind: "step";
  group: TimelineStepGroup;
}

interface TimelineGapSpec {
  kind: "gap";
  durationMs: number;
  startedAt: string;
  endedAt: string;
  startedAtMs: number;
  endedAtMs: number;
}

type TimelineSpec = TimelineStepSpec | TimelineGapSpec;

export function entriesToDag(entries: Entry[]): { nodes: Node<FlowNodeData>[]; edges: Edge[] } {
  if (entries.length === 0) return { nodes: [], edges: [] };

  const sorted = [...entries].sort((a, b) => toMs(a.timestamp) - toMs(b.timestamp));
  const stepGroups = sorted.length > 0 ? collapseArrangeRuns(groupEntriesIntoSteps(sorted)) : [];
  const { arrangeGroups, mainGroups } = splitArrangeGroups(stepGroups);
  const orderedSpecs = expandTimelineSpecs(mainGroups);

  const timelineNodes: Node<FlowNodeData>[] = orderedSpecs.map((spec, index) => {
    if (spec.kind === "gap") {
      return {
        id: nodeIdForSpec(spec, index),
        type: "gapNode",
        position: { x: 0, y: 0 },
        data: {
          kind: "gap",
          label: "Idle gap",
          durationMs: spec.durationMs,
          startedAt: spec.startedAt,
          endedAt: spec.endedAt,
          inspectable: false,
        } satisfies GapNodeData,
      };
    }

    const group = spec.group.entries;
    const first = group[0];
    const last = group[group.length - 1];
    const hasFailed = group.some((entry) => isFailed(entry.result));
    const firstError = group.find((entry) => entry.error)?.error ?? null;
    const traceId = group.find((entry) => entry.trace_id)?.trace_id ?? null;

    return createSystemNode(nodeIdForSpec(spec, index), {
      kind: spec.group.kind,
      system: first.system,
      action: spec.group.actionLabel,
      result: hasFailed ? "FAILED" : "PASSED",
      count: spec.group.displayCount,
      error: firstError,
      entries: group,
      traceId,
      startedAt: first.timestamp,
      endedAt: last.timestamp,
      durationMs: Math.max(0, spec.group.endedAtMs - spec.group.startedAtMs),
      inspectable: true,
    });
  });

  const edges: Edge[] = [];
  for (let i = 1; i < orderedSpecs.length; i++) {
    const previousSpec = orderedSpecs[i - 1];
    const currentSpec = orderedSpecs[i];

    edges.push({
      id: `edge-${i - 1}-${i}`,
      source: nodeIdForSpec(previousSpec, i - 1),
      target: nodeIdForSpec(currentSpec, i),
      type: "durationEdge",
      markerEnd: { type: MarkerType.ArrowClosed },
      data: {
        durationMs: Math.max(0, getSpecStartedAtMs(currentSpec) - getSpecEndedAtMs(previousSpec)),
      } satisfies DurationEdgeData,
    });
  }

  const nodes = [...timelineNodes];

  if (arrangeGroups.length > 0) {
    const firstTimelineNodeId = timelineNodes.length > 0 ? timelineNodes[0].id : null;

    arrangeGroups.forEach((group, index) => {
      const first = group.entries[0];
      const last = group.entries[group.entries.length - 1];
      const hasFailed = group.entries.some((entry) => isFailed(entry.result));
      const firstError = group.entries.find((entry) => entry.error)?.error ?? null;
      const traceId = group.entries.find((entry) => entry.trace_id)?.trace_id ?? null;
      const arrangeNodeId = `arrange-step-${index}`;

      nodes.push(
        createSystemNode(arrangeNodeId, {
          kind: "arrange",
          system: first.system,
          action: group.actionLabel,
          result: hasFailed ? "FAILED" : "PASSED",
          count: group.displayCount,
          error: firstError,
          entries: group.entries,
          traceId,
          startedAt: first.timestamp,
          endedAt: last.timestamp,
          durationMs: Math.max(0, group.endedAtMs - group.startedAtMs),
          inspectable: true,
        }),
      );

      if (firstTimelineNodeId) {
        edges.push({
          id: `${arrangeNodeId}-${firstTimelineNodeId}`,
          source: arrangeNodeId,
          target: firstTimelineNodeId,
          type: "durationEdge",
          markerEnd: { type: MarkerType.ArrowClosed },
          data: {
            durationMs: 0,
            label: "ready",
          } satisfies DurationEdgeData,
        });
      }
    });
  }

  return { nodes, edges };
}

export function spansToTraceDag(spans: Span[]): { nodes: Node<FlowNodeData>[]; edges: Edge[] } {
  if (spans.length === 0) return { nodes: [], edges: [] };

  const nodes: Node<FlowNodeData>[] = spans.map((span) => {
    const system = detectSystemFromSpan(span);

    return {
      id: span.span_id,
      type: "systemNode",
      position: { x: 0, y: 0 },
      data: {
        kind: "trace",
        system,
        action: span.operation_name,
        result: span.status,
        count: 1,
        error: span.exception_message ?? null,
        entries: [],
        traceId: span.trace_id,
        startedAt: null,
        endedAt: null,
        durationMs: Math.max(0, (span.end_time_nanos - span.start_time_nanos) / 1_000_000),
        inspectable: true,
      } satisfies SystemNodeData,
    };
  });

  const spanIds = new Set(spans.map((span) => span.span_id));
  const edges: Edge[] = spans
    .filter((span) => span.parent_span_id && spanIds.has(span.parent_span_id))
    .map((span) => ({
      id: `span-edge-${span.parent_span_id}-${span.span_id}`,
      source: span.parent_span_id!,
      target: span.span_id,
      type: "durationEdge",
      markerEnd: { type: MarkerType.ArrowClosed },
      data: {
        durationMs: Math.max(0, (span.end_time_nanos - span.start_time_nanos) / 1_000_000),
      } satisfies DurationEdgeData,
    }));

  return { nodes, edges };
}

export function applyDagreLayout(
  nodes: Node<FlowNodeData>[],
  edges: Edge[],
  direction: "LR" | "TB" = "LR",
): Node<FlowNodeData>[] {
  if (nodes.length === 0) return nodes;

  const g = new dagre.graphlib.Graph();
  g.setDefaultEdgeLabel(() => ({}));
  g.setGraph({
    rankdir: direction,
    nodesep: 128,
    ranksep: 176,
    edgesep: 48,
    marginx: 24,
    marginy: 24,
  });

  for (const node of nodes) {
    const size = getNodeLayoutSize(node);
    g.setNode(node.id, size);
  }
  for (const edge of edges) {
    g.setEdge(edge.source, edge.target);
  }

  dagre.layout(g);

  return nodes.map((node) => {
    const pos = g.node(node.id);
    const size = getNodeLayoutSize(node);
    return {
      ...node,
      position: { x: pos.x - size.width / 2, y: pos.y - size.height / 2 },
    };
  });
}

export function getNodeLayoutSize(node: Node<FlowNodeData>): { width: number; height: number } {
  switch (node.type) {
    case "gapNode":
      return cloneLayoutSize(GAP_NODE_SIZE);
    case "systemNode":
      return getSystemNodeLayoutSize(node.data as SystemNodeData);
    default:
      return cloneLayoutSize(STEP_NODE_SIZE);
  }
}

const DB_SYSTEM_MAP: Record<string, string> = {
  postgresql: "PostgreSQL",
  mysql: "MySQL",
  mssql: "MSSQL",
  mongodb: "MongoDB",
  redis: "Redis",
  couchbase: "Couchbase",
  elasticsearch: "Elasticsearch",
  cassandra: "Cassandra",
};

function detectSystemFromSpan(span: Span): string {
  const attrs = parseAttrs(span.attributes);
  const keys = Object.keys(attrs);

  if (keys.some((key) => key.startsWith("http."))) return "HTTP";
  if (keys.some((key) => key.startsWith("messaging."))) return "Kafka";
  if (keys.some((key) => key.startsWith("db."))) {
    const dbSystem = attrs["db.system"];
    if (dbSystem) return DB_SYSTEM_MAP[dbSystem.toLowerCase()] ?? dbSystem;
    return "Database";
  }
  if (keys.some((key) => key.startsWith("rpc."))) return "gRPC";

  return span.service_name || "Unknown";
}

function createSystemNode(id: string, data: SystemNodeData): Node<FlowNodeData> {
  return {
    id,
    type: "systemNode",
    position: { x: 0, y: 0 },
    data,
  };
}

function getSystemNodeLayoutSize(data: SystemNodeData): { width: number; height: number } {
  switch (data.kind) {
    case "trace":
      return cloneLayoutSize(TRACE_NODE_SIZE);
    case "arrange":
      return cloneLayoutSize(ARRANGE_NODE_SIZE);
    default:
      return cloneLayoutSize(STEP_NODE_SIZE);
  }
}

function cloneLayoutSize(size: { width: number; height: number }): {
  width: number;
  height: number;
} {
  return { width: size.width, height: size.height };
}

function groupEntriesIntoSteps(entries: Entry[]): TimelineStepGroup[] {
  const groups: TimelineStepGroup[] = [];
  let currentEntries: Entry[] = [entries[0]];
  let currentStartedAtMs = toMs(entries[0].timestamp);
  let currentEndedAtMs = currentStartedAtMs;

  for (let i = 1; i < entries.length; i++) {
    const previous = currentEntries[currentEntries.length - 1];
    const next = entries[i];
    const nextAtMs = toMs(next.timestamp);

    if (canMergeAdjacentEntries(previous, next, currentEndedAtMs, nextAtMs)) {
      currentEntries.push(next);
      currentEndedAtMs = nextAtMs;
      continue;
    }

    groups.push({
      entries: currentEntries,
      startedAtMs: currentStartedAtMs,
      endedAtMs: currentEndedAtMs,
      kind: isArrangeEntryGroup(currentEntries) ? "arrange" : "step",
      actionLabel: currentEntries[0].action,
      displayCount: currentEntries.length,
    });
    currentEntries = [next];
    currentStartedAtMs = nextAtMs;
    currentEndedAtMs = nextAtMs;
  }

  groups.push({
    entries: currentEntries,
    startedAtMs: currentStartedAtMs,
    endedAtMs: currentEndedAtMs,
    kind: isArrangeEntryGroup(currentEntries) ? "arrange" : "step",
    actionLabel: currentEntries[0].action,
    displayCount: currentEntries.length,
  });

  return groups;
}

function canMergeAdjacentEntries(
  previous: Entry,
  next: Entry,
  previousAtMs: number,
  nextAtMs: number,
): boolean {
  return (
    next.system === previous.system &&
    next.action === previous.action &&
    next.result === previous.result &&
    (next.error ?? null) === (previous.error ?? null) &&
    (next.trace_id ?? null) === (previous.trace_id ?? null) &&
    nextAtMs - previousAtMs <= ADJACENT_MERGE_WINDOW_MS
  );
}

function expandTimelineSpecs(groups: TimelineStepGroup[]): TimelineSpec[] {
  const specs: TimelineSpec[] = [];

  for (let i = 0; i < groups.length; i++) {
    if (i > 0) {
      const previous = groups[i - 1];
      const current = groups[i];
      const gapMs = Math.max(0, current.startedAtMs - previous.endedAtMs);

      if (gapMs >= EXECUTION_GAP_THRESHOLD_MS) {
        specs.push({
          kind: "gap",
          durationMs: gapMs,
          startedAt: previous.entries[previous.entries.length - 1].timestamp,
          endedAt: current.entries[0].timestamp,
          startedAtMs: previous.endedAtMs,
          endedAtMs: current.startedAtMs,
        });
      }
    }

    specs.push({
      kind: "step",
      group: groups[i],
    });
  }

  return specs;
}

function splitArrangeGroups(groups: TimelineStepGroup[]): {
  arrangeGroups: TimelineStepGroup[];
  mainGroups: TimelineStepGroup[];
} {
  let arrangeCount = 0;
  while (arrangeCount < groups.length && groups[arrangeCount].kind === "arrange") {
    arrangeCount += 1;
  }

  return {
    arrangeGroups: groups.slice(0, arrangeCount),
    mainGroups: groups.slice(arrangeCount),
  };
}

function collapseArrangeRuns(groups: TimelineStepGroup[]): TimelineStepGroup[] {
  const collapsed: TimelineStepGroup[] = [];
  let index = 0;

  while (index < groups.length) {
    const current = groups[index];
    if (current.kind !== "arrange") {
      collapsed.push(current);
      index += 1;
      continue;
    }

    const system = current.entries[0]?.system;
    const arrangeRun = [current];
    index += 1;

    while (
      index < groups.length &&
      groups[index].kind === "arrange" &&
      groups[index].entries[0]?.system === system
    ) {
      arrangeRun.push(groups[index]);
      index += 1;
    }

    collapsed.push(combineArrangeRun(arrangeRun));
  }

  return collapsed;
}

function combineArrangeRun(groups: TimelineStepGroup[]): TimelineStepGroup {
  const firstGroup = groups[0];
  if (!firstGroup) {
    throw new Error("arrange run cannot be empty");
  }

  if (groups.length === 1) {
    return firstGroup;
  }

  const entries = groups.flatMap((group) => group.entries);
  const system = firstGroup.entries[0]?.system ?? "Unknown";

  return {
    entries,
    startedAtMs: firstGroup.startedAtMs,
    endedAtMs: groups[groups.length - 1].endedAtMs,
    kind: "arrange",
    actionLabel: summarizeArrangeAction(system, groups.length, firstGroup.actionLabel),
    displayCount: groups.length,
  };
}

function isArrangeEntryGroup(entries: Entry[]): boolean {
  const first = entries[0];
  if (!first) {
    return false;
  }

  return isArrangeSystem(first.system) && isArrangeAction(first.action);
}

function isArrangeSystem(system: string): boolean {
  return ARRANGE_SYSTEMS.has(system);
}

function isArrangeAction(action: string): boolean {
  return ARRANGE_ACTION_PATTERNS.some((pattern) => pattern.test(action));
}

function nodeIdForSpec(spec: TimelineSpec, index: number): string {
  return spec.kind === "gap" ? `gap-${index}` : `step-${index}`;
}

function getSpecStartedAtMs(spec: TimelineSpec): number {
  return spec.kind === "gap" ? spec.startedAtMs : spec.group.startedAtMs;
}

function getSpecEndedAtMs(spec: TimelineSpec): number {
  return spec.kind === "gap" ? spec.endedAtMs : spec.group.endedAtMs;
}

function summarizeArrangeAction(
  system: string,
  registrationCount: number,
  fallbackAction: string,
): string {
  if (registrationCount <= 1) {
    return fallbackAction;
  }

  if (system === "WireMock" || system === "gRPC Mock") {
    return `Registered ${registrationCount} stubs`;
  }

  return `${registrationCount} setup actions`;
}

function toMs(timestamp: string): number {
  return new Date(timestamp).getTime();
}

const ARRANGE_SYSTEMS = new Set(["WireMock", "gRPC Mock"]);
const ARRANGE_ACTION_PATTERNS = [/^Register stub:/, /^Register .* stub:/];
