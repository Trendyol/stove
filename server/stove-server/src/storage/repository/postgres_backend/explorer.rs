use postgres::{SimpleQueryMessage, Transaction};

use super::PostgresBackend;
use crate::error::Result;
use crate::storage::models::{DatabaseColumn, DatabaseQueryResult, DatabaseSchema, DatabaseTable};

impl PostgresBackend {
  pub(in crate::storage::repository) fn database_schema(&self) -> Result<DatabaseSchema> {
    let mut connection = self.lock_explorer();
    let rows = connection.query(
      "SELECT
         columns.table_name,
         columns.column_name,
         columns.data_type,
         columns.is_nullable = 'YES',
         EXISTS (
           SELECT 1
           FROM information_schema.table_constraints constraints
           JOIN information_schema.key_column_usage keys
             ON constraints.constraint_name = keys.constraint_name
            AND constraints.table_schema = keys.table_schema
            AND constraints.table_name = keys.table_name
           WHERE constraints.constraint_type = 'PRIMARY KEY'
             AND constraints.table_schema = columns.table_schema
             AND constraints.table_name = columns.table_name
             AND keys.column_name = columns.column_name
         )
       FROM information_schema.columns columns
       JOIN information_schema.tables tables
         ON tables.table_schema = columns.table_schema
        AND tables.table_name = columns.table_name
       WHERE columns.table_schema = current_schema()
         AND tables.table_type = 'BASE TABLE'
       ORDER BY columns.table_name, columns.ordinal_position",
      &[],
    )?;

    let mut tables: Vec<DatabaseTable> = Vec::new();
    for row in rows {
      let table_name: String = row.get(0);
      let column = DatabaseColumn {
        name: row.get(1),
        data_type: row.get(2),
        nullable: row.get(3),
        primary_key: row.get(4),
      };
      if let Some(table) = tables.last_mut().filter(|table| table.name == table_name) {
        table.columns.push(column);
      } else {
        tables.push(DatabaseTable {
          name: table_name,
          columns: vec![column],
        });
      }
    }

    Ok(DatabaseSchema {
      backend: "postgresql".to_string(),
      tables,
    })
  }

  pub(in crate::storage::repository) fn execute_database_query(
    &self,
    sql: &str,
    max_rows: usize,
  ) -> Result<DatabaseQueryResult> {
    let mut connection = self.lock_explorer();
    let mut transaction = connection.transaction()?;
    transaction.batch_execute("SET LOCAL statement_timeout = '10s'")?;
    let statement = transaction.prepare(sql)?;
    let columns = statement
      .columns()
      .iter()
      .map(|column| column.name().to_string())
      .collect::<Vec<_>>();

    if columns.is_empty() {
      let affected_rows = transaction.execute(&statement, &[])?;
      transaction.commit()?;
      return Ok(DatabaseQueryResult {
        columns,
        rows: Vec::new(),
        affected_rows,
        truncated: false,
      });
    }

    let rows = fetch_bounded_rows(&mut transaction, sql, max_rows)?;
    transaction.commit()?;
    let truncated = rows.len() > max_rows;
    let mut rows = rows;
    rows.truncate(max_rows);

    Ok(DatabaseQueryResult {
      columns,
      affected_rows: rows.len() as u64,
      rows,
      truncated,
    })
  }
}

fn fetch_bounded_rows(
  transaction: &mut Transaction<'_>,
  sql: &str,
  max_rows: usize,
) -> Result<Vec<Vec<Option<String>>>> {
  transaction.batch_execute(&format!(
    "DECLARE stove_explorer_rows NO SCROLL CURSOR FOR {sql}"
  ))?;
  let messages = transaction.simple_query(&format!(
    "FETCH FORWARD {} FROM stove_explorer_rows",
    max_rows.saturating_add(1)
  ))?;

  let mut rows = Vec::with_capacity(max_rows.saturating_add(1));
  for message in messages {
    if let SimpleQueryMessage::Row(row) = message {
      rows.push(
        (0..row.len())
          .map(|index| row.get(index).map(str::to_string))
          .collect(),
      );
    }
  }
  Ok(rows)
}
