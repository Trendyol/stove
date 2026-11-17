use super::{
  LiveDashboardEvent, LiveDashboardPayload, PersistedDashboardEvent, PreparedDashboardEvent,
};
use crate::error::{AppError, Result};
use crate::proto::RunEnvironmentEvent;

pub(super) fn prepare(
  run_id: &str,
  environment: &RunEnvironmentEvent,
) -> Result<PreparedDashboardEvent> {
  validate(environment)?;
  Ok(PreparedDashboardEvent {
    live: LiveDashboardEvent::new(
      run_id,
      LiveDashboardPayload::RunEnvironment(environment.clone()),
    ),
    persisted: PersistedDashboardEvent::RunEnvironment {
      run_id: run_id.to_string(),
      environment: environment.clone(),
    },
  })
}

fn validate(environment: &RunEnvironmentEvent) -> Result<()> {
  let mut ids = std::collections::HashSet::new();
  let valid = environment.components.len() <= 1024
    && environment.components.iter().all(|component| {
      !component.id.is_empty()
        && ids.insert(&component.id)
        && !component.name.is_empty()
        && matches!(component.role.as_str(), "SYSTEM" | "APPLICATION")
        && component.phases.len() <= 16
        && component.phases.iter().all(|phase| {
          !phase.name.is_empty()
            && phase.duration_ms >= 0
            && matches!(
              phase.status.as_str(),
              "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED"
            )
        })
    });
  if !valid {
    return Err(AppError::InvalidEvent("invalid run environment".into()));
  }
  Ok(())
}

#[cfg(test)]
mod tests;
