pub(crate) mod postgres;
pub(crate) mod sqlite;

struct Migration {
  version: i64,
  name: &'static str,
  sql: &'static str,
}

impl Migration {
  const fn new(version: i64, name: &'static str, sql: &'static str) -> Self {
    Self { version, name, sql }
  }
}
