import type { RetentionPolicy, RetentionRule, Run } from "../api/types";
import { isRecord } from "../api/validation";
import { matchesMetadata } from "./metadata-filter";

export const defaultRetention: RetentionPolicy = {
  passed_runs_per_app: 3,
  failed_runs_per_app: 5,
  rules: [],
};

export const isRetentionLimit = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= 0;

export function isRetentionRule(value: unknown): value is RetentionRule {
  if (!isRecord(value) || !isRecord(value.metadata)) return false;
  return (
    Object.keys(value).every((key) =>
      ["app_name", "metadata", "passed_runs_per_app", "failed_runs_per_app"].includes(key),
    ) &&
    (value.app_name == null ||
      (typeof value.app_name === "string" && value.app_name.trim() !== "")) &&
    Object.keys(value.metadata).length > 0 &&
    Object.entries(value.metadata).every(
      ([key, values]) =>
        key.trim() !== "" &&
        Array.isArray(values) &&
        values.length > 0 &&
        values.every((v) => typeof v === "string"),
    ) &&
    (value.passed_runs_per_app == null || isRetentionLimit(value.passed_runs_per_app)) &&
    (value.failed_runs_per_app == null || isRetentionLimit(value.failed_runs_per_app)) &&
    (value.passed_runs_per_app != null || value.failed_runs_per_app != null)
  );
}

export function isRetentionPolicy(value: unknown): value is RetentionPolicy {
  return (
    isRecord(value) &&
    Object.keys(value).every((key) =>
      ["passed_runs_per_app", "failed_runs_per_app", "rules"].includes(key),
    ) &&
    isRetentionLimit(value.passed_runs_per_app) &&
    isRetentionLimit(value.failed_runs_per_app) &&
    Array.isArray(value.rules) &&
    value.rules.length <= 100 &&
    value.rules.every(isRetentionRule)
  );
}

/** The request may omit rules, but explicit null and unknown fields are invalid. */
export function parseRetentionPolicy(value: unknown): RetentionPolicy | null {
  if (!isRecord(value)) return null;
  const policy = { ...value, rules: value.rules === undefined ? [] : value.rules };
  return isRetentionPolicy(policy) ? policy : null;
}

type Candidate = Pick<Run, "id" | "status" | "app_name" | "metadata">;

/** Candidates are newest first, with the caller's stable tie-breaker. */
export function expiredRunIds(
  policy: RetentionPolicy,
  candidates: readonly Candidate[],
  protectedRun?: string,
): string[] {
  const counts = new Map<string, number>();
  const expired: string[] = [];
  for (const run of candidates) {
    if (run.status === "RUNNING") continue;
    const bucket = policy.rules.findIndex(
      (rule) =>
        (rule.app_name == null || rule.app_name === run.app_name) &&
        matchesMetadata(run.metadata, rule.metadata),
    );
    const outcome = run.status === "PASSED" ? "passed_runs_per_app" : "failed_runs_per_app";
    const limit = policy.rules[bucket]?.[outcome] ?? policy[outcome];
    if (limit === 0) continue;
    const key = JSON.stringify([run.app_name, bucket, outcome]);
    const count = (counts.get(key) ?? 0) + 1;
    counts.set(key, count);
    if (count > limit && run.id !== protectedRun) expired.push(run.id);
  }
  return expired;
}
