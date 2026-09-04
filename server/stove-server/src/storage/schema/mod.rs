//! Diesel schemas are backend-specific where their physical types differ.

pub(crate) mod postgres;
pub(crate) mod sqlite;
