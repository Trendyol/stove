use std::sync::{
  Mutex,
  atomic::{AtomicU64, Ordering},
};
use std::time::Instant;

pub(super) const BOUNDS: [u64; 13] = [
  1_000, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000,
  10_000_000, 30_000_000,
];

#[derive(Default)]
pub(crate) struct Operation {
  snapshot: Mutex<()>,
  active: AtomicU64,
  bytes: AtomicU64,
  rejected: AtomicU64,
  completed: AtomicU64,
  failed: AtomicU64,
  micros: AtomicU64,
  buckets: [AtomicU64; BOUNDS.len()],
}

pub(super) struct Snapshot {
  pub counters: [u64; 5],
  pub micros: u64,
  pub buckets: [u64; BOUNDS.len()],
}

impl Operation {
  pub(crate) fn reject(&self) {
    self.rejected.fetch_add(1, Ordering::Relaxed);
  }
  pub(crate) fn start(&self) -> Observation<'_> {
    self.start_with_bytes(0)
  }
  pub(crate) fn start_with_bytes(&self, bytes: u64) -> Observation<'_> {
    self.active.fetch_add(1, Ordering::Relaxed);
    self.bytes.fetch_add(bytes, Ordering::Relaxed);
    Observation {
      operation: self,
      bytes,
      started: Instant::now(),
      success: false,
    }
  }
  pub(super) fn snapshot(&self) -> Snapshot {
    let _snapshot = self
      .snapshot
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner);
    Snapshot {
      counters: [
        &self.active,
        &self.bytes,
        &self.rejected,
        &self.completed,
        &self.failed,
      ]
      .map(|counter| counter.load(Ordering::Relaxed)),
      micros: self.micros.load(Ordering::Relaxed),
      buckets: std::array::from_fn(|index| self.buckets[index].load(Ordering::Relaxed)),
    }
  }
}

/// Held inside blocking work: cancellation must not falsely report released capacity.
pub(crate) struct Observation<'a> {
  operation: &'a Operation,
  bytes: u64,
  started: Instant,
  success: bool,
}
impl Observation<'_> {
  pub(crate) fn finish<T, E>(&mut self, result: &Result<T, E>) {
    self.success = result.is_ok();
  }
}
impl Drop for Observation<'_> {
  fn drop(&mut self) {
    let _snapshot = self
      .operation
      .snapshot
      .lock()
      .unwrap_or_else(std::sync::PoisonError::into_inner);
    let micros = u64::try_from(self.started.elapsed().as_micros()).unwrap_or(u64::MAX);
    self.operation.micros.fetch_add(micros, Ordering::Relaxed);
    for (bound, bucket) in BOUNDS.iter().zip(&self.operation.buckets) {
      if micros <= *bound {
        bucket.fetch_add(1, Ordering::Relaxed);
      }
    }
    if !self.success {
      self.operation.failed.fetch_add(1, Ordering::Relaxed);
    }
    self.operation.completed.fetch_add(1, Ordering::Relaxed);
    self.operation.active.fetch_sub(1, Ordering::Relaxed);
    self
      .operation
      .bytes
      .fetch_sub(self.bytes, Ordering::Relaxed);
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  #[test]
  fn observations_release_capacity_and_count_failures() {
    let metric = Operation::default();
    {
      let mut observation = metric.start_with_bytes(1024);
      assert_eq!(metric.snapshot().counters[1], 1024);
      observation.finish(&Ok::<_, ()>(()));
    }
    {
      let _observation = metric.start();
    }
    metric.reject();
    let snapshot = metric.snapshot();
    assert_eq!(snapshot.counters, [0, 0, 1, 2, 1]);
    assert!(snapshot.buckets.windows(2).all(|pair| pair[0] <= pair[1]));
    assert!(
      snapshot
        .buckets
        .iter()
        .all(|count| *count <= snapshot.counters[3])
    );
  }
}
