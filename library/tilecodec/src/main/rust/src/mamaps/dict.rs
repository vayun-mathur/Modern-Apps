//! The archive-wide dictionary: layer names, `kind`s and `kind_detail`s, interned to integers.
//!
//! This is the largest single win over MVT. There, every tile carries its own string table and
//! every feature carries a key/value property map, so the renderer paid a `String` allocation per
//! feature per tile to read the one property it actually looks at. Here a feature's whole
//! attribute surface is two `u16`s and three bits, because that is all the style filters on.
//!
//! # Why the table is a constant and not observed
//!
//! Ids come from [`SCHEMA`]'s position, never from the order a build happened to encounter
//! values in. Two consequences, both load-bearing:
//!
//! * A California archive has a **byte-identical** dictionary to a planet one, so the section is
//!   comparable across builds and a diff of two archives is a diff of their tiles.
//! * An id never shifts between builds, so a cached tile from yesterday's archive cannot be
//!   reinterpreted against today's dictionary and come out a different colour. The
//!   [`build_id`](super::header::Header::build_id) still invalidates the cache; this makes the
//!   failure impossible rather than merely unlikely.
//!
//! The dictionary is still written to the file. A reader validates its own compiled-in table
//! against it on open, and `mamaps_dump` needs the names to print without linking the schema.
//!
//! # Size
//!
//! Ten layers, ~122 kinds and 45 details, at a length byte each: under 2 KiB as measured,
//! which is what lets the header, the dictionary and the root index share one 16 KiB opening
//! read.
//!
//! Split into [`tables`] (the constant tables), [`types`] (the struct and its accessors),
//! [`serialize`] and [`parse`] (`parse` plus `check_matches_schema`), and [`tests`].
//! Pure moves; this module only declares the submodules and re-exports the public surface.

mod parse;
mod serialize;
mod tables;
mod types;

#[cfg(test)]
mod tests;

pub use tables::{
    DETAILS, KINDS, LAYERS, LAYER_BOUNDARIES, LAYER_BUILDINGS, LAYER_JUNCTION, LAYER_LANDTYPE,
    LAYER_PLACES, LAYER_POI, LAYER_ROADS, LAYER_SAFETY, LAYER_TRAFFIC, LAYER_TRANSIT, NONE,
};
pub use types::Dictionary;
