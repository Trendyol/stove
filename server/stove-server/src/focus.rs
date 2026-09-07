//! Exact evidence resolution. A target is never substituted with a newer record.
use crate::error::{AppError, Result};
use crate::storage::{
  models::{Entry, MockInteraction, MockWarning, Snapshot, Span},
  repository::Repository,
};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use utoipa::ToSchema;

#[derive(Debug, Clone, Copy, Deserialize, Serialize, ToSchema)]
#[serde(rename_all = "lowercase")]
pub enum EvidenceKind {
  Entry,
  Span,
  Snapshot,
  Interaction,
  Warning,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(tag = "kind", content = "value", rename_all = "lowercase")]
pub enum EvidenceTarget {
  Entry(Entry),
  Span(Span),
  Snapshot(Snapshot),
  Interaction(MockInteraction),
  Warning(MockWarning),
}

#[derive(Debug, Serialize, ToSchema)]
pub struct FocusedEvidence {
  pub target: EvidenceTarget,
  pub entries: Vec<Entry>,
  pub spans: Vec<Span>,
  pub interactions: Vec<MockInteraction>,
  pub warnings: Vec<MockWarning>,
  pub has_more_before: bool,
  pub has_more_after: bool,
  pub context_limit: usize,
}

pub fn resolve(
  repository: &Repository,
  run_id: &str,
  test_id: Option<&str>,
  kind: EvidenceKind,
  id: i64,
  limit: usize,
) -> Result<FocusedEvidence> {
  let target = resolve_target(repository, run_id, test_id, kind, id)?;
  let limit = limit.min(100);
  let mut result = FocusedEvidence {
    target,
    entries: vec![],
    spans: vec![],
    interactions: vec![],
    warnings: vec![],
    has_more_before: false,
    has_more_after: false,
    context_limit: limit,
  };
  match &result.target {
    EvidenceTarget::Entry(entry) => {
      let mut before = repository.entry_context(
        run_id,
        &entry.test_id,
        &entry.timestamp,
        id,
        true,
        i64::try_from(limit + 1).unwrap(),
      )?;
      let mut after = repository.entry_context(
        run_id,
        &entry.test_id,
        &entry.timestamp,
        id,
        false,
        i64::try_from(limit + 1).unwrap(),
      )?;
      result.has_more_before = before.len() > limit;
      result.has_more_after = after.len() > limit;
      before.truncate(limit);
      before.reverse();
      after.truncate(limit);
      result.entries = before;
      result.entries.push(entry.clone());
      result.entries.extend(after);
    }
    EvidenceTarget::Span(span) => {
      let ancestors = repository.get_span_ancestors(run_id, &span.trace_id, id, limit + 1)?;
      let mut seen = HashSet::new();
      for ancestor in ancestors {
        if !seen.insert(ancestor.id) {
          break;
        }
        if result.spans.len() > limit {
          result.has_more_before = true;
          break;
        }
        result.spans.push(ancestor);
      }
      result.spans.reverse();
    }
    EvidenceTarget::Interaction(item) => {
      result.interactions.push(item.clone());
      if let Some(stub_id) = &item.stub_id {
        result.warnings = repository.related_warnings(
          run_id,
          test_id,
          stub_id,
          i64::try_from(limit + 1).unwrap(),
        )?;
        result.has_more_after = result.warnings.len() > limit;
        result.warnings.truncate(limit);
      }
    }
    EvidenceTarget::Warning(item) => {
      result.warnings.push(item.clone());
      if let Some(stub_id) = &item.stub_id {
        result.interactions = repository.related_interactions(
          run_id,
          test_id,
          stub_id,
          i64::try_from(limit + 1).unwrap(),
        )?;
        result.has_more_after = result.interactions.len() > limit;
        result.interactions.truncate(limit);
      }
    }
    EvidenceTarget::Snapshot(_) => {}
  }
  Ok(result)
}

pub(crate) fn resolve_target(
  repository: &Repository,
  run_id: &str,
  test_id: Option<&str>,
  kind: EvidenceKind,
  id: i64,
) -> Result<EvidenceTarget> {
  if repository.get_run(run_id)?.is_none() {
    return Err(AppError::NotFound("Run is unavailable".into()));
  }
  if let Some(test_id) = test_id
    && repository.get_test(run_id, test_id)?.is_none()
  {
    return Err(AppError::NotFound("Test is unavailable in this run".into()));
  }
  let missing = || AppError::NotFound("Evidence is unavailable in this scope".into());
  let target = match kind {
    EvidenceKind::Entry => EvidenceTarget::Entry(
      repository
        .get_entry(run_id, test_id.ok_or_else(missing)?, id)?
        .ok_or_else(missing)?,
    ),
    EvidenceKind::Span => EvidenceTarget::Span(
      if let Some(test_id) = test_id {
        repository.get_test_span(run_id, test_id, id)?
      } else {
        repository.get_span(run_id, id)?
      }
      .ok_or_else(missing)?,
    ),
    EvidenceKind::Snapshot => EvidenceTarget::Snapshot(
      repository
        .get_snapshot(run_id, test_id.ok_or_else(missing)?, id)?
        .ok_or_else(missing)?,
    ),
    EvidenceKind::Interaction => {
      let item = repository
        .get_mock_interaction(run_id, id)?
        .ok_or_else(missing)?;
      if item.test_id.as_deref() != test_id {
        return Err(missing());
      }
      EvidenceTarget::Interaction(item)
    }
    EvidenceKind::Warning => {
      let item = repository
        .get_mock_warning(run_id, id)?
        .ok_or_else(missing)?;
      if item.test_id.as_deref() != test_id {
        return Err(missing());
      }
      EvidenceTarget::Warning(item)
    }
  };
  Ok(target)
}
