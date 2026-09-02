use crate::error::Result;
use crate::storage::models::{DatabaseQueryResult, DatabaseSchema};

use super::{Backend, Repository, run_blocking};

impl Repository {
  pub fn database_schema(&self) -> Result<DatabaseSchema> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.database_schema(),
      Backend::Postgres(postgres) => run_blocking(|| postgres.database_schema()),
    }
  }

  pub fn execute_database_query(&self, sql: &str, max_rows: usize) -> Result<DatabaseQueryResult> {
    match &self.backend {
      Backend::Sqlite(sqlite) => sqlite.execute_database_query(sql, max_rows),
      Backend::Postgres(postgres) => {
        run_blocking(|| postgres.execute_database_query(sql, max_rows))
      }
    }
  }
}
