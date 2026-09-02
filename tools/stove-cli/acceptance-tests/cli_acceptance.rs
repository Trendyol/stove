mod support;

use std::collections::BTreeSet;
use std::time::Duration;

use anyhow::{Context, Result};
use reqwest::Method;
use serde_json::{Value, json};

use support::{
  PostgresTestDatabase, RunningStove, failed_entry, failed_span, mock_interaction, mock_warning,
  run_ended, run_started, send_events, snapshot, test_ended, test_started,
};

#[tokio::test]
async fn real_cli_exposes_grpc_rest_mcp_agent_loop_and_embedded_spa() -> Result<()> {
  let stove = RunningStove::start(Some(0)).await?;
  let mut grpc = stove.grpc_client().await?;

  send_events(
    &mut grpc,
    [
      run_started(
        "pipeline-42",
        "service-tests",
        1_704_067_200,
        &[
          ("team", "checkout"),
          ("tribe", "commerce"),
          ("gitlab.pipeline_id", "42"),
        ],
      ),
      test_started(
        "pipeline-42",
        "test-failed",
        1_704_067_201,
        "declines an invalid payment",
      ),
      failed_entry("pipeline-42", "test-failed", 1_704_067_202),
      failed_span("pipeline-42"),
      snapshot("pipeline-42", "test-failed", 1_704_067_203),
      mock_interaction("pipeline-42", "test-failed", 1_704_067_204),
      mock_warning("pipeline-42", "test-failed", 1_704_067_205),
      test_ended(
        "pipeline-42",
        "test-failed",
        1_704_067_206,
        "FAILED",
        "payment declined",
      ),
      run_ended("pipeline-42", 1_704_067_207, 1, 0, 1),
      run_started(
        "pipeline-84",
        "service-tests",
        1_704_070_800,
        &[
          ("team", "catalog"),
          ("tribe", "commerce"),
          ("gitlab.pipeline_id", "84"),
        ],
      ),
      test_started(
        "pipeline-84",
        "test-passed",
        1_704_070_801,
        "accepts a valid payment",
      ),
      test_ended("pipeline-84", "test-passed", 1_704_070_802, "PASSED", ""),
      run_ended("pipeline-84", 1_704_070_803, 1, 1, 0),
    ],
  )
  .await?;

  // A tool call flushes the asynchronous ingest queue before reading it.
  let runs_tool = stove
    .mcp_tool(
      "stove_runs",
      json!({
        "app_name": "service-tests",
        "metadata": {"team": "checkout", "gitlab.pipeline_id": "42"}
      }),
    )
    .await?;
  let runs = runs_tool["result"]["structuredContent"]["runs"]
    .as_array()
    .context("stove_runs returns an array")?;
  assert_eq!(runs.len(), 1);
  assert_eq!(runs[0]["run_id"], "pipeline-42");
  assert_eq!(runs[0]["metadata"]["tribe"], "commerce");

  assert_rest_lifecycle(&stove).await?;
  assert_mcp_and_agent_loop(&stove).await?;
  assert_remote_mcp_headers_are_accepted(&stove).await?;
  assert_embedded_spa(&stove).await?;

  if let Ok(seconds) = std::env::var("STOVE_ACCEPTANCE_BROWSER_HOLD_SECONDS") {
    let seconds = seconds.parse::<u64>().unwrap_or(120);
    eprintln!("STOVE_ACCEPTANCE_BROWSER_URL={}", stove.base_url);
    tokio::time::sleep(Duration::from_secs(seconds)).await;
  }

  Ok(())
}

#[tokio::test]
async fn real_cli_admin_retention_preview_purge_and_clear_are_safe() -> Result<()> {
  let stove = RunningStove::start(Some(0)).await?;
  let mut grpc = stove.grpc_client().await?;

  send_events(
    &mut grpc,
    [
      run_started(
        "purge-old",
        "retention-app",
        1_704_067_200,
        &[("team", "checkout")],
      ),
      test_started("purge-old", "old-test", 1_704_067_201, "old evidence"),
      failed_entry("purge-old", "old-test", 1_704_067_202),
      test_ended(
        "purge-old",
        "old-test",
        1_704_067_203,
        "FAILED",
        "old failure",
      ),
      run_ended("purge-old", 1_704_067_204, 1, 0, 1),
      run_started(
        "completed-middle",
        "retention-app",
        1_707_609_600,
        &[("team", "checkout")],
      ),
      run_ended("completed-middle", 1_707_609_601, 0, 0, 0),
      run_started(
        "active-one",
        "retention-app",
        1_709_251_200,
        &[("team", "checkout")],
      ),
      run_started(
        "active-two",
        "retention-app",
        1_711_929_600,
        &[("team", "checkout")],
      ),
      run_started(
        "completed-latest",
        "retention-app",
        1_714_608_000,
        &[("team", "checkout")],
      ),
      run_ended("completed-latest", 1_714_608_001, 0, 0, 0),
    ],
  )
  .await?;
  stove.mcp_tool("stove_runs", json!({})).await?;

  let status = stove.get_json("/admin/status").await?;
  assert_eq!(status["backend"], "sqlite");
  assert_eq!(status["retention_runs_per_app"], 0);
  assert_eq!(status["runs"], 5);
  assert_eq!(status["running_runs"], 2);

  let preview = stove
    .request_json(
      Method::POST,
      "/admin/purge/preview",
      json!({
        "app_name": "retention-app",
        "older_than": "2024-02-01T00:00:00Z"
      }),
    )
    .await?;
  assert_eq!(preview["run_ids"], json!(["purge-old"]));
  assert_eq!(preview["run_count"], 1);
  assert_eq!(preview["evidence"]["tests"], 1);
  assert_eq!(preview["evidence"]["entries"], 1);

  let purge = stove
    .request_json(
      Method::POST,
      "/admin/purge",
      json!({"run_ids": preview["run_ids"]}),
    )
    .await?;
  assert_eq!(purge["purged_run_ids"], json!(["purge-old"]));
  assert_eq!(purge["purged_runs"], 1);

  let updated = stove
    .request_json(Method::PUT, "/admin/retention", json!({"runs_per_app": 1}))
    .await?;
  assert_eq!(updated["retention_runs_per_app"], 1);
  assert_eq!(updated["runs"], 3);
  assert_eq!(updated["running_runs"], 2);

  let retained_ids = run_ids(&stove.get_json("/runs?app=retention-app").await?);
  assert_eq!(
    retained_ids,
    BTreeSet::from([
      "active-one".to_string(),
      "active-two".to_string(),
      "completed-latest".to_string(),
    ])
  );

  let completed_only = stove
    .request_json(
      Method::POST,
      "/admin/purge/preview",
      json!({"app_name": "retention-app"}),
    )
    .await?;
  assert_eq!(completed_only["run_ids"], json!(["completed-latest"]));

  let including_active = stove
    .request_json(
      Method::POST,
      "/admin/purge/preview",
      json!({"app_name": "retention-app", "include_running": true}),
    )
    .await?;
  assert_eq!(
    run_ids(&including_active["run_ids"]),
    BTreeSet::from([
      "active-one".to_string(),
      "active-two".to_string(),
      "completed-latest".to_string(),
    ])
  );

  let protected = stove
    .request_json(
      Method::POST,
      "/admin/purge",
      json!({"run_ids": ["active-one"]}),
    )
    .await?;
  assert_eq!(protected["purged_runs"], 0);
  assert_eq!(
    stove.get_json("/runs/active-one").await?["status"],
    "RUNNING"
  );

  let purged_completed = stove
    .request_json(
      Method::POST,
      "/admin/purge",
      json!({"run_ids": ["completed-latest"]}),
    )
    .await?;
  assert_eq!(
    purged_completed["purged_run_ids"],
    json!(["completed-latest"])
  );

  let cleared = stove.client.delete(stove.api_url("/data")).send().await?;
  assert!(cleared.status().is_success());
  assert_eq!(stove.get_json("/admin/status").await?["runs"], 0);

  Ok(())
}

#[tokio::test]
async fn local_cli_default_retention_keeps_one_completed_run_and_all_active_runs() -> Result<()> {
  let stove = RunningStove::start(None).await?;
  let mut grpc = stove.grpc_client().await?;
  send_events(
    &mut grpc,
    [
      run_started("completed-old", "default-app", 1_704_067_200, &[]),
      run_ended("completed-old", 1_704_067_201, 0, 0, 0),
      run_started("active", "default-app", 1_704_153_600, &[]),
      run_started("completed-new", "default-app", 1_704_240_000, &[]),
      run_ended("completed-new", 1_704_240_001, 0, 0, 0),
    ],
  )
  .await?;
  stove.mcp_tool("stove_runs", json!({})).await?;

  let status = stove.get_json("/admin/status").await?;
  assert_eq!(status["retention_runs_per_app"], 1);
  assert_eq!(status["runs"], 2);
  assert_eq!(status["running_runs"], 1);
  assert_eq!(
    run_ids(&stove.get_json("/runs?app=default-app").await?),
    BTreeSet::from(["active".to_string(), "completed-new".to_string()])
  );
  assert!(stove.get_json("/runs/completed-old").await?.is_null());

  Ok(())
}

#[tokio::test]
async fn postgres_cli_runs_migrations_jsonb_filters_retention_and_admin_in_testcontainer()
-> Result<()> {
  let database = PostgresTestDatabase::start().await?;
  let stove = RunningStove::start_postgres(&database.url, Some(0)).await?;
  let mut grpc = stove.grpc_client().await?;
  send_events(
    &mut grpc,
    [
      run_started(
        "postgres-old",
        "postgres-app",
        1_704_067_200,
        &[("team", "checkout"), ("gitlab.pipeline_id", "40")],
      ),
      run_ended("postgres-old", 1_704_067_201, 0, 0, 0),
      run_started(
        "postgres-other",
        "postgres-app",
        1_704_153_600,
        &[("team", "catalog"), ("gitlab.pipeline_id", "42")],
      ),
      run_ended("postgres-other", 1_704_153_601, 0, 0, 0),
      run_started(
        "postgres-pipeline-42",
        "postgres-app",
        1_704_240_000,
        &[("team", "checkout"), ("gitlab.pipeline_id", "42")],
      ),
      run_ended("postgres-pipeline-42", 1_704_240_001, 0, 0, 0),
      run_started(
        "postgres-active",
        "postgres-app",
        1_704_326_400,
        &[("team", "checkout"), ("gitlab.pipeline_id", "43")],
      ),
    ],
  )
  .await?;

  let filtered = stove
    .mcp_tool(
      "stove_runs",
      json!({
        "app_name": "postgres-app",
        "metadata": {"team": "checkout", "gitlab.pipeline_id": "42"}
      }),
    )
    .await?;
  assert_eq!(
    filtered["result"]["structuredContent"]["runs"][0]["run_id"], "postgres-pipeline-42",
    "unexpected PostgreSQL MCP response: {filtered:#}"
  );

  let mut rest_url = reqwest::Url::parse(&stove.api_url("/runs"))?;
  rest_url
    .query_pairs_mut()
    .append_pair("app", "postgres-app")
    .append_pair(
      "metadata",
      r#"{"team":"checkout","gitlab.pipeline_id":"42"}"#,
    );
  let rest_filtered = stove
    .client
    .get(rest_url)
    .send()
    .await?
    .json::<Value>()
    .await?;
  assert_eq!(
    run_ids(&rest_filtered),
    BTreeSet::from(["postgres-pipeline-42".to_string()])
  );

  let status = stove.get_json("/admin/status").await?;
  assert_eq!(status["backend"], "postgresql");
  assert_eq!(status["retention_runs_per_app"], 0);
  assert_eq!(status["runs"], 4);
  assert_eq!(status["running_runs"], 1);

  database
    .with_client(|postgres| {
      let migrations: i64 = postgres
        .query_one("SELECT COUNT(*) FROM schema_migrations", &[])?
        .get(0);
      assert!(migrations > 0);
      let metadata_type: String = postgres
        .query_one(
          "SELECT data_type FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'runs'
              AND column_name = 'metadata'",
          &[],
        )?
        .get(0);
      assert_eq!(metadata_type, "jsonb");
      let index_definition: String = postgres
        .query_one(
          "SELECT indexdef FROM pg_indexes
            WHERE schemaname = current_schema()
              AND indexname = 'idx_runs_metadata'",
          &[],
        )?
        .get(0);
      let index_definition = index_definition.to_ascii_lowercase();
      assert!(index_definition.contains("using gin"));
      assert!(index_definition.contains("jsonb_path_ops"));
      let stored_team: String = postgres
        .query_one(
          "SELECT metadata ->> 'team' FROM runs WHERE id = $1",
          &[&"postgres-pipeline-42"],
        )?
        .get(0);
      assert_eq!(stored_team, "checkout");
      Ok(())
    })
    .await?;

  let retained = stove
    .request_json(Method::PUT, "/admin/retention", json!({"runs_per_app": 1}))
    .await?;
  assert_eq!(retained["runs"], 2);
  assert_eq!(retained["running_runs"], 1);
  assert_eq!(
    run_ids(&stove.get_json("/runs?app=postgres-app").await?),
    BTreeSet::from([
      "postgres-active".to_string(),
      "postgres-pipeline-42".to_string(),
    ])
  );

  let preview = stove
    .request_json(
      Method::POST,
      "/admin/purge/preview",
      json!({"app_name": "postgres-app"}),
    )
    .await?;
  assert_eq!(preview["run_ids"], json!(["postgres-pipeline-42"]));
  let protected = stove
    .request_json(
      Method::POST,
      "/admin/purge",
      json!({"run_ids": ["postgres-active"]}),
    )
    .await?;
  assert_eq!(protected["purged_runs"], 0);
  let purged = stove
    .request_json(
      Method::POST,
      "/admin/purge",
      json!({"run_ids": preview["run_ids"]}),
    )
    .await?;
  assert_eq!(purged["purged_run_ids"], json!(["postgres-pipeline-42"]));
  assert_eq!(
    run_ids(&stove.get_json("/runs?app=postgres-app").await?),
    BTreeSet::from(["postgres-active".to_string()])
  );

  drop(stove);
  drop(database);
  Ok(())
}

async fn assert_rest_lifecycle(stove: &RunningStove) -> Result<()> {
  let apps = stove.get_json("/apps").await?;
  assert_eq!(apps.as_array().context("apps array")?.len(), 1);
  assert_eq!(apps[0]["app_name"], "service-tests");

  let all_runs = stove.get_json("/runs?app=service-tests").await?;
  assert_eq!(all_runs.as_array().context("runs array")?.len(), 2);

  let mut url = reqwest::Url::parse(&stove.api_url("/runs"))?;
  url
    .query_pairs_mut()
    .append_pair("app", "service-tests")
    .append_pair(
      "metadata",
      r#"{"team":"checkout","gitlab.pipeline_id":"42"}"#,
    );
  let matching = stove.client.get(url).send().await?.json::<Value>().await?;
  assert_eq!(matching.as_array().context("matching runs array")?.len(), 1);
  assert_eq!(matching[0]["id"], "pipeline-42");
  assert_eq!(matching[0]["metadata"]["tribe"], "commerce");

  let mut missing_url = reqwest::Url::parse(&stove.api_url("/runs"))?;
  missing_url
    .query_pairs_mut()
    .append_pair("metadata", r#"{"team":"unknown"}"#);
  assert!(
    stove
      .client
      .get(missing_url)
      .send()
      .await?
      .json::<Value>()
      .await?
      .as_array()
      .context("missing runs array")?
      .is_empty()
  );

  let run = stove.get_json("/runs/pipeline-42").await?;
  assert_eq!(run["status"], "FAILED");
  assert_eq!(run["metadata"]["team"], "checkout");

  let tests = stove.get_json("/runs/pipeline-42/tests").await?;
  assert_eq!(tests[0]["id"], "test-failed");
  assert_eq!(tests[0]["status"], "FAILED");

  let entries = stove
    .get_json("/runs/pipeline-42/tests/test-failed/entries")
    .await?;
  assert_eq!(entries[0]["action"], "POST /orders");
  let spans = stove
    .get_json("/runs/pipeline-42/tests/test-failed/spans")
    .await?;
  assert_eq!(spans[0]["trace_id"], "trace-42");
  assert_eq!(spans[0]["exception_type"], "PaymentDeclinedException");
  let snapshots = stove
    .get_json("/runs/pipeline-42/tests/test-failed/snapshots")
    .await?;
  assert_eq!(snapshots[0]["system"], "Kafka");
  let interactions = stove
    .get_json("/runs/pipeline-42/tests/test-failed/interactions")
    .await?;
  assert_eq!(interactions[0]["target"], "/payments");
  let warnings = stove
    .get_json("/runs/pipeline-42/tests/test-failed/warnings")
    .await?;
  assert_eq!(warnings[0]["kind"], "UNUSED_STUB");

  Ok(())
}

async fn assert_mcp_and_agent_loop(stove: &RunningStove) -> Result<()> {
  let initialized = stove
    .mcp_call(
      "initialize",
      json!({
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "acceptance-test", "version": "1"}
      }),
    )
    .await?;
  assert_eq!(initialized["result"]["serverInfo"]["name"], "stove");

  let listed = stove.mcp_call("tools/list", json!({})).await?;
  let tool_names: BTreeSet<&str> = listed["result"]["tools"]
    .as_array()
    .context("tools list")?
    .iter()
    .filter_map(|tool| tool["name"].as_str())
    .collect();
  for expected in [
    "stove_apps",
    "stove_runs",
    "stove_failures",
    "stove_failure_detail",
    "stove_trace",
    "stove_snapshot",
    "stove_raw_evidence",
  ] {
    assert!(tool_names.contains(expected), "missing MCP tool {expected}");
  }

  let apps = stove.mcp_tool("stove_apps", json!({})).await?;
  assert_eq!(
    apps["result"]["structuredContent"]["apps"][0]["app_name"],
    "service-tests"
  );

  let failures = stove
    .mcp_tool("stove_failures", json!({"run_id": "pipeline-42"}))
    .await?;
  let failure = &failures["result"]["structuredContent"]["groups"][0]["failures"][0];
  assert_eq!(failure["run_id"], "pipeline-42");
  assert_eq!(failure["test_id"], "test-failed");

  let detail_call = &failure["detail_tool_call"];
  assert_exact_selector(detail_call);
  let detail = stove.follow_tool_call(detail_call).await?;
  let detail_content = &detail["result"]["structuredContent"];
  assert_eq!(detail_content["run_id"], "pipeline-42");
  assert_eq!(detail_content["test"]["test_id"], "test-failed");
  assert_eq!(
    detail_content["trace_summary"]["trace_status"],
    "correlated"
  );
  assert_eq!(detail_content["snapshot_summaries"][0]["system"], "Kafka");
  assert_eq!(
    detail_content["failed_entries"][0]["input"]["authorization"],
    "[REDACTED]"
  );

  let trace_call = &detail_content["trace_tool_call"];
  assert_exact_selector(trace_call);
  let trace = stove.follow_tool_call(trace_call).await?;
  assert_eq!(
    trace["result"]["structuredContent"]["run_id"],
    "pipeline-42"
  );
  assert_eq!(
    trace["result"]["structuredContent"]["test"]["test_id"],
    "test-failed"
  );

  let snapshot_call = &detail_content["snapshot_tool_call"];
  assert_exact_selector(snapshot_call);
  let snapshot_detail = stove.follow_tool_call(snapshot_call).await?;
  assert_eq!(
    snapshot_detail["result"]["structuredContent"]["snapshots"][0]["state"]["parse_status"],
    "ok"
  );

  let raw_call = &detail_content["failed_entries"][0]["raw_tool_call"];
  assert_exact_selector(raw_call);
  let raw = stove.follow_tool_call(raw_call).await?;
  assert_eq!(
    raw["result"]["structuredContent"]["raw_evidence"]["kind"],
    "entry"
  );
  assert_eq!(
    raw["result"]["structuredContent"]["raw_evidence"]["evidence"]["action"],
    "POST /orders"
  );

  Ok(())
}

async fn assert_remote_mcp_headers_are_accepted(stove: &RunningStove) -> Result<()> {
  let response = stove
    .client
    .post(stove.mcp_url())
    .header(reqwest::header::HOST, "stove.internal.example")
    .header(reqwest::header::ORIGIN, "https://agents.internal.example")
    .header(
      reqwest::header::ACCEPT,
      "application/json, text/event-stream",
    )
    .json(&json!({
      "jsonrpc": "2.0",
      "id": 10,
      "method": "tools/list",
      "params": {}
    }))
    .send()
    .await?;
  assert!(response.status().is_success());
  Ok(())
}

async fn assert_embedded_spa(stove: &RunningStove) -> Result<()> {
  let response = stove.client.get(&stove.base_url).send().await?;
  assert!(response.status().is_success());
  assert!(
    response
      .headers()
      .get(reqwest::header::CONTENT_TYPE)
      .and_then(|value| value.to_str().ok())
      .is_some_and(|value| value.starts_with("text/html"))
  );
  let html = response.text().await?;
  assert!(html.contains("<div id=\"root\"></div>"));
  let asset_path = html
    .split("src=\"")
    .nth(1)
    .and_then(|rest| rest.split('\"').next())
    .context("embedded SPA script path")?;
  let asset = stove
    .client
    .get(format!("{}{asset_path}", stove.base_url))
    .send()
    .await?;
  assert!(asset.status().is_success());
  let javascript = asset.text().await?;
  assert!(javascript.contains("Metadata filters"));
  assert!(javascript.contains("Select key"));
  assert!(javascript.contains("Select value"));
  assert!(javascript.contains("Dashboard administration"));
  Ok(())
}

fn assert_exact_selector(call: &Value) {
  assert_eq!(call["arguments"]["run_id"], "pipeline-42");
  assert_eq!(call["arguments"]["test_id"], "test-failed");
}

fn run_ids(value: &Value) -> BTreeSet<String> {
  value
    .as_array()
    .expect("runs should be an array")
    .iter()
    .filter_map(|run| {
      run
        .as_str()
        .or_else(|| run["id"].as_str())
        .map(ToString::to_string)
    })
    .collect()
}
