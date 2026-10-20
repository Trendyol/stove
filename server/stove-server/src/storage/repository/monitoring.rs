use super::{Backend, Repository};
use std::sync::Arc;
use std::time::Duration;

impl Repository {
  /// One sampler and one diagnostic worker per repository. SQL never runs on a
  /// scrape request. Workers hold only weak references while sleeping.
  pub fn start_monitoring(self: &Arc<Self>) {
    if !self.metrics.start_once() {
      return;
    }
    let weak = Arc::downgrade(&self.metrics.app);
    tokio::spawn(async move {
      let mut interval = tokio::time::interval(Duration::from_secs(1));
      interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
      loop {
        let scheduled = interval.tick().await;
        let Some(metrics) = weak.upgrade() else {
          break;
        };
        let attempt = metrics.worker("runtime_heartbeat");
        metrics
          .runtime_delay
          .observe(scheduled.elapsed().as_secs_f64());
        attempt.finish(true);
      }
    });
    let weak = Arc::downgrade(self);
    tokio::spawn(async move {
      let mut interval = tokio::time::interval(Duration::from_secs(10));
      interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
      loop {
        interval.tick().await;
        let Some(repo) = weak.upgrade() else {
          break;
        };
        let attempt = repo.metrics.app.worker("database_capacity");
        let capacity = match &repo.backend {
          Backend::Sqlite(backend) => backend.capacity(),
          Backend::Postgres(backend) => backend.capacity(),
        };
        repo.metrics.sample(capacity);
        attempt.finish(true);
      }
    });
    let weak = Arc::downgrade(self);
    tokio::spawn(async move {
      let mut interval = tokio::time::interval(Duration::from_secs(30));
      interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
      loop {
        interval.tick().await;
        let Some(repo) = weak.upgrade() else {
          break;
        };
        let attempt = repo.metrics.app.worker("database_diagnostics");
        // Await even if a probe stalls: never accumulate detached SQL workers.
        if let Err(error) = tokio::task::spawn_blocking(move || {
          repo.metrics.set_health(match &repo.backend {
            Backend::Sqlite(backend) => backend.probe_roles(),
            Backend::Postgres(backend) => backend.probe_roles(),
          });
          let diagnostics = match &repo.backend {
            Backend::Sqlite(backend) => backend.diagnostics(),
            Backend::Postgres(backend) => backend.diagnostics(),
          };
          let success = diagnostics.is_ok();
          repo.metrics.set_diagnostics(diagnostics);
          attempt.finish(success);
        })
        .await
        {
          tracing::warn!(%error, "Database metrics collector failed");
        }
      }
    });
  }
}
