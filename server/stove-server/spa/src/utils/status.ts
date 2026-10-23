import type { components } from "../api/generated/schema";

export type Status = components["schemas"]["TestStatus"];

export const isFailed = (s: Status): boolean => s === "FAILED" || s === "ERROR";
export const isRunning = (s: Status): boolean => s === "RUNNING";
export const isPassed = (s: Status): boolean => s === "PASSED";

export function aggregateStatus(statuses: Iterable<Status>): Status {
  let hasPassed = false;
  let hasRunning = false;
  for (const s of statuses) {
    if (isFailed(s)) return "FAILED";
    if (isRunning(s)) hasRunning = true;
    if (isPassed(s)) hasPassed = true;
  }
  return hasPassed && !hasRunning ? "PASSED" : "RUNNING";
}
