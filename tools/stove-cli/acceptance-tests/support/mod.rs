use std::collections::HashMap;
use std::io::Read;
use std::net::TcpListener;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use reqwest::{Client, Method, Response};
use serde_json::{Value, json};
use stove::proto;
use stove::proto::dashboard_event_service_client::DashboardEventServiceClient;
use tempfile::TempDir;
use testcontainers::{
  ContainerAsync, GenericImage, ImageExt,
  core::{IntoContainerPort, WaitFor},
  runners::AsyncRunner,
};
use tonic::transport::Channel;

const STARTUP_TIMEOUT: Duration = Duration::from_secs(20);

pub struct RunningStove {
  pub base_url: String,
  pub grpc_url: String,
  pub client: Client,
  child: Child,
  _database_dir: TempDir,
}

impl RunningStove {
  pub async fn start(retention_runs_per_app: Option<usize>) -> Result<Self> {
    Self::start_with_database(retention_runs_per_app, None).await
  }

  pub async fn start_postgres(
    database_url: &str,
    retention_runs_per_app: Option<usize>,
  ) -> Result<Self> {
    Self::start_with_database(retention_runs_per_app, Some(database_url)).await
  }

  async fn start_with_database(
    retention_runs_per_app: Option<usize>,
    database_url: Option<&str>,
  ) -> Result<Self> {
    let http_port = free_port()?;
    let grpc_port = free_port()?;
    let database_dir = tempfile::tempdir().context("create acceptance database directory")?;
    let database_path = database_dir.path().join("acceptance.sqlite");

    let mut command = Command::new(env!("CARGO_BIN_EXE_stove"));
    command
      .arg("--port")
      .arg(http_port.to_string())
      .arg("--grpc-port")
      .arg(grpc_port.to_string())
      .arg("--no-skills-check")
      .env("RUST_LOG", "warn")
      .stdout(Stdio::piped())
      .stderr(Stdio::piped());
    if let Some(database_url) = database_url {
      command.arg("--database-url").arg(database_url);
    } else {
      command.arg("--db").arg(&database_path);
    }
    if let Some(retention) = retention_runs_per_app {
      command
        .arg("--retention-runs-per-app")
        .arg(retention.to_string());
    }

    let child = command.spawn().context("launch the real stove binary")?;
    let mut server = Self {
      base_url: format!("http://127.0.0.1:{http_port}"),
      grpc_url: format!("http://127.0.0.1:{grpc_port}"),
      client: Client::new(),
      child,
      _database_dir: database_dir,
    };
    server.wait_until_ready().await?;
    Ok(server)
  }

  pub fn api_url(&self, path: &str) -> String {
    format!("{}/api/v1{path}", self.base_url)
  }

  pub fn mcp_url(&self) -> String {
    format!("{}/mcp", self.base_url)
  }

  pub async fn grpc_client(&self) -> Result<DashboardEventServiceClient<Channel>> {
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    loop {
      match DashboardEventServiceClient::connect(self.grpc_url.clone()).await {
        Ok(client) => return Ok(client),
        Err(error) if Instant::now() < deadline => {
          tokio::time::sleep(Duration::from_millis(50)).await;
          drop(error);
        }
        Err(error) => return Err(error).context("connect to the stove gRPC server"),
      }
    }
  }

  pub async fn get(&self, path: &str) -> Result<Response> {
    self
      .client
      .get(self.api_url(path))
      .send()
      .await
      .with_context(|| format!("GET {path}"))
  }

  pub async fn get_json(&self, path: &str) -> Result<Value> {
    checked_json(self.get(path).await?).await
  }

  pub async fn request_json(&self, method: Method, path: &str, body: Value) -> Result<Value> {
    let response = self
      .client
      .request(method, self.api_url(path))
      .json(&body)
      .send()
      .await
      .with_context(|| format!("request {path}"))?;
    checked_json(response).await
  }

  pub async fn mcp_call(&self, method: &str, params: Value) -> Result<Value> {
    let response = self
      .client
      .post(self.mcp_url())
      .header(
        reqwest::header::ACCEPT,
        "application/json, text/event-stream",
      )
      .json(&json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": method,
        "params": params,
      }))
      .send()
      .await
      .with_context(|| format!("MCP call {method}"))?;
    checked_json(response).await
  }

  pub async fn mcp_tool(&self, name: &str, arguments: Value) -> Result<Value> {
    self
      .mcp_call(
        "tools/call",
        json!({
          "name": name,
          "arguments": arguments,
        }),
      )
      .await
  }

  pub async fn follow_tool_call(&self, call: &Value) -> Result<Value> {
    let tool = call["tool"].as_str().context("tool call has a tool name")?;
    self.mcp_tool(tool, call["arguments"].clone()).await
  }

  async fn wait_until_ready(&mut self) -> Result<()> {
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    loop {
      if let Some(status) = self.child.try_wait().context("inspect stove process")? {
        let output = self.read_output();
        bail!("stove exited during startup with {status}:\n{output}");
      }
      if self
        .client
        .get(self.api_url("/meta"))
        .send()
        .await
        .is_ok_and(|response| response.status().is_success())
      {
        return Ok(());
      }
      if Instant::now() >= deadline {
        bail!("stove did not become ready at {}", self.base_url);
      }
      tokio::time::sleep(Duration::from_millis(50)).await;
    }
  }

  fn read_output(&mut self) -> String {
    let mut output = String::new();
    if let Some(stdout) = &mut self.child.stdout {
      let _ = stdout.read_to_string(&mut output);
    }
    if let Some(stderr) = &mut self.child.stderr {
      let _ = stderr.read_to_string(&mut output);
    }
    output
  }
}

pub struct PostgresTestDatabase {
  pub url: String,
  _container: ContainerAsync<GenericImage>,
}

impl PostgresTestDatabase {
  pub async fn start() -> Result<Self> {
    let container = GenericImage::new("postgres", "17-alpine")
      .with_exposed_port(5432.tcp())
      .with_wait_for(WaitFor::message_on_stdout(
        "PostgreSQL init process complete; ready for start up.",
      ))
      .with_env_var("POSTGRES_DB", "postgres")
      .with_env_var("POSTGRES_USER", "postgres")
      .with_env_var("POSTGRES_PASSWORD", "postgres")
      .start()
      .await
      .context("start PostgreSQL Testcontainer")?;
    let host = container
      .get_host()
      .await
      .context("resolve PostgreSQL Testcontainer host")?;
    let port = container
      .get_host_port_ipv4(5432)
      .await
      .context("resolve PostgreSQL Testcontainer port")?;
    let url = format!("postgresql://postgres:postgres@{host}:{port}/postgres?sslmode=disable");
    wait_for_postgres(&url).await?;
    Ok(Self {
      url,
      _container: container,
    })
  }

  pub async fn with_client<T, F>(&self, operation: F) -> Result<T>
  where
    T: Send + 'static,
    F: FnOnce(&mut postgres::Client) -> Result<T> + Send + 'static,
  {
    let database_url = self.url.clone();
    tokio::task::spawn_blocking(move || {
      let mut client = connect_postgres(&database_url)?;
      operation(&mut client)
    })
    .await
    .context("join PostgreSQL acceptance operation")?
  }
}

impl Drop for RunningStove {
  fn drop(&mut self) {
    if self.child.try_wait().ok().flatten().is_none() {
      let _ = self.child.kill();
    }
    let _ = self.child.wait();
  }
}

pub async fn send_events(
  client: &mut DashboardEventServiceClient<Channel>,
  events: impl IntoIterator<Item = proto::DashboardEvent>,
) -> Result<()> {
  for event in events {
    let ack = client
      .send_event(event)
      .await
      .context("send dashboard event")?
      .into_inner();
    if !ack.accepted {
      bail!("stove rejected a dashboard event");
    }
  }
  Ok(())
}

pub fn run_started(
  run_id: &str,
  app_name: &str,
  seconds: i64,
  metadata: &[(&str, &str)],
) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::RunStarted(
      proto::RunStartedEvent {
        timestamp: timestamp(seconds),
        app_name: app_name.to_string(),
        systems: vec!["HTTP".to_string(), "Kafka".to_string()],
        stove_version: stove::STOVE_CLI_VERSION.to_string(),
        metadata: metadata
          .iter()
          .map(|(key, value)| ((*key).to_string(), (*value).to_string()))
          .collect(),
      },
    )),
  }
}

pub fn run_ended(
  run_id: &str,
  seconds: i64,
  total_tests: i32,
  passed: i32,
  failed: i32,
) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::RunEnded(
      proto::RunEndedEvent {
        timestamp: timestamp(seconds),
        total_tests,
        passed,
        failed,
        duration_ms: 1_000,
      },
    )),
  }
}

pub fn test_started(
  run_id: &str,
  test_id: &str,
  seconds: i64,
  test_name: &str,
) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::TestStarted(
      proto::TestStartedEvent {
        test_id: test_id.to_string(),
        test_name: test_name.to_string(),
        spec_name: "AcceptanceSpec".to_string(),
        timestamp: timestamp(seconds),
        test_path: vec!["acceptance".to_string(), test_name.to_string()],
      },
    )),
  }
}

pub fn test_ended(
  run_id: &str,
  test_id: &str,
  seconds: i64,
  status: &str,
  error: &str,
) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::TestEnded(
      proto::TestEndedEvent {
        test_id: test_id.to_string(),
        status: status.to_string(),
        duration_ms: 750,
        error: error.to_string(),
        timestamp: timestamp(seconds),
      },
    )),
  }
}

pub fn failed_entry(run_id: &str, test_id: &str, seconds: i64) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::EntryRecorded(
      proto::EntryRecordedEvent {
        test_id: test_id.to_string(),
        timestamp: timestamp(seconds),
        system: "HTTP".to_string(),
        action: "POST /orders".to_string(),
        result: "FAILED".to_string(),
        input: r#"{"authorization":"secret","order":"42"}"#.to_string(),
        output: r#"{"status":"DECLINED"}"#.to_string(),
        metadata: HashMap::from([("attempt".to_string(), "1".to_string())]),
        expected: "ACCEPTED".to_string(),
        actual: "DECLINED".to_string(),
        error: "payment declined".to_string(),
        trace_id: "trace-42".to_string(),
      },
    )),
  }
}

pub fn failed_span(run_id: &str) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::SpanRecorded(
      proto::SpanRecordedEvent {
        trace_id: "trace-42".to_string(),
        span_id: "span-42".to_string(),
        parent_span_id: String::new(),
        operation_name: "POST /orders".to_string(),
        service_name: "checkout-api".to_string(),
        start_time_nanos: 1_000_000,
        end_time_nanos: 8_000_000,
        status: "ERROR".to_string(),
        attributes: HashMap::from([("x-stove-test-id".to_string(), "test-failed".to_string())]),
        exception: Some(proto::ExceptionInfo {
          r#type: "PaymentDeclinedException".to_string(),
          message: "payment declined".to_string(),
          stack_trace: vec!["checkout.PaymentClient.authorize".to_string()],
        }),
      },
    )),
  }
}

pub fn snapshot(run_id: &str, test_id: &str, seconds: i64) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::Snapshot(
      proto::SnapshotEvent {
        test_id: test_id.to_string(),
        system: "Kafka".to_string(),
        state_json: r#"{"published":[],"failed":[{"order":"42"}]}"#.to_string(),
        summary: "No accepted order event".to_string(),
        timestamp: timestamp(seconds),
        trigger: "FAILURE".to_string(),
      },
    )),
  }
}

pub fn mock_interaction(run_id: &str, test_id: &str, seconds: i64) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::MockInteraction(
      proto::MockInteractionEvent {
        test_id: test_id.to_string(),
        timestamp: timestamp(seconds),
        system: "WireMock".to_string(),
        protocol: "HTTP".to_string(),
        method: "POST".to_string(),
        target: "/payments".to_string(),
        matched: false,
        stub_id: "payment-stub".to_string(),
        attribution: proto::MockInteractionAttribution::ProvenHeader as i32,
        request_body: r#"{"authorization":"secret"}"#.to_string(),
        request_body_truncated: false,
        response_body: String::new(),
        response_body_truncated: false,
        status: "404".to_string(),
        latency_ms: 20,
        near_misses: vec!["expected amount 100, got 99".to_string()],
        trace_id: "trace-42".to_string(),
        scenario_name: String::new(),
        scenario_state: String::new(),
        next_scenario_state: String::new(),
        configured_delay_ms: -1,
        fault: String::new(),
        client_deadline_ms: -1,
      },
    )),
  }
}

pub fn mock_warning(run_id: &str, test_id: &str, seconds: i64) -> proto::DashboardEvent {
  proto::DashboardEvent {
    run_id: run_id.to_string(),
    event: Some(proto::dashboard_event::Event::MockWarning(
      proto::MockWarningEvent {
        test_id: test_id.to_string(),
        timestamp: timestamp(seconds),
        system: "WireMock".to_string(),
        kind: "UNUSED_STUB".to_string(),
        message: "payment fallback was unused".to_string(),
        stub_id: "fallback-stub".to_string(),
        target: "/payments/fallback".to_string(),
      },
    )),
  }
}

fn timestamp(seconds: i64) -> Option<prost_types::Timestamp> {
  Some(prost_types::Timestamp { seconds, nanos: 0 })
}

fn free_port() -> Result<u16> {
  let listener = TcpListener::bind("127.0.0.1:0").context("reserve a local port")?;
  Ok(listener.local_addr()?.port())
}

fn connect_postgres(database_url: &str) -> Result<postgres::Client> {
  if database_url.contains("sslmode=disable") {
    return postgres::Client::connect(database_url, postgres::NoTls)
      .context("connect to acceptance PostgreSQL");
  }
  let connector = native_tls::TlsConnector::builder()
    .build()
    .context("build PostgreSQL TLS connector")?;
  postgres::Client::connect(
    database_url,
    postgres_native_tls::MakeTlsConnector::new(connector),
  )
  .context("connect to acceptance PostgreSQL")
}

async fn wait_for_postgres(database_url: &str) -> Result<()> {
  let deadline = Instant::now() + STARTUP_TIMEOUT;
  loop {
    let database_url = database_url.to_string();
    let ready = tokio::task::spawn_blocking(move || {
      connect_postgres(&database_url)
        .and_then(|mut client| {
          client
            .simple_query("SELECT 1")
            .context("probe PostgreSQL Testcontainer")?;
          Ok(())
        })
        .is_ok()
    })
    .await
    .context("join PostgreSQL readiness probe")?;
    if ready {
      return Ok(());
    }
    if Instant::now() >= deadline {
      bail!("PostgreSQL Testcontainer did not accept connections before the startup timeout");
    }
    tokio::time::sleep(Duration::from_millis(50)).await;
  }
}

async fn checked_json(response: Response) -> Result<Value> {
  let status = response.status();
  let body = response.text().await.context("read response body")?;
  if !status.is_success() {
    bail!("HTTP {status}: {body}");
  }
  serde_json::from_str(&body).with_context(|| format!("decode JSON response: {body}"))
}
