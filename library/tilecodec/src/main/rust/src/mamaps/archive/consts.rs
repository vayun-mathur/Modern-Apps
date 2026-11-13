/// Magic opening every section directory is closed by: 8 bytes at the footer's head.
pub const ARCHIVE_MAGIC: &[u8; 8] = b"MAMA8\0\0\0";
/// Footer wire length. Fixed so a reader can slice it off the file's end.
pub const ARCHIVE_FOOTER_LEN: usize = 32;
/// One directory entry's wire length.
pub const ARCHIVE_ENTRY_LEN: usize = 24 + 8;
/// Directory header wire length (`u32` count).
pub const ARCHIVE_DIR_HEADER_LEN: usize = 4;
/// Alignment every sidecar payload starts on.
pub const ARCHIVE_ALIGN: u64 = 8;

/// Graph `metadata.bin` payload: `MARG` + version + counts.
pub const ARCHIVE_KIND_GRAPH_META: u8 = 1;
/// Graph `nodes.bin` payload: `NodeRec[N+1]`, 12 B each.
pub const ARCHIVE_KIND_GRAPH_NODES: u8 = 2;
/// Graph `edges.bin` payload: records + escape index + escape rows + name bitmap + name offs.
pub const ARCHIVE_KIND_GRAPH_EDGES: u8 = 3;
/// Graph `intermediate.bin` payload: blob + trailer.
pub const ARCHIVE_KIND_GRAPH_INTERMEDIATE: u8 = 4;
/// Graph `road_names.bin` payload: NUL-terminated string pool.
pub const ARCHIVE_KIND_GRAPH_NAMES: u8 = 5;
/// Graph `lanes.bin` payload: sparse lane index + blob.
pub const ARCHIVE_KIND_GRAPH_LANES: u8 = 6;
/// Graph `elevation.bin` payload: `i16[N]`.
pub const ARCHIVE_KIND_GRAPH_ELEVATION: u8 = 7;
/// POI `poi_index.bin` payload: 14 B records.
pub const ARCHIVE_KIND_POI_INDEX: u8 = 8;
/// POI `poi_names.bin` payload: NUL-terminated pool.
pub const ARCHIVE_KIND_POI_NAMES: u8 = 9;
/// POI `poi_attrs.bin` payload: optional attribute sidecar.
pub const ARCHIVE_KIND_POI_ATTRS: u8 = 10;
/// POI `poi_spatial.bin` payload: optional CSR grid.
pub const ARCHIVE_KIND_POI_SPATIAL: u8 = 11;
/// POI `poi_name_index.bin` payload: optional word index.
pub const ARCHIVE_KIND_POI_WORDS: u8 = 12;
/// Transit `<feed>.transit` payload: `TRIX` pack.
pub const ARCHIVE_KIND_TRANSIT: u8 = 13;
/// Graph `overlay.bin` payload: the `MOVL` highway overlay (optional).
pub const ARCHIVE_KIND_GRAPH_OVERLAY: u8 = 14;
/// POI `poi_region.bin` payload: one tagged OSM boundary id per index ordinal
/// (`PRG1` + version + count, then `u64` ids; 0 means no boundary).
pub const ARCHIVE_KIND_POI_REGION: u8 = 15;
/// POI `poi_admin.bin` payload: the boundary hierarchy behind kind 15 — per
/// linked boundary and ancestor its name, `name:en`, `admin_level` and parent
/// (`PAD1` + version + count + names_len, 32 B entries, then a name pool; see
/// `osm_ingest/src/poi_admin.rs`). Optional.
pub const ARCHIVE_KIND_POI_ADMIN: u8 = 16;
/// Highest kind this build writes. Readers accept any non-zero kind and skip
/// the ones they do not know, so a newer archive still opens in an older app.
pub const ARCHIVE_KIND_MAX_KNOWN: u8 = ARCHIVE_KIND_POI_ADMIN;

/// `metadata.bin` (`MARG`) magic, little-endian `u32`.
pub const GRAPH_MAGIC: u32 = 0x4752_414D;
/// Newest (and only, for the single archive) graph version: v7 blocked record
/// sections (nodes, edges records, elevation).
pub const GRAPH_VERSION: u32 = 7;
/// Oldest graph version accepted: v6 raw tables still validate by length.
pub const GRAPH_VERSION_MIN: u32 = 6;
/// `metadata.bin` wire length: magic + version + 4 counts.
pub const GRAPH_META_LEN: u64 = 40;
/// `transit` (`TRIX`) magic, little-endian `u32`.
pub const TRANSIT_MAGIC: u32 = 0x5452_4958;
/// Newest transit version accepted. v7 adds a host-only section the app never reads.
pub const TRANSIT_VERSION: u32 = 7;
/// Oldest transit version accepted.
pub const TRANSIT_VERSION_MIN: u32 = 3;
/// POI index record stride.
pub const POI_RECORD_BYTES: u64 = 14;
