pub mod support;

use std::collections::{BTreeMap, HashSet};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, ensure};
use reqwest::Client;
use serde_json::{Value, json};

use support::{PostgresTestDatabase, RunningStove};

const DEFAULT_RUNS: usize = 50_000;
const DEFAULT_REQUESTS: usize = 120;
const DEFAULT_CONCURRENCY: usize = 12;
const DEFAULT_P95_BUDGET_MS: u128 = 2_000;
const FILTERED_RUN_ID: &str = "load-run-4242";

#[derive(Clone, Copy)]
struct LoadConfig {
  runs: usize,
  requests: usize,
  concurrency: usize,
  p95_budget_ms: u128,
}

impl LoadConfig {
  fn from_environment() -> Self {
    let requests = setting("STOVE_LOAD_TEST_REQUESTS", DEFAULT_REQUESTS).max(1);
    Self {
      runs: setting("STOVE_LOAD_TEST_RUNS", DEFAULT_RUNS).max(10_000),
      requests,
      concurrency: setting("STOVE_LOAD_TEST_CONCURRENCY", DEFAULT_CONCURRENCY)
        .max(1)
        .min(requests),
      p95_budget_ms: setting_u128("STOVE_LOAD_TEST_P95_MS", DEFAULT_P95_BUDGET_MS),
    }
  }
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
enum QueryKind {
  RestMetadataFilter,
  DashboardApps,
  DashboardRuns,
  AdminStatus,
  DashboardSpa,
  McpMetadataFilter,
  MetadataFacets,
  MultiValueMetadata,
  TestPages,
  McpFailures,
  McpApps,
  McpDiagnosis,
}

impl QueryKind {
  fn for_request(request: usize) -> Self {
    match request % 12 {
      0 => Self::RestMetadataFilter,
      1 => Self::DashboardApps,
      2 => Self::DashboardRuns,
      3 => Self::AdminStatus,
      4 => Self::DashboardSpa,
      5 => Self::McpMetadataFilter,
      6 => Self::MetadataFacets,
      7 => Self::MultiValueMetadata,
      8 => Self::TestPages,
      9 => Self::McpFailures,
      10 => Self::McpApps,
      _ => Self::McpDiagnosis,
    }
  }

  const fn label(self) -> &'static str {
    match self {
      Self::RestMetadataFilter => "rest_metadata_filter",
      Self::DashboardApps => "dashboard_apps",
      Self::DashboardRuns => "dashboard_runs",
      Self::AdminStatus => "admin_status",
      Self::DashboardSpa => "dashboard_spa",
      Self::McpMetadataFilter => "mcp_metadata_filter",
      Self::MetadataFacets => "metadata_facets",
      Self::MultiValueMetadata => "multi_value_metadata",
      Self::TestPages => "test_pages",
      Self::McpFailures => "mcp_failures",
      Self::McpApps => "mcp_apps",
      Self::McpDiagnosis => "mcp_diagnosis",
    }
  }
}

struct Measurement {
  kind: QueryKind,
  latency: Duration,
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn postgres_metadata_queries_and_dashboard_reads_remain_responsive_under_load() -> Result<()>
{
  let config = LoadConfig::from_environment();
  let database = PostgresTestDatabase::start().await?;
  let stove = RunningStove::start_postgres(&database.url, Some(0)).await?;
  seed_and_verify_database(&database, config.runs).await?;
  verify_all_pages(&stove, config.runs / 10).await?;
  let (measurements, elapsed) = execute_load(&stove, config).await?;
  verify_measurements(&measurements, elapsed, config)?;
  Ok(())
}

async fn verify_all_pages(stove: &RunningStove, expected: usize) -> Result<()> {
  let mut cursor: Option<String> = None;
  let mut ids = HashSet::new();
  loop {
    let mut url = reqwest::Url::parse(&format!("{}/api/v1/runs", stove.base_url))?;
    url
      .query_pairs_mut()
      .append_pair("app", "load-app-0")
      .append_pair("limit", "100");
    if let Some(token) = &cursor {
      url.query_pairs_mut().append_pair("cursor", token);
    }
    let page = get_json(&stove.client, url).await?;
    for run in page["runs"].as_array().context("page runs")? {
      ensure!(
        ids.insert(run["id"].as_str().context("run id")?.to_owned()),
        "duplicate run across pages"
      );
    }
    cursor = page["next_cursor"].as_str().map(ToOwned::to_owned);
    if cursor.is_none() {
      break;
    }
  }
  ensure!(
    ids.len() == expected,
    "walked {} runs, expected {expected}",
    ids.len()
  );
  Ok(())
}

async fn seed_and_verify_database(database: &PostgresTestDatabase, run_count: usize) -> Result<()> {
  database
    .with_client(move |postgres| {
      seed_runs(postgres, run_count)?;
      verify_query_plans(postgres)
    })
    .await
}

async fn execute_load(
  stove: &RunningStove,
  config: LoadConfig,
) -> Result<(Vec<Measurement>, Duration)> {
  let next_request = Arc::new(AtomicUsize::new(0));
  let mut workers = tokio::task::JoinSet::new();
  for _ in 0..config.concurrency {
    let next_request = Arc::clone(&next_request);
    let client = stove.client.clone();
    let base_url = stove.base_url.clone();
    workers.spawn(async move {
      let mut measurements = Vec::new();
      loop {
        let request = next_request.fetch_add(1, Ordering::Relaxed);
        if request >= config.requests {
          break;
        }
        measurements.push(run_query(&client, &base_url, request, config.runs).await?);
      }
      Result::<Vec<Measurement>>::Ok(measurements)
    });
  }

  let started = Instant::now();
  let mut measurements = Vec::with_capacity(config.requests);
  while let Some(result) = workers.join_next().await {
    measurements.extend(result.context("join load-test worker")??);
  }
  Ok((measurements, started.elapsed()))
}

fn verify_measurements(
  measurements: &[Measurement],
  elapsed: Duration,
  config: LoadConfig,
) -> Result<()> {
  ensure!(
    measurements.len() == config.requests,
    "completed {} of {} load-test requests",
    measurements.len(),
    config.requests,
  );
  let all_latencies = measurements
    .iter()
    .map(|measurement| measurement.latency)
    .collect::<Vec<_>>();
  // Report every slice before failing so a budget regression names the endpoint
  // that caused it instead of only the aggregate.
  let mut violations = Vec::new();
  report_latencies("all", &all_latencies, config.p95_budget_ms, &mut violations);
  eprintln!(
    "PostgreSQL load test: runs={}, requests={}, concurrency={}, elapsed_ms={}",
    config.runs,
    config.requests,
    config.concurrency,
    elapsed.as_millis(),
  );

  let mut by_kind = BTreeMap::<QueryKind, Vec<Duration>>::new();
  for measurement in measurements {
    by_kind
      .entry(measurement.kind)
      .or_default()
      .push(measurement.latency);
  }
  for (kind, durations) in &by_kind {
    report_latencies(
      kind.label(),
      durations,
      config.p95_budget_ms,
      &mut violations,
    );
  }
  ensure!(
    violations.is_empty(),
    "load-test latency budget of {} ms exceeded: {}",
    config.p95_budget_ms,
    violations.join("; "),
  );
  Ok(())
}

fn report_latencies(
  kind: &str,
  durations: &[Duration],
  budget_ms: u128,
  violations: &mut Vec<String>,
) {
  if durations.is_empty() {
    return;
  }
  let mut sorted = durations.to_vec();
  sorted.sort_unstable();
  let p95 = percentile(&sorted, 95);
  let slowest = sorted[sorted.len() - 1];
  eprintln!(
    "  {kind}: requests={}, p95_ms={}, max_ms={}",
    sorted.len(),
    p95.as_millis(),
    slowest.as_millis(),
  );
  if p95.as_millis() > budget_ms {
    violations.push(format!(
      "{kind} p95 latency {} ms exceeded the {budget_ms} ms budget",
      p95.as_millis(),
    ));
  }
}

fn verify_query_plans(postgres: &mut postgres::Client) -> Result<()> {
  let (metadata_plan, dashboard_plan) = explain_query_plans(postgres)?;
  ensure!(
    metadata_plan.contains("idx_runs_metadata"),
    "PostgreSQL did not select idx_runs_metadata for a selective JSONB query:\n{metadata_plan}"
  );
  ensure!(
    metadata_plan.contains("Bitmap Index Scan"),
    "expected a bitmap index scan for the selective JSONB query:\n{metadata_plan}"
  );
  ensure!(
    (dashboard_plan.contains("idx_runs_app_started_at")
      || dashboard_plan.contains("idx_runs_started_at_id"))
      && dashboard_plan.contains("Index Cond:")
      && !dashboard_plan.contains("Sort"),
    "PostgreSQL did not use an ordered keyset index scan for the dashboard query:\n{dashboard_plan}"
  );
  Ok(())
}

fn seed_runs(postgres: &mut postgres::Client, run_count: usize) -> Result<()> {
  let run_count = i64::try_from(run_count).context("load-test run count exceeds i64")?;
  let inserted = postgres.execute(
    r#"
      INSERT INTO runs (
        id, app_name, started_at, ended_at, status, total_tests, passed, failed,
        duration_ms, systems, stove_version, metadata
      )
      SELECT
        'load-run-' || value,
        'load-app-' || (value % 10),
        to_char(
          TIMESTAMPTZ '2024-01-01T00:00:00Z' + value * INTERVAL '1 second',
          'YYYY-MM-DD"T"HH24:MI:SS"Z"'
        ),
        to_char(
          TIMESTAMPTZ '2024-01-01T00:00:00Z' + value * INTERVAL '1 second',
          'YYYY-MM-DD"T"HH24:MI:SS"Z"'
        ),
        'PASSED', 1, 1, 0, 100, '["HTTP"]', 'load-test',
        jsonb_build_object(
          'team', 'team-' || (value % 100),
          'tribe', 'tribe-' || (value % 10),
          'gitlab.pipeline_id', value::text,
          'project', 'project-' || (value % 50)
        )
      FROM generate_series(1::bigint, $1::bigint) AS series(value)
    "#,
    &[&run_count],
  )?;
  ensure!(inserted == run_count as u64, "seeded {inserted} runs");
  postgres.batch_execute(r#"
    INSERT INTO tests (run_id, id, test_name, spec_name, test_path, started_at, status)
    SELECT 'load-run-4242', 'test-' || lpad(value::text, 4, '0'), 'test ' || value, 'LoadSpec', '["load"]',
      '2024-01-01T00:00:00Z', CASE WHEN value % 2 = 0 THEN 'FAILED' ELSE 'PASSED' END
    FROM generate_series(1, 2000) value;
    UPDATE runs SET status = 'FAILED', total_tests = 2000, failed = 1000, passed = 1000 WHERE id = 'load-run-4242';
    ANALYZE runs;
    ANALYZE tests;
  "#)?;
  Ok(())
}

fn explain_query_plans(postgres: &mut postgres::Client) -> Result<(String, String)> {
  let metadata_rows = postgres.query(
    "EXPLAIN SELECT id FROM runs WHERE metadata @> $1::text::jsonb",
    &[&r#"{"team":"team-42","gitlab.pipeline_id":"4242"}"#],
  )?;
  let dashboard_rows = postgres.query(
    "EXPLAIN SELECT id, app_name, started_at, status, metadata
       FROM runs
      WHERE app_name = $1
        AND (started_at, id) < ($2, $3)
      ORDER BY started_at DESC, id DESC LIMIT 51",
    &[&"load-app-2", &"2024-01-01T06:00:00Z", &"load-run-21600"],
  )?;
  Ok((format_plan(metadata_rows), format_plan(dashboard_rows)))
}

fn format_plan(rows: Vec<postgres::Row>) -> String {
  rows
    .into_iter()
    .map(|row| row.get::<_, String>(0))
    .collect::<Vec<_>>()
    .join("\n")
}

async fn run_query(
  client: &Client,
  base_url: &str,
  request: usize,
  run_count: usize,
) -> Result<Measurement> {
  let started = Instant::now();
  let kind = QueryKind::for_request(request);
  execute_query(kind, client, base_url, request, run_count).await?;
  Ok(Measurement {
    kind,
    latency: started.elapsed(),
  })
}

async fn execute_query(
  kind: QueryKind,
  client: &Client,
  base_url: &str,
  request: usize,
  run_count: usize,
) -> Result<()> {
  match kind {
    QueryKind::TestPages => assert_test_pages(client, base_url).await,
    QueryKind::McpFailures => {
      assert_mcp_survey(client, base_url, "stove_failures", json!({"limit":20})).await
    }
    QueryKind::McpApps => assert_mcp_survey(client, base_url, "stove_apps", json!({})).await,
    QueryKind::McpDiagnosis => {
      assert_mcp_survey(
        client,
        base_url,
        "stove_diagnose",
        json!({"app_name":"load-app-2", "metadata":{"team":"team-42"}}),
      )
      .await
    }
    QueryKind::MetadataFacets => assert_metadata_facets(client, base_url, run_count).await,
    QueryKind::MultiValueMetadata => assert_multi_value_metadata(client, base_url).await,
    QueryKind::RestMetadataFilter => assert_rest_metadata_filter(client, base_url).await,
    QueryKind::DashboardApps => assert_dashboard_apps(client, base_url).await,
    QueryKind::DashboardRuns => assert_dashboard_runs(client, base_url, run_count).await,
    QueryKind::AdminStatus => assert_admin_status(client, base_url, run_count).await,
    QueryKind::DashboardSpa => assert_dashboard_spa(client, base_url).await,
    QueryKind::McpMetadataFilter => assert_mcp_metadata_filter(client, base_url, request).await,
  }
}

async fn assert_rest_metadata_filter(client: &Client, base_url: &str) -> Result<()> {
  let mut url = reqwest::Url::parse(&format!("{base_url}/api/v1/runs"))?;
  url.query_pairs_mut().append_pair(
    "metadata",
    r#"{"team":"team-42","gitlab.pipeline_id":"4242"}"#,
  );
  let runs = get_json(client, url).await?;
  ensure!(has_single_run(&runs["runs"], "id"));
  Ok(())
}

async fn assert_dashboard_apps(client: &Client, base_url: &str) -> Result<()> {
  let apps = get_json(client, format!("{base_url}/api/v1/apps")).await?;
  ensure!(apps.as_array().is_some_and(|apps| apps.len() == 10));
  Ok(())
}

async fn assert_dashboard_runs(client: &Client, base_url: &str, run_count: usize) -> Result<()> {
  #[derive(serde::Deserialize)]
  struct Page {
    runs: Vec<serde::de::IgnoredAny>,
    next_cursor: Option<String>,
  }
  let page: Page = client
    .get(format!("{base_url}/api/v1/runs?app=load-app-0"))
    .send()
    .await?
    .error_for_status()?
    .json()
    .await?;
  ensure!(page.runs.len() == (run_count / 10).min(50));
  ensure!(page.next_cursor.is_some() == (run_count / 10 > 50));
  Ok(())
}

async fn assert_metadata_facets(client: &Client, base_url: &str, run_count: usize) -> Result<()> {
  let facets = get_json(
    client,
    format!("{base_url}/api/v1/runs/metadata?app=load-app-0"),
  )
  .await?;
  let pipeline = facets
    .as_array()
    .context("metadata facets")?
    .iter()
    .find(|facet| facet["key"] == "gitlab.pipeline_id")
    .context("pipeline facet")?;
  ensure!(pipeline["values"].as_array().context("facet values")?.len() == run_count / 10);
  Ok(())
}

async fn assert_multi_value_metadata(client: &Client, base_url: &str) -> Result<()> {
  let mut url = reqwest::Url::parse(&format!("{base_url}/api/v1/runs"))?;
  // A sparse OR selector without a single-value predicate must still find the
  // matching rows before applying the page limit.
  url
    .query_pairs_mut()
    .append_pair("metadata", r#"{"gitlab.pipeline_id":["4242","8484"]}"#);
  let page = get_json(client, url).await?;
  let runs = page["runs"].as_array().context("multi-value runs")?;
  ensure!(runs.len() == 2);
  ensure!(runs[0]["id"] == "load-run-8484" && runs[1]["id"] == "load-run-4242");
  ensure!(page["next_cursor"].is_null());
  Ok(())
}

async fn assert_admin_status(client: &Client, base_url: &str, run_count: usize) -> Result<()> {
  let status = get_json(client, format!("{base_url}/api/v1/admin/status")).await?;
  ensure!(status["backend"] == "postgresql");
  ensure!(status["runs"] == run_count);
  Ok(())
}

async fn assert_dashboard_spa(client: &Client, base_url: &str) -> Result<()> {
  let html = client
    .get(base_url)
    .send()
    .await?
    .error_for_status()?
    .text()
    .await?;
  ensure!(html.contains("<div id=\"root\"></div>"));
  Ok(())
}

async fn assert_mcp_metadata_filter(client: &Client, base_url: &str, request: usize) -> Result<()> {
  let response = client
    .post(format!("{base_url}/mcp"))
    .header(
      reqwest::header::ACCEPT,
      "application/json, text/event-stream",
    )
    .json(&json!({
      "jsonrpc": "2.0",
      "id": request,
      "method": "tools/call",
      "params": {
        "name": "stove_runs",
        "arguments": {
          "metadata": {"team": "team-42", "gitlab.pipeline_id": "4242"}
        }
      }
    }))
    .send()
    .await?
    .error_for_status()?
    .json::<Value>()
    .await?;
  ensure!(has_single_run(
    &response["result"]["structuredContent"]["runs"],
    "run_id",
  ));
  Ok(())
}

fn has_single_run(value: &Value, id_field: &str) -> bool {
  value
    .as_array()
    .is_some_and(|runs| runs.len() == 1 && runs[0][id_field] == FILTERED_RUN_ID)
}

async fn get_json(client: &Client, url: impl reqwest::IntoUrl) -> Result<Value> {
  Ok(
    client
      .get(url)
      .send()
      .await?
      .error_for_status()?
      .json()
      .await?,
  )
}

fn percentile(sorted: &[Duration], percentile: usize) -> Duration {
  let index = sorted.len().saturating_mul(percentile).div_ceil(100) - 1;
  sorted[index]
}

fn setting(name: &str, default: usize) -> usize {
  std::env::var(name)
    .ok()
    .and_then(|value| value.parse().ok())
    .unwrap_or(default)
}

fn setting_u128(name: &str, default: u128) -> u128 {
  std::env::var(name)
    .ok()
    .and_then(|value| value.parse().ok())
    .unwrap_or(default)
}

async fn assert_test_pages(client: &Client, base_url: &str) -> Result<()> {
  let first = get_json(
    client,
    format!("{base_url}/api/v1/runs/load-run-4242/tests?outcome=fail"),
  )
  .await?;
  ensure!(first["tests"].as_array().context("tests")?.len() == 50);
  ensure!(first["total"] == 1000 && first["summary"]["total"] == 2000);
  let cursor = first["next_cursor"].as_str().context("next test cursor")?;
  let second = get_json(
    client,
    format!("{base_url}/api/v1/runs/load-run-4242/tests?outcome=fail&cursor={cursor}"),
  )
  .await?;
  ensure!(second["tests"][0]["id"] == "test-0102");
  let sparse = get_json(
    client,
    format!("{base_url}/api/v1/runs/load-run-4242/tests?search=test%202000"),
  )
  .await?;
  ensure!(sparse["total"] == 1 && sparse["tests"][0]["id"] == "test-2000");
  Ok(())
}

async fn assert_mcp_survey(
  client: &Client,
  base_url: &str,
  name: &str,
  arguments: Value,
) -> Result<()> {
  let response: Value = client.post(format!("{base_url}/mcp"))
    .header(reqwest::header::ACCEPT, "application/json, text/event-stream")
    .json(&json!({"jsonrpc":"2.0", "id":1, "method":"tools/call", "params":{"name":name,"arguments":arguments}}))
    .send().await?.error_for_status()?.json().await?;
  ensure!(response["result"]["isError"] == false, "{response}");
  let content = &response["result"]["structuredContent"];
  match name {
    "stove_failures" => {
      ensure!(content["total_failure_count"] == 1000 && content["failure_count"] == 20);
      ensure!(content["next_cursor"].is_string());
    }
    "stove_apps" => ensure!(
      content["apps"]
        .as_array()
        .context("apps")?
        .iter()
        .find(|app| app["app_name"] == "load-app-2")
        .context("app")?["failed_runs"]
        == 1
    ),
    "stove_diagnose" => {
      ensure!(content["status"] == "ambiguous_run");
      ensure!(
        content["candidate_runs"]
          .as_array()
          .context("candidates")?
          .len()
          == 5
      );
      ensure!(content["matched_runs"].as_i64().context("matches")? > 5);
    }
    _ => unreachable!(),
  }
  Ok(())
}
