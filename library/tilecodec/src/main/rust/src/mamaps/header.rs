//! The `.mamaps` header: 128 bytes that locate every other section.
//!
//! Every section is found **only** through an offset declared here. Nothing is inferred from
//! layout, because layout is not fixed: the real 137 GB PMTiles archive puts its leaf
//! directories *after* its tile data, and a reader that assumed otherwise would address the
//! wrong bytes. The same freedom is deliberately kept here.
//!
//! The header is small enough that it always arrives inside the reader's opening prefix
//! together with the dictionary and the root index, which is what makes a cold open one range
//! request. [`super::write`] asserts that budget rather than letting a build silently cost every
//! reader a third round trip.
//!
//! Split into [`consts`] (magic, versions, lengths, flags), [`types`] (the struct and its
//! accessors), [`parse`] (`parse` plus its internal `check`), [`serialize`] and [`tests`].
//! Pure moves; this module only declares the submodules and re-exports the public surface.

mod consts;
mod parse;
mod serialize;
mod types;

#[cfg(test)]
mod tests;

pub use consts::{
    COMPRESSION_DEFLATE, COMPRESSION_NONE, COMPRESSION_ZSTD, COMPRESSION_ZSTD_DICT, FLAG_BODIES_COMPRESSED,
    FLAG_LEAF_LEN_64, FLAG_LEAVES_COMPRESSED, FLAG_RINGS_VALIDATED, FLAG_RUN_LENGTH_PRESENT, FORMAT_VERSION,
    FORMAT_VERSION_HEADER_V10, FORMAT_VERSION_V9, FORMAT_VERSION_V10, FORMAT_VERSION_V11, HEADER_LEN, MAGIC,
    MAX_ZOOM,
};
pub use types::Header;
