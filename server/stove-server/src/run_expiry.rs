//! Expire quiet producers even when no new events arrive.
use crate::storage::repository::Repository;
use std::{sync::Arc, time::Duration};
use tokio::{sync::watch, task::JoinHandle};

pub fn spawn(repository: Arc<Repository>, mut shutdown: watch::Receiver<bool>) -> JoinHandle<()> {
  tokio::spawn(async move {
    let mut interval = tokio::time::interval(Duration::from_secs(60));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
      tokio::select! {
        biased;
        _ = shutdown.changed() => break,
        _ = interval.tick() => {}
      }
      if *shutdown.borrow() {
        break;
      }
      let repository = repository.clone();
      let result = tokio::task::spawn_blocking(move || {
        let attempt = repository.metrics.app.worker("run_expiry");
        let result = repository.expire_idle_runs();
        attempt.finish(result.is_ok());
        result
      })
      .await;
      match result {
        Ok(Ok(count)) if count > 0 => tracing::info!(count, "Deleted idle runs"),
        Ok(Ok(_)) => {}
        Ok(Err(error)) => tracing::warn!(%error, "Run expiry failed; retrying on next sweep"),
        Err(error) => tracing::warn!(%error, "Run expiry worker failed"),
      }
    }
  })
}
