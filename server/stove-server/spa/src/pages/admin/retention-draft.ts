import type { RetentionPolicy, RetentionRule } from "../../api/types";
import { isRetentionLimit, isRetentionPolicy } from "../../utils/retention";

export interface RetentionRuleDraft {
  id: string;
  app_name: string | null;
  metadata: Record<string, string[]>;
  passed_runs_per_app: string;
  failed_runs_per_app: string;
  metadataKey: string;
  metadataValue: string;
}

export interface RetentionDraft {
  passed_runs_per_app: string;
  failed_runs_per_app: string;
  rules: RetentionRuleDraft[];
}

let nextRuleId = 0;

export function createRuleDraft(rule?: RetentionRule): RetentionRuleDraft {
  return {
    id: `retention-rule-${++nextRuleId}`,
    app_name: rule?.app_name ?? null,
    metadata: structuredClone(rule?.metadata ?? {}),
    passed_runs_per_app: rule?.passed_runs_per_app?.toString() ?? "",
    failed_runs_per_app: rule?.failed_runs_per_app?.toString() ?? "",
    metadataKey: "",
    metadataValue: "",
  };
}

export function createRetentionDraft(policy: RetentionPolicy): RetentionDraft {
  return {
    passed_runs_per_app: String(policy.passed_runs_per_app),
    failed_runs_per_app: String(policy.failed_runs_per_app),
    rules: policy.rules.map(createRuleDraft),
  };
}

// Empty optional overrides inherit. Incomplete or unsafe integers stay in the form.
function parseLimit(value: string): number | null | undefined {
  if (value === "") return null;
  if (!/^\d+$/.test(value)) return undefined;
  const limit = Number(value);
  return isRetentionLimit(limit) ? limit : undefined;
}

export function retentionPolicyFromDraft(draft: RetentionDraft): RetentionPolicy | null {
  const passed = parseLimit(draft.passed_runs_per_app);
  const failed = parseLimit(draft.failed_runs_per_app);
  if (passed == null || failed == null) return null;
  const rules: RetentionRule[] = [];
  for (const rule of draft.rules) {
    const passedLimit = parseLimit(rule.passed_runs_per_app);
    const failedLimit = parseLimit(rule.failed_runs_per_app);
    if (passedLimit === undefined || failedLimit === undefined) return null;
    rules.push({
      app_name: rule.app_name,
      metadata: structuredClone(rule.metadata),
      passed_runs_per_app: passedLimit,
      failed_runs_per_app: failedLimit,
    });
  }
  const policy = { passed_runs_per_app: passed, failed_runs_per_app: failed, rules };
  return isRetentionPolicy(policy) ? policy : null;
}
