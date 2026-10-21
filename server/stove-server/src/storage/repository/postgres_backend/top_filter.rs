use diesel::prelude::*;
use diesel::sql_types::{Nullable, Text};

use super::PostgresBackend;
use crate::error::Result;
use crate::storage::models::{TopFilterCatalog, TopFilterSettings};
use crate::storage::repository::mapping::{AppSummaryRow, TopFilterRow};
use crate::storage::repository::top_filter::{CATALOG_RUNS_SQL, catalog};

impl PostgresBackend {
  pub fn top_filter_catalog(&self) -> Result<TopFilterCatalog> {
    let mut connection = self.checkout_read()?;
    let conn = &mut *connection;
    conn.transaction(|conn| {
      let settings =
        diesel::sql_query("SELECT top_filter_key FROM dashboard_preferences WHERE id = 1")
          .get_result::<TopFilterRow>(conn)?;
      let rows = diesel::sql_query(CATALOG_RUNS_SQL).load::<AppSummaryRow>(conn)?;
      catalog(settings, rows)
    })
  }

  pub fn update_top_filter(&self, settings: &TopFilterSettings) -> Result<()> {
    let mut connection = self.checkout_write()?;
    let conn = &mut *connection;
    diesel::sql_query("UPDATE dashboard_preferences SET top_filter_key = $1 WHERE id = 1")
      .bind::<Nullable<Text>, _>(&settings.metadata_key)
      .execute(conn)?;
    Ok(())
  }
}
