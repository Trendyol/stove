import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api/client";
import type { ProfileSeries, Run, Test } from "../api/types";
import {
  formatProfileValue,
  frameKey,
  type ProfileFrame,
  profileLabel,
  profileOrder,
  shortMethod,
} from "../utils/profiles";
import { Icon } from "./Icon";
import { ProfileFlameGraph } from "./ProfileFlameGraph";

export function DrillDownPanel({
  run,
  tests,
  onClose,
}: {
  run: Run;
  tests: Test[];
  onClose: () => void;
}) {
  const [service, setService] = useState("");
  const [metric, setMetric] = useState("");
  const [testId, setTestId] = useState("");
  const testWindow = tests.find((t) => t.id === testId);
  const parameters = new URLSearchParams();
  if (testWindow) {
    parameters.set("from_ms", String(Date.parse(testWindow.started_at)));
    if (testWindow.ended_at) parameters.set("until_ms", String(Date.parse(testWindow.ended_at)));
  }
  const query = useQuery({
    queryKey: ["profiles", run.id, parameters.toString()],
    queryFn: ({ signal }) => api.profiles(run.id, parameters, signal),
    refetchInterval: run.status === "RUNNING" ? 5000 : false,
  });
  const series = query.data?.series ?? [];
  const services = [...new Set(series.map((s) => s.service))].sort();
  const activeService = services.includes(service) ? service : (services[0] ?? "");
  const available = series
    .filter((s) => s.service === activeService)
    .sort((a, b) => profileOrder(a) - profileOrder(b) || a.metric.localeCompare(b.metric));
  const selected = available.find((s) => s.metric === metric) ?? available[0];
  const live = run.status === "RUNNING";
  return (
    <section className="test-detail profiles-panel" aria-label="Drill Down">
      <header className="profiles-header">
        <div>
          <div className="stove-kicker">Performance evidence</div>
          <h1>
            Drill Down{" "}
            <span className="profile-status" data-live={live}>
              {live ? "Collecting" : "Run complete"}
            </span>
          </h1>
          <p>Find where your application spends time and allocates memory.</p>
        </div>
        <button type="button" className="profile-button" onClick={onClose}>
          Back to tests
        </button>
      </header>
      <div className="profile-scope">
        {services.length > 1 && (
          <label>
            Service
            <select
              value={activeService}
              onChange={(e) => {
                setService(e.target.value);
                setMetric("");
              }}
            >
              {services.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
        )}
        <div className="profile-window-controls">
          <label>
            Recording window
            <select value={testId} onChange={(e) => setTestId(e.target.value)}>
              <option value="">Entire run</option>
              {tests.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.test_name}
                </option>
              ))}
            </select>
          </label>
          <div className="profile-refresh">
            <button
              type="button"
              className="profile-button"
              disabled={query.isFetching}
              onClick={() => void query.refetch()}
            >
              <Icon name="activity" /> Refresh
            </button>
            {live && <span>Updates every 5s</span>}
          </div>
        </div>
      </div>
      {testWindow && (
        <p className="profile-scope-note">
          Showing recordings that overlap this test, including other threads and background work.
        </p>
      )}
      {query.isError && (
        <div className="profile-notice is-error" role="alert">
          {query.error.message}
        </div>
      )}
      {query.data?.truncated && (
        <div className="profile-notice" role="status">
          Only the newest recordings fit this view. Select a narrower recording window for more
          detail.
        </div>
      )}
      {query.isPending && (
        <div className="profile-empty" role="status">
          <Icon name="activity" />
          <h2>Loading profiling evidence</h2>
          <p>Preparing call stacks and method statistics…</p>
        </div>
      )}
      {!query.isPending && !query.isError && !selected && (
        <div className="profile-empty">
          <Icon name="activity" />
          <h2>{testWindow ? "No recordings overlap this test" : "No profiles received yet"}</h2>
          {testWindow ? (
            <>
              <p>Try the entire run to explore all available profiling evidence.</p>
              <button type="button" className="profile-button" onClick={() => setTestId("")}>
                Show entire run
              </button>
            </>
          ) : (
            <>
              <p>
                {live
                  ? "Profiles appear after the first upload or when the run finishes. This page updates automatically."
                  : "Profiling must be enabled before the test run starts. Enable it in your test project, then run the tests again."}
              </p>
              <details>
                <summary>Profiling setup</summary>
                <p>JVM applications running inside the test process:</p>
                <pre>
                  {
                    "stoveTracing {\n  profiling {\n    enabled.set(true)\n    allocations.set(true)\n  }\n}"
                  }
                </pre>
                <p>
                  Run JVM tests through Gradle with the Stove dashboard integration. For Go, start
                  stove-profiler in the application and map the dashboard’s external profiling
                  configuration into its environment. Check application output for upload errors.
                </p>
              </details>
            </>
          )}
        </div>
      )}
      {selected && (
        <>
          <fieldset className="profile-metrics" aria-label="Profile type">
            {available.map((s) => (
              <button
                type="button"
                key={s.metric}
                aria-pressed={s.metric === selected.metric}
                onClick={() => setMetric(s.metric)}
                title={s.metric}
              >
                <span>{profileLabel(s.metric)}</span>{" "}
                <strong>{formatProfileValue(s.total, s.unit)}</strong>
              </button>
            ))}
          </fieldset>
          <ProfileDetail
            key={`${selected.service}:${selected.metric}:${testId}`}
            series={selected}
          />
        </>
      )}
      {query.data && (
        <details className="profile-interpretation">
          <summary>About sampled evidence</summary>
          <p>{query.data.interpretation}</p>
        </details>
      )}
    </section>
  );
}

function ProfileDetail({ series }: { series: ProfileSeries }) {
  const [search, setSearch] = useState("");
  const [method, setMethod] = useState<ProfileFrame>();
  const [sort, setSort] = useState<"self" | "inclusive">("self");
  const needle = search.trim().toLowerCase();
  const hotspots = series.hotspots
    .filter((h) => `${h.frame.name} ${h.frame.file}`.toLowerCase().includes(needle))
    .sort((a, b) =>
      sort === "self" ? b.self_value - a.self_value : b.inclusive_value - a.inclusive_value,
    );
  const shown = series.stacks.reduce((total, s) => total + s.value, 0);
  const coverage = series.total > 0 ? Math.min(100, (shown / series.total) * 100) : 0;
  const duration = Math.max(0, series.until_ms - series.from_ms);
  return (
    <div className="profile-detail">
      <div className="profile-summary">
        <div>
          <span>Recording duration</span>
          <strong>{formatProfileValue(duration * 1e6, "nanoseconds")}</strong>
          <small>
            {new Date(series.from_ms).toLocaleTimeString()} –{" "}
            {new Date(series.until_ms).toLocaleTimeString()}
          </small>
        </div>
        <div>
          <span>Stack records</span>
          <strong>{series.sample_records.toLocaleString()}</strong>
          <small>Aggregated samples · {series.sample_rates.join(", ")} Hz configured</small>
        </div>
        <div>
          <span>Graph coverage</span>
          <strong>
            {coverage.toFixed(1)}
            <em>%</em>
          </strong>
          <small>Of total sampled {series.unit === "nanoseconds" ? "time" : series.unit}</small>
        </div>
      </div>
      <div className="profile-explorer">
        <div className="profile-explorer-heading">
          <div>
            <h2>Explore call stacks</h2>
            <p>Follow wide frames to find where work accumulates.</p>
          </div>
          <span className="profile-tag">Flame graph</span>
        </div>
        <div className="profile-search-row">
          <label className="profile-search">
            <Icon name="search" />
            <input
              type="search"
              aria-label="Search methods"
              placeholder="Find a method or source file…"
              value={search}
              onChange={(e) => {
                setSearch(e.target.value);
                setMethod(undefined);
              }}
            />
          </label>
          {(search || method) && (
            <button
              className="profile-button"
              type="button"
              onClick={() => {
                setSearch("");
                setMethod(undefined);
              }}
            >
              Clear selection
            </button>
          )}
        </div>
        <ProfileFlameGraph series={series} search={search} selectedFrame={method} />
        {series.omitted_stack_value > 0 && (
          <p className="profile-graph-note">
            Showing {series.stacks.length} stacks covering {coverage.toFixed(1)}% of the total.{" "}
            {formatProfileValue(series.omitted_stack_value, series.unit)} in other stacks is not
            drawn; method totals below include it.
          </p>
        )}
      </div>
      <div className="profile-hotspots">
        <div className="profile-explorer-heading">
          <div>
            <h2>
              Hottest methods <span className="profile-count">{hotspots.length}</span>
            </h2>
            <p>Select a method to highlight its occurrences in the graph.</p>
          </div>
          <label className="profile-sort">
            Rank by
            <select value={sort} onChange={(e) => setSort(e.target.value as "self" | "inclusive")}>
              <option value="self">Self cost</option>
              <option value="inclusive">Including callees</option>
            </select>
          </label>
        </div>
        <div className="profile-table">
          <table>
            <thead>
              <tr>
                <th>Method</th>
                <th title="Work in this method, excluding calls it makes">Self</th>
                <th title="Work in this method and its callees">Total</th>
                <th>Self share</th>
              </tr>
            </thead>
            <tbody>
              {hotspots.map((h) => (
                <tr
                  key={frameKey(h.frame)}
                  data-selected={method && frameKey(method) === frameKey(h.frame)}
                >
                  <td>
                    <button
                      className="profile-method"
                      type="button"
                      aria-pressed={!!method && frameKey(method) === frameKey(h.frame)}
                      onClick={() => setMethod(h.frame)}
                      title={h.frame.name}
                    >
                      <strong>{shortMethod(h.frame.name)}</strong>
                      <span>{h.frame.name}</span>
                    </button>
                    {h.frame.file && (
                      <small className="profile-source">
                        {h.frame.file}
                        {h.frame.line > 0 && `:${h.frame.line}`}
                      </small>
                    )}
                  </td>
                  <td>{formatProfileValue(h.self_value, series.unit)}</td>
                  <td>{formatProfileValue(h.inclusive_value, series.unit)}</td>
                  <td>
                    <div className="profile-share">
                      <span>{h.self_percent.toFixed(1)}%</span>
                      <div>
                        <i style={{ width: `${Math.max(0, Math.min(100, h.self_percent))}%` }} />
                      </div>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!hotspots.length && (
          <p className="profile-no-matches">
            No ranked methods match “{search}”. Search also highlights matching frames in the graph.
          </p>
        )}
      </div>
      {series.findings.length > 0 && (
        <div className="profile-insights">
          <h2>Reading this profile</h2>
          {series.findings.map((f, i) => (
            <article key={`${f.kind}:${i}`}>
              <Icon name={f.kind === "hotspot" ? "activity" : "warning"} />
              <div>
                <strong>{f.kind === "hotspot" ? "Concentrated work" : "Limited coverage"}</strong>
                <p>{f.evidence}</p>
                <p className="profile-muted">{f.suggestion}</p>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
