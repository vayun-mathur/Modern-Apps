//! Supplemental street-geometry extractors for the geocoder build.
//!
//! Minimum spacing and sampling helpers shared by the street extract passes.

/// Minimum spacing between samples along a street, in e7 latitude units (~250 m). Sampling
/// runs on OSM's e7 coordinates, before rows are rounded to the stored e5.
pub const STREET_SAMPLE_E7: i64 = 25_000;
