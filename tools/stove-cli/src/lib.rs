#![deny(clippy::all)]
#![warn(clippy::pedantic)]
// Allow these pedantic lints project-wide — they conflict with our conventions.
#![allow(clippy::module_name_repetitions)]
#![allow(clippy::missing_errors_doc)]
#![allow(clippy::missing_panics_doc)]
#![allow(clippy::redundant_closure_for_method_calls)]

pub mod config;
pub mod error;
pub mod grpc;
pub mod http;
pub mod sse;
pub mod storage;

/// Generated protobuf types from shared `.proto` contract.
#[allow(clippy::pedantic)]
pub mod proto {
  tonic::include_proto!("stove.portal.v1");
}
