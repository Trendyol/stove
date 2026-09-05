use diesel::QueryableByName;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use utoipa::{IntoParams, ToSchema};

use super::{Backend, Repository};
use crate::error::{AppError, Result};
use crate::storage::models::Test;

#[derive(Default, Deserialize, IntoParams)]
#[into_params(parameter_in = Query)]
pub struct PageQuery {
  /// Opt in to an object containing `items`, `next_cursor` and `watermark`.
  #[serde(default)]
  pub page: bool,
  /// Opaque cursor returned by the previous page.
  pub cursor: Option<String>,
  /// Page size (default 200, maximum 1000).
  pub limit: Option<usize>,
  /// Case-insensitive literal substring search across the complete collection.
  pub search: Option<String>,
}

#[derive(Serialize, ToSchema)]
pub struct Page<T> {
  pub items: Vec<T>,
  pub next_cursor: Option<String>,
  pub watermark: u64,
}

#[derive(Serialize, ToSchema)]
#[serde(untagged)]
pub enum Collection<T> {
  Legacy(Vec<T>),
  Page(Page<T>),
}

/// Normalize and validate transport options once; scope validation stays with each cursor.
struct PageOptions<C> {
  limit: usize,
  search: String,
  pattern: String,
  cursor: Option<C>,
}
impl<C: serde::de::DeserializeOwned> PageOptions<C> {
  fn parse(query: PageQuery) -> Result<Self> {
    let limit = query.limit.unwrap_or(200);
    let search = query.search.unwrap_or_default().to_lowercase();
    if !(1..=1000).contains(&limit)
      || search.len() > 256
      || query
        .cursor
        .as_ref()
        .is_some_and(|cursor| cursor.len() > 16_384)
    {
      return Err(AppError::InvalidEvent(
        "invalid page size, search or cursor length".into(),
      ));
    }
    let cursor = query
      .cursor
      .as_deref()
      .map(serde_json::from_str)
      .transpose()?;
    let pattern = format!(
      "%{}%",
      search
        .replace('\\', "\\\\")
        .replace('%', "\\%")
        .replace('_', "\\_")
    );
    Ok(Self {
      limit,
      search,
      pattern,
      cursor,
    })
  }
}

/// The lookahead item establishes whether another page exists, but never enters the response.
fn finish_page<T, C: Serialize>(
  mut items: Vec<T>,
  limit: usize,
  watermark: u64,
  cursor: impl FnOnce(&T) -> C,
) -> Result<Page<T>> {
  let more = items.len() > limit;
  items.truncate(limit);
  let next_cursor = if more {
    items
      .last()
      .map(|item| serde_json::to_string(&cursor(item)))
      .transpose()?
  } else {
    None
  };
  Ok(Page {
    items,
    next_cursor,
    watermark,
  })
}

#[derive(Serialize, Deserialize)]
pub(super) struct TestCursor {
  run: String,
  search: String,
  pub started_at: String,
  pub id: String,
}

pub(super) struct TestPageRequest {
  pub run: String,
  pub search: String,
  pub pattern: String,
  pub cursor: Option<TestCursor>,
  pub limit: usize,
}

impl TestPageRequest {
  fn new(run: String, query: PageQuery) -> Result<Self> {
    let PageOptions {
      limit,
      search,
      pattern,
      cursor,
    } = PageOptions::<TestCursor>::parse(query)?;
    if cursor
      .as_ref()
      .is_some_and(|cursor| cursor.run != run || cursor.search != search)
    {
      return Err(AppError::InvalidEvent(
        "cursor belongs to a different collection or search".into(),
      ));
    }
    Ok(Self {
      run,
      search,
      pattern,
      cursor,
      limit,
    })
  }

  pub fn finish(&self, items: Vec<Test>, watermark: u64) -> Result<Page<Test>> {
    finish_page(items, self.limit, watermark, |test| TestCursor {
      run: self.run.clone(),
      search: self.search.clone(),
      started_at: test.started_at.clone(),
      id: test.id.clone(),
    })
  }
}

impl Repository {
  pub(crate) async fn tests_page(
    self: &Arc<Self>,
    run: String,
    query: PageQuery,
  ) -> Result<Page<Test>> {
    let request = TestPageRequest::new(run, query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.tests_page(&request),
        Backend::Postgres(database) => database.tests_page(&request),
      })
      .await
  }
}

#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub(super) enum EvidenceKind {
  Entries,
  RawEntries,
  Snapshots,
  Spans,
  TraceSpans,
  MockInteractions,
  AmbientMockInteractions,
  MockWarnings,
  AmbientMockWarnings,
}

#[derive(Serialize, Deserialize)]
pub(super) struct EvidenceCursor {
  run: String,
  test: String,
  kind: EvidenceKind,
  search: String,
  id: i64,
}

pub(super) struct EvidencePageRequest {
  pub run: String,
  pub test: String,
  pub kind: EvidenceKind,
  pub search: String,
  pub pattern: String,
  pub after: i64,
  pub limit: usize,
}

#[derive(diesel::QueryableByName)]
pub(super) struct EntryPageRow {
  #[diesel(embed)]
  pub entry: super::mapping::EntryRow,
  #[diesel(sql_type = diesel::sql_types::BigInt)]
  pub cursor_id: i64,
}

impl EvidencePageRequest {
  fn new(run: String, test: String, kind: EvidenceKind, query: PageQuery) -> Result<Self> {
    let PageOptions {
      limit,
      search,
      pattern,
      cursor,
    } = PageOptions::<EvidenceCursor>::parse(query)?;
    if cursor.as_ref().is_some_and(|cursor| {
      cursor.run != run
        || cursor.test != test
        || cursor.kind != kind
        || cursor.search != search
        || cursor.id < 0
    }) {
      return Err(AppError::InvalidEvent(
        "cursor belongs to a different collection or search".into(),
      ));
    }
    Ok(Self {
      run,
      test,
      kind,
      search,
      pattern,
      after: cursor.map_or(0, |cursor| cursor.id),
      limit,
    })
  }

  pub fn finish<T>(&self, rows: Vec<(T, i64)>, watermark: u64) -> Result<Page<T>> {
    let page = finish_page(rows, self.limit, watermark, |(_, id)| EvidenceCursor {
      run: self.run.clone(),
      test: self.test.clone(),
      kind: self.kind,
      search: self.search.clone(),
      id: *id,
    })?;
    Ok(Page {
      items: page.items.into_iter().map(|(item, _)| item).collect(),
      next_cursor: page.next_cursor,
      watermark: page.watermark,
    })
  }
}

impl Repository {
  pub(crate) async fn entries_page(
    self: &Arc<Self>,
    run: String,
    test: String,
    raw: bool,
    query: PageQuery,
  ) -> Result<Page<crate::storage::models::Entry>> {
    let request = EvidencePageRequest::new(
      run,
      test,
      if raw {
        EvidenceKind::RawEntries
      } else {
        EvidenceKind::Entries
      },
      query,
    )?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.entries_page(&request),
        Backend::Postgres(database) => database.entries_page(&request),
      })
      .await
  }
}

#[derive(Serialize, ToSchema, diesel::Queryable)]
pub struct SnapshotSummary {
  pub id: i64,
  pub run_id: String,
  pub test_id: String,
  pub system: String,
  pub summary: String,
  pub captured_at: Option<String>,
  pub trigger: String,
  pub state_bytes: i64,
}

#[derive(Serialize, ToSchema)]
#[serde(untagged)]
pub enum SnapshotCollection {
  Legacy(Vec<crate::storage::models::Snapshot>),
  Page(Page<SnapshotSummary>),
}

impl Repository {
  pub(crate) async fn snapshots_page(
    self: &Arc<Self>,
    run: String,
    test: String,
    query: PageQuery,
  ) -> Result<Page<SnapshotSummary>> {
    let request = EvidencePageRequest::new(run, test, EvidenceKind::Snapshots, query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.snapshots_page(&request),
        Backend::Postgres(database) => database.snapshots_page(&request),
      })
      .await
  }

  pub(crate) async fn snapshot_detail(
    self: &Arc<Self>,
    run: String,
    test: String,
    id: i64,
  ) -> Result<Option<crate::storage::models::Snapshot>> {
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.snapshot_detail(&run, &test, id),
        Backend::Postgres(database) => database.snapshot_detail(&run, &test, id),
      })
      .await
  }
}

impl Repository {
  pub(crate) async fn spans_page(
    self: &Arc<Self>,
    run: String,
    test: String,
    trace: bool,
    query: PageQuery,
  ) -> Result<Page<crate::storage::models::Span>> {
    let kind = if trace {
      EvidenceKind::TraceSpans
    } else {
      EvidenceKind::Spans
    };
    let request = EvidencePageRequest::new(run, test, kind, query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.spans_page(&request),
        Backend::Postgres(database) => database.spans_page(&request),
      })
      .await
  }
}

impl Repository {
  pub(crate) async fn mock_interactions_page(
    self: &Arc<Self>,
    run: String,
    test: Option<String>,
    ambient: bool,
    query: PageQuery,
  ) -> Result<Page<crate::storage::models::MockInteraction>> {
    let kind = if ambient {
      EvidenceKind::AmbientMockInteractions
    } else {
      EvidenceKind::MockInteractions
    };
    let request = EvidencePageRequest::new(run, test.unwrap_or_default(), kind, query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.mock_interactions_page(&request),
        Backend::Postgres(database) => database.mock_interactions_page(&request),
      })
      .await
  }
}

impl Repository {
  pub(crate) async fn mock_warnings_page(
    self: &Arc<Self>,
    run: String,
    test: Option<String>,
    ambient: bool,
    query: PageQuery,
  ) -> Result<Page<crate::storage::models::MockWarning>> {
    let kind = if ambient {
      EvidenceKind::AmbientMockWarnings
    } else {
      EvidenceKind::MockWarnings
    };
    let request = EvidencePageRequest::new(run, test.unwrap_or_default(), kind, query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.mock_warnings_page(&request),
        Backend::Postgres(database) => database.mock_warnings_page(&request),
      })
      .await
  }
}

#[derive(Serialize, Deserialize)]
pub(super) struct RunCursor {
  app: Option<String>,
  metadata: std::collections::BTreeMap<String, String>,
  search: String,
  pub started_at: String,
  pub id: String,
}

pub(super) struct RunPageRequest {
  pub app: Option<String>,
  pub metadata: std::collections::BTreeMap<String, String>,
  pub search: String,
  pub pattern: String,
  pub cursor: Option<RunCursor>,
  pub limit: usize,
}

impl RunPageRequest {
  fn new(
    app: Option<String>,
    metadata: std::collections::BTreeMap<String, String>,
    query: PageQuery,
  ) -> Result<Self> {
    let PageOptions {
      limit,
      search,
      pattern,
      cursor,
    } = PageOptions::<RunCursor>::parse(query)?;
    if cursor.as_ref().is_some_and(|cursor| {
      cursor.app != app || cursor.metadata != metadata || cursor.search != search
    }) {
      return Err(AppError::InvalidEvent(
        "cursor belongs to a different collection or search".into(),
      ));
    }
    Ok(Self {
      app,
      metadata,
      search,
      pattern,
      cursor,
      limit,
    })
  }

  pub fn finish(
    &self,
    items: Vec<crate::storage::models::Run>,
    watermark: u64,
  ) -> Result<Page<crate::storage::models::Run>> {
    finish_page(items, self.limit, watermark, |run| RunCursor {
      app: self.app.clone(),
      metadata: self.metadata.clone(),
      search: self.search.clone(),
      started_at: run.started_at.clone(),
      id: run.id.clone(),
    })
  }
}

#[derive(Serialize, Deserialize)]
pub(super) struct AppCursor {
  name: String,
  search: String,
}

pub(super) struct AppPageRequest {
  pub after: Option<String>,
  pub search: String,
  pub pattern: String,
  pub limit: usize,
}

impl AppPageRequest {
  fn new(query: PageQuery) -> Result<Self> {
    let PageOptions {
      limit,
      search,
      pattern,
      cursor,
    } = PageOptions::<AppCursor>::parse(query)?;
    if cursor
      .as_ref()
      .is_some_and(|cursor| cursor.search != search)
    {
      return Err(AppError::InvalidEvent(
        "cursor belongs to a different search".into(),
      ));
    }
    Ok(Self {
      after: cursor.map(|cursor| cursor.name),
      search,
      pattern,
      limit,
    })
  }

  pub fn finish(
    &self,
    items: Vec<crate::storage::models::AppSummary>,
    watermark: u64,
  ) -> Result<Page<crate::storage::models::AppSummary>> {
    finish_page(items, self.limit, watermark, |app| AppCursor {
      name: app.app_name.clone(),
      search: self.search.clone(),
    })
  }
}

impl Repository {
  pub(crate) async fn runs_page(
    self: &Arc<Self>,
    app: Option<String>,
    metadata: std::collections::BTreeMap<String, String>,
    query: PageQuery,
  ) -> Result<Page<crate::storage::models::Run>> {
    let request = RunPageRequest::new(app, metadata, query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.runs_page(&request),
        Backend::Postgres(database) => database.runs_page(&request),
      })
      .await
  }

  pub(crate) async fn apps_page(
    self: &Arc<Self>,
    query: PageQuery,
  ) -> Result<Page<crate::storage::models::AppSummary>> {
    let request = AppPageRequest::new(query)?;
    self
      .read_async(move |repository| match &repository.backend {
        Backend::Sqlite(database) => database.apps_page(&request),
        Backend::Postgres(database) => database.apps_page(&request),
      })
      .await
  }
}
