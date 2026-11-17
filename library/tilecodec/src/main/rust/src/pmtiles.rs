//! PMTiles v3 container — read and write.
//!
//! Both halves are needed: reading, because the `transit_stops` layer has to be
//! composited into the existing basemap archive, and writing, because that
//! composite has to come back out as a PMTiles the app can stream.
//!
//! Layout, as verified against the published `v5-ca.pmtiles`:
//!
//! ```text
//! [ 127-byte header ][ root directory ][ metadata ][ leaf directories ][ tile data ]
//! ```
//!
//! The header is fixed-width little-endian; everything else is at an offset it
//! names. Directories and metadata are compressed with `internal_compression`,
//! tiles with `tile_compression` — both gzip in practice.
//!
//! A **directory** is a columnar run of varints:
//!
//! ```text
//! uvarint num_entries
//! num_entries x uvarint  tile_id delta   (first is absolute)
//! num_entries x uvarint  run_length
//! num_entries x uvarint  length
//! num_entries x uvarint  offset          (0 = contiguous with the previous entry)
//! ```
//!
//! `run_length == 0` marks a **leaf pointer**: its offset/length address the leaf
//! directories section instead of tile data. `run_length >= 1` is a tile run, where
//! `run_length` consecutive tile ids all resolve to the same bytes — that is how
//! PMTiles collapses the vast runs of identical ocean tiles.
//!
//! Tile ids are positions along a **Hilbert curve**, offset by the number of tiles
//! in all lower zooms, so a z/x/y maps to a single integer whose neighbours are
//! spatially close. That locality is what lets the app fetch a screenful of tiles
//! in few range requests.


pub mod codec;
pub mod directory;
pub mod file;
pub mod hilbert;
pub mod read;
pub mod types;
pub mod write;

#[cfg(test)]
mod tests;

pub use types::{
    COMPRESSION_GZIP, COMPRESSION_NONE, Entry, Header, HEADER_LEN, MAGIC, SPEC_VERSION,
    TILE_TYPE_MVT,
};
pub use hilbert::{tile_id, tile_zxy, zoom_base};
pub use directory::{parse_directory, serialize_directory};
pub use read::Archive;
pub use file::ArchiveFile;
pub use codec::read_exact_at;
pub use write::{Builder, StreamBuilder};
pub(crate) use codec::{decompress, find_entry};
