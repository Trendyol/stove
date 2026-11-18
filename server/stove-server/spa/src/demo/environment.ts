import type { ComponentPhase, Run, RunComponent, RunEnvironment } from "../api/types";

export function demoEnvironment(run: Run): RunEnvironment {
  const components = run.systems.map((name, index) => component(run, name, index));
  components.push({
    ...component(run, run.app_name, components.length),
    role: "APPLICATION",
    type: "SpringBootApplication",
  });
  components.push({
    ...component(run, "PostgreSQL [analytics]", components.length),
    type: "PostgreSQLSystem",
    key: "analytics",
    phases: [],
  });
  return { components };
}

function component(run: Run, name: string, index: number): RunComponent {
  const phase = (name: string, duration_ms: number): ComponentPhase => ({
    name,
    status: "SUCCEEDED",
    started_at: run.started_at,
    duration_ms,
    error: "",
  });
  return {
    id: `component-${index + 1}`,
    name,
    type: `${name}System`,
    role: "SYSTEM",
    key: "",
    phases:
      run.status === "RUNNING" ? [phase("START", 240)] : [phase("START", 240), phase("CLOSE", 18)],
  };
}
