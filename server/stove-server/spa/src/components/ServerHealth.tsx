import { useQuery } from "@tanstack/react-query";
import { useId } from "react";
import { api } from "../api/client";
import { isDemo } from "../utils/demo-mode";

export function ServerHealth({ liveConnected }: { liveConnected: boolean }) {
  const id = useId();
  const health = useQuery({
    queryKey: ["server-health"],
    queryFn: ({ signal }) => api.getHealth(signal),
    enabled: !isDemo,
    refetchInterval: 15_000,
    retry: false,
  });
  // A failed refresh must not leave a previously healthy report on screen.
  const report = health.isError ? undefined : health.data;
  const healthy = !isDemo && report?.healthy === true && liveConnected;
  const label = healthLabel(health.isError, report?.healthy, liveConnected);
  const summary = healthSummary(health.isError, Boolean(report), healthy);

  return (
    <>
      <button
        type="button"
        className={`stove-stream-status stove-focus-ring ${healthy || isDemo ? "is-live" : "is-polling"}`}
        popoverTarget={id}
        title="View server health"
      >
        <span aria-hidden="true" />
        {label}
      </button>
      <section
        id={id}
        popover="auto"
        className="stove-health-popover"
        aria-label="Server health"
        onToggle={(event) => {
          if (event.newState === "open" && !isDemo) void health.refetch();
        }}
      >
        <div className="stove-health-heading">
          <strong>Server health</strong>
          <button
            type="button"
            className="stove-topbar-link stove-focus-ring"
            popoverTarget={id}
            popoverTargetAction="hide"
            aria-label="Close server health"
          >
            ×
          </button>
        </div>
        {isDemo ? (
          <p>
            This demo uses sample data. Server health is available when connected to your own Stove
            server.
          </p>
        ) : (
          <>
            <p className="stove-health-summary" role="status">
              {summary}
            </p>
            <dl className="stove-health-checks">
              {report?.checks.map((check) => (
                <HealthRow
                  key={check.name}
                  name={check.name}
                  healthy={check.healthy}
                  message={check.message}
                />
              ))}
              <HealthRow
                name="Live updates"
                healthy={liveConnected}
                message={
                  liveConnected
                    ? "Live event stream is connected."
                    : "Live event stream is disconnected. Reconnecting; dashboard data is refreshed by polling."
                }
              />
            </dl>
            {report ? (
              <p className="stove-health-meta">
                Server v{report.stove_server_version} · {report.backend}
                <br />
                Checked at {new Date(report.checked_at).toLocaleTimeString()}
              </p>
            ) : null}
            <button
              type="button"
              className="stove-topbar-link stove-focus-ring"
              disabled={health.isFetching}
              onClick={() => void health.refetch()}
            >
              {health.isFetching ? "Checking…" : "Check again"}
            </button>
          </>
        )}
      </section>
    </>
  );
}

function HealthRow({
  name,
  healthy,
  message,
}: {
  name: string;
  healthy: boolean;
  message: string;
}) {
  return (
    <div>
      <dt>
        {name}
        <span className={healthy ? "is-healthy" : "is-unhealthy"}>
          {healthy ? "OK" : "Needs attention"}
        </span>
      </dt>
      <dd>{message}</dd>
    </div>
  );
}

function healthLabel(
  failed: boolean,
  reportHealthy: boolean | undefined,
  connected: boolean,
): string {
  if (isDemo) return "Demo data";
  if (failed) return "Health unavailable";
  if (reportHealthy === false) return "Needs attention";
  if (!connected) return "Reconnecting";
  if (reportHealthy === undefined) return "Checking health";
  return "Connected";
}

function healthSummary(failed: boolean, hasReport: boolean, healthy: boolean): string {
  if (failed) return "Unable to check server health. Check your connection and try again.";
  if (!hasReport) return "Checking server health…";
  if (healthy) return "All checks passed.";
  return "Some checks need attention.";
}
