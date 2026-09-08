//! MCP analysis orchestration.
//!
//! Each user-facing MCP tool lives in its own per-tool module (`apps`,
//! `runs`, `failures`, `diagnose`, `timeline`, `trace`, `snapshot`, `raw_evidence`) as
//! an `impl Analyzer` block. This module owns the shared `Analyzer` handle,
//! argument validation, tool dispatch, and navigation decoration. Protocol
//! envelopes and the JSON text fallback are assembled outside the analysis layer.
//! `test_evidence` loads scoped records; `diagnostic_findings` ranks them before
//! the diagnosis tool renders its response.

mod apps;
mod common;
mod diagnose;
mod diagnostic_findings;
pub(super) mod evidence;
mod failures;
mod interactions;
mod navigation;
mod raw_evidence;
mod runs;
mod snapshot;
mod test_evidence;
mod timeline;
mod trace;
mod trace_summary;

use serde_json::Value;
use std::sync::Arc;

use self::common::display_error;
use crate::mcp::contract::ToolName;
use crate::storage::models::Run;
use crate::storage::models::Test;
use crate::storage::repository::Repository;

#[derive(Clone)]
pub struct Analyzer {
  repository: Arc<Repository>,
  public_url: Option<String>,
}

impl Analyzer {
  #[must_use]
  pub fn new(repository: Arc<Repository>, public_url: Option<String>) -> Self {
    Self {
      repository,
      public_url,
    }
  }

  pub(crate) fn call_tool(&self, tool: ToolName, arguments: Value) -> Result<Value, String> {
    crate::mcp::tools::validate_arguments(tool, &arguments)?;
    let mut result = match tool {
      ToolName::Apps => self.apps(arguments),
      ToolName::Runs => self.runs(arguments),
      ToolName::Failures => self.failures(arguments),
      ToolName::FailureDetail => self.failure_detail(arguments),
      ToolName::Diagnose => self.diagnose(&arguments),
      ToolName::Timeline => self.timeline(arguments),
      ToolName::Trace => self.trace(arguments),
      ToolName::Snapshot => self.snapshot(arguments),
      ToolName::Interactions => self.interactions(arguments),
      ToolName::RawEvidence => self.raw_evidence(arguments),
    }?;
    navigation::attach(&mut result, self.public_url.as_deref());
    Ok(result)
  }

  pub(super) fn resolve_test(&self, run_id: &str, test_id: &str) -> Result<(Run, Test), String> {
    let run = self
      .repository
      .get_run(run_id)
      .map_err(display_error)?
      .ok_or_else(|| format!("run `{run_id}` was not found"))?;
    let test = self
      .repository
      .get_test(run_id, test_id)
      .map_err(display_error)?
      .ok_or_else(|| format!("test `{test_id}` was not found in run `{run_id}`"))?;
    Ok((run, test))
  }
}
