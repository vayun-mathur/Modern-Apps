//! One tile body: every layer's geometry, flat and renderer-shaped.
//!
//! # What is not here
//!
//! **Triangles.** Pre-tessellating would bake the style's layer set into the data, so a restyle
//! would mean a rebuild — and the whole point of splitting one data-driven layer into several is
//! that it is a *paint* decision.
//!
//! **Points.** The renderer decodes them and throws them away, so they are not carried. A
//! geometry type is line or polygon and nothing else.
//!
//! **A property map.** A feature's whole attribute surface is `kind`, `kind_detail` and three
//! bits, because that is all the style filters on. No per-tile string table, no keys, no values.
//!
//! # Why all layers share one body
//!
//! Splitting per layer would multiply a cold tile's range requests by seven, and the renderer
//! wants every style layer for one tile at once anyway. One body, one request.
//!
//! # Layout
//!
//! ```text
//! [BodyHeader 16][LayerIndex × n][per layer: FeatureRecord[] PartEntry[] coord arena]
//! ```
//!
//! The fixed records are 4-byte aligned so a reader takes zero-copy slices of them.
//!
//! # Why coordinates are varints and everything else is not
//!
//! The first draft of this format stored the arena as flat `[i16 x, i16 y]` pairs, on the reasoning
//! that a zero-copy slice is the cheapest possible decode. Measured against the real published
//! archive that cost **1.72× the compressed bytes of the MVT it replaces**, consistently from z0 to
//! z14, because 87% of a body is coordinates and a fixed 4 bytes per point is simply worse than a
//! zigzag varint delta of 1 to 2. The interned dictionary is a real win — upstream `water` features
//! carry forty `name:*` localisations each — but it is not where the bytes are.
//!
//! So the arena is per-part zigzag varint deltas, which is what MVT does and for the same reason,
//! and it brings the format to 1.14× MVT. What is still won over MVT is the *decode*: no per-tile
//! string table, no property map, no `String` allocation per feature, and one flat `Vec` of points
//! per layer rather than a geometry-command walk per feature.
//!
//! Deltas restart at each part, so a part is independently decodable and a long line does not
//! accumulate a large running value.
//!
//! `raw_len` lives in the body header, which is **outside** the compressed frame, so a
//! decompressing reader knows the exact output size before it starts: an exact-length read from
//! the leaf entry and a single allocation from here.


pub mod codec;
pub mod consts;
pub mod emit;
pub mod emit_extra;
pub mod model;
pub mod tables;
pub mod tables_extra;

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_extra;
#[cfg(test)]
mod tests_extra2;

pub use consts::{
    BODY_FLAG_BUILDING_TABLE, BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ID_TABLE,
    BODY_FLAG_LANE_TABLE, BODY_FLAG_NAME_TABLE, BODY_FLAG_ROAD_LANES, BODY_HEADER_LEN,
    DEFAULT_EXTENT, FEATURE_RECORD_LEN, FLAG_DETAIL_NUMERIC, FLAG_IS_BRIDGE, FLAG_IS_LINK,
    FLAG_IS_ONEWAY, FLAG_IS_TUNNEL, GEOM_LINE, GEOM_POINT, GEOM_POLYGON, LAYER_INDEX_LEN,
    PART_ENTRY_LEN, WINDING_HOLE, WINDING_OUTER,
};
pub use model::{
    BUILDING_ATTRS_LEN, Body, BuildingAttrs, Carriageway, Feature, Heightmap, ID_NONE, LANE_LEFT,
    LANE_MERGE_TO_LEFT, LANE_MERGE_TO_RIGHT, LANE_NONE, LANE_REVERSE, LANE_RIGHT, LANE_SHARP_LEFT,
    LANE_SHARP_RIGHT, LANE_SLIGHT_LEFT, LANE_SLIGHT_RIGHT, LANE_THROUGH, LaneTurns, Layer,
    MarkingConvention, NAME_NONE, Part, REGION_NONE, ROOF_DOME, ROOF_FLAT, ROOF_GABLED, ROOF_HIPPED,
    ROOF_ORIENT_ACROSS, ROOF_ORIENT_ALONG, ROOF_ORIENT_MAX, ROOF_PYRAMIDAL, ROOF_SHAPE_MAX,
    ROOF_SKILLION,
};
pub use emit::{Scratch, serialize, serialize_into, serialize_into_v10, serialize_into_v11};
pub(crate) use codec::parse_layer;
pub(crate) use tables::align4;
