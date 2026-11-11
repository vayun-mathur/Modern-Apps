//! The header struct and its cheap accessors; parsing lives in `parse`,
//! serialization in `serialize`.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

use super::consts::{FLAG_BODIES_COMPRESSED, FLAG_LEAVES_COMPRESSED, FLAG_RINGS_VALIDATED, HEADER_LEN};

/// What a reader must know before it can address anything.
///
/// Field order **is** wire order, and the byte map is in [`Header::parse`]. Lengths are `u32`
/// wherever a section cannot plausibly exceed 4 GiB; offsets are `u64` without exception,
/// because the data section on its own is already past that on a planet build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub flags: u16,
    pub compression: u8,
    pub layer_count: u8,
    pub min_zoom: u8,
    pub max_zoom: u8,
    /// Identifies the *data*, not the format: hashed over the generator revision, the input
    /// digest, the zoom range, the layer set and the simplification parameters.
    ///
    /// This is the only thing standing between republishing under a stable `immutable` URL and
    /// every existing reader serving stale tiles forever. It costs no extra request, because it
    /// is already in the prefix a reader fetches to open the archive at all.
    pub build_id: u64,
    /// The whole file, so a truncated download is caught on open rather than at the first tile
    /// that happens to land past the end.
    pub file_len: u64,
    pub dict_offset: u64,
    pub dict_len: u32,
    /// Leaf entries per leaf. A power of two, doubled at build time when the root would not fit.
    pub leaf_entry_capacity: u32,
    pub root_offset: u64,
    pub root_len: u32,
    pub leaf_count: u32,
    pub leaf_offset: u64,
    /// Leaf index length. u64 on the wire when FLAG_LEAF_LEN_64 is set
    /// (planet: ~4.29 GB); otherwise the 72..76 u32 plus 76..80 zero
    /// reserved — so NA/us-west stay byte-identical.
    pub leaf_len: u64,
    pub data_offset: u64,
    pub data_len: u64,
    /// Tiles that resolve to a body, counting every id a run covers.
    pub tiles_addressed: u64,
    /// Bodies actually stored, after run-length and content dedup.
    pub bodies_written: u64,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
}

impl Header {
    pub fn compressed(&self) -> bool {
        self.flags & FLAG_BODIES_COMPRESSED != 0
    }

    pub fn rings_validated(&self) -> bool {
        self.flags & FLAG_RINGS_VALIDATED != 0
    }

    /// Each leaf is one raw-DEFLATE frame whose stored length rides in its
    /// root entry, rather than raw fixed-stride entries. The root itself is
    /// unchanged — still raw, still 32-byte stride, still inside the opening
    /// prefix.
    pub fn leaves_compressed(&self) -> bool {
        self.flags & FLAG_LEAVES_COMPRESSED != 0
    }

    /// This header's wire length: always 128 (v8 and v9).
    pub fn wire_len(&self) -> usize {
        HEADER_LEN
    }
}
