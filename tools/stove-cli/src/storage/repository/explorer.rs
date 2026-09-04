use crate::error::Result;
use crate::storage::models::{DatabaseQueryResult, DatabaseSchema};

use super::{Backend, Repository};

impl Repository {
  pub fn database_schema(&self) -> Result<DatabaseSchema> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.database_schema(),
      Backend::Postgres(postgres) => postgres.database_schema(),
    })
  }

  pub fn execute_database_query(&self, sql: &str, max_rows: usize) -> Result<DatabaseQueryResult> {
    self.with_backend(|backend| match backend {
      Backend::Sqlite(sqlite) => sqlite.execute_database_query(sql, max_rows),
      Backend::Postgres(postgres) => postgres.execute_database_query(sql, max_rows),
    })
  }
}
