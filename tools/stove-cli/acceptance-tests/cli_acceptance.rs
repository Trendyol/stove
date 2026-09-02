mod support;

use std::collections::BTreeSet;
use std::time::Duration;

use anyhow::{Context, Result};
use reqwest::Method;
use serde_json::{Value, json};
use stove::proto;
use stove::proto::dashboard_event_service_client::DashboardEventServiceClient;
use tonic::transport::Channel;

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
  let stove = RunningStove::start_postgres_with_config_file(&database.url, Some(0)).await?;
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
        .query_one("SELECT COUNT(*) FROM refinery_schema_history", &[])?
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

#[tokio::test]
async fn two_postgres_pods_share_ordered_live_events_deduplication_and_retention() -> Result<()> {
  let database = PostgresTestDatabase::start().await?;
  let (first, second) = tokio::try_join!(
    RunningStove::start_postgres(&database.url, Some(5)),
    RunningStove::start_postgres(&database.url, Some(5)),
  )?;
  let mut first_grpc = first.grpc_client().await?;
  let mut second_grpc = second.grpc_client().await?;
  let mut first_sse = SseStream::connect(&first, None).await?;
  let mut second_sse = SseStream::connect(&second, None).await?;
  let mut shared_events = Vec::new();

  let started = identified(
    run_started(
      "distributed-run",
      "distributed-app",
      1_704_067_200,
      &[("team", "checkout"), ("gitlab.pipeline_id", "9001")],
    ),
    "00000000-0000-4000-8000-000000000001",
    1,
  );
  let ack = first_grpc.send_event(started).await?.into_inner();
  assert!(ack.accepted);
  assert!(!ack.duplicate);

  let (live_id, live) = next_shared_sse_frame(&mut first_sse, &mut second_sse).await?;
  assert_eq!(live["run_id"], "distributed-run");
  assert_eq!(live["event_type"], "run_started");
  assert_eq!(live["seq"], live_id);
  shared_events.push((live_id, live));

  send_events(
    &mut second_grpc,
    [identified(
      test_started(
        "distributed-run",
        "distributed-test",
        1_704_067_201,
        "survives a pod loss",
      ),
      "00000000-0000-4000-8000-000000000002",
      2,
    )],
  )
  .await?;
  let test_started = next_shared_sse_frame(&mut first_sse, &mut second_sse).await?;
  assert_eq!(test_started.1["event_type"], "test_started");
  shared_events.push(test_started);

  let failed = identified(
    failed_entry("distributed-run", "distributed-test", 1_704_067_202),
    "00000000-0000-4000-8000-000000000003",
    3,
  );
  first_grpc.send_event(failed.clone()).await?;
  let failed_live = next_shared_sse_frame(&mut first_sse, &mut second_sse).await?;
  assert_eq!(failed_live.1["event_type"], "entry_recorded");
  assert_eq!(failed_live.1["payload"]["result"], "FAILED");
  shared_events.push(failed_live);

  let duplicate = second_grpc.send_event(failed).await?.into_inner();
  assert!(duplicate.accepted);
  assert!(duplicate.duplicate);

  let mut passing = failed_entry("distributed-run", "distributed-test", 1_704_067_203);
  if let Some(proto::dashboard_event::Event::EntryRecorded(entry)) = passing.event.as_mut() {
    entry.result = "PASSED".to_string();
    entry.error.clear();
    entry.actual = entry.expected.clone();
  }
  second_grpc
    .send_event(identified(
      passing,
      "00000000-0000-4000-8000-000000000004",
      4,
    ))
    .await?;
  let passing_live = next_shared_sse_frame(&mut first_sse, &mut second_sse).await?;
  assert_eq!(passing_live.1["event_type"], "entry_recorded");
  assert_eq!(passing_live.1["payload"]["result"], "PASSED");
  shared_events.push(passing_live);

  let mut replay = SseStream::connect(&first, Some(live_id)).await?;
  for expected in shared_events.iter().skip(1) {
    assert_eq!(&replay.next().await?, expected);
  }

  let last_live_id =
    assert_concurrent_live_delivery(&first_grpc, &second_grpc, &mut first_sse, &mut second_sse)
      .await?;

  assert_eq!(
    first.get_json("/runs/distributed-run").await?["metadata"]["team"],
    "checkout"
  );
  assert_eq!(
    second.get_json("/runs/distributed-run").await?["metadata"]["gitlab.pipeline_id"],
    "9001"
  );

  first
    .request_json(Method::PUT, "/admin/retention", json!({"runs_per_app": 2}))
    .await?;
  assert_eq!(
    second.get_json("/admin/status").await?["retention_runs_per_app"],
    2
  );

  drop(first_grpc);
  drop(first_sse);
  drop(replay);
  drop(first);

  send_events(
    &mut second_grpc,
    [
      identified(
        test_ended(
          "distributed-run",
          "distributed-test",
          1_704_067_204,
          "PASSED",
          "",
        ),
        "00000000-0000-4000-8000-000000000005",
        5,
      ),
      identified(
        run_ended("distributed-run", 1_704_067_205, 1, 1, 0),
        "00000000-0000-4000-8000-000000000006",
        6,
      ),
    ],
  )
  .await?;

  let test_ended = second_sse.next().await?;
  assert_eq!(test_ended.1["event_type"], "test_ended");
  assert!(test_ended.0 > last_live_id);
  let run_ended = second_sse.next().await?;
  assert_eq!(run_ended.1["event_type"], "run_ended");
  assert!(run_ended.0 > test_ended.0);

  let run = second.get_json("/runs/distributed-run").await?;
  assert_eq!(run["status"], "PASSED");
  let entries = second
    .get_json("/runs/distributed-run/tests/distributed-test/entries")
    .await?;
  assert_eq!(entries.as_array().map(Vec::len), Some(1));
  assert_eq!(entries[0]["attempt_count"], 2);
  assert_eq!(entries[0]["failure_count"], 1);

  assert_exact_concurrent_retention(&database.url, &second).await?;
  Ok(())
}

fn identified(
  mut event: proto::DashboardEvent,
  event_id: &str,
  sequence: u64,
) -> proto::DashboardEvent {
  event.event_id = event_id.to_string();
  event.sequence = sequence;
  event
}

struct SseStream {
  response: reqwest::Response,
  buffer: String,
}

impl SseStream {
  async fn connect(stove: &RunningStove, last_event_id: Option<u64>) -> Result<Self> {
    let mut request = stove.client.get(stove.api_url("/events/stream"));
    if let Some(last_event_id) = last_event_id {
      request = request.header("Last-Event-ID", last_event_id);
    }
    let response = request.send().await?;
    anyhow::ensure!(
      response.status().is_success(),
      "SSE endpoint is unavailable"
    );
    Ok(Self {
      response,
      buffer: String::new(),
    })
  }

  async fn next(&mut self) -> Result<(u64, Value)> {
    next_sse_frame(&mut self.response, &mut self.buffer).await
  }
}

async fn next_shared_sse_frame(
  first: &mut SseStream,
  second: &mut SseStream,
) -> Result<(u64, Value)> {
  let (first_event, second_event) = tokio::try_join!(first.next(), second.next())?;
  assert_eq!(first_event, second_event);
  Ok(first_event)
}

async fn assert_concurrent_live_delivery(
  first_grpc: &DashboardEventServiceClient<Channel>,
  second_grpc: &DashboardEventServiceClient<Channel>,
  first_sse: &mut SseStream,
  second_sse: &mut SseStream,
) -> Result<u64> {
  const EVENT_COUNT: usize = 32;

  let mut sends = tokio::task::JoinSet::new();
  for index in 0..EVENT_COUNT {
    let mut client = if index % 2 == 0 {
      first_grpc.clone()
    } else {
      second_grpc.clone()
    };
    sends.spawn(async move {
      let run_id = format!("live-load-{index}");
      let event_id = format!("00000000-0000-4000-9000-{index:012}");
      client
        .send_event(identified(
          run_started(
            &run_id,
            "distributed-live-load",
            1_704_068_000 + index as i64,
            &[],
          ),
          &event_id,
          1,
        ))
        .await
        .map(tonic::Response::into_inner)
    });
  }
  while let Some(result) = sends.join_next().await {
    let acknowledgement = result.context("join concurrent live-event sender")??;
    assert!(acknowledgement.accepted);
    assert!(!acknowledgement.duplicate);
  }

  let mut previous_id = 0;
  let mut run_ids = BTreeSet::new();
  for _ in 0..EVENT_COUNT {
    let (id, event) = next_shared_sse_frame(first_sse, second_sse).await?;
    assert!(id > previous_id, "live-event IDs must be strictly ordered");
    assert_eq!(event["seq"], id);
    assert_eq!(event["event_type"], "run_started");
    run_ids.insert(event["run_id"].as_str().unwrap_or_default().to_string());
    previous_id = id;
  }
  assert_eq!(run_ids.len(), EVENT_COUNT);
  Ok(previous_id)
}

async fn next_sse_frame(
  response: &mut reqwest::Response,
  buffer: &mut String,
) -> Result<(u64, Value)> {
  tokio::time::timeout(Duration::from_secs(10), async {
    loop {
      if let Some(boundary) = buffer.find("\n\n") {
        let frame = buffer[..boundary].to_string();
        buffer.drain(..boundary + 2);
        if let Some(data) = frame.lines().find_map(|line| line.strip_prefix("data: ")) {
          let id = frame
            .lines()
            .find_map(|line| line.strip_prefix("id: "))
            .context("SSE event has an id")?
            .parse()
            .context("parse SSE event id")?;
          let data = serde_json::from_str(data).context("parse SSE data")?;
          return Ok((id, data));
        }
      }
      let chunk = response.chunk().await?.context("SSE stream ended")?;
      buffer.push_str(&String::from_utf8_lossy(&chunk));
    }
  })
  .await
  .context("wait for cross-pod SSE event")?
}

async fn assert_exact_concurrent_retention(
  database_url: &str,
  reader: &RunningStove,
) -> Result<()> {
  let first = RunningStove::start_postgres(database_url, None).await?;
  let second = RunningStove::start_postgres(database_url, None).await?;
  let mut start_client = first.grpc_client().await?;
  for (index, run_id) in ["retention-a", "retention-b", "retention-c"]
    .into_iter()
    .enumerate()
  {
    send_events(
      &mut start_client,
      [run_started(
        run_id,
        "distributed-retention",
        1_704_100_000 + i64::try_from(index)?,
        &[],
      )],
    )
    .await?;
  }

  let mut client_a = first.grpc_client().await?;
  let mut client_b = second.grpc_client().await?;
  let mut client_c = second.grpc_client().await?;
  let (a, b, c) = tokio::join!(
    client_a.send_event(run_ended("retention-a", 1_704_100_010, 0, 0, 0)),
    client_b.send_event(run_ended("retention-b", 1_704_100_011, 0, 0, 0)),
    client_c.send_event(run_ended("retention-c", 1_704_100_012, 0, 0, 0)),
  );
  a?;
  b?;
  c?;

  let retained = reader.get_json("/runs?app=distributed-retention").await?;
  assert_eq!(retained.as_array().map(Vec::len), Some(2));
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
    .get_json("/runs/pipeline-42/tests/test-failed/mock-interactions")
    .await?;
  assert_eq!(interactions[0]["target"], "/payments");
  let warnings = stove
    .get_json("/runs/pipeline-42/tests/test-failed/mock-warnings")
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
