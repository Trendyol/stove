import type { ComponentPhase, RunComponent, RunEnvironment } from "./types";
import { arrayOf, isNumber, isString, object } from "./validation";

export const isComponentPhase = object<ComponentPhase>({
  name: isString,
  status: isString,
  started_at: isString,
  duration_ms: isNumber,
  error: isString,
});
export const isRunComponent = object<RunComponent>({
  id: isString,
  name: isString,
  type: isString,
  key: isString,
  role: isString,
  phases: arrayOf(isComponentPhase),
});
export const isRunEnvironment = object<RunEnvironment>({ components: arrayOf(isRunComponent) });
export const environmentKey = (runId: string) => ["environment", runId] as const;
