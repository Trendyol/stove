import type { Run, Test } from "./types";
import {
  isNullableNumber,
  isNullableString,
  isNumber,
  isRunStatus,
  isStatus,
  isString,
  isStringArray,
  isStringRecord,
  object,
} from "./validation";
export const isRun = object<Run>({
  last_activity_at: isString,
  id: isString,
  app_name: isString,
  started_at: isString,
  ended_at: isNullableString,
  status: isRunStatus,
  total_tests: isNumber,
  passed: isNumber,
  failed: isNumber,
  duration_ms: isNullableNumber,
  stove_version: isNullableString,
  systems: isStringArray,
  metadata: isStringRecord,
});

export const isTest = object<Test>({
  id: isString,
  run_id: isString,
  test_name: isString,
  spec_name: isString,
  test_path: isStringArray,
  started_at: isString,
  ended_at: isNullableString,
  status: isStatus,
  duration_ms: isNullableNumber,
  error: isNullableString,
});
