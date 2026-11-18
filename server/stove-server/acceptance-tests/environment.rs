use super::support::{PostgresTestDatabase, RunningStove, run_started, send_events};
use anyhow::Result;
use stove::proto::{self, dashboard_event::Event};

#[tokio::test]
async fn run_environment_persists_through_both_storage_backends() -> Result<()> {
  let postgres = PostgresTestDatabase::start().await?;
  let servers = [
    RunningStove::start(Some(0)).await?,
    RunningStove::start_postgres(&postgres.url, Some(0)).await?,
  ];
  for server in servers {
    verify_environment(&server).await?;
  }
  Ok(())
}

async fn verify_environment(server: &RunningStove) -> Result<()> {
  let mut grpc = server.grpc_client().await?;
  send_events(&mut grpc, [run_started("inventory", "checkout", 1, &[])]).await?;
  assert!(
    server
      .get_json("/runs/inventory/environment")
      .await?
      .is_null()
  );
  for status in ["RUNNING", "FAILED"] {
    verify_phase(server, &mut grpc, status).await?;
  }
  Ok(())
}

async fn verify_phase(
  server: &RunningStove,
  grpc: &mut super::DashboardEventServiceClient<super::Channel>,
  status: &str,
) -> Result<()> {
  let environment = inventory(status);
  send_events(
    grpc,
    [proto::DashboardEvent {
      run_id: "inventory".into(),
      event: Some(Event::RunEnvironment(environment.clone())),
      ..Default::default()
    }],
  )
  .await?;
  let stored = server.get_json("/runs/inventory/environment").await?;
  assert_eq!(stored, serde_json::to_value(environment)?);
  Ok(())
}

fn inventory(status: &str) -> proto::RunEnvironmentEvent {
  proto::RunEnvironmentEvent {
    components: vec![proto::RunComponent {
      id: "postgres-analytics".into(),
      name: "PostgreSQL".into(),
      key: "analytics".into(),
      r#type: "PostgresqlSystem".into(),
      role: "SYSTEM".into(),
      phases: vec![proto::ComponentPhase {
        name: "START".into(),
        status: status.into(),
        started_at: "2026-11-17T00:00:00Z".into(),
        duration_ms: 123,
        error: String::new(),
      }],
    }],
  }
}
