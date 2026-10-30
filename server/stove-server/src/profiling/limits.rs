//! Independent budgets for ingestion, retained data, and query responses.
pub(super) const MAX_UPLOAD_BYTES: usize = 16 * 1024 * 1024;
pub(super) const MAX_DECODED_BYTES: usize = 32 * 1024 * 1024;
pub(super) const MAX_COMPRESSED_BYTES: usize = 8 * 1024 * 1024;
pub(super) const MAX_CONCURRENT_CONVERSIONS: usize = 2;
pub(super) const MAX_QUERY_BYTES: usize = 64 * 1024 * 1024;
pub(super) const MAX_SERIES: usize = 32;
pub(super) const MAX_HOTSPOTS: usize = 20;
pub(super) const MAX_STACKS: usize = 200;
