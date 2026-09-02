use diesel::prelude::*;
use native_tls::TlsConnector;
use postgres::{Client, NoTls};
use postgres_native_tls::MakeTlsConnector;

use crate::error::Result;
use crate::storage::migrations::postgres::run_migrations;
use crate::storage::schema::postgres::dashboard_settings;

pub(super) struct PostgresConnections {
  pub write: PgConnection,
  pub read: PgConnection,
  pub explorer: Client,
}

pub(super) fn open(database_url: &str, default_retention: usize) -> Result<PostgresConnections> {
  let mut explorer = connect_driver(database_url)?;
  run_migrations(&mut explorer)?;

  let mut write = PgConnection::establish(database_url)?;
  seed_default_retention(&mut write, default_retention)?;
  let read = PgConnection::establish(database_url)?;
  Ok(PostgresConnections {
    write,
    read,
    explorer,
  })
}

pub(super) fn connect_driver(database_url: &str) -> Result<Client> {
  if database_url.contains("sslmode=disable") {
    return Ok(Client::connect(database_url, NoTls)?);
  }
  let connector = TlsConnector::builder().build()?;
  Ok(Client::connect(
    database_url,
    MakeTlsConnector::new(connector),
  )?)
}

fn seed_default_retention(connection: &mut PgConnection, default_retention: usize) -> Result<()> {
  diesel::insert_into(dashboard_settings::table)
    .values((
      dashboard_settings::setting_key.eq("retention_runs_per_app"),
      dashboard_settings::setting_value.eq(default_retention.to_string()),
    ))
    .on_conflict(dashboard_settings::setting_key)
    .do_nothing()
    .execute(connection)?;
  Ok(())
}
