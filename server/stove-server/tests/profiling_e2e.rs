mod common;

use common::TestServer;
use serde_json::{Value, json};
use stove::profiling::{ProfileQuery, StoredChunk, report};

fn chunk(run: &str, id: &str, instance: &str, end: i64, live: i64) -> StoredChunk {
  let sample = |value| {
    json!({"frames":[
    {"name":"app.Root","file":"Root.java","line":1},
    {"name":"app.Hot","file":"Hot.java","line":42},
    {"name":"app.Hot","file":"Hot.java","line":42}
  ],"value":value,"labels":{"trace_id":"trace-1","profile_id":"span-1"}})
  };
  StoredChunk {
    id: id.into(),
    run_id: run.into(),
    service: "checkout".into(),
    instance: instance.into(),
    from_ms: end - 1000,
    until_ms: end,
    payload: serde_json::to_vec(&json!({"sample_rate":100,"profiles":[
      {"metric":"cpu:cpu","unit":"nanoseconds","samples":[sample(100)]},
      {"metric":"memory:live","unit":"count","samples":[sample(live)]}
    ]}))
    .unwrap(),
  }
}

async fn upload(server: &TestServer, run: &str, data: &[u8], labels: &[u8]) -> reqwest::Response {
  let mut body = Vec::new();
  for (name, bytes) in [("jfr", data), ("labels", labels)] {
    body.extend_from_slice(format!("--stove\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").as_bytes());
    body.extend_from_slice(bytes);
    body.extend_from_slice(b"\r\n");
  }
  body.extend_from_slice(b"--stove--\r\n");
  server
    .client
    .post(format!(
      "{}/ingest?name=checkout&format=jfr&from=1710000000&until=1710000010&sampleRate=100",
      server.base_url
    ))
    .header("content-type", "multipart/form-data; boundary=stove")
    .header("x-stove-run-id", run)
    .header("x-stove-instance-id", "jvm-1")
    .body(body)
    .send()
    .await
    .unwrap()
}

#[tokio::test]
async fn actual_pyroscope_jfr_and_label_contract_is_ingested_idempotently() {
  let server = TestServer::start().await;
  server.seed_run("profiles", "checkout");
  server.seed_run("other", "other-app");
  let data = include_bytes!("fixtures/profiling/dump1.jfr.gz");
  let labels = include_bytes!("fixtures/profiling/dump1.labels.pb.gz");
  for _ in 0..2 {
    let response = upload(&server, "profiles", data, labels).await;
    let status = response.status();
    assert_eq!(status, 200, "{}", response.text().await.unwrap());
  }
  assert_eq!(server.repo.profile_chunks("profiles").unwrap().len(), 1);
  let profiles = server.get_json("/runs/profiles/profiles").await;
  let series = profiles["series"].as_array().unwrap();
  assert!(!series.is_empty(), "{profiles}");
  // Oracle: upstream dump1 expected pprof contains 21.88 seconds of sampled CPU.
  assert_eq!(series[0]["total"], 21_880_000_000i64);
  assert_eq!(series[0]["hotspots"][0]["frame"]["name"], "App.fib");
  assert_eq!(
    series[0]["stacks"][0]["frames"][0]["name"],
    "java/lang/Thread.run"
  );
  let chunks = server.repo.profile_chunks("profiles").unwrap();
  let recording: stove::profiling::Recording =
    serde_json::from_reader(flate2::read::GzDecoder::new(chunks[0].payload.as_slice())).unwrap();
  assert!(!recording.frame_table.is_empty());
  assert!(recording.profiles.iter().flat_map(|p| &p.samples).all(|s| {
    s.frames.is_empty()
      && !s.frame_ids.is_empty()
      && s
        .frame_ids
        .iter()
        .all(|id| *id < recording.frame_table.len())
  }));
  assert!(
    recording.profiles[0]
      .samples
      .iter()
      .any(|s| s.labels.contains_key("thread_name"))
  );

  assert!(
    series
      .iter()
      .any(|s| s["metric"].as_str().unwrap().starts_with("process_cpu:"))
  );
  assert!(series.iter().all(|s| s["total"].as_i64().unwrap() > 0));
  assert!(series.iter().any(|s| {
    !s["stacks"].as_array().unwrap()[0]["frames"]
      .as_array()
      .unwrap()
      .is_empty()
  }));
  assert_eq!(
    server.get_json("/runs/other/profiles").await["series"],
    json!([])
  );
  assert_eq!(upload(&server, "unknown", data, labels).await.status(), 503);
  let invalid = upload(&server, "profiles", b"invalid JFR", b"").await;
  assert_eq!(invalid.status(), 400);
  let error = invalid.text().await.unwrap();
  assert!(error.contains("JFR conversion failed"), "{error}");
  assert!(!error.contains("Serialization error"), "{error}");
  assert_eq!(
    upload(&server, "profiles", &[0x1f, 0x8b, 0], b"")
      .await
      .status(),
    400
  );
  assert_eq!(
    upload(&server, "profiles", data, b"invalid protobuf")
      .await
      .status(),
    400
  );
}

#[tokio::test]
async fn shared_frames_preserve_deep_allocation_stacks_and_label_filters() {
  let server = TestServer::start().await;
  server.seed_run("deep", "app");
  let frame_table: Vec<_> = (0..128)
    .map(|i| {
      json!({
        "name": format!("app.{}.method{i}", "LongClassName".repeat(24)),
        "file": "Application.java", "line": i
      })
    })
    .collect();
  let frame_ids: Vec<_> = (0..128).chain(std::iter::once(127)).collect();
  let samples: Vec<_> = (0..1000)
    .map(|i| {
      json!({
        "frame_ids": frame_ids, "value": 512_000,
        "labels": {"trace_id": format!("trace-{i}")}
      })
    })
    .collect();
  // Repeating these same frames inline exceeds the old 30 MiB conversion budget.
  assert!(serde_json::to_vec(&frame_table).unwrap().len() * samples.len() > 32 * 1024 * 1024);
  let mut c = chunk("deep", "compact", "jvm", 2000, 0);
  c.payload = serde_json::to_vec(&json!({
    "frame_table": frame_table, "sample_rate": 100,
    "profiles": [{"metric": "memory:alloc_in_new_tlab_bytes", "unit": "bytes", "samples": samples}]
  }))
  .unwrap();
  assert!(c.payload.len() < 1024 * 1024);
  server.repo.save_profile(&c).unwrap();
  let result = server.get_json("/runs/deep/profiles").await;
  let series = &result["series"][0];
  assert_eq!(series["total"], 512_000_000);
  assert_eq!(series["sample_records"], 1000);
  assert_eq!(series["stacks"][0]["frames"].as_array().unwrap().len(), 129);
  assert_eq!(series["hotspots"][0]["inclusive_value"], 512_000_000);
  assert_eq!(result["truncated"], false);
  let filtered = server
    .get_json("/runs/deep/profiles?trace_id=trace-42")
    .await;
  assert_eq!(filtered["series"][0]["total"], 512_000);

  let mut malformed: Value = serde_json::from_slice(&c.payload).unwrap();
  malformed["profiles"][0]["samples"][0]["frame_ids"] = json!([128]);
  c.id = "invalid-reference".into();
  c.payload = serde_json::to_vec(&malformed).unwrap();
  server.repo.save_profile(&c).unwrap();
  let error = report(&server.repo, "deep", &ProfileQuery::default())
    .err()
    .unwrap()
    .to_string();
  assert!(error.contains("Invalid profile frame reference"), "{error}");
}

#[tokio::test]
async fn counters_sum_but_live_snapshots_use_latest_per_instance_with_exact_filters() {
  let server = TestServer::start().await;
  server.seed_run("run", "app");
  for c in [
    chunk("run", "old", "a", 2000, 500),
    chunk("run", "new", "a", 4000, 7),
    chunk("run", "other", "b", 4000, 3),
  ] {
    server.repo.save_profile(&c).unwrap();
  }
  let result = report(&server.repo, "run", &ProfileQuery::default()).unwrap();
  let data = serde_json::to_value(result).unwrap();
  let series = data["series"].as_array().unwrap();
  let cpu = series.iter().find(|s| s["metric"] == "cpu:cpu").unwrap();
  assert_eq!(cpu["total"], 300);
  assert_eq!(
    cpu["hotspots"][0]["inclusive_value"], 300,
    "recursive frame counted once"
  );
  assert_eq!(cpu["hotspots"][0]["self_percent"], 100.0);
  assert_eq!(
    series
      .iter()
      .find(|s| s["metric"] == "memory:live")
      .unwrap()["total"],
    10
  );
  let filtered=server.get_json("/runs/run/profiles?instance=a&until_ms=3000&metric=memory%3Alive&trace_id=trace-1&span_id=span-1").await;
  assert_eq!(filtered["series"][0]["total"], 500);
  assert_eq!(
    server.get_json("/runs/run/profiles?trace_id=missing").await["series"],
    json!([])
  );
  assert_eq!(
    server.get_json("/runs/run/profiles?service=missing").await["series"],
    json!([])
  );
  assert_eq!(
    server
      .get("/runs/run/profiles?from_ms=10&until_ms=1")
      .await
      .status(),
    400
  );
}

#[tokio::test]
async fn profile_storage_bounds_persists_and_cascades() {
  let temp = tempfile::tempdir().unwrap();
  let path = temp.path().join("profiles.db");
  let repo =
    stove::storage::repository::Repository::connect_sqlite(path.to_str().unwrap(), 0).unwrap();
  repo
    .save_run_start("run", "app", "2024-01-01T00:00:00Z", &[])
    .unwrap();
  for i in 0..260 {
    repo
      .save_profile(&chunk("run", &format!("chunk-{i:03}"), "a", i * 1000, 1))
      .unwrap();
  }
  assert_eq!(repo.profile_chunks("run").unwrap().len(), 256);
  assert_eq!(
    repo.profile_chunks("run").unwrap().last().unwrap().id,
    "chunk-004"
  );
  drop(repo);
  let repo =
    stove::storage::repository::Repository::connect_sqlite(path.to_str().unwrap(), 0).unwrap();
  assert_eq!(repo.profile_chunks("run").unwrap().len(), 256);
  repo.purge_runs(&["run".into()], true).unwrap();
  assert!(repo.profile_chunks("run").unwrap().is_empty());
  assert!(
    repo
      .save_profile(&chunk("run", "late", "a", 300000, 1))
      .is_err()
  );
}

async fn tool(server: &TestServer, name: &str, args: Value) -> Value {
  server.client.post(server.mcp_url()).json(&json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}})).send().await.unwrap().json().await.unwrap()
}
#[tokio::test]
async fn mcp_exposes_evidence_and_rejects_cross_application_baselines() {
  let server = TestServer::start().await;
  for (id, app) in [("run", "app"), ("baseline", "app"), ("unrelated", "other")] {
    server.seed_run(id, app);
    server
      .repo
      .save_profile(&chunk(id, id, "jvm", 2000, 5))
      .unwrap();
  }
  let result = tool(
    &server,
    "stove_profiles",
    json!({"run_id":"run","baseline_run_id":"baseline"}),
  )
  .await;
  let data = &result["result"]["structuredContent"];
  assert_eq!(data["app_name"], "app", "{result}");
  assert_eq!(data["baseline"]["run_id"], "baseline");
  assert_eq!(data["series"][0]["stacks"], json!([]));
  assert!(!data["series"][0]["findings"].as_array().unwrap().is_empty());
  let bad = tool(
    &server,
    "stove_profiles",
    json!({"run_id":"run","baseline_run_id":"unrelated"}),
  )
  .await;
  assert_eq!(bad["result"]["isError"], true, "{bad}");
  let diagnosis = tool(&server, "stove_diagnose", json!({"run_id":"run"})).await;
  assert_eq!(
    diagnosis["result"]["structuredContent"]["profiling"]["run_id"], "run",
    "{diagnosis}"
  );
}

#[tokio::test]
async fn newer_recording_without_live_objects_does_not_resurrect_old_survivors() {
  let server = TestServer::start().await;
  server.seed_run("run", "app");
  server
    .repo
    .save_profile(&chunk("run", "old", "a", 2000, 500))
    .unwrap();
  let mut latest = chunk("run", "new", "a", 4000, 0);
  let mut recording: Value = serde_json::from_slice(&latest.payload).unwrap();
  recording["profiles"]
    .as_array_mut()
    .unwrap()
    .retain(|p| p["metric"] != "memory:live");
  latest.payload = serde_json::to_vec(&recording).unwrap();
  server.repo.save_profile(&latest).unwrap();
  let result = server
    .get_json("/runs/run/profiles?metric=memory%3Alive")
    .await;
  assert_eq!(result["series"], json!([]));
}

#[tokio::test]
async fn mcp_bounds_large_hotspots_and_rejects_oversized_metadata() {
  let server = TestServer::start().await;
  server.seed_run("large-stacks", "app");
  let mut recording = chunk("large-stacks", "profile", "jvm", 2000, 0);
  let samples: Vec<_> = (0..24)
    .map(|index| json!({
      "frames": [{"name": format!("{index}{}", "m".repeat(4090)), "file": "f".repeat(4096), "line": 1}],
      "value": 100,
      "labels": {}
    }))
    .collect();
  recording.payload = serde_json::to_vec(&json!({
    "sample_rate": 100,
    "profiles": [{"metric": "cpu:cpu", "unit": "nanoseconds", "samples": samples}]
  }))
  .unwrap();
  server.repo.save_profile(&recording).unwrap();
  let response = tool(&server, "stove_profiles", json!({"run_id": "large-stacks"})).await;
  let data = &response["result"]["structuredContent"];
  assert_eq!(data["truncated"], true, "{response}");
  assert_eq!(data["series"][0]["total"], 2400);
  assert!(serde_json::to_vec(data).unwrap().len() < 64 * 1024);

  server.seed_run("large-metadata", &"a".repeat(70 * 1024));
  server
    .repo
    .save_profile(&chunk("large-metadata", "metadata-profile", "jvm", 2000, 1))
    .unwrap();
  let response = tool(
    &server,
    "stove_profiles",
    json!({"run_id": "large-metadata"}),
  )
  .await;
  assert_eq!(response["result"]["isError"], true, "{response}");
  assert!(
    response
      .to_string()
      .contains("metadata exceeds the MCP response budget")
  );
}
