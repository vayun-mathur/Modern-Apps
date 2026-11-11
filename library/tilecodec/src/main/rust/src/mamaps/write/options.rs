use std::path::PathBuf;

pub const DEFAULT_LEAF_CAPACITY: u32 = 4096;
/// Raw DEFLATE over everything but the body header.

/// What a build declares about itself before the first tile.
pub struct Options {
    pub min_zoom: u8,
    pub max_zoom: u8,
    /// Hashed over the format version, the generator revision, the input digest, the zoom range,
    /// the layer set, the simplification parameters and the schema-table version.
    ///
    /// The writer does not compute it: only the generator knows those inputs. It does carry it, and
    /// a reader's cache marker is `CACHE_FORMAT | URL | build_id`.
    pub build_id: u64,
    /// Fold the archive's own tile content into the header's build id: when set, the id written
    /// is [`content_build_id`](super::content_build_id)`(build_id, digest)`, where the digest runs
    /// over every appended tile in order (tile id, stored length, stored-body hash). Identical
    /// tiles and seed give an identical id on any machine; any changed tile byte changes it.
    pub build_id_from_content: bool,
    pub compress: bool,
    /// Store each leaf as one raw-DEFLATE frame rather than raw fixed-stride entries.
    ///
    /// The root stays raw either way — same 32-byte stride, same opening-prefix budget — and
    /// each frame's stored length rides in its root entry. Measured 0.39 on twenty random
    /// California leaves (2.54 GB → ~1.00 GB). On by default: a leaf that would grow is stored
    /// raw instead, so this never costs a byte, and the reader takes every length from a field
    /// either way. Off keeps the legacy all-raw layout bit for bit.
    pub compress_leaves: bool,
    /// Set when the generator normalised ring winding and hole containment, which is what lets the
    /// renderer skip its repair pass.
    pub rings_validated: bool,
    pub leaf_entry_capacity: u32,
    /// Where the body scratch file goes, or the system temporary directory when unset.
    ///
    /// Worth naming rather than always taking `std::env::temp_dir()`, because the scratch file is
    /// the size of the data section: 652 MB for California and a projected 82 GB for a planet
    /// build, and a system temporary directory is routinely on a small system volume. The generator
    /// already puts its *feature* spill beside the output for the same reason.
    pub spill_dir: Option<PathBuf>,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    /// Threads `finish` may use to compress leaves; `0` means the machine's available
    /// parallelism. Speed only: every leaf's frame is a pure function of the leaf, so the
    /// archive is the same at any count.
    pub threads: usize,
    /// Called on the body scratch file right after it is created, before anything is written:
    /// lets a host tool mark the scratch for its filesystem (e.g. uncompressed on a
    /// `compress-force` btrfs, where the already-DEFLATEd bodies would be zstd'd again at
    /// writeback). Never observable in the archive.
    pub spill_file_hook: Option<fn(&std::fs::File)>,
}

impl Default for Options {
    fn default() -> Options {
        Options {
            min_zoom: 0,
            max_zoom: 14,
            build_id: 0,
            build_id_from_content: false,
            compress: true,
            compress_leaves: true,
            rings_validated: false,
            leaf_entry_capacity: DEFAULT_LEAF_CAPACITY,
            spill_dir: None,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_287,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_287,
            threads: 0,
            spill_file_hook: None,
        }
    }
}
