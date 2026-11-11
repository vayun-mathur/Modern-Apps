//! Reading a `.mamaps` archive over range requests.
//!
//! The cost contract, which is the reason the container is shaped the way it is:
//!
//! | | requests |
//! |---|
//! | open | 1 |
//! | a tile whose leaf is cached | 1 |
//! | a tile whose leaf is not | 2 |
//! | ever | never 3 |
//!
//! Open is one request because the header, the dictionary and the root index all fit inside
//! [`OPEN_PREFIX_BYTES`] and none of the three is compressed, so all three are used straight out
//! of the prefix. Sixteen cached leaves at 4096 entries each address 65 536 tiles with no
//! directory traffic at all, which matches the locality the transport was tuned around.
//!
//! **Every read takes its length from a header or entry field.** No sentinel, no length
//! discovered mid-stream, no read-until-it-looks-done — because the disk cache in `tile::cache`
//! only stores a `206` whose body length equals what was asked for, so a read of the wrong length
//! does not merely fail, it poisons that range for every later read.
//!
//! The reader speaks v8 and v9 headers (128 bytes, 10 layers) and v8/v9/v10 bodies. Anything
//! else is refused on open rather than reinterpreted.

pub mod archive;
pub mod helpers;
pub mod leaf_cache;
pub mod overlay;

// Private imports mirroring the original single-file module, so tests' `use super::*`
// keeps resolving the same names. Pure moves; nothing here changed.
#[allow(unused_imports)]
use crate::mamaps::body::{
    align4, Body, BuildingAttrs, Carriageway, Feature, Heightmap, LaneTurns, Layer,
    MarkingConvention, Part, BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ROAD_LANES,
    BODY_HEADER_LEN, FLAG_DETAIL_NUMERIC, FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_ONEWAY,
    FLAG_IS_TUNNEL, GEOM_LINE, GEOM_POINT, GEOM_POLYGON, ID_NONE, LAYER_INDEX_LEN, NAME_NONE,
    PART_ENTRY_LEN, WINDING_HOLE, WINDING_OUTER,
};
#[allow(unused_imports)]
use crate::mamaps::dict::{self, Dictionary};
#[allow(unused_imports)]
use crate::mamaps::header::{Header, COMPRESSION_DEFLATE, COMPRESSION_NONE, HEADER_LEN};
#[allow(unused_imports)]
use crate::mamaps::index::{self, LeafEntry, RootEntry};
#[allow(unused_imports)]
use crate::mamaps::body;
#[allow(unused_imports)]
use crate::pmtiles::tile_id;
#[allow(unused_imports)]
use crate::proto::{err, Error, Result};
#[allow(unused_imports)]
use crate::stream::{RangeReader, OPEN_PREFIX_BYTES};
#[allow(unused_imports)]
use std::cell::RefCell;

pub use archive::MamapsArchive;
pub use helpers::{decompress, open_prefix, read_all, MIN_FILE_LEN};
pub use leaf_cache::{LeafCache, DEFAULT_LEAF_CACHE_BYTES, DEFAULT_LEAF_CACHE_CAPACITY};
pub use overlay::{OverlayArchive, TileSource, base_matches, prune_tombstones};
