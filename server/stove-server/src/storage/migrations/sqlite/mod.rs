use refinery::embed_migrations;
use rusqlite::Connection;

use crate::error::Result;

mod embedded {
  use super::embed_migrations;

  embed_migrations!("src/storage/migrations/sqlite");
}

/// Apply pending `SQLite` migrations with Refinery.
pub(crate) fn run_migrations(connection: &mut Connection) -> Result<()> {
  embedded::migrations::runner().run(connection)?;
  Ok(())
}

#[cfg(test)]
pub(crate) fn migration_count() -> usize {
  embedded::migrations::runner().get_migrations().len()
}
