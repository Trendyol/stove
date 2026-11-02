//! Independent budgets for ingestion, retained data, and query responses.
pub(super) const MAX_UPLOAD_BYTES: usize = 16 * 1024 * 1024;
pub(super) const MAX_DECODED_BYTES: usize = 32 * 1024 * 1024;
pub(super) const MAX_COMPRESSED_BYTES: usize = 8 * 1024 * 1024;
pub(super) const MAX_CONCURRENT_CONVERSIONS: usize = 2;
pub(crate) const MAX_QUERY_CHUNKS: usize = 4096;
pub(super) const MAX_ADMITTED_UPLOADS: usize = 24;
pub(super) const MAX_CONCURRENT_UPLOADS: usize = 4;
pub(super) const CAPACITY_WAIT: std::time::Duration = std::time::Duration::from_secs(2);
pub(super) const UPLOAD_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(30);
pub(super) const MAX_QUERY_BYTES: usize = 64 * 1024 * 1024;
pub(super) const MAX_SERIES: usize = 32;
pub(super) const MAX_HOTSPOTS: usize = 20;
pub(super) const MAX_STACKS: usize = 200;
