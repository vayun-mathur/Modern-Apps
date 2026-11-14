//! PMTiles v3 and Mapbox Vector Tile 2.1 — the formats the Maps basemap is made of.
//!
//! One codec, two consumers. `scripts/maps/tile_build` **writes** the archives
//! (it replaced `tippecanoe` and `tile-join`, neither of which has a Windows
//! path), and the Vulkan vector renderer in `:library:map` **reads** the
//! `.mamaps` archives `scripts/maps` builds from them. Both have to agree down
//! to the byte, so there is one implementation rather than one per consumer.
//!
//! That history is why the tests here assert against **real** data — the published
//! archive's header and root directory, and a tile lifted out of it with a ranged
//! GET — rather than only round-tripping through our own writer. A synthetic round
//! trip proves we agree with ourselves; `tests/fixtures/README.md` records what
//! each fixture is and where it came from.
//!
//! The layers, each usable on its own:
//!
//!   * [`proto`] — protobuf wire codec. The read half mirrors `osm_ingest`'s
//!     decode-only reader; the write half is used only by the tiler.
//!   * [`mvt`] — vector tile 2.1 decode/encode, for the layers tiled from
//!     upstream Protomaps data.
//!   * [`gz`] — gzip framing over `miniz_oxide`'s raw DEFLATE, which PMTiles needs
//!     for both its directories and its tiles.
//!   * [`pmtiles`] — the v3 container, read and write, including Hilbert tile ids
//!     and the root/leaf directory split.
//!   * [`mamaps`] — the container the Vulkan renderer reads: geometry only,
//!     pre-clipped, attributes interned to integers, flat little-endian structs.
//!     **v8 only**: a 128-byte header, 9 layers, full bodies. Its writer is
//!     behind the `write` feature, so Android links only the read half.
//!
//! Nothing here allocates a thread, opens a socket or touches a GPU. The reader the
//! app uses over HTTP range requests is [`MamapsArchive`](mamaps::MamapsArchive),
//! built on the [`stream`] transport the caller supplies.

pub mod gz;
pub mod mamaps;
pub mod mvt;
pub mod pmtiles;
pub mod proto;
pub mod stream;

/// Blocked zstd sections ([`blocked`]): random-access compression for graph
/// tables. Read side always linked; the writer ([`blocked::write_blocked`])
/// is behind the `write` feature like the tile writer.
pub mod blocked;
/// Byte-addressed block compression for planet5 sidecars ([`zsec`]).
pub mod zsec;
