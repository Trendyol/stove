export type Status = "RUNNING" | "PASSED" | "FAILED" | "ERROR";

export const isFailed = (s: Status): boolean => s === "FAILED" || s === "ERROR";
export const isRunning = (s: Status): boolean => s === "RUNNING";
export const isPassed = (s: Status): boolean => s === "PASSED";

export function aggregateStatus(statuses: Iterable<Status>): Status {
  let hasPassed = false;
  for (const s of statuses) {
    if (isFailed(s)) return "FAILED";
    if (isRunning(s)) return "RUNNING";
    if (isPassed(s)) hasPassed = true;
  }
  return hasPassed ? "PASSED" : "RUNNING";
}

export function collectStatuses<T>(items: T[], getStatus: (item: T) => Status): Status {
  const statuses = items.map(getStatus);
  return aggregateStatus(statuses);
}
