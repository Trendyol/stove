//! Backend-independent rules for idle expiry.
use crate::config::DEFAULT_RUN_IDLE_TIMEOUT_SECONDS;
use crate::error::{AppError, Result};

pub(in crate::storage::repository) const SWEEP_BATCH_SIZE: i64 = 1000;
const MAX_IDLE_TIMEOUT_SECONDS: u64 = i32::MAX as u64;

/// A validated persisted timeout. Zero disables expiry.
#[derive(Clone, Copy)]
pub(in crate::storage::repository) struct IdleTimeout(u64);

impl IdleTimeout {
  pub fn new(seconds: u64) -> Result<Self> {
    if seconds > MAX_IDLE_TIMEOUT_SECONDS {
      return Err(AppError::InvalidEvent(format!(
        "run_idle_timeout_seconds must be between 0 and {MAX_IDLE_TIMEOUT_SECONDS}"
      )));
    }
    Ok(Self(seconds))
  }

  pub fn from_stored(value: Option<&str>) -> Result<Self> {
    let seconds = value.map_or(Ok(DEFAULT_RUN_IDLE_TIMEOUT_SECONDS), |value| {
      value
        .parse()
        .map_err(|_| AppError::InvalidStoredField(Self::SETTING_KEY))
    })?;
    Self::new(seconds)
  }

  pub const SETTING_KEY: &str = "run_idle_timeout_seconds";

  pub const fn seconds(self) -> u64 {
    self.0
  }

  /// Avoid reading the database clock at all when expiry is disabled.
  pub fn expiry_cutoff(
    self,
    database_now: impl FnOnce() -> Result<String>,
  ) -> Result<Option<String>> {
    if self.0 == 0 {
      return Ok(None);
    }
    let database_time = database_now()?;
    let now = chrono::DateTime::parse_from_rfc3339(&database_time)
      .map_err(|_| AppError::InvalidStoredField("database clock"))?;
    // Construction bounds the timeout to i32::MAX seconds.
    let cutoff = (now - chrono::Duration::seconds(self.0.cast_signed()))
      .to_rfc3339_opts(chrono::SecondsFormat::Millis, true);
    Ok(Some(cutoff))
  }
}
