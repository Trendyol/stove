use super::*;
use crate::{
  ingest::EventIngestor, proto, sse::manager::SseManager, storage::repository::Repository,
};
use prometheus::TextEncoder;
use std::time::Instant;

fn scrape(registry: &Registry, metrics: &AppMetrics) -> String {
  metrics.refresh();
  TextEncoder::new()
    .encode_to_string(&registry.gather())
    .unwrap()
}

#[test]
fn pending_events_and_failed_work_are_cleaned_up_on_drop() {
  let registry = Registry::new();
  let metrics = AppMetrics::new(&registry);
  let first = metrics.ingest();
  let second = metrics.ingest();
  let text = scrape(&registry, &metrics);
  assert!(text.contains("stove_app_ingestion_in_flight 2\n"));
  assert!(!text.contains("stove_app_ingestion_oldest_pending_age_seconds 0\n"));
  drop(first);
  assert!(scrape(&registry, &metrics).contains("stove_app_ingestion_in_flight 1\n"));
  drop(second);
  let text = scrape(&registry, &metrics);
  assert!(text.contains("stove_app_ingestion_in_flight 0\n"));
  assert!(text.contains("stove_app_ingestion_oldest_pending_age_seconds 0\n"));
  assert!(text.contains("stove_app_ingestion_duration_seconds_count 2\n"));

  let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
    let _work = metrics.worker("test");
    let _ingestion = metrics.ingest();
    panic!("worker failed");
  }));
  let text = scrape(&registry, &metrics);
  assert!(text.contains("stove_app_worker_runs_total{outcome=\"error\",worker=\"test\"} 1\n"));
  assert!(text.contains("stove_app_ingestion_in_flight 0\n"));
  assert!(text.contains("stove_app_ingestion_events_total{outcome=\"error\"} 3\n"));
  metrics.worker("test").finish(true);
  assert!(metrics.worker_success.with_label_values(&["test"]).get() > 0.);
}

#[test]
fn pending_ingestion_age_exposes_a_blocked_database_writer() {
  let repository = Arc::new(Repository::connect_sqlite(":memory:", 0).unwrap());
  let ingestor = EventIngestor::new(repository.clone(), Arc::new(SseManager::new()));
  let handle = repository.with_write_db_locked(|| {
    let handle = std::thread::spawn(move || {
      ingestor.ingest(&proto::DashboardEvent {
        run_id: "pending-test".into(),
        event: Some(proto::dashboard_event::Event::RunStarted(
          proto::RunStartedEvent {
            app_name: "pending-test".into(),
            ..Default::default()
          },
        )),
        ..Default::default()
      })
    });
    let deadline = Instant::now() + std::time::Duration::from_secs(2);
    loop {
      let text = repository.metrics.prometheus();
      if text
        .lines()
        .any(|line| line.starts_with("stove_app_ingestion_in_flight{") && line.ends_with(" 1"))
        && text.lines().any(|line| {
          line.starts_with("stove_app_ingestion_oldest_pending_age_seconds{")
            && !line.ends_with(" 0")
        })
      {
        break;
      }
      assert!(Instant::now() < deadline, "ingestion did not start");
      std::thread::sleep(std::time::Duration::from_millis(1));
    }
    handle
  });
  assert!(handle.join().unwrap().unwrap().accepted);
  let text = repository.metrics.prometheus();
  assert!(
    text
      .lines()
      .any(|line| line.starts_with("stove_app_ingestion_in_flight{") && line.ends_with(" 0"))
  );
  assert!(text.lines().any(|line| {
    line.starts_with("stove_app_ingestion_oldest_pending_age_seconds{") && line.ends_with(" 0")
  }));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn http_metrics_use_route_templates_exclude_scrapes_and_track_stream_lifetime() {
  let repository = Arc::new(Repository::connect_sqlite(":memory:", 0).unwrap());
  let manager = Arc::new(SseManager::new());
  let ingestor = EventIngestor::new(repository.clone(), manager.clone());
  let app = crate::http::server::create_router_with_public_url(
    repository.clone(),
    manager,
    ingestor,
    Some("https://stove.example/observe".into()),
  );
  let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
  let base = format!("http://{}", listener.local_addr().unwrap());
  let server = tokio::spawn(async move {
    axum::serve(listener, app).await.unwrap();
  });
  let client = reqwest::Client::new();
  for id in ["private-run-one", "private-run-two"] {
    assert_eq!(
      client
        .get(format!("{base}/observe/api/v1/runs/{id}"))
        .send()
        .await
        .unwrap()
        .status(),
      200
    );
  }
  client
    .get(format!("{base}/random-private-path"))
    .send()
    .await
    .unwrap();
  for path in ["/metrics", "/observe/metrics"] {
    client.get(format!("{base}{path}")).send().await.unwrap();
  }
  let text = repository.metrics.prometheus();
  assert!(!text.contains("private-run"));
  assert!(!text.contains("random-private-path"));
  let requests: Vec<_> = text
    .lines()
    .filter(|line| line.starts_with("stove_app_http_requests_total{"))
    .collect();
  assert_eq!(requests.len(), 2, "{requests:?}");
  assert!(
    requests
      .iter()
      .any(|line| line.contains("/api/v1/runs/{run_id}")
        && line.contains("status=\"200\"")
        && line.ends_with(" 2"))
  );
  assert!(!requests.iter().any(|line| line.contains("/metrics")));
  let response = client
    .get(format!("{base}/api/v1/events/stream"))
    .send()
    .await
    .unwrap();
  assert_eq!(
    repository
      .metrics
      .app
      .streams
      .with_label_values(&["sse"])
      .get(),
    1
  );
  drop(response);
  tokio::time::timeout(std::time::Duration::from_secs(2), async {
    while repository
      .metrics
      .app
      .streams
      .with_label_values(&["sse"])
      .get()
      != 0
    {
      tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
  })
  .await
  .unwrap();
  server.abort();
}

#[cfg(target_os = "linux")]
#[test]
fn process_metrics_come_from_the_prometheus_collector() {
  let registry = Registry::new();
  let metrics = AppMetrics::new(&registry);
  let text = scrape(&registry, &metrics);
  for name in [
    "stove_app_process_cpu_seconds_total",
    "stove_app_process_resident_memory_bytes",
    "stove_app_process_open_fds",
    "stove_app_process_threads",
  ] {
    assert!(
      text.lines().any(|line| line.starts_with(name)),
      "missing {name}"
    );
  }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn grpc_stream_gauge_is_released_when_ingestion_fails() {
  use crate::proto::{
    dashboard_event_service_client::DashboardEventServiceClient,
    dashboard_event_service_server::DashboardEventServiceServer,
  };
  use tokio_stream::wrappers::{ReceiverStream, TcpListenerStream};
  let repository = Arc::new(Repository::connect_sqlite(":memory:", 0).unwrap());
  let ingestor = EventIngestor::new(repository.clone(), Arc::new(SseManager::new()));
  let service = crate::grpc::service::DashboardEventServiceImpl::new(ingestor);
  let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
  let address = format!("http://{}", listener.local_addr().unwrap());
  let server = tokio::spawn(async move {
    tonic::transport::Server::builder()
      .add_service(DashboardEventServiceServer::new(service))
      .serve_with_incoming(TcpListenerStream::new(listener))
      .await
      .unwrap();
  });
  let mut client = DashboardEventServiceClient::connect(address).await.unwrap();
  let (sender, receiver) = tokio::sync::mpsc::channel(1);
  let response =
    tokio::spawn(async move { client.stream_events(ReceiverStream::new(receiver)).await });
  tokio::time::timeout(std::time::Duration::from_secs(2), async {
    while repository
      .metrics
      .app
      .streams
      .with_label_values(&["grpc"])
      .get()
      != 1
    {
      tokio::time::sleep(std::time::Duration::from_millis(1)).await;
    }
  })
  .await
  .unwrap();
  sender.send(proto::DashboardEvent::default()).await.unwrap();
  let error = response.await.unwrap().unwrap_err();
  assert_eq!(error.code(), tonic::Code::InvalidArgument);
  assert_eq!(
    repository
      .metrics
      .app
      .streams
      .with_label_values(&["grpc"])
      .get(),
    0
  );
  let text = repository.metrics.prometheus();
  assert!(
    text
      .lines()
      .any(|line| line.starts_with("stove_app_ingestion_events_total{")
        && line.contains("outcome=\"error\"")
        && line.ends_with(" 1"))
  );
  server.abort();
}
