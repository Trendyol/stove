//! One admission boundary for synchronous work scheduled on Tokio's blocking pool.
use crate::error::{AppError, Result};
use crate::metrics::Operation;
use std::sync::Arc;
use tokio::sync::Semaphore;

pub(crate) async fn admitted<T: Send + 'static>(
  admission: &Arc<Semaphore>,
  metrics: &'static Operation,
  bytes: u64,
  name: &'static str,
  work: impl FnOnce() -> Result<T> + Send + 'static,
) -> Result<T> {
  let permit = admission.clone().try_acquire_owned().map_err(|_| {
    metrics.reject();
    AppError::Overloaded
  })?;
  let mut observation = metrics.start_with_bytes(bytes);
  tokio::task::spawn_blocking(move || {
    // Both guards belong to the worker, even if its awaiting request is cancelled.
    let _permit = permit;
    let result = work();
    observation.finish(&result);
    result
  })
  .await
  .map_err(|error| AppError::Startup(format!("{name} worker failed: {error}")))?
}
