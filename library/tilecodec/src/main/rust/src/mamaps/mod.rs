//! `.mamaps` — the container the Vulkan renderer reads, shaped like what it draws.
//!
//! v8 or v9 headers (always 128 bytes), 10 layers, v8/v9/v10 tile bodies (the planet
//! archives are v9 headers over v10 bodies). Anything else is refused on open rather than
//! reinterpreted.
//!
//! # Why not MVT inside PMTiles
//!
//! Because almost none of it is used. An MVT tile is protobuf varints, a per-tile string table and
//! an arbitrary key/value property map per feature; the renderer reads exactly **one** property
//! (`kind`, as a `String`), handles two of the four geometry types, and decodes points only to
//! throw them away. Everything else is bytes every device downloads, inflates and discards.
//!
//! So: geometry only, pre-clipped, pre-simplified, attributes interned to integers, flat
//! little-endian structs. Not pre-tessellated — triangles would bake the style's layer set into the
//! data, and which layer a feature belongs to is a paint decision.
//!
//! # The sections
//!
//! ```text
//! [header 128][dictionary][root index][leaf indices][tile data]
//! ```
//!
//! Order on disk is **not** part of the format. Every section is located only by a header-declared
//! offset, because the real 137 GB PMTiles archive puts its leaves after its tile data and a reader
//! that assumed layout would address the wrong bytes.
//!
//! * [`header`] — 128 fixed bytes, including the `build_id` that makes republishing under an
//!   `immutable` URL safe.
//! * [`dict`] — layer names and the `kind`/`kind_detail` tables, **pre-seeded from a constant
//!   schema table** so an id never shifts between builds.
//! * [`index`] — a fixed-stride root and fixed-stride leaves, uncompressed so the root is usable
//!   straight out of the opening prefix.
//! * [`body`] — one per tile, with every layer inside it.
//! * [`read`] — open in one request, a warm tile in one, a cold tile in two, never three.
//! * `write` — the builder, behind the `write` feature.
//!
//! # What the `write` feature is for
//!
//! Android reads archives; it never writes one. Gating the builder keeps the interner and the
//! DEFLATE *encoder* out of the `aarch64-linux-android` cdylib, and — more usefully — makes it a
//! compile error rather than a review comment if the read path ever grows a dependency on the write
//! path.

pub mod body;
pub mod dict;
pub mod header;
pub mod index;
pub mod read;
pub mod zdict;

// The archive section index (ArchiveView + kind consts) is read-side: Android reads the
// graph/transit/poi sidecars bundled in a .mamaps. Only the writer below is write-gated.
pub mod archive;

#[cfg(feature = "write")]
pub mod write;

pub use body::Body;
pub use dict::Dictionary;
pub use header::Header;
pub use read::MamapsArchive;

#[cfg(all(test, feature = "write"))]
mod tests;
#[cfg(all(test, feature = "write"))]
mod tests_extra;
