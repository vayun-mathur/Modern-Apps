//! Core A* routing: edge snapping, search, and path reconstruction.
//!
//! Port of the `--- CORE ROUTING ---` and path-reconstruction sections of
//! `native-lib.cpp`. Kept JNI-free: `perform_search_loop` calls back through an
//! `ensure_traffic` closure for the DRIVING traffic prefetch, and
//! `reconstruct_path` returns plain [`StepData`] that `lib.rs` marshals into the
//! Kotlin `RawStep[]`.
//!
//! Split into [`search`] (snapping, A* loop, on-edge direct path) and [`steps`]
//! (step building, path reconstruction, elevation, lane guidance). Pure moves;
//! this module only declares the submodules and re-exports the public surface.

pub mod astar;
pub mod search;
pub mod steps;

pub use astar::perform_search_loop;
pub use search::{Direct, RoutingContext, SnappedEdge, find_nearest_edge, prepare_routing};
pub use steps::{StepData, ascent_descent, reconstruct_path, route_ascent_descent};

#[cfg(test)]
mod search_cov_tests;
