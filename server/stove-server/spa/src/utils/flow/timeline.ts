import { type Edge, MarkerType, type Node } from "@xyflow/react";
import type { Entry } from "../../api/types";
import { isFailed } from "../result";
import {
  collapseArrangeRuns,
  groupEntriesIntoSteps,
  splitArrangeGroups,
  type TimelineStepGroup,
} from "./grouping";
import type { DurationEdgeData, FlowNodeData, GapNodeData, SystemNodeData } from "./types";

const EXECUTION_GAP_THRESHOLD_MS = 1000;

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

export function entriesToDag(entries: readonly Entry[]): {
  nodes: Node<FlowNodeData>[];
  edges: Edge[];
} {
  if (entries.length === 0) return { nodes: [], edges: [] };
  const sorted = [...entries].sort((left, right) => toMs(left.timestamp) - toMs(right.timestamp));
  const { arrangeGroups, mainGroups } = splitArrangeGroups(
    collapseArrangeRuns(groupEntriesIntoSteps(sorted)),
  );
  const specs = expandTimelineSpecs(mainGroups);
  const timelineNodes = specs.map(timelineNode);
  const arrangeNodes = arrangeGroups.map((group, index) =>
    groupNode(`arrange-step-${index}`, group),
  );
  const firstTimelineNode = timelineNodes[0];
  const arrangeEdges: Edge[] = firstTimelineNode
    ? arrangeNodes.map((node) => ({
        id: `${node.id}-${firstTimelineNode.id}`,
        source: node.id,
        target: firstTimelineNode.id,
        type: "durationEdge",
        markerEnd: { type: MarkerType.ArrowClosed },
        data: { durationMs: 0, label: "ready" } satisfies DurationEdgeData,
      }))
    : [];
  return {
    nodes: [...timelineNodes, ...arrangeNodes],
    edges: [...timelineEdges(specs), ...arrangeEdges],
  };
}

function timelineNode(spec: TimelineSpec, index: number): Node<FlowNodeData> {
  const id = nodeIdForSpec(spec, index);
  if (spec.kind === "step") return groupNode(id, spec.group);
  return {
    id,
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

function groupNode(id: string, group: TimelineStepGroup): Node<FlowNodeData> {
  const first = group.entries[0];
  const last = group.entries[group.entries.length - 1];
  return createSystemNode(id, {
    kind: group.kind,
    system: first.system,
    action: group.actionLabel,
    result: group.entries.some((entry) => isFailed(entry.result)) ? "FAILED" : "PASSED",
    count: group.displayCount,
    error: group.entries.find((entry) => entry.error)?.error ?? null,
    entries: group.entries,
    traceId: group.entries.find((entry) => entry.trace_id)?.trace_id ?? null,
    startedAt: first.timestamp,
    endedAt: last.timestamp,
    durationMs: Math.max(0, group.endedAtMs - group.startedAtMs),
    inspectable: true,
  });
}

function timelineEdges(specs: TimelineSpec[]): Edge[] {
  return specs.slice(1).map((current, offset) => ({
    id: `edge-${offset}-${offset + 1}`,
    source: nodeIdForSpec(specs[offset], offset),
    target: nodeIdForSpec(current, offset + 1),
    type: "durationEdge",
    markerEnd: { type: MarkerType.ArrowClosed },
    data: {
      durationMs: Math.max(0, getSpecStartedAtMs(current) - getSpecEndedAtMs(specs[offset])),
    } satisfies DurationEdgeData,
  }));
}

function createSystemNode(id: string, data: SystemNodeData): Node<FlowNodeData> {
  return {
    id,
    type: "systemNode",
    position: { x: 0, y: 0 },
    data,
  };
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

function nodeIdForSpec(spec: TimelineSpec, index: number): string {
  return spec.kind === "gap" ? `gap-${index}` : `step-${index}`;
}

function getSpecStartedAtMs(spec: TimelineSpec): number {
  return spec.kind === "gap" ? spec.startedAtMs : spec.group.startedAtMs;
}

function getSpecEndedAtMs(spec: TimelineSpec): number {
  return spec.kind === "gap" ? spec.endedAtMs : spec.group.endedAtMs;
}

function toMs(timestamp: string): number {
  return new Date(timestamp).getTime();
}
