import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { api } from "../../api/client";
import { environmentKey } from "../../api/environment";
import type {
  ComponentPhase,
  RunEnvironment as Environment,
  Run,
  RunComponent,
} from "../../api/types";
import { formatDuration } from "../../utils/format";

export function RunEnvironment({ run }: { run: Run }) {
  const query = useQuery({
    queryKey: environmentKey(run.id),
    queryFn: ({ signal }) => api.getRunEnvironment(run.id, signal),
  });
  return (
    <details className="stove-run-details stove-run-environment" open>
      <summary>Run environment{query.data ? ` · ${query.data.components.length}` : ""}</summary>
      <p>Registered components. Captured activity is shown in the tests.</p>
      <EnvironmentContents query={query} systems={run.systems} />
    </details>
  );
}

function EnvironmentContents({
  query,
  systems,
}: {
  query: UseQueryResult<Environment | null>;
  systems: string[];
}) {
  if (query.isPending) return <p>Loading environment…</p>;
  if (query.isError) return <p role="alert">Could not load the run environment.</p>;
  if (!query.data) return <LegacyEnvironment systems={systems} />;
  return (
    <ul>
      {query.data.components.map((component) => (
        <Component key={component.id} component={component} />
      ))}
    </ul>
  );
}

function Component({ component }: { component: RunComponent }) {
  return (
    <li>
      <details>
        <summary>
          <strong>{component.name}</strong>
          <small>{component.role === "APPLICATION" ? "AUT" : "System"}</small>
        </summary>
        {component.key && <p>Registration key: {component.key}</p>}
        <p className="stove-component-type">{component.type}</p>
        {component.phases.length ? (
          <ul>
            {component.phases.map((phase) => (
              <Phase key={phase.name} phase={phase} />
            ))}
          </ul>
        ) : (
          <p>No lifecycle phases observed.</p>
        )}
      </details>
    </li>
  );
}

function Phase({ phase }: { phase: ComponentPhase }) {
  return (
    <li className="stove-component-phase" data-status={phase.status}>
      <span>{phase.name.toLowerCase().replace(/_/g, " ")}</span>
      <span>
        {phase.status.toLowerCase()} · {formatDuration(phase.duration_ms)}
      </span>
      {phase.error && <pre>{phase.error}</pre>}
    </li>
  );
}

function LegacyEnvironment({ systems }: { systems: string[] }) {
  return (
    <>
      <p>Full environment not reported by this client.</p>
      {systems.length > 0 && (
        <ul>
          {systems.map((name, index) => (
            <li key={`${name}-${index}`}>{name}</li>
          ))}
        </ul>
      )}
    </>
  );
}
