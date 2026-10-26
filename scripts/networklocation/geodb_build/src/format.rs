//! The `geocoder.db` on-disk format, in one place.
//!
//! Every constant here is duplicated by hand in the reader,
//! `networklocation/src/main/rust/src/geocoder.rs`. That is the format's main hazard: the two
//! sides share no code, and a divergence does not fail loudly — it decodes to plausible
//! nonsense. `write::verify` reads a finished database back through an independent path, and
//! the reader has fixture tests, but neither replaces keeping this list and its counterpart in
//! step.
//!
//! ```text
//! 0  magic  u32 = "MAGE"
//! 4  version u32 = 5
//! 8  n      u32
//! 12 21 sections, each [u32 size][body]:
//!      [0..6]   dictionaries: name, house, street, city, state, country, postcode
//!      [7..16]  columns: lat, lon, name, house, street, city, state, country, postcode, kind
//!      [17]     grid
//!      [18]     fwd      (records with a street, by structured address)
//!      [19]     nm_name  (name ids, ascending)
//!      [20]     nm_rec   (record index for each nm_name entry)
//! ```
//!
//! All integers are **big-endian**, matching v2.
//!
//! ## What changed from v4
//!
//! v5 changes no content, only how it can be read. The reader memory-maps the file and must
//! never have to hold a section in RAM; v4's reader inflated every dictionary on open, about
//! 2 GB of heap for the planet.
//!
//! - **Dictionaries are blocked**: [`DICT_BLOCK`] strings per zstd frame, behind an
//!   uncompressed table of each block's first string, so a lookup binary-searches that table
//!   and decompresses one block. See `codec::encode_dict`.
//! - **Column directories store each block's end offset** instead of its compressed length, so
//!   a block is located without a prefix-sum table.
//!
//! ## What changed from v3
//!
//! v4 is v3 cut down for size; the section layout is unchanged.
//!
//! - Coordinates are **e5** (about 1.1 m), not e7. Nobody needs centimetres from a geocoder,
//!   and the two coordinate columns were nearly half the file.
//! - `fwd` holds only records that carry a street. A forward query always names one, so street
//!   samples and unaddressed POIs and places could never match and were dead weight.
//! - The name index holds one entry per street name per grid cell, not one per street sample.
//!
//! ## What changed from v2
//!
//! - Coordinates are **e7**, not e6. OSM stores e7, so v2's e6 threw away a decimal digit for
//!   nothing. This is the change that forces [`morton_in_cell`] to widen (see below).
//! - Two new columns, `name` and `kind`, and a `name` dictionary. v2 could only hold addresses
//!   because it had nowhere to put a feature's name or to say what sort of feature it was.
//! - Two new sections, `nm_name`/`nm_rec`, indexing records by name so forward search can find
//!   a POI or a place rather than only an address.

/// `"MAGE"`.
pub const MAGIC: u32 = 0x4D41_4745;
/// Format version. v2 was address-only and e6; v3 was e7 with a full forward index; v4 was e5;
/// v5 is v4 laid out to be read from a memory map.
pub const VERSION: u32 = 5;
/// Records per compressed column block.
pub const BLOCK: usize = 4096;
/// Strings per compressed dictionary block. Small enough that a lookup decompresses only a few
/// kilobytes; large enough that the uncompressed first-string table stays a fraction of the
/// dictionary.
pub const DICT_BLOCK: usize = 1024;
/// Zstandard level for column blocks and dictionaries. Measured on California's column blocks:
/// level 3 is 9.5% larger than 19 and about 37x faster to write.
pub const ZSTD_LEVEL: i32 = 3;

/// Grid cell edge in e5 units: 0.05 degrees, about 5.5 km of latitude.
pub const CELL: i64 = 5_000;
/// Cell columns around the planet.
pub const COLS: i64 = 36_000_000 / CELL;
/// Lowest representable latitude, e5.
pub const MIN_LAT: i32 = -9_000_000;
/// Lowest representable longitude, e5.
pub const MIN_LON: i32 = -18_000_000;

/// Number of string dictionaries.
pub const DICTS: usize = 7;
/// Number of record columns.
pub const COLUMNS: usize = 10;
/// Total sections after the header.
pub const SECTIONS: usize = DICTS + COLUMNS + 4;

/// Dictionary slots, in section order.
pub const D_NAME: usize = 0;
/// House-number dictionary.
pub const D_HOUSE: usize = 1;
/// Street-name dictionary.
pub const D_STREET: usize = 2;
/// City dictionary.
pub const D_CITY: usize = 3;
/// State dictionary.
pub const D_STATE: usize = 4;
/// Country-code dictionary.
pub const D_COUNTRY: usize = 5;
/// Postcode dictionary.
pub const D_POSTCODE: usize = 6;

/// Column slots, in section order.
pub const C_LAT: usize = 0;
/// Longitude column.
pub const C_LON: usize = 1;
/// Name dictionary id.
pub const C_NAME: usize = 2;
/// House-number dictionary id.
pub const C_HOUSE: usize = 3;
/// Street dictionary id.
pub const C_STREET: usize = 4;
/// City dictionary id.
pub const C_CITY: usize = 5;
/// State dictionary id.
pub const C_STATE: usize = 6;
/// Country dictionary id.
pub const C_COUNTRY: usize = 7;
/// Postcode dictionary id.
pub const C_POSTCODE: usize = 8;
/// Record kind, one of the `K_*` values.
pub const C_KIND: usize = 9;

/// Which columns are delta-encoded against the previous value in their block.
pub const DELTA: [bool; COLUMNS] =
    [true, true, false, false, false, false, false, false, false, false];

/// Column names, for the writer's size report.
pub const COL_NAME: [&str; COLUMNS] =
    ["lat", "lon", "name", "house", "street", "city", "state", "country", "postcode", "kind"];

/// Dictionaries whose order is a **searchable** contract: the reader binary-searches them, so
/// they must be sorted by UTF-16 code unit. The rest are frequency-ordered for size.
pub const SEARCHABLE: [bool; DICTS] = [true, false, true, true, true, true, false];

/// A postal address.
pub const K_ADDRESS: u8 = 1;
/// A named road, sampled along its length.
pub const K_STREET: u8 = 2;
/// A named point of interest.
pub const K_POI: u8 = 3;
/// A populated place: city, town, village, suburb, neighbourhood.
pub const K_PLACE: u8 = 4;

/// Round an OSM e7 coordinate to the e5 the database stores.
pub fn e7_to_e5(v: i32) -> i32 {
    (v as i64 + 50).div_euclid(100) as i32
}

/// Grid cell containing an e5 coordinate. Row-major, `COLS` cells per row.
pub fn cell_id(lat: i32, lon: i32) -> i64 {
    let row = (lat as i64 - MIN_LAT as i64) / CELL;
    let col = (lon as i64 - MIN_LON as i64) / CELL;
    row * COLS + col
}

/// Z-order key of a coordinate **within** its cell.
///
/// Records are ordered by cell and then by this, so both delta columns stay small: a plain
/// `(lat, lon)` sort keeps the latitude delta tiny but lets longitude swing across the whole
/// cell on every row.
///
/// An e5 cell is 5 000 units and needs 13 bits per axis; this interleaves 20, so the offsets
/// cannot overflow. Too few bits would not fail — it would silently produce a wrong ordering.
pub fn morton_in_cell(lat: i32, lon: i32) -> u64 {
    let lat_off = ((lat as i64 - MIN_LAT as i64) % CELL) as u64;
    let lon_off = ((lon as i64 - MIN_LON as i64) % CELL) as u64;
    (interleave20(lat_off) << 1) | interleave20(lon_off)
}

/// Spread the low 20 bits of `v` into the even bit positions of a 40-bit result.
///
/// Called twice per comparison while sorting hundreds of millions of rows, so it uses the
/// shift-and-mask spread rather than a bit-at-a-time loop.
fn interleave20(v: u64) -> u64 {
    let mut x = v & 0xF_FFFF;
    x = (x | (x << 16)) & 0x0000_FFFF_0000_FFFF;
    x = (x | (x << 8)) & 0x00FF_00FF_00FF_00FF;
    x = (x | (x << 4)) & 0x0F0F_0F0F_0F0F_0F0F;
    x = (x | (x << 2)) & 0x3333_3333_3333_3333;
    (x | (x << 1)) & 0x5555_5555_5555_5555
}

/// Whether an e5 coordinate is inside the representable range.
pub fn in_range(lat: i32, lon: i32) -> bool {
    (MIN_LAT..=9_000_000).contains(&lat) && (MIN_LON..=18_000_000).contains(&lon)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cells_tile_the_planet_without_gaps_or_overlap() {
        assert_eq!(COLS, 7200);
        // South-west corner is cell 0.
        assert_eq!(cell_id(MIN_LAT, MIN_LON), 0);
        // One cell east.
        assert_eq!(cell_id(MIN_LAT, MIN_LON + CELL as i32), 1);
        // One cell north.
        assert_eq!(cell_id(MIN_LAT + CELL as i32, MIN_LON), COLS);
        // The north-east corner stays inside i64 comfortably.
        let last = cell_id(9_000_000, 18_000_000);
        assert!(last > 0 && last < 26_000_000, "cell id {last} out of expected range");
    }

    #[test]
    fn a_cell_is_a_twentieth_of_a_degree() {
        // Anchored at a cell boundary so the arithmetic is unambiguous: 0.05 degrees is one
        // cell, so 0.04 stays inside and 0.06 does not.
        let lat = MIN_LAT + 2555 * CELL as i32;
        let a = cell_id(lat, -12_241_942);
        assert_eq!(cell_id(lat + 4_000, -12_241_942), a);
        assert_ne!(cell_id(lat + 6_000, -12_241_942), a);
        // And a cell step north moves by exactly one grid row.
        assert_eq!(cell_id(lat + CELL as i32, -12_241_942), a + COLS);
    }

    #[test]
    fn the_fast_interleave_matches_the_bit_loop() {
        let slow = |v: u64| (0..20).fold(0u64, |r, i| r | ((v >> i) & 1) << (2 * i));
        let mut v = 0x9E37_79B9u64;
        for _ in 0..100_000 {
            v ^= v << 13;
            v ^= v >> 7;
            v ^= v << 17;
            assert_eq!(interleave20(v), slow(v), "{v:#x}");
        }
        for v in [0, 1, 4_999, 0xF_FFFF, u64::MAX] {
            assert_eq!(interleave20(v), slow(v), "{v:#x}");
        }
    }

    #[test]
    fn morton_offsets_cannot_overflow_the_interleave() {
        assert!(CELL <= (1 << 20), "cell wider than the interleave can represent");
        // Extremes of a cell.
        let base_lat = MIN_LAT;
        let base_lon = MIN_LON;
        let hi = morton_in_cell(base_lat + CELL as i32 - 1, base_lon + CELL as i32 - 1);
        assert!(hi < (1u64 << 40), "morton escaped 40 bits: {hi:#x}");
        assert_eq!(morton_in_cell(base_lat, base_lon), 0);
    }

    #[test]
    fn morton_orders_nearby_points_together() {
        // Two points a metre apart must be far closer in Z-order than two a cell apart.
        let a = morton_in_cell(3_777_493, -12_241_942);
        let b = morton_in_cell(3_777_494, -12_241_942);
        let near = a.abs_diff(b);
        let far = a.abs_diff(morton_in_cell(3_777_493 + 4_000, -12_241_942));
        assert!(near < far, "z-order does not preserve locality: {near} vs {far}");
    }

    #[test]
    fn morton_is_injective_within_a_cell() {
        let mut seen = std::collections::HashSet::new();
        for dlat in 0..40 {
            for dlon in 0..40 {
                let m = morton_in_cell(MIN_LAT + dlat * 123, MIN_LON + dlon * 123);
                assert!(seen.insert(m), "collision at ({dlat}, {dlon})");
            }
        }
    }

    #[test]
    fn range_check_matches_the_e5_field_width() {
        assert!(in_range(0, 0));
        assert!(in_range(9_000_000, 18_000_000));
        assert!(in_range(-9_000_000, -18_000_000));
        assert!(!in_range(9_000_001, 0));
        assert!(!in_range(0, -18_000_001));
    }

    #[test]
    fn e7_rounds_to_the_nearest_e5() {
        assert_eq!(e7_to_e5(37_7749349), 3_777_493);
        assert_eq!(e7_to_e5(37_7749350), 3_777_494);
        assert_eq!(e7_to_e5(-122_4194249), -12_241_942);
        assert_eq!(e7_to_e5(-122_4194251), -12_241_943);
        assert_eq!(e7_to_e5(1_800_000_000), 18_000_000);
    }

    #[test]
    fn section_count_matches_the_layout() {
        assert_eq!(SECTIONS, 21);
        assert_eq!(DELTA.len(), COLUMNS);
        assert_eq!(COL_NAME.len(), COLUMNS);
        assert_eq!(SEARCHABLE.len(), DICTS);
    }
}
