//! Wire constants for the `.mamaps` header: magic, versions, lengths and flags.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

pub const MAGIC: &[u8; 7] = b"MAMAPS\0";

/// Bumped only for a change a reader cannot ignore. The archive carries a
/// [`build_id`](crate::mamaps::header::Header::build_id) for "same format, different data".
///
/// v8 merges the four wash layers (`earth`, `water`, `landcover`, `landuse`) into one
/// `landtype` layer (id 0) capped at z14 at 1.0x sharpness, and appends `orchard`,
/// `vineyard` and `quarry` to [`dict::KINDS`](crate::mamaps::dict::KINDS). Old layer ids
/// 4..11 shift down by 3. The layer addition alone forces the bump — `read`'s
/// `check_matches_schema` validates the whole dictionary on open, so an older reader
/// rejects a v8 archive anyway. Clean break: rebuild and republish
/// planet.mamaps + basemap.mamaps, force-update apps, no back-compat.
///
/// v7 adds what the carriageway renderer needs to paint lane markings onto a road surface rather
/// than stroke parallel lines over it: a `FLAG_IS_ONEWAY` feature flag (bit 4), and one more
/// optional trailing body section behind `BODY_FLAG_ROAD_LANES` — a per-road **carriageway table**
/// (dense-parallel to the `roads` layer: the `lanes:forward`/`lanes:backward` split that says where
/// the centre line goes, and which dividers are solid) plus a per-tile **marking convention** byte
/// (left- or right-hand traffic, yellow or white centre line). The 24-byte feature record is
/// unchanged. The bump is forced twice over: an older reader rejects both an unknown body flag and
/// an unknown feature flag bit, so a v7 archive is a clean rejection rather than a wrong map.
///
/// v7 also adds the `junction` layer (id 11) to [`dict::LAYERS`](crate::mamaps::dict::LAYERS): one
/// `GEOM_LINE` feature per lane connector through an intersection, built from the same v6 routing
/// graph `traffic` reads. **That was only free because it landed inside v7's window.** Appending a
/// layer normally forces a bump of its own — v4 did exactly this for `traffic` — because `read`'s
/// `check_matches_schema` compares the whole dictionary on open, so every existing archive stops
/// opening. It cost nothing here only because v7 was committed but never built or shipped, leaving
/// no deployed reader to reject. The window shuts the moment a v7 archive exists: any layer added
/// after that costs v8.
///
/// v6 adds two optional trailing body sections behind new body flags: a per-building **S3DB
/// attribute table** (`BODY_FLAG_BUILDING_TABLE`, dense-parallel to the `buildings` layer —
/// heights, roof shape/height/direction/orientation and wall/roof colours for 3D extrusion) and a
/// per-tile **DEM heightmap** grid (`BODY_FLAG_HEIGHTMAP`, `u16` metres for 3D terrain relief).
/// The 24-byte feature record is unchanged, so the bump is not forced by the record width — it is
/// forced because an older reader rejects an unknown body flag, and would otherwise silently draw a
/// v6 tile flat. `Body::parse` refuses a version it does not speak, so an old v5 archive is a clean
/// rejection rather than a wrong map.
///
/// v5 bakes lane data into the `roads` layer: a per-feature **lane count** in the byte the
/// feature record kept reserved (byte 23), which the renderer expands into that many parallel
/// carriageway lanes with dividers at high zoom. The record width is unchanged, so the bump is
/// not forced by the dictionary (no new `kind`/`kind_detail`) — it is forced because an older
/// reader would read a v5 body's lane byte as the reserved zero it always was and draw every
/// multi-lane road as a single line. `Body::parse` refuses a version it does not speak, so the
/// mismatch is a clean rejection rather than a silently wrong map.
///
/// v4 adds the `traffic` layer (id 10) to [`dict::LAYERS`](crate::mamaps::dict::LAYERS): one
/// `GEOM_LINE` feature per drivable component segment of the v6 routing graph, each carrying
/// its packed `component_id` in the body id table. The layer addition alone forces the bump —
/// `read`'s `check_matches_schema` validates the whole dictionary on open, so an older reader
/// rejects a v4 archive anyway.
///
/// v3 adds `fuel`, `hotel` and `atm` to [`dict::KINDS`](crate::mamaps::dict::KINDS) and an optional
/// per-body feature id table. The kind additions alone force the bump: `read`'s
/// `check_matches_schema` validates the whole dictionary on open, so an older reader would
/// reject a v3 archive anyway. v1 is no longer read — the last v1 archive predates `places`,
/// `poi` and `transit` entirely.
///
/// The reader speaks v8 and v9 headers ([`FORMAT_VERSION_V9`]); any other version byte is
/// refused on open. Bodies carry their own version byte (v8, v9 or [`FORMAT_VERSION_V10`]).
pub const FORMAT_VERSION: u8 = 8;

/// The v9 format version: zstd bodies, compressed leaves, packed sidecars.
///
/// Bumped for the structural-savings rebuild. A v9 archive is refused by a v8
/// reader (clean rejection, not a wrong map); a v9 reader still refuses
/// anything it does not speak. See the v8 history above for what forces a bump.
pub const FORMAT_VERSION_V9: u8 = 9;

/// The v10 format version: narrowed body records.
///
/// v10 bodies drop dead bytes from feature records (per-layer classes),
/// shrink part entries 12 B to 4 B, and store side tables bitset+sparse. The
/// header/index framing is unchanged from v9; only body payloads differ, gated
/// by the per-body version byte. A v10 body in a v8 archive is refused at the
/// body, not the header — but builders ship v10 bodies under v9+ headers.
pub const FORMAT_VERSION_V10: u8 = 10;

/// The planet5 *header* version: bodies are zstd frames that reference trained dictionaries
/// ([`COMPRESSION_ZSTD_DICT`]), stored in a section between the root index and the leaves.
///
/// Planet5 size work. Shares the number 10 with the v10 *body* version but lives in a different
/// byte (header byte 7 vs body byte 3); the two never meet. A v8/v9-only reader refuses a v10
/// header cleanly, which is what keeps planet4 apps on planet4. This reader still speaks v8 and
/// v9 headers unchanged.
pub const FORMAT_VERSION_HEADER_V10: u8 = 10;

/// Body version 11: a v10 body whose heightmap samples are MED-predicted (LOCO-I) zigzag
/// residuals rather than raw `u16`s. Lossless (mod 2^16 arithmetic); only the entropy of the
/// heightmap section changes, which is what makes it compress. Planet5 only.
pub const FORMAT_VERSION_V11: u8 = 11;

pub const HEADER_LEN: usize = 128;

/// Bodies are compressed frames; clear means the body is stored raw.
pub const FLAG_BODIES_COMPRESSED: u16 = 1 << 0;
/// At least one leaf entry has a `run_length` above 1, so a reader must honour runs.
pub const FLAG_RUN_LENGTH_PRESENT: u16 = 1 << 1;
/// Ring winding and hole containment were normalised at build time.
///
/// What lets `tess::fill`'s repair pass be skipped: with this set, every polygon part group has
/// exactly one CCW outer and its holes are CW and strictly inside it.
pub const FLAG_RINGS_VALIDATED: u16 = 1 << 2;
/// The leaf index exceeds 4 GiB (planet scale: ~268M bodies ×16 B ≈4.29 GB).
/// When set, bytes 72..80 of the header hold leaf_len as a little-endian u64
/// (instead of u32 leaf_len at 72..76 plus 0-reserved at 76..80). Common
/// path (NA ~418 MB) stays flag 0 + u32 + 0 — byte-identical to v1.
pub const FLAG_LEAF_LEN_64: u16 = 1 << 3;
/// Each leaf is stored as one raw-DEFLATE frame (v9 only) rather than raw
/// fixed-stride entries.
///
/// The root stays raw — 32-byte stride, usable straight out of the opening
/// prefix — and each root entry's formerly-reserved word carries the frame's
/// stored length ([`crate::mamaps::index::RootEntry::stored_len`]). The reader
/// inflates one leaf to exactly `leaf_entry_count * 16` bytes and binary
/// searches in memory, so lookups keep their shape and their request count.
/// An older reader refuses cleanly: the flag is unknown to it, and so is the
/// non-zero reserved word. See [`crate::mamaps::index`] for the framing.
pub const FLAG_LEAVES_COMPRESSED: u16 = 1 << 4;

pub(super) const KNOWN_FLAGS: u16 =
    FLAG_BODIES_COMPRESSED | FLAG_RUN_LENGTH_PRESENT | FLAG_RINGS_VALIDATED | FLAG_LEAF_LEN_64
    | FLAG_LEAVES_COMPRESSED;

/// Bodies stored as written.
pub const COMPRESSION_NONE: u8 = 0;
/// Raw DEFLATE, one independent frame per body.
///
/// Not zstd, which is what a container designed on paper would reach for. This crate's whole
/// dependency list is `miniz_oxide` — pure Rust, so it cross-compiles for
/// `aarch64-linux-android` with no C toolchain and nothing to configure — and zstd's encoder
/// would end that. Not gzip either: gzip costs 18 bytes of framing per body and existed only
/// because MapLibre requires it, which nothing reading this format does.
pub const COMPRESSION_DEFLATE: u8 = 1;
/// zstd, one independent frame per body (v9 only).
///
/// Same framing as DEFLATE (raw frame behind the uncompressed 16-byte body
/// header), different codec. Encoder and decoder are pure Rust (`zstd-rs` /
/// `ruzstd`) so the aarch64-linux-android cdylib keeps its no-C-toolchain
/// property — see the crate docs in `Cargo.toml`.
pub const COMPRESSION_ZSTD: u8 = 2;
/// zstd frames that may reference a trained dictionary by the dictionary id in their frame header
/// (planet5, header version [`FORMAT_VERSION_HEADER_V10`] only). The dictionaries live in the
/// section between the root index and the leaves (see [`crate::mamaps::zdict`]).
pub const COMPRESSION_ZSTD_DICT: u8 = 3;

/// The deepest zoom a `tile_id_lo` can span inside one leaf. See [`crate::mamaps::index`].
pub const MAX_ZOOM: u8 = 22;
