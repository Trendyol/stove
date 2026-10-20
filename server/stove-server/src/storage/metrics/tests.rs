use super::*;

#[test]
fn histogram_exposition_is_cumulative_and_escapes_labels() {
  let metrics = StorageMetrics::with_instance("sqlite", "host\"\\\nname");
  metrics
    .instruments
    .observe("operation", "get_runs", "read", 0.002, Outcome::default());
  metrics.instruments.observe(
    "operation",
    "get_runs",
    "read",
    0.2,
    Outcome::from_error(Some(&AppError::Startup(
      "secret SQL must never be exported".into(),
    ))),
  );
  let output = metrics.prometheus();
  let buckets: Vec<_> = output
    .lines()
    .filter(|line| line.starts_with("stove_db_duration_seconds_bucket"))
    .collect();
  assert_eq!(buckets.len(), 18);
  assert!(buckets[2].ends_with(" 0"));
  assert!(buckets[3].ends_with(" 1"));
  assert!(buckets.last().unwrap().ends_with(" 2"));
  assert!(output.contains("stove_instance=\"host\\\"\\\\\\nname\""));
  assert!(!output.contains("secret SQL"));
  assert!(
    output
      .lines()
      .any(|line| line.starts_with("stove_db_errors_total{") && line.ends_with(" 1"))
  );
}

#[test]
fn retention_counts_only_committed_deletions_and_bounds_pending_state() {
  let metrics = StorageMetrics::new("sqlite");
  metrics
    .measure("update_retention", "write", || {
      for _ in 0..1000 {
        retention(|| Ok(2))?;
      }
      Ok(())
    })
    .unwrap();
  let result: Result<()> = metrics.measure("commit_dashboard_event", "write", || {
    retention(|| Ok(9))?;
    Err(AppError::Startup("commit failed".into()))
  });
  assert!(result.is_err());
  assert_eq!(metrics.snapshot().counters["retention_runs_deleted"], 2000);
  let maintenance =
    metrics
      .instruments
      .durations
      .with_label_values(&["maintenance", "retention", "write"]);
  assert_eq!(maintenance.get_sample_count(), 2);
  assert_eq!(
    metrics
      .instruments
      .errors
      .with_label_values(&["maintenance", "retention", "write"])
      .get(),
    1
  );
}

#[test]
fn nested_measurements_and_panics_restore_thread_local_state() {
  let metrics = StorageMetrics::new("sqlite");
  let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
    let _: Result<()> = metrics.measure("purge_runs", "write", || {
      retention(|| Ok(10))?;
      panic!("operation failed");
    });
  }));
  metrics.measure("get_runs", "read", || Ok(())).unwrap();
  assert_eq!(metrics.snapshot().counters["retention_runs_deleted"], 0);
  assert!(MAINTENANCE.with(|pending| pending.borrow().is_none()));
}

#[test]
fn acquisition_wait_is_excluded_from_execution_and_waiters_are_live() {
  let metrics = StorageMetrics::new("sqlite");
  metrics.sample(vec![Capacity {
    role: "write".into(),
    ..Capacity::default()
  }]);
  metrics
    .measure("save_run", "write", || {
      metrics.wait("mutex", "write", || {
        assert_eq!(metrics.snapshot().capacity[0].waiters, 1);
        std::thread::sleep(std::time::Duration::from_millis(10));
        Ok(())
      })
    })
    .unwrap();
  assert_eq!(metrics.snapshot().capacity[0].waiters, 0);
  let sum = |kind, name| {
    metrics
      .instruments
      .durations
      .with_label_values(&[kind, name, "write"])
      .get_sample_sum()
  };
  let total = sum("operation", "save_run");
  let execution = sum("execution", "save_run");
  let wait = sum("wait", "mutex");
  assert!(wait >= 0.01);
  assert!((total - execution - wait).abs() < 0.000_001);
}

#[test]
fn failed_diagnostics_preserve_last_sample_and_report_failure() {
  let metrics = StorageMetrics::new("sqlite");
  metrics.set_diagnostics(Ok(DatabaseDiagnostics {
    database_bytes: Some(4096),
    ..DatabaseDiagnostics::default()
  }));
  let collected_at = metrics.snapshot().diagnostics.collected_at;
  metrics.set_diagnostics(Err("Database connection failed".into()));
  let snapshot = metrics.snapshot();
  assert_eq!(snapshot.diagnostics.database_bytes, Some(4096));
  assert_eq!(snapshot.diagnostics.collected_at, collected_at);
  assert!(snapshot.diagnostics.error.is_some());
  assert!(
    metrics
      .prometheus()
      .lines()
      .any(|line| line.starts_with("stove_db_diagnostics_up{") && line.ends_with(" 0"))
  );
}

#[test]
fn external_statistics_resets_and_unavailable_values_are_reflected_on_scrapes() {
  let metrics = StorageMetrics::new("postgresql");
  for deadlocks in [12, 12, 15, 2] {
    metrics.set_diagnostics(Ok(DatabaseDiagnostics {
      deadlocks: Some(deadlocks),
      blocked_sessions: Some(3),
      ..DatabaseDiagnostics::default()
    }));
    assert!(metrics.prometheus().lines().any(|line| {
      line.starts_with("stove_db_postgres_deadlocks_total{")
        && line.ends_with(&format!(" {deadlocks}"))
    }));
  }
  metrics.set_diagnostics(Ok(DatabaseDiagnostics::default()));
  let output = metrics.prometheus();
  assert!(!output.contains("stove_db_postgres_deadlocks_total{"));
  assert!(!output.contains("stove_db_blocked_sessions{"));
}
