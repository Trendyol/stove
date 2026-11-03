//! Versioned, resource- and filter-bound keyset cursors. Cursors are not snapshots.
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use sha2::{Digest, Sha256};

use crate::error::{AppError, Result};

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Cursor<P> {
  version: u8,
  resource: String,
  filter_hash: String,
  position: P,
}

pub(super) trait CursorPosition: Serialize + DeserializeOwned {
  fn is_valid(&self) -> bool;
}

pub(super) fn encode<P: CursorPosition>(
  resource: &str,
  filter: &impl Serialize,
  position: P,
) -> Result<String> {
  Ok(URL_SAFE_NO_PAD.encode(serde_json::to_vec(&Cursor {
    version: 1,
    resource: resource.to_owned(),
    filter_hash: filter_hash(filter)?,
    position,
  })?))
}

pub(super) fn decode<P: CursorPosition>(
  token: &str,
  resource: &str,
  filter: &impl Serialize,
) -> Result<P> {
  let invalid = || AppError::InvalidEvent("invalid cursor or filters changed".into());
  if token.len() > 16384 {
    return Err(invalid());
  }
  let bytes = URL_SAFE_NO_PAD.decode(token).map_err(|_| invalid())?;
  let cursor: Cursor<P> = serde_json::from_slice(&bytes).map_err(|_| invalid())?;
  if cursor.version != 1
    || cursor.resource != resource
    || cursor.filter_hash != filter_hash(filter)?
    || !cursor.position.is_valid()
  {
    return Err(invalid());
  }
  Ok(cursor.position)
}

fn filter_hash(filter: &impl Serialize) -> Result<String> {
  Ok(URL_SAFE_NO_PAD.encode(Sha256::digest(serde_json::to_vec(filter)?)))
}
