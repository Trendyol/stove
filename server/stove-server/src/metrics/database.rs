use super::Operation;
/// Fixed dimensions prevent query text or evidence from increasing metric cardinality.
#[derive(Clone, Copy)]
pub(crate) enum DatabaseOperation {
  SqliteWriteWait,
  SqliteReadWait,
  SqliteReplayWait,
  SqliteExplorerWait,
  SqliteIngestTransaction,
  SqliteReplayTransaction,
  PostgresWriteWait,
  PostgresReadWait,
  PostgresReplayWait,
  PostgresExplorerWait,
  PostgresIngestTransaction,
  PostgresReplayTransaction,
}
pub(super) const OPERATIONS: [DatabaseOperation; 12] = [
  DatabaseOperation::SqliteWriteWait,
  DatabaseOperation::SqliteReadWait,
  DatabaseOperation::SqliteReplayWait,
  DatabaseOperation::SqliteExplorerWait,
  DatabaseOperation::SqliteIngestTransaction,
  DatabaseOperation::SqliteReplayTransaction,
  DatabaseOperation::PostgresWriteWait,
  DatabaseOperation::PostgresReadWait,
  DatabaseOperation::PostgresReplayWait,
  DatabaseOperation::PostgresExplorerWait,
  DatabaseOperation::PostgresIngestTransaction,
  DatabaseOperation::PostgresReplayTransaction,
];
impl DatabaseOperation {
  pub(super) const fn name(self) -> &'static str {
    match self {
      Self::SqliteWriteWait => "sqlite_write_wait",
      Self::SqliteReadWait => "sqlite_read_wait",
      Self::SqliteReplayWait => "sqlite_replay_wait",
      Self::SqliteExplorerWait => "sqlite_explorer_wait",
      Self::SqliteIngestTransaction => "sqlite_ingest_transaction",
      Self::SqliteReplayTransaction => "sqlite_replay_transaction",
      Self::PostgresWriteWait => "postgres_write_wait",
      Self::PostgresReadWait => "postgres_read_wait",
      Self::PostgresReplayWait => "postgres_replay_wait",
      Self::PostgresExplorerWait => "postgres_explorer_wait",
      Self::PostgresIngestTransaction => "postgres_ingest_transaction",
      Self::PostgresReplayTransaction => "postgres_replay_transaction",
    }
  }
}
pub(super) static DATABASE: std::sync::LazyLock<[Operation; 12]> =
  std::sync::LazyLock::new(|| std::array::from_fn(|_| Operation::default()));

pub(crate) fn database_result<T, E>(
  operation: DatabaseOperation,
  work: impl FnOnce() -> Result<T, E>,
) -> Result<T, E> {
  let mut observation = DATABASE[operation as usize].start();
  let result = work();
  observation.finish(&result);
  result
}
pub(crate) fn database_acquire<T>(operation: DatabaseOperation, work: impl FnOnce() -> T) -> T {
  match database_result(operation, || Ok::<_, std::convert::Infallible>(work())) {
    Ok(value) => value,
    Err(never) => match never {},
  }
}
