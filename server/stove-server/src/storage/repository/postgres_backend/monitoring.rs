use super::{PostgresBackend, database};
use crate::storage::metrics::{Capacity, DatabaseDiagnostics, HealthSample};
use diesel::connection::SimpleConnection;
use std::time::Duration;

const PROBE_TIMEOUT: Duration = Duration::from_millis(250);
const PROBE_SQL: &str = "SET LOCAL statement_timeout = '1s'; SET LOCAL lock_timeout = '250ms'; SELECT id FROM runs LIMIT 1";

impl PostgresBackend {
  pub(in crate::storage::repository) fn capacity(&self) -> Vec<Capacity> {
    let lane = |role: &str, state: diesel::r2d2::State, maximum| {
      Capacity::new(role, maximum, state.connections, state.idle_connections)
    };
    vec![
      lane("read", self.read.state(), self.read.max_size()),
      lane("write", self.write.state(), self.write.max_size()),
      lane("explorer", self.explorer.state(), self.explorer.max_size()),
    ]
  }
  pub(in crate::storage::repository) fn probe_roles(&self) -> Vec<HealthSample> {
    let mut checks = Vec::new();
    for (role, pool) in [("read", &self.read), ("write", &self.write)] {
      checks.push(HealthSample::probe(role, || {
        pool.get_timeout(PROBE_TIMEOUT).is_ok_and(|mut conn| {
          use diesel::Connection;
          conn
            .transaction::<_, diesel::result::Error, _>(|conn| conn.batch_execute(PROBE_SQL))
            .is_ok()
        })
      }));
    }
    checks.push(HealthSample::probe("explorer", || {
      self
        .explorer
        .get_timeout(PROBE_TIMEOUT)
        .is_ok_and(|mut conn| {
          let Ok(mut transaction) = conn.transaction() else {
            return false;
          };
          transaction.batch_execute(PROBE_SQL).is_ok()
        })
    }));
    checks
  }
  pub(in crate::storage::repository) fn diagnostics(
    &self,
  ) -> std::result::Result<DatabaseDiagnostics, String> {
    // Dedicated short-lived session: diagnostics never consume query capacity.
    // Connection settings apply before the first SQL and do not leak to pools.
    let mut config: postgres::Config = self
      .database_url
      .parse()
      .map_err(|_| "Database monitoring configuration unavailable")?;
    config.connect_timeout(Duration::from_secs(2));
    let options = format!(
      "{} -c statement_timeout=1000 -c lock_timeout=250 -c default_transaction_read_only=on",
      config.get_options().unwrap_or("")
    );
    config.options(&options);
    let mut client = config
      .connect(database::tls_connector().map_err(|_| "Database monitoring TLS unavailable")?)
      .map_err(|_| "Database monitoring connection failed")?;
    let row = client.query_one("SELECT pg_database_size(current_database()), deadlocks, EXTRACT(EPOCH FROM stats_reset)::float8 FROM pg_stat_database WHERE datname = current_database()", &[]).map_err(|_| "Database size/statistics query failed")?;
    let mut result = DatabaseDiagnostics {
      database_bytes: u64::try_from(row.get::<_, i64>(0)).ok(),
      deadlocks: Some(row.get(1)),
      stats_reset_at: row.get(2),
      ..DatabaseDiagnostics::default()
    };
    result.notes.push("Database size and deadlocks are database-wide; pool metrics are per Stove instance. PostgreSQL disk capacity requires infrastructure monitoring.".into());
    let visibility: bool = client.query_one("SELECT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) OR pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER')", &[]).map_err(|_| "Monitoring permissions query failed")?.get(0);
    if visibility {
      match client.query_one("SELECT count(*) FILTER (WHERE wait_event_type = 'Lock'), COALESCE(max(EXTRACT(EPOCH FROM (clock_timestamp() - xact_start))), 0)::float8 FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()", &[]) {
        Ok(row) => { result.blocked_sessions = Some(row.get(0)); result.oldest_transaction_seconds = Some(row.get(1)); }
        Err(_) => result.notes.push("Session diagnostics unavailable.".into()),
      }
    } else {
      result.notes.push(
        "Session diagnostics require pg_read_all_stats; unavailable values are omitted.".into(),
      );
    }
    match client.query_one("SELECT COALESCE(sum(n_dead_tup), 0)::bigint, EXTRACT(EPOCH FROM max(last_autovacuum))::float8 FROM pg_stat_user_tables WHERE schemaname = current_schema()", &[]) {
      Ok(row) => { result.dead_tuples = Some(row.get(0)); result.last_autovacuum_at = row.get(1); }
      Err(_) => result.notes.push("Table/autovacuum diagnostics unavailable.".into()),
    }
    Ok(result)
  }
}
