//! The single-archive sidecar: graph + POI + transit after the tile data (`MAMA8`).
//!
//! INTEGRATOR (mod.rs registration — one line, applied separately):
//! ```rust,ignore
//! pub mod archive;
//! ```
//!
//! # Why a single archive exists
//!
//! The app downloads thirteen files (`MainActivity`'s gate) into one directory
//! and joins them by name and by count: graph packs by `metadata.bin` counts,
//! POI sidecars by ordinal, transit by `<feed>.transit`. A directory assembled
//! from two vintages reads garbage — and only the lengths catch it (see
//! `maps/src/main/rust/src/graph.rs`). The single archive replaces the
//! directory with one file: `[header|dict|root|leaves|tile data]` byte-identical
//! to today, then `[graph|poi|transit|section dir|footer]` appended 8-byte
//! aligned, each payload the same bytes as today.
//!
//! # Where it sits (coordinate with the v8 tail, do NOT collide)
//!
//! ```text
//! [header 128|160][dict][root][leaves][tile data][shared?][pad8]
//!   [graph payloads][poi payloads][transit pack][pad8]
//!   [section dir][footer 32]
//! ```
//!
//! * The tile prefix (`header` through `tile data`) is untouched: same offsets,
//!   same lengths, same 16 KiB open-prefix invariant. A reader that ignores the
//!   sidecar opens exactly as before.
//! * The v8 shared section (`MBSH`, named by the header's 160-byte tail
//!   `shared_offset`/`shared_len`) stays immediately after the tile data, where
//!   `write::StreamWriter` puts it. The sidecar starts at `shared_end` on v8,
//!   at `data_end` on v7 — see [`sidecar_start`]. It never reuses, moves, or
//!   reinterprets the header tail.
//! * Every sidecar payload starts 8-byte aligned; padding is zeroes.
//! * The section directory and footer are the last bytes: the footer names the
//!   directory, the directory names every payload. Nothing is inferred from
//!   layout — same rule the top-level header follows.
//!
//! # Section directory and footer wire
//!
//! Directory: `u32` LE count `n`, then `n` × 32-byte entries, padded with
//! zeroes to 8 bytes. Entries ascend by `kind` (deterministic build, same rule
//! as the shared section's pool directory).
//!
//! Entry (32 B, all little-endian): `0` kind (one of `ARCHIVE_KIND_*`), `1..4`
//! reserved zero, `4..8` flags (none defined, must be zero), `8..16` absolute
//! file offset, `16..24` payload length (excluding padding), `24..32` extra
//! (per-kind count the validator checks: see [`ArchiveEntry::extra`] kinds).
//!
//! Footer (32 B, all little-endian): `0..8` magic `MAMA8\0\0\0`, `8..16`
//! `dir_offset`, `16..24` `dir_len`, `24..32` `build_id` (must equal the tile
//! header's `build_id`).
//!
//! # Validators (per section)
//!
//! * `offset + len <= file_len` (checked_add, no wrap), 8-byte aligned offsets.
//! * No overlap: not with the tile prefix + shared section, not with each other.
//! * Magic/version per section: `MARG` v6..=7 (graph meta), `TRIX` v3..=7
//!   (transit), `MAPA` v1 (POI attrs), `PSP1`/`PNI1` (POI grid/words).
//! * Count match: nodes `(N+1)*12`, edges exact length from `(E, escapes,
//!   named)`, intermediate trailer `G <= E`, lanes sentinel, elevation `N*2`,
//!   POI `len/14 == attrs/spatial/words counts`, transit section dir in bounds.
//! * `build_id == header.build_id`: one archive, one id. See
//!   [`unified_build_id`].
//!
//! # Lazy mapping contract
//!
//! * Always: tile header + directory + this footer + section dir (kilobytes).
//! * First route: `nodes`/`edges`/`intermediate` slices.
//! * Marshal (instruction emission): `names`/`lanes` slices.
//! * Profile: `elevation` slice.
//! * First use: POI slices / transit pack.
//!
//! The slices are file offsets, so an `mmap` loader maps the one file once and
//! hands out `(offset, len)` views — no copies, no second download. What backs
//! them (`MmapRegion`, `MappedByteBuffer`, `FileChannel.map`) is each loader's
//! own business; this file owns only the offsets and the checks.
//!
//! # Unified build id
//!
//! One archive, one id: [`unified_build_id`] hashes the graph revision, the OSM
//! digest, the GTFS digest, the DEM digest and the tiler revision. A republish
//! under a stable URL wipes range caches via the existing
//! `basemap_origin(url, build_id)` marker (`library/map/.../tile/source.rs`) —
//! the marker already keys on `(CACHE_FORMAT, url, build_id)`, so a new id
//! drops every stale byte range without a second request.

pub mod build_id;
pub mod consts;
pub mod entry;
pub mod footer;
pub mod layout;
pub mod view;

#[cfg(test)]
mod tests;

pub use build_id::unified_build_id;
pub use consts::{
    ARCHIVE_ALIGN, ARCHIVE_DIR_HEADER_LEN, ARCHIVE_ENTRY_LEN, ARCHIVE_FOOTER_LEN, ARCHIVE_MAGIC,
    ARCHIVE_KIND_GRAPH_EDGES, ARCHIVE_KIND_GRAPH_ELEVATION, ARCHIVE_KIND_GRAPH_INTERMEDIATE,
    ARCHIVE_KIND_GRAPH_LANES, ARCHIVE_KIND_GRAPH_META, ARCHIVE_KIND_GRAPH_NAMES,
    ARCHIVE_KIND_GRAPH_NODES, ARCHIVE_KIND_GRAPH_OVERLAY, ARCHIVE_KIND_MAX_KNOWN, ARCHIVE_KIND_POI_ATTRS,
    ARCHIVE_KIND_POI_ADMIN, ARCHIVE_KIND_POI_INDEX, ARCHIVE_KIND_POI_NAMES, ARCHIVE_KIND_POI_REGION,
    ARCHIVE_KIND_POI_SPATIAL, ARCHIVE_KIND_POI_WORDS, ARCHIVE_KIND_TRANSIT, GRAPH_MAGIC,
    GRAPH_META_LEN, GRAPH_VERSION, GRAPH_VERSION_BLOCKED, GRAPH_VERSION_MIN, GRAPH_VERSION_VARINT,
    POI_RECORD_BYTES, TRANSIT_MAGIC, TRANSIT_VERSION, TRANSIT_VERSION_MIN, TRANSIT_VERSION_ZSEC,
};
pub use entry::{is_known_kind, ArchiveEntry};
pub use footer::ArchiveFooter;
pub use layout::{align_up, serialize_dir, sidecar_start};
pub use view::ArchiveView;
