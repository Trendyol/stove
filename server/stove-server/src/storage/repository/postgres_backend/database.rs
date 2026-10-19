use std::time::Duration;

use diesel::prelude::*;
use diesel::r2d2::{ConnectionManager, ManageConnection, Pool, PooledConnection};
use native_tls::TlsConnector;
use postgres::Client;
use postgres_native_tls::MakeTlsConnector;
use r2d2_postgres::PostgresConnectionManager;

use crate::error::Result;
use crate::storage::migrations::postgres::run_migrations;
use crate::storage::schema::postgres::dashboard_settings;

pub(super) type DieselPool = Pool<ConnectionManager<PgConnection>>;
pub(super) type DieselConnection = PooledConnection<ConnectionManager<PgConnection>>;
type ExplorerManager = PostgresConnectionManager<MakeTlsConnector>;
pub(super) type ExplorerPool = Pool<ExplorerManager>;
pub(super) type ExplorerConnection = PooledConnection<ExplorerManager>;

pub(super) struct PostgresConnections {
  pub write: DieselPool,
  pub read: DieselPool,
  pub explorer: ExplorerPool,
}

pub(super) fn open(database_url: &str, default_retention: usize) -> Result<PostgresConnections> {
  let explorer = build_pool(
    PostgresConnectionManager::new(database_url.parse()?, tls_connector()?),
    2,
  )?;
  run_migrations(&mut *explorer.get()?)?;

  // Preserve serialized ingestion, while dashboard and explorer queries have
  // independent capacity. Connections are created on demand, up to these limits.
  let write = build_pool(ConnectionManager::new(database_url), 1)?;
  seed_default_retention(&mut *write.get()?, default_retention)?;
  let read = build_pool(ConnectionManager::new(database_url), 4)?;
  // Fail startup if the read pool cannot reach the database.
  drop(read.get()?);
  Ok(PostgresConnections {
    write,
    read,
    explorer,
  })
}

fn build_pool<M: ManageConnection>(manager: M, max_size: u32) -> Result<Pool<M>> {
  Ok(
    Pool::builder()
      .max_size(max_size)
      .min_idle(Some(0))
      .test_on_check_out(true)
      .connection_timeout(Duration::from_secs(2))
      .idle_timeout(Some(Duration::from_mins(5)))
      .max_lifetime(Some(Duration::from_mins(30)))
      .build(manager)?,
  )
}

// LISTEN needs its own session for the lifetime of the subscription; it must not
// borrow from a query pool or return a listening session to other operations.
pub(super) fn connect_driver(database_url: &str) -> Result<Client> {
  Ok(Client::connect(database_url, tls_connector()?)?)
}

fn tls_connector() -> Result<MakeTlsConnector> {
  Ok(MakeTlsConnector::new(TlsConnector::builder().build()?))
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
