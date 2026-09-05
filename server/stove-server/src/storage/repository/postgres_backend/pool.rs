use std::ops::{Deref, DerefMut};
use std::sync::{Condvar, Mutex};

use diesel::{Connection, PgConnection};

use crate::error::Result;

/// Fixed-size synchronous pool. Callers acquire only from blocking workers.
pub(super) struct Pool {
  available: Mutex<Vec<PgConnection>>,
  ready: Condvar,
}

impl Pool {
  pub(super) fn new(first: PgConnection, url: &str, size: usize) -> Result<Self> {
    assert!(size > 0, "connection pool must be nonempty");
    let mut connections = Vec::with_capacity(size);
    connections.push(first);
    for _ in 1..size {
      connections.push(PgConnection::establish(url)?);
    }
    Ok(Self {
      available: Mutex::new(connections),
      ready: Condvar::new(),
    })
  }

  pub(super) fn get(&self) -> Lease<'_> {
    let mut available = self.available.lock().expect("connection pool poisoned");
    loop {
      if let Some(connection) = available.pop() {
        return Lease {
          pool: self,
          connection: Some(connection),
        };
      }
      available = self
        .ready
        .wait(available)
        .expect("connection pool poisoned");
    }
  }
}

pub(super) struct Lease<'a> {
  pool: &'a Pool,
  connection: Option<PgConnection>,
}

impl Deref for Lease<'_> {
  type Target = PgConnection;
  fn deref(&self) -> &Self::Target {
    self.connection.as_ref().expect("active lease")
  }
}

impl DerefMut for Lease<'_> {
  fn deref_mut(&mut self) -> &mut Self::Target {
    self.connection.as_mut().expect("active lease")
  }
}

impl Drop for Lease<'_> {
  fn drop(&mut self) {
    if let Some(connection) = self.connection.take() {
      self
        .pool
        .available
        .lock()
        .expect("connection pool poisoned")
        .push(connection);
      self.pool.ready.notify_one();
    }
  }
}
