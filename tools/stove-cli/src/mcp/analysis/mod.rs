//! MCP analysis orchestration.
//!
//! Each user-facing MCP tool lives in its own per-tool module (`apps`,
//! `runs`, `failures`, `timeline`, `trace`, `snapshot`, `raw_evidence`) as
//! an `impl Analyzer` block. This module owns the shared `Analyzer` handle,
//! the `call_tool` dispatcher, and `resolve_test`
//! which several tools share.

mod apps;
mod common;
pub(super) mod evidence;
mod failures;
mod interactions;
mod raw_evidence;
mod runs;
mod snapshot;
mod timeline;
mod trace;

use serde_json::Value;
use std::sync::Arc;

use self::common::display_error;
use crate::mcp::contract::ToolName;
use crate::storage::models::Run;
use crate::storage::models::Test;
use crate::storage::repository::Repository;

#[derive(Debug, Clone)]
pub struct ToolOutput {
  pub structured: Value,
  pub text: String,
}

#[derive(Clone)]
pub struct Analyzer {
  repository: Arc<Repository>,
}

impl Analyzer {
  #[must_use]
  pub fn new(repository: Arc<Repository>) -> Self {
    Self { repository }
  }

  pub fn call_tool(&self, name: &str, arguments: Value) -> Result<ToolOutput, String> {
    match ToolName::from_str(name) {
      Some(ToolName::Apps) => self.apps(arguments),
      Some(ToolName::Runs) => self.runs(arguments),
      Some(ToolName::Failures) => self.failures(arguments),
      Some(ToolName::FailureDetail) => self.failure_detail(arguments),
      Some(ToolName::Timeline) => self.timeline(arguments),
      Some(ToolName::Trace) => self.trace(arguments),
      Some(ToolName::Snapshot) => self.snapshot(arguments),
      Some(ToolName::Interactions) => self.interactions(arguments),
      Some(ToolName::RawEvidence) => self.raw_evidence(arguments),
      None => Err(format!("unknown Stove MCP tool: {name}")),
    }
  }

  pub(super) fn resolve_test(&self, run_id: &str, test_id: &str) -> Result<(Run, Test), String> {
    let run = self
      .repository
      .get_run(run_id)
      .map_err(display_error)?
      .ok_or_else(|| format!("run `{run_id}` was not found"))?;
    let test = self
      .repository
      .get_tests_for_run(run_id)
      .map_err(display_error)?
      .into_iter()
      .find(|test| test.id == test_id)
      .ok_or_else(|| format!("test `{test_id}` was not found in run `{run_id}`"))?;
    Ok((run, test))
  }
}
