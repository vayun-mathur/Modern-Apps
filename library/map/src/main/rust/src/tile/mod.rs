//! The tile pipeline: fetch, cache, decode, tessellate, choose.
//!
//! [`select`] decides what the viewport needs, [`source`] gets the bytes (through the
//! cache in [`cache`] and, on device, out through Kotlin's HTTP stack), and
//! [`geometry`] turns them into triangles.

pub mod arrow;
pub mod cache;
pub mod geometry;
pub mod geometry_query;
pub mod glyph;
pub mod placement;
pub mod select;
pub mod source;
pub mod sprite;
pub mod sprite_extra;
pub mod symbol;
pub mod taper;
pub mod taper_extra;
