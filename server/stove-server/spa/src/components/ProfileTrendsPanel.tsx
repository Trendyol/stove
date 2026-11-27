import { skipToken, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../api/client";
import type { ProfileTrendSeries } from "../api/types";
import type { MetadataFilter } from "../utils/metadata-filter";
import {
  formatProfileValue,
  type ProfileSeriesIdentity,
  profileLabel,
  profileSeriesKey,
  shortMethod,
} from "../utils/profiles";
import { Icon } from "./Icon";

const percent = (value: number) => `${value.toFixed(1)}%`;

type InspectProfile = (
  run: ProfileTrendSeries["runs"][number],
  series: ProfileSeriesIdentity,
  trigger: HTMLButtonElement,
) => void;

export function ProfileTrendsPanel({
  appName,
  metadata,
  enabled,
  onInspect,
}: {
  appName: string;
  metadata: MetadataFilter | null;
  enabled: boolean;
  onInspect: InspectProfile;
}) {
  const [seriesId, setSeriesId] = useState("");
  const query = useQuery({
    queryKey: ["profile-trends", appName, metadata],
    queryFn:
      metadata === null ? skipToken : ({ signal }) => api.profileTrends(appName, metadata, signal),
    enabled: enabled && metadata !== null,
  });
  const report = query.data;
  const selected =
    report?.series.find((s) => profileSeriesKey(s) === seriesId) ?? report?.series[0];
  return (
    <section className="profile-hotspots" aria-label="Passing run history">
      <div className="profile-explorer-heading">
        <div>
          <h2>Recurring hotspots</h2>
          <p>Compare passing runs of {appName} that match the dashboard filters.</p>
        </div>
        {metadata !== null && (
          <button
            type="button"
            className="profile-button"
            disabled={query.isFetching}
            onClick={() => void query.refetch()}
          >
            <Icon name="activity" /> Refresh history
          </button>
        )}
      </div>
      <div className="profile-trend-body">
        {metadata === null ? (
          <p>No passing runs match the current filters.</p>
        ) : (
          query.isPending && <p role="status">Loading passing run history…</p>
        )}
        {query.isError && (
          <div className="profile-notice is-error" role="alert">
            {query.error.message}
          </div>
        )}
        {report?.truncated && (
          <div className="profile-notice" role="status">
            Some profiling evidence exceeded the history limits.
          </div>
        )}
        {report && !selected && (
          <p>
            {report.passed_runs_considered === 0
              ? "No passing runs match the current filters."
              : `No usable profiles were found in the recent passing runs. A profile needs at least ${report.min_sample_records} sample records.`}
          </p>
        )}
        {report && selected && (
          <>
            <div className="profile-trend-scope">
              <label>
                Service and profile metric
                <select
                  value={profileSeriesKey(selected)}
                  onChange={(event) => setSeriesId(event.target.value)}
                >
                  {report.series.map((s) => (
                    <option key={profileSeriesKey(s)} value={profileSeriesKey(s)}>
                      {s.service} · {profileLabel(s.metric)} ({s.metric}, {s.unit})
                    </option>
                  ))}
                </select>
              </label>
              <p>
                {selected.runs.length} of {report.passed_runs_considered} recent passing runs have
                usable samples for this metric.
              </p>
            </div>
            <div key={profileSeriesKey(selected)}>
              {selected.runs.length < 2 ? (
                <p>At least two passing runs with usable profiles are needed for a trend.</p>
              ) : (
                <>
                  {!selected.bottlenecks.length && <p>No recurring hotspots in these runs.</p>}
                  <p className="profile-trend-rule">
                    A method is a hotspot at {report.hotspot_threshold_percent}% or more of sampled
                    work, excluding its callees. To recur, it must be hot in at least two runs and
                    at least half of the usable runs.
                  </p>
                  {selected.bottlenecks.map((signal) => (
                    <TrendHotspot
                      key={JSON.stringify([signal.method, signal.file])}
                      signal={signal}
                      series={selected}
                      onInspect={onInspect}
                    />
                  ))}
                  <p className="profile-trend-rule">
                    Compare runs with similar workloads and recording settings before treating a
                    shift in share as a regression.
                  </p>
                </>
              )}
            </div>
          </>
        )}
      </div>
    </section>
  );
}

function TrendHotspot({
  signal,
  series,
  onInspect,
}: {
  signal: ProfileTrendSeries["bottlenecks"][number];
  series: ProfileTrendSeries;
  onInspect: InspectProfile;
}) {
  const { runs, unit, metric } = series;
  const shares = signal.self_percent_by_run;
  const basis =
    metric.endsWith(":cpu") || metric === "go:samples" ? "sampled CPU" : "the profile total";
  const points = shares
    .map((share, i, shares) => `${2 + (96 * i) / (shares.length - 1)},${26 - share * 0.24}`)
    .join(" ");
  return (
    <details className="profile-trend-item">
      <summary>
        <span className="profile-trend-method" title={signal.method}>
          <strong>{shortMethod(signal.method)}</strong>
          <small>{signal.method}</small>
        </span>
        <span>
          <strong>
            {percent(shares[0])} → {percent(shares[shares.length - 1])} of {basis}
          </strong>
          <small>
            Hot in {signal.hot_runs} of {runs.length} runs · Median self share{" "}
            {percent(signal.median_self_percent)}
          </small>
        </span>
        <svg className="profile-trend-chart" aria-hidden="true" viewBox="0 0 100 28">
          <polyline fill="none" stroke="currentColor" strokeWidth="2" points={points} />
        </svg>
        <Icon name="chevron" className="profile-trend-chevron" />
      </summary>
      {signal.file && <p className="profile-trend-file">{signal.file}</p>}
      <div className="profile-table profile-history-table">
        <table>
          <thead>
            <tr>
              <th scope="col">Run (oldest first)</th>
              <th scope="col">Profile total</th>
              <th scope="col">Self share</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((run, index) => (
              <tr key={run.run_id}>
                <th scope="row">
                  <time dateTime={run.started_at}>{new Date(run.started_at).toLocaleString()}</time>
                  <small className="profile-source">{run.run_id}</small>
                  <button
                    type="button"
                    className="profile-button"
                    aria-label={`Inspect profile for run ${run.run_id}`}
                    onClick={(event) => onInspect(run, series, event.currentTarget)}
                  >
                    Inspect profile
                  </button>
                </th>
                <td>{formatProfileValue(run.total, unit)}</td>
                <td>{percent(shares[index])}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}
