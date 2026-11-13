//! Writing a `.mamaps` archive, streaming and byte-identically.
//!
//! Bodies are appended in ascending tile-id order — which the generator gets for free, because its
//! spill buckets are zoom-major Hilbert ranges — and the index is built as they go. Nothing is held
//! in memory but the index entries and the dedup buckets: bodies go to a scratch file as they
//! arrive and are copied onto the end of the archive at the finish.
//!
//! # Why the data section is not held in memory
//!
//! It was, and it cost twice the archive. A `data: Vec<u8>` grew to the whole 652 MB data section
//! of a California build, and then `finish` allocated a second 655 MB `Vec` and copied into it: a
//! 1.3 GB peak for a 655 MB file, and a projected 82 GB peak on a planet build, which is a hard
//! blocker on its own. Sending bodies straight to a scratch file makes the writer's peak the
//! *index* instead, at 16 bytes per stored body rather than the body itself, and
//! [`StreamWriter::finish_to_path`] never materialises the archive at all.
//!
//! # Dedup
//!
//! Two kinds, both of which matter:
//!
//! * **Run-length**, for consecutive identical bodies. An ocean is thousands of consecutive
//!   identical tiles, and a run collapses them to one entry as well as one body.
//! * **Content**, for non-consecutive ones — an FNV-1a bucket confirmed by a full byte compare, the
//!   same pattern `pmtiles::StreamBuilder` uses. Every hit is a fact, not a probability.
//!
//! Together these are what makes empty and ocean tiles nearly free: on the planet PMTiles archive
//! the same two collapse 1.57 M addressed tiles to 1.03 M stored bodies.
//!
//! Both compares are against bytes that now live in a file, which is the one thing spilling the
//! data section actually complicates. Neither compare pays much for it:
//!
//! * A run-length compare is against the body the **last index entry** points at, and the writer
//!   keeps exactly those bytes to hand. That is one body, not a cache, and it never reads the file.
//! * A content compare is against a body at an arbitrary earlier offset, so it may read. Only the
//!   candidates in one FNV bucket are ever compared, and [`Spill`]'s write buffer answers a compare
//!   against a body still in it without a syscall.
//!
//! # Determinism
//!
//! Byte-identical output for identical input, at any thread count, because nothing in the emit
//! path iterates a hash map: the dictionary comes from a constant table, layers are sorted by id,
//! and the bucket map is only ever *probed*, never walked. `pyramid.rs`'s existing byte-identity
//! suite is the precedent and this holds to the same standard.
//!
//! Spilling changes none of that. The scratch file holds the same bytes in the same order the
//! in-memory `Vec` did, so which candidate a compare confirms — and therefore which offset an entry
//! is given — cannot depend on whether the bytes came from the buffer or from the file. That is
//! what lets [`StreamWriter::finish`] and [`StreamWriter::finish_to_path`] be two ways of emitting
//! one archive rather than two archives.


pub mod codec;
pub mod options;
pub mod spill;
pub mod writer;
pub mod writer_extra;

#[cfg(all(test, feature = "write"))]
mod tests;

pub use codec::{compress_body, compress_body_with, hash64};
#[cfg(feature = "write")]
pub use codec::{compress_body_zstd, compress_body_zstd_level};
pub use options::Options;
pub use writer::{content_build_id, StreamWriter};
pub(crate) use writer::Pending;
