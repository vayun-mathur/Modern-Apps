//! Host-side OSM ingest for the Maps offline packs.
//!
//! Three tools share this library:
//!
//! * `road_graph` — the routing graph (`metadata.bin`, `nodes.bin`, `edges.bin`,
//!   `lanes.bin`, `road_names.bin`), consumed by
//!   `maps/src/main/rust/src/graph.rs`.
//! * `poi_extract` — the `ma_pois` layer's geojsonseq plus `poi_names.bin` and
//!   `poi_index.bin`, consumed by `PoiIndex.kt`.
//! * `osm_extract` — one geojsonseq per baked vector layer, replacing the
//!   `osmium tags-filter | osmium export | normalize_*.py` chain. See
//!   [`extract`].
//!
//! The first two used to be C++ built against libosmium, which meant they only
//! ran under WSL. Reading `.osm.pbf` directly — a hand-rolled protobuf decoder
//! plus a pure-Rust inflate — makes them native on every platform the app is
//! built on, and the third never had a Windows path at all.

pub mod admin;
pub mod bbox;
pub mod chains;
pub mod compact;
pub mod dem;
pub mod extract;
pub mod geojson;
pub mod geom;
pub mod graph_build;
pub mod maxspeed;
pub mod mem;
pub mod names;
pub mod nodeloc;
pub mod osm;
pub mod overlay;
pub mod par;
pub mod pbf;
pub(crate) mod progress;
pub mod pbf_extra;
pub mod poi_attrs;
pub mod timing;
pub mod poi_build;
pub mod poi_reviews;
pub mod poi_side;
pub mod proto;
pub mod region;
pub mod rings;
pub mod roads;
pub mod safety;
pub mod select;
pub mod spatial;
pub mod tags;
pub mod tags_extra;
pub mod transit_lines;

#[cfg(test)]
mod testpbf;
