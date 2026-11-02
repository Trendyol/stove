mod common;
use common::TestServer;
use serde_json::{Value, json};
use std::io::{Read, Write};
use stove::profiling::{ProfileQuery, StoredChunk, report};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
fn chunk(run: &str, id: &str, service: &str, until: i64, samples: Value) -> StoredChunk {
  let bytes=serde_json::to_vec(&json!({"sample_rate":100,"profiles":[{"metric":"cpu:cpu","unit":"nanoseconds","samples":samples}]})).unwrap();
  let mut z = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
  z.write_all(&bytes).unwrap();
  StoredChunk {
    id: id.into(),
    run_id: run.into(),
    service: service.into(),
    instance: "one".into(),
    from_ms: until - 1000,
    until_ms: until,
    payload: z.finish().unwrap(),
  }
}
fn samples(n: usize, name_len: usize) -> Value {
  Value::Array((0..n).map(|i|json!({"frames":[{"name":format!("{i:02}{}","x".repeat(name_len)),"file":"file","line":i}],"value":1,"labels":{}})).collect())
}
async fn tool(s: &TestServer, run: &str, baseline: Option<&str>) -> Value {
  let mut args = json!({"run_id":run});
  if let Some(b) = baseline {
    args["baseline_run_id"] = json!(b);
  }
  s.client.post(s.mcp_url()).json(&json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stove_profiles","arguments":args}})).send().await.unwrap().json().await.unwrap()
}

#[tokio::test]
async fn retention_and_decode_budgets_share_evidence_across_streams() {
  let s = TestServer::start().await;
  s.seed_run("retention", "app");
  for (service, offset) in [("a", 0), ("b", 300)] {
    for i in 0..300 {
      let mut c = chunk(
        "retention",
        &format!("{service}-{i:03}"),
        service,
        (i + offset) * 1000,
        samples(1, 1),
      );
      // Highly compressible metadata exercises the decoded, rather than stored-byte, budget.
      let mut raw = Vec::new();
      flate2::read::GzDecoder::new(c.payload.as_slice())
        .read_to_end(&mut raw)
        .unwrap();
      let mut v: Value = serde_json::from_slice(&raw).unwrap();
      v["padding"] = json!("x".repeat(300 * 1024));
      let mut z = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
      z.write_all(&serde_json::to_vec(&v).unwrap()).unwrap();
      c.payload = z.finish().unwrap();
      s.repo.save_profile(&c).unwrap();
    }
  }
  let chunks = s.repo.profile_chunks("retention").unwrap();
  assert_eq!(chunks.iter().filter(|c| c.service == "a").count(), 256);
  assert_eq!(chunks.iter().filter(|c| c.service == "b").count(), 256);
  assert_eq!(chunks[0].id, "a-299");
  assert_eq!(chunks[1].id, "b-299");
  let r = report(&s.repo, "retention", &ProfileQuery::default()).unwrap();
  assert!(r.truncated);
  assert_eq!(r.series.len(), 2);
  assert!(
    r.series[0]
      .sample_records
      .abs_diff(r.series[1].sample_records)
      <= 1
  );
  assert!(r.series[1].sample_records > 100);
  let r = report(
    &s.repo,
    "retention",
    &ProfileQuery {
      service: Some("b".into()),
      ..Default::default()
    },
  )
  .unwrap();
  assert_eq!(r.series.len(), 1);
  assert_eq!(r.series[0].service, "b");
  s.repo.purge_runs(&["retention".into()], true).unwrap();
  assert!(s.repo.profile_chunks("retention").unwrap().is_empty());
}

#[tokio::test]
async fn mcp_budgets_the_complete_single_and_baseline_envelope() {
  let s = TestServer::start().await;
  for run in ["large", "baseline"] {
    s.seed_run(run, "app");
    s.repo
      .save_profile(&chunk(run, run, "svc", 1000, samples(20, 3118)))
      .unwrap();
  }
  for baseline in [None, Some("baseline")] {
    let response = tool(&s, "large", baseline).await;
    assert_ne!(response["result"]["isError"], true, "{response}");
    assert!(serde_json::to_vec(&response).unwrap().len() <= 65536);
    let structured = &response["result"]["structuredContent"];
    assert_eq!(structured["truncated"], true);
    assert_eq!(structured["series"][0]["total"], 20);
    let fallback: Value =
      serde_json::from_str(response["result"]["content"][0]["text"].as_str().unwrap()).unwrap();
    assert_eq!(&fallback, structured);
    if baseline.is_some() {
      assert_eq!(structured["baseline"]["series"][0]["total"], 20);
    }
  }
}

#[tokio::test]
async fn grouped_counts_preserve_coverage_and_filters_and_reject_zero() {
  let s = TestServer::start().await;
  for run in ["original", "grouped"] {
    s.seed_run(run, "app");
  }
  let mut one = samples(1, 1)[0].clone();
  one["labels"] = json!({"trace_id":"trace","profile_id":"span"});
  s.repo
    .save_profile(&chunk(
      "original",
      "many",
      "svc",
      1000,
      Value::Array(vec![one.clone(); 20]),
    ))
    .unwrap();
  one["value"] = json!(20);
  one["count"] = json!(20);
  s.repo
    .save_profile(&chunk(
      "grouped",
      "group",
      "svc",
      1000,
      json!([one.clone()]),
    ))
    .unwrap();
  let query = ProfileQuery {
    trace_id: Some("trace".into()),
    span_id: Some("span".into()),
    ..Default::default()
  };
  for run in ["original", "grouped"] {
    let r = report(&s.repo, run, &query).unwrap();
    assert_eq!(r.series[0].sample_records, 20);
    assert_eq!(r.series[0].total, 20);
    assert!(
      !r.series[0]
        .findings
        .iter()
        .any(|f| f.kind == "limited_coverage")
    );
    let absent = ProfileQuery {
      span_id: Some("missing".into()),
      ..query.clone()
    };
    assert!(report(&s.repo, run, &absent).unwrap().series.is_empty());
  }
  s.seed_run("invalid", "app");
  one["count"] = json!(0);
  s.repo
    .save_profile(&chunk("invalid", "zero", "svc", 1000, json!([one])))
    .unwrap();
  assert!(report(&s.repo, "invalid", &query).is_err());
}
fn multipart() -> Vec<u8> {
  let mut body = Vec::new();
  for (field, file) in [("jfr", "dump1.jfr.gz"), ("labels", "dump1.labels.pb.gz")] {
    body.extend_from_slice(format!("--stove\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{field}\"\r\nContent-Type: application/octet-stream\r\n\r\n").as_bytes());
    body.extend(
      std::fs::read(
        std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
          .join("tests/fixtures/profiling")
          .join(file),
      )
      .unwrap(),
    );
    body.extend_from_slice(b"\r\n");
  }
  body.extend_from_slice(b"--stove--\r\n");
  body
}
async fn upload(s: &TestServer, run: &str, service: Option<&str>) -> reqwest::Response {
  let mut r = s
    .client
    .post(format!(
      "{}/ingest?name=app&format=jfr&from=1710000000&until=1710000010&sampleRate=100",
      s.base_url
    ))
    .header("content-type", "multipart/form-data; boundary=stove")
    .header("x-stove-run-id", run)
    .header("x-stove-instance-id", "jvm-1");
  if let Some(name) = service {
    r = r.header("x-stove-service-name", name);
  }
  r.body(multipart()).send().await.unwrap()
}
async fn slow_body(s: &TestServer, run: &str) -> tokio::net::TcpStream {
  let address = s.base_url.strip_prefix("http://").unwrap();
  let mut c = tokio::net::TcpStream::connect(address).await.unwrap();
  let h = format!(
    "POST /ingest?name=app&format=jfr&from=1710000000&until=1710000010&sampleRate=100 HTTP/1.1\r\nHost: {address}\r\nContent-Type: multipart/form-data; boundary=stove\r\nX-Stove-Run-Id: {run}\r\nX-Stove-Instance-Id: slow\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
    multipart().len()
  );
  c.write_all(h.as_bytes()).await.unwrap();
  c.write_all(b"--stove\r\n").await.unwrap();
  c
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn stalled_uploads_leave_conversion_capacity_and_purge_races_retry() {
  let s = TestServer::start().await;
  s.seed_run("upload", "app");
  let first = slow_body(&s, "upload").await;
  let second = slow_body(&s, "upload").await;
  tokio::time::sleep(std::time::Duration::from_millis(200)).await;
  assert_eq!(upload(&s, "upload", None).await.status(), 200);
  drop(first);
  drop(second);
  tokio::time::sleep(std::time::Duration::from_millis(100)).await;
  s.seed_run("race", "app");
  let mut race = slow_body(&s, "race").await;
  tokio::time::sleep(std::time::Duration::from_millis(100)).await;
  s.repo.purge_runs(&["race".into()], true).unwrap();
  race
    .write_all(&multipart()[b"--stove\r\n".len()..])
    .await
    .unwrap();
  let mut wire = Vec::new();
  tokio::time::timeout(
    std::time::Duration::from_secs(10),
    race.read_to_end(&mut wire),
  )
  .await
  .unwrap()
  .unwrap();
  let status = String::from_utf8_lossy(&wire)
    .lines()
    .next()
    .unwrap()
    .to_owned();
  assert!(status.contains("503"), "{status}");
  assert!(s.repo.profile_chunks("race").unwrap().is_empty());
}

#[tokio::test]
async fn profile_http_response_negotiates_gzip() {
  let s = TestServer::start().await;
  s.seed_run("gzip", "app");
  s.repo
    .save_profile(&chunk("gzip", "gzip", "svc", 1000, samples(20, 3000)))
    .unwrap();
  let client = reqwest::Client::builder().no_gzip().build().unwrap();
  let response = client
    .get(s.url("/runs/gzip/profiles"))
    .header("Accept-Encoding", "gzip")
    .send()
    .await
    .unwrap();
  assert_eq!(response.headers()["content-encoding"], "gzip");
  assert!(
    response.headers()["vary"]
      .to_str()
      .unwrap()
      .to_lowercase()
      .contains("accept-encoding")
  );
  let compressed = response.bytes().await.unwrap();
  let mut decoded = Vec::new();
  flate2::read::GzDecoder::new(compressed.as_ref())
    .read_to_end(&mut decoded)
    .unwrap();
  let value: Value = serde_json::from_slice(&decoded).unwrap();
  assert_eq!(value, s.get_json("/runs/gzip/profiles").await);
  assert!(compressed.len() * 10 < decoded.len());
}

#[tokio::test]
async fn traces_and_entries_negotiate_gzip_too() {
  let s = TestServer::start().await;
  s.seed_full_run();
  let client = reqwest::Client::builder().no_gzip().build().unwrap();
  for path in ["/runs/run-1/tests/test-1/entries", "/traces/trace-abc"] {
    let response = client
      .get(s.url(path))
      .header("Accept-Encoding", "gzip")
      .send()
      .await
      .unwrap();
    assert_eq!(response.status(), 200, "{path}");
    assert_eq!(response.headers()["content-encoding"], "gzip", "{path}");
    let bytes = response.bytes().await.unwrap();
    let decoded: Value =
      serde_json::from_reader(flate2::read::GzDecoder::new(bytes.as_ref())).unwrap();
    assert_eq!(decoded, s.get_json(path).await);
  }
}
