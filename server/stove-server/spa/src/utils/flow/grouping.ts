import type { Entry } from "../../api/types";

const ADJACENT_MERGE_WINDOW_MS = 250;

export interface TimelineStepGroup {
  entries: Entry[];
  startedAtMs: number;
  endedAtMs: number;
  kind: "step" | "arrange";
  actionLabel: string;
  displayCount: number;
}

export function groupEntriesIntoSteps(entries: Entry[]): TimelineStepGroup[] {
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

export function splitArrangeGroups(groups: TimelineStepGroup[]): {
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

export function collapseArrangeRuns(groups: TimelineStepGroup[]): TimelineStepGroup[] {
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
