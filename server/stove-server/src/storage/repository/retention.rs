//! Outcome-specific quotas. Each ordered metadata rule owns an independent
//! per-application bucket; unmatched runs use the default bucket.
use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use utoipa::ToSchema;

use crate::error::{AppError, Result};

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RetentionPolicy {
  pub passed_runs_per_app: usize,
  pub failed_runs_per_app: usize,
  #[serde(default)]
  #[schema(required = true)]
  pub rules: Vec<RetentionRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RetentionRule {
  /// Omit to match this metadata across all applications.
  pub app_name: Option<String>,
  /// Exact values: OR within each key, AND across keys. Must not be empty.
  pub metadata: BTreeMap<String, Vec<String>>,
  /// Omit to inherit the default passed-run limit. Zero means unlimited.
  pub passed_runs_per_app: Option<usize>,
  /// Omit to inherit the default failed-run limit. Zero means unlimited.
  pub failed_runs_per_app: Option<usize>,
}

impl Default for RetentionPolicy {
  fn default() -> Self {
    Self {
      passed_runs_per_app: 3,
      failed_runs_per_app: 5,
      rules: Vec::new(),
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(super) enum Outcome {
  Passed,
  Failed,
}

impl Outcome {
  fn from_status(status: &str) -> Option<Self> {
    match status {
      "RUNNING" => None,
      "PASSED" => Some(Self::Passed),
      _ => Some(Self::Failed),
    }
  }
}

pub(super) struct RetentionCandidate {
  pub id: String,
  pub status: String,
  pub metadata: BTreeMap<String, String>,
}

impl RetentionRule {
  fn matches(&self, app: &str, metadata: &BTreeMap<String, String>) -> bool {
    self.app_name.as_deref().is_none_or(|name| name == app)
      && super::metadata::matches_metadata(self.metadata.iter(), metadata)
  }

  fn limit(&self, outcome: Outcome) -> Option<usize> {
    match outcome {
      Outcome::Passed => self.passed_runs_per_app,
      Outcome::Failed => self.failed_runs_per_app,
    }
  }
}

impl RetentionPolicy {
  pub const SETTING_KEY: &'static str = "retention_policy";

  pub fn validate(&self) -> Result<()> {
    if self.rules.len() > 100 {
      return Err(AppError::InvalidEvent(
        "At most 100 retention rules are allowed".into(),
      ));
    }
    for rule in &self.rules {
      if rule
        .app_name
        .as_ref()
        .is_some_and(|name| name.trim().is_empty())
        || rule.metadata.is_empty()
        || rule
          .metadata
          .iter()
          .any(|(key, values)| key.trim().is_empty() || values.is_empty())
        || (rule.passed_runs_per_app.is_none() && rule.failed_runs_per_app.is_none())
      {
        return Err(AppError::InvalidEvent(
          "Retention rules require non-empty metadata keys and value lists, at least one limit, and a non-blank application when specified".into(),
        ));
      }
    }
    Ok(())
  }

  pub(super) fn disabled(&self) -> bool {
    self.passed_runs_per_app == 0 && self.failed_runs_per_app == 0 && self.rules.is_empty()
  }

  pub(super) fn from_stored(value: &str) -> Result<Self> {
    let policy: Self =
      serde_json::from_str(value).map_err(|_| AppError::InvalidStoredField("retention_policy"))?;
    policy
      .validate()
      .map_err(|_| AppError::InvalidStoredField("retention_policy"))?;
    Ok(policy)
  }

  pub(super) fn default_limit(&self, outcome: Outcome) -> usize {
    match outcome {
      Outcome::Passed => self.passed_runs_per_app,
      Outcome::Failed => self.failed_runs_per_app,
    }
  }

  fn quota(
    &self,
    app: &str,
    candidate: &RetentionCandidate,
    outcome: Outcome,
  ) -> (Option<usize>, usize) {
    let bucket = self
      .rules
      .iter()
      .position(|rule| rule.matches(app, &candidate.metadata));
    let limit = bucket
      .and_then(|index| self.rules[index].limit(outcome))
      .unwrap_or_else(|| self.default_limit(outcome));
    (bucket, limit)
  }

  /// Input is ordered newest first by the backend's stable ingestion ordering.
  pub(super) fn expired(
    &self,
    app: &str,
    candidates: impl IntoIterator<Item = RetentionCandidate>,
    protected_run: Option<&str>,
  ) -> Vec<String> {
    let mut counts = HashMap::<(Option<usize>, Outcome), usize>::new();
    let mut expired = Vec::new();
    for candidate in candidates {
      let Some(outcome) = Outcome::from_status(&candidate.status) else {
        continue;
      };
      let (bucket, limit) = self.quota(app, &candidate, outcome);
      if limit == 0 {
        continue;
      }
      let count = counts.entry((bucket, outcome)).or_default();
      *count += 1;
      // Count the protected run in its bucket but let its receipt/outbox commit.
      if *count > limit && Some(candidate.id.as_str()) != protected_run {
        expired.push(candidate.id);
      }
    }
    expired
  }
}

#[cfg(test)]
pub(crate) fn equal_retention(limit: usize) -> RetentionPolicy {
  RetentionPolicy {
    passed_runs_per_app: limit,
    failed_runs_per_app: limit,
    rules: Vec::new(),
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn shared_retention_contract() {
    let fixtures: serde_json::Value =
      serde_json::from_str(include_str!("../../../tests/fixtures/retention.json")).unwrap();
    for case in fixtures["valid"].as_array().unwrap() {
      let policy: RetentionPolicy = serde_json::from_value(case["policy"].clone()).unwrap();
      policy.validate().unwrap();
      let runs = case["runs"].as_array().unwrap();
      let apps: std::collections::BTreeSet<_> = runs
        .iter()
        .map(|run| run["app_name"].as_str().unwrap())
        .collect();
      let mut expired = Vec::new();
      for app in apps {
        let candidates =
          runs
            .iter()
            .filter(|run| run["app_name"] == app)
            .map(|run| RetentionCandidate {
              id: run["id"].as_str().unwrap().into(),
              status: run["status"].as_str().unwrap().into(),
              metadata: serde_json::from_value(run["metadata"].clone()).unwrap(),
            });
        expired.extend(policy.expired(app, candidates, case["protected_run"].as_str()));
      }
      expired.sort();
      let mut expected: Vec<String> = serde_json::from_value(case["expired"].clone()).unwrap();
      expected.sort();
      assert_eq!(expired, expected, "{}", case["name"]);
    }
    for case in fixtures["invalid"].as_array().unwrap() {
      let mut input = case["policy"].clone();
      if let Some(count) = case["repeat_rules"].as_u64() {
        input["rules"] = serde_json::Value::Array(vec![
          input["rules"][0].clone();
          usize::try_from(count).unwrap()
        ]);
      }
      let parsed = serde_json::from_value::<RetentionPolicy>(input);
      assert!(
        parsed.map_or(true, |policy| policy.validate().is_err()),
        "{}",
        case["name"]
      );
    }
  }
}
