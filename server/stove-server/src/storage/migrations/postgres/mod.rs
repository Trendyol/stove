use postgres::Client;
use refinery::embed_migrations;

use crate::error::Result;

mod embedded {
  use super::embed_migrations;

  embed_migrations!("src/storage/migrations/postgres");
}

/// Serialize concurrent pod startup, then apply pending `PostgreSQL` migrations
/// with Refinery. The advisory lock is intentionally outside the migration
/// framework because it coordinates independent server processes.
pub(crate) fn run_migrations(client: &mut Client) -> Result<()> {
  client.query_one(
    "SELECT pg_advisory_lock(hashtextextended('stove_schema_migrations', 0))",
    &[],
  )?;
  let result = embedded::migrations::runner().run(client);
  let unlock_result = client.query_one(
    "SELECT pg_advisory_unlock(hashtextextended('stove_schema_migrations', 0))",
    &[],
  );
  result?;
  unlock_result?;
  Ok(())
}

#[cfg(test)]
pub(crate) fn migration_count() -> usize {
  embedded::migrations::runner().get_migrations().len()
}
