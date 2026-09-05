use std::fmt::Write;

use rusqlite::types::ValueRef;

use super::SqliteBackend;
use crate::error::Result;
use crate::storage::models::{DatabaseColumn, DatabaseQueryResult, DatabaseSchema, DatabaseTable};

impl SqliteBackend {
  pub(in crate::storage::repository) fn database_schema(&self) -> Result<DatabaseSchema> {
    let _memory_guard = self.in_memory.then(|| self.lock_write());
    let connection = self.lock_explorer();
    let mut statement = connection.prepare(
      "SELECT name FROM sqlite_schema \
       WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    )?;
    let names = statement
      .query_map([], |row| row.get::<_, String>(0))?
      .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(statement);

    let mut tables = Vec::with_capacity(names.len());
    for name in names {
      let mut columns_statement = connection
        .prepare("SELECT name, type, \"notnull\", pk FROM pragma_table_info(?1) ORDER BY cid")?;
      let columns = columns_statement
        .query_map([&name], |row| {
          let primary_key = row.get::<_, i64>(3)? > 0;
          Ok(DatabaseColumn {
            name: row.get(0)?,
            data_type: row.get(1)?,
            nullable: row.get::<_, i64>(2)? == 0 && !primary_key,
            primary_key,
          })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
      tables.push(DatabaseTable { name, columns });
    }

    Ok(DatabaseSchema {
      backend: "sqlite".to_string(),
      tables,
    })
  }

  pub(in crate::storage::repository) fn execute_database_query(
    &self,
    sql: &str,
    max_rows: usize,
  ) -> Result<DatabaseQueryResult> {
    let _memory_guard = self.in_memory.then(|| self.lock_write());
    let connection = self.lock_explorer();
    let mut statement = connection.prepare(sql)?;
    let columns = statement
      .column_names()
      .into_iter()
      .map(str::to_string)
      .collect::<Vec<_>>();

    if columns.is_empty() {
      let affected_rows = statement.execute([])?;
      return Ok(DatabaseQueryResult {
        columns,
        rows: Vec::new(),
        affected_rows: affected_rows as u64,
        truncated: false,
      });
    }

    let column_count = columns.len();
    let mut cursor = statement.query([])?;
    let mut rows = Vec::new();
    let mut truncated = false;
    while let Some(row) = cursor.next()? {
      if rows.len() == max_rows {
        truncated = true;
        break;
      }
      let values = (0..column_count)
        .map(|index| row.get_ref(index).map(sqlite_value))
        .collect::<rusqlite::Result<Vec<_>>>()?;
      rows.push(values);
    }
    let affected_rows = rows.len() as u64;

    Ok(DatabaseQueryResult {
      columns,
      rows,
      affected_rows,
      truncated,
    })
  }
}

fn sqlite_value(value: ValueRef<'_>) -> Option<String> {
  match value {
    ValueRef::Null => None,
    ValueRef::Integer(value) => Some(value.to_string()),
    ValueRef::Real(value) => Some(value.to_string()),
    ValueRef::Text(value) => Some(String::from_utf8_lossy(value).into_owned()),
    ValueRef::Blob(value) => Some(hex(value)),
  }
}

fn hex(value: &[u8]) -> String {
  let mut encoded = String::with_capacity(value.len() * 2 + 2);
  encoded.push_str("0x");
  for byte in value {
    let _ = write!(encoded, "{byte:02x}");
  }
  encoded
}
