//! The two optional POI side files: `poi_spatial.bin` and `poi_name_index.bin`.
//!
//! Both exist to remove lookups whose cost grew with the size of the whole dataset.
//! `poi_index.bin` is Morton-sorted, which bounds a bbox query by the *Morton span* of
//! that bbox — and a box straddling a high-order Morton boundary spans a huge key
//! interval even when it is geographically tiny. A plain row/column grid has no such
//! pathology, so `nearest`/`inViewport` become exactly cell-local. Name search had no
//! index at all and scanned the name pool and then every record.
//!
//! ## Layouts
//!
//! Both files are little-endian, self-describing (unlike the transit pack's grid, whose
//! parameters live in a shared header), and both carry the record count so a reader can
//! refuse a side file left over from a different `poi_index.bin` rather than silently
//! joining by a stale ordinal.
//!
//! **`poi_spatial.bin`** — the sparse CSR grid the transit pack already uses
//! (`gtfs_ingest/src/index.rs`, read by `maps/src/main/rust/src/transit.rs`), so there
//! is a working reference on both sides:
//!
//! ```text
//! magic "PSP1" | u32 version | u32 record_count | u32 cell_count
//! i32 lat0_e7  | i32 lon0_e7  | u32 cell_e7      | u32 cols        (32-byte header)
//! u32 cell_ids[cell_count]        ascending, populated cells only
//! u32 cell_off[cell_count + 1]    CSR prefix into ordinals
//! u32 ordinals[record_count]      grouped by cell, ascending within a cell
//! ```
//!
//! **`poi_name_index.bin`** — one entry per (record, word), sorted by the word:
//!
//! ```text
//! magic "PNI1" | u32 version | u32 record_count | u32 entry_count  (16-byte header)
//! u32 ordinals[entry_count]   the record the word belongs to
//! u8  word_idx[entry_count]   which word of that record's name
//! ```
//!
//! **`poi_region.bin`** — one tagged OSM boundary id per POI ordinal:
//!
//! ```text
//! magic "PRG1" | u32 version | u32 record_count  (12-byte header)
//! u64 tagged_ids[record_count]  native OSM label-member link first,
//!                               smallest containing admin fallback, else 0
//! ```
//!
//! The tagged id packs the OSM id space in the low 2 bits (0 none / 1 node /
//! 2 way / 3 relation): `tagged = (osm_id << 2) | space`. Boundary relations
//! use space 3, boundary ways space 2. `0` (`REGION_NONE`) stamps nothing, so
//! the reader masks nothing and the app's name-match fallback still applies.
//!
//! Parallel arrays rather than a packed struct, so an entry costs 5 bytes and not the 8
//! that alignment would round it up to. It is still the largest of the POI files:
//! indexing *every* word, not just the name's first, means the entry count is roughly
//! the record count times the average word count, and it cannot be per-*name* instead —
//! `poi_names.bin` is deduplicated, so a word only identifies a name, and a name still
//! has to be resolved to the records that use it.
//!
//! ## The cross-language contract
//!
//! A sorted index is only searchable if the reader orders words exactly as the writer
//! did, so both sides commit to this and nothing subtler:
//!
//! * A **word** is a maximal run of bytes that are not ASCII whitespace. Splitting on
//!   whitespace only is safe on raw UTF-8 because no byte of a multi-byte sequence is
//!   ever below `0x80`.
//! * The **sort key** is the word's bytes with ASCII `A-Z` mapped to lowercase and every
//!   other byte, including all non-ASCII, left alone. Deliberately *not* Unicode
//!   case folding: `str::to_lowercase` and Kotlin's `String.lowercase` do not agree
//!   byte-for-byte on every input, and a disagreement here is a silently unfindable POI.
//! * At most [`MAX_WORDS`] words per name are indexed, since the word index is a `u8`.
//!
//! The Kotlin reader is `PoiIndex.kt`; [`tests`] pins the ordering from this side.

/// Tagged-OSM-id space bits packed into the low 2 bits of a region link.
pub const REGION_NONE: u64 = 0;

/// Tag an OSM relation id as a region link.
pub fn tag_relation(id: i64) -> u64 {
    ((id as u64) << 2) | 3
}

/// Tag an OSM way id as a region link.
pub fn tag_way(id: i64) -> u64 {
    ((id as u64) << 2) | 2
}

use std::cmp::Ordering;
use std::io::{self, Write};

pub const SPATIAL_MAGIC: &[u8; 4] = b"PSP1";
pub const SPATIAL_VERSION: u32 = 1;
pub const SPATIAL_HEADER_BYTES: usize = 32;

pub const NAME_MAGIC: &[u8; 4] = b"PNI1";
pub const NAME_VERSION: u32 = 1;
pub const NAME_HEADER_BYTES: usize = 16;

pub const REGION_MAGIC: &[u8; 4] = b"PRG1";
pub const REGION_VERSION: u32 = 1;
pub const REGION_HEADER_BYTES: usize = 12;

/// Grid cell size in 1e-7 degrees: 0.02°, the same as the transit pack's.
pub const CELL_E7: u32 = 200_000;

/// Words indexed per name. Bounded because the word index is stored as a `u8`.
pub const MAX_WORDS: usize = 255;

/// ASCII-only lowercase. See the module's cross-language contract.
#[inline]
pub fn ascii_lower(b: u8) -> u8 {
    if b.is_ascii_uppercase() {
        b + 32
    } else {
        b
    }
}

/// Order two words as the index is sorted: ASCII-lowercased byte order.
pub fn cmp_word(a: &[u8], b: &[u8]) -> Ordering {
    let common = a.len().min(b.len());
    for i in 0..common {
        match ascii_lower(a[i]).cmp(&ascii_lower(b[i])) {
            Ordering::Equal => {}
            other => return other,
        }
    }
    a.len().cmp(&b.len())
}

/// The whitespace-separated words of `name`, paired with their index, capped at
/// [`MAX_WORDS`].
pub fn words(name: &[u8]) -> impl Iterator<Item = (u8, &[u8])> {
    name.split(|b| b.is_ascii_whitespace())
        .filter(|w| !w.is_empty())
        .take(MAX_WORDS)
        .enumerate()
        .map(|(i, w)| (i as u8, w))
}

/// The `word_idx`th word of `name`, or `None` when the name has fewer.
///
/// The reader needs the same lookup, so this is the definition both sides share rather
/// than each re-deriving "which run of bytes is word 3".
pub fn word_at(name: &[u8], word_idx: u8) -> Option<&[u8]> {
    words(name).find(|(i, _)| *i == word_idx).map(|(_, w)| w)
}

/// Where the grid is anchored and how wide it is.
///
/// Anchored at the minimum corner of the records' own extent, like the transit pack's,
/// so a regional extract does not pay for cells covering the rest of the planet.
pub struct Grid {
    pub lat0_e7: i32,
    pub lon0_e7: i32,
    pub cols: u32,
}

impl Grid {
    /// Fit a grid to `coords`. `None` when there are no records to bound.
    pub fn fit(coords: &[(i32, i32)]) -> Option<Grid> {
        let (mut min_lat, mut min_lon) = (i32::MAX, i32::MAX);
        let (mut max_lat, mut max_lon) = (i32::MIN, i32::MIN);
        for &(lat, lon) in coords {
            min_lat = min_lat.min(lat);
            min_lon = min_lon.min(lon);
            max_lat = max_lat.max(lat);
            max_lon = max_lon.max(lon);
        }
        if min_lat > max_lat {
            return None;
        }
        let cols = cell_axis(max_lon, min_lon) + 1;
        Some(Grid {
            lat0_e7: min_lat,
            lon0_e7: min_lon,
            cols,
        })
    }

    pub fn row(&self, lat_e7: i32) -> u32 {
        cell_axis(lat_e7, self.lat0_e7)
    }

    pub fn col(&self, lon_e7: i32) -> u32 {
        cell_axis(lon_e7, self.lon0_e7).min(self.cols.saturating_sub(1))
    }

    pub fn cell(&self, lat_e7: i32, lon_e7: i32) -> u32 {
        self.row(lat_e7)
            .saturating_mul(self.cols)
            .saturating_add(self.col(lon_e7))
    }
}

/// Cell offset of `value` from the grid origin, floored at 0.
fn cell_axis(value: i32, origin: i32) -> u32 {
    let d = value as i64 - origin as i64;
    if d <= 0 {
        0
    } else {
        (d / CELL_E7 as i64) as u32
    }
}

/// Write `poi_spatial.bin` for records whose coordinates are `coords[ordinal]`.
///
/// Returns the number of populated cells. Writes a valid, empty grid for no records
/// rather than refusing, so the file's presence never depends on the extract.
pub fn write_spatial<W: Write>(out: &mut W, coords: &[(i32, i32)]) -> io::Result<usize> {
    let grid = Grid::fit(coords);
    let (lat0, lon0, cols) = match &grid {
        Some(g) => (g.lat0_e7, g.lon0_e7, g.cols),
        None => (0, 0, 0),
    };

    // (cell, ordinal), sorted so equal cells are adjacent and ordinals ascend within
    // one — the order the CSR encodes, and the order a reader walks.
    let mut pairs: Vec<(u32, u32)> = Vec::with_capacity(coords.len());
    if let Some(g) = &grid {
        for (ordinal, &(lat, lon)) in coords.iter().enumerate() {
            pairs.push((g.cell(lat, lon), ordinal as u32));
        }
    }
    pairs.sort_unstable();

    let mut cell_ids: Vec<u32> = Vec::new();
    let mut cell_off: Vec<u32> = Vec::new();
    for (i, &(cell, _)) in pairs.iter().enumerate() {
        if cell_ids.last() != Some(&cell) {
            cell_ids.push(cell);
            cell_off.push(i as u32);
        }
    }
    cell_off.push(pairs.len() as u32);

    out.write_all(SPATIAL_MAGIC)?;
    out.write_all(&SPATIAL_VERSION.to_le_bytes())?;
    out.write_all(&(coords.len() as u32).to_le_bytes())?;
    out.write_all(&(cell_ids.len() as u32).to_le_bytes())?;
    out.write_all(&lat0.to_le_bytes())?;
    out.write_all(&lon0.to_le_bytes())?;
    out.write_all(&CELL_E7.to_le_bytes())?;
    out.write_all(&cols.to_le_bytes())?;
    for id in &cell_ids {
        out.write_all(&id.to_le_bytes())?;
    }
    for off in &cell_off {
        out.write_all(&off.to_le_bytes())?;
    }
    for &(_, ordinal) in &pairs {
        out.write_all(&ordinal.to_le_bytes())?;
    }
    Ok(cell_ids.len())
}

/// Write `poi_name_index.bin` for records whose names are `names[ordinal]`.
///
/// Returns the number of entries. The sort resolves each entry's word out of `names`
/// rather than materialising it, which keeps the working set to 8 bytes per entry
/// instead of that plus a copy of every word.
pub fn write_name_index<W: Write>(out: &mut W, names: &[&[u8]]) -> io::Result<usize> {
    let mut entries: Vec<(u32, u8)> = Vec::new();
    for (ordinal, name) in names.iter().enumerate() {
        for (word_idx, _) in words(name) {
            entries.push((ordinal as u32, word_idx));
        }
    }
    // Ordinal breaks ties so the file is reproducible: two runs over the same input
    // must be byte-identical, which an unstable sort only gives on a total order.
    entries.sort_unstable_by(|a, b| {
        let wa = word_at(names[a.0 as usize], a.1).unwrap_or_default();
        let wb = word_at(names[b.0 as usize], b.1).unwrap_or_default();
        cmp_word(wa, wb).then(a.cmp(b))
    });

    out.write_all(NAME_MAGIC)?;
    out.write_all(&NAME_VERSION.to_le_bytes())?;
    out.write_all(&(names.len() as u32).to_le_bytes())?;
    out.write_all(&(entries.len() as u32).to_le_bytes())?;
    for &(ordinal, _) in &entries {
        out.write_all(&ordinal.to_le_bytes())?;
    }
    for &(_, word_idx) in &entries {
        out.write_all(&[word_idx])?;
    }
    Ok(entries.len())
}

/// Write `poi_region.bin`: one tagged OSM boundary id per record ordinal, in
/// the same Morton-sorted order as `poi_index.bin`. Same count-refused
/// convention as the other side files: the reader joins by ordinal only when
/// the counts agree.
pub fn write_region<W: Write>(out: &mut W, region_ids: &[u64]) -> io::Result<()> {
    out.write_all(REGION_MAGIC)?;
    out.write_all(&REGION_VERSION.to_le_bytes())?;
    out.write_all(&(region_ids.len() as u32).to_le_bytes())?;
    for id in region_ids {
        out.write_all(&id.to_le_bytes())?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u32_at(bytes: &[u8], at: usize) -> u32 {
        u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap())
    }

    #[test]
    fn words_split_on_whitespace_only() {
        let got: Vec<&[u8]> = words(b"Joe's  Pizza\tBar").map(|(_, w)| w).collect();
        assert_eq!(got, vec![&b"Joe's"[..], &b"Pizza"[..], &b"Bar"[..]]);
    }

    #[test]
    fn word_indices_count_from_zero() {
        let name = b"San Francisco Public Library";
        assert_eq!(word_at(name, 0), Some(&b"San"[..]));
        assert_eq!(word_at(name, 3), Some(&b"Library"[..]));
        assert_eq!(word_at(name, 4), None);
    }

    /// Only ASCII folds. A Unicode-aware lowercase would disagree with the reader.
    #[test]
    fn comparison_folds_ascii_and_leaves_other_bytes_alone() {
        assert_eq!(cmp_word(b"PIZZA", b"pizza"), Ordering::Equal);
        assert_eq!(cmp_word(b"Cafe", b"cafz"), Ordering::Less);
        // U+00C9 vs U+00E9: both pass through, so they order by raw byte.
        assert_eq!(cmp_word("É".as_bytes(), "é".as_bytes()), Ordering::Less);
    }

    #[test]
    fn a_shorter_word_sorts_before_a_longer_one_sharing_its_prefix() {
        assert_eq!(cmp_word(b"pizz", b"pizza"), Ordering::Less);
    }

    #[test]
    fn the_grid_is_anchored_at_the_records_minimum_corner() {
        let coords = [(37_700_000, -122_400_000), (37_720_000, -122_380_000)];
        let g = Grid::fit(&coords).expect("records exist");
        assert_eq!(g.lat0_e7, 37_700_000);
        assert_eq!(g.lon0_e7, -122_400_000);
        assert_eq!(g.cell(37_700_000, -122_400_000), 0);
    }

    #[test]
    fn an_empty_extract_has_no_grid() {
        assert!(Grid::fit(&[]).is_none());
    }

    #[test]
    fn the_spatial_csr_groups_every_ordinal_under_its_cell() {
        // Two POIs in one cell (0.02deg apart is one cell over, so keep them closer)
        // and a third far away.
        let coords = [
            (37_700_000, -122_400_000),
            (37_700_100, -122_400_100),
            (38_000_000, -122_000_000),
        ];
        let mut buf = Vec::new();
        let cells = write_spatial(&mut buf, &coords).unwrap();
        assert_eq!(cells, 2, "two populated cells");
        assert_eq!(&buf[0..4], SPATIAL_MAGIC);
        assert_eq!(u32_at(&buf, 8), 3, "record count");
        assert_eq!(u32_at(&buf, 12), 2, "cell count");

        let ids = SPATIAL_HEADER_BYTES;
        let off = ids + 4 * cells;
        let ords = off + 4 * (cells + 1);
        assert!(u32_at(&buf, ids) < u32_at(&buf, ids + 4), "cell ids ascend");
        assert_eq!(u32_at(&buf, off), 0);
        assert_eq!(u32_at(&buf, off + 4), 2, "first cell holds two records");
        assert_eq!(u32_at(&buf, off + 8), 3);
        assert_eq!(u32_at(&buf, ords), 0);
        assert_eq!(u32_at(&buf, ords + 4), 1);
        assert_eq!(u32_at(&buf, ords + 8), 2);
        assert_eq!(buf.len(), ords + 4 * coords.len());
    }

    #[test]
    fn the_name_index_sorts_every_word_of_every_record() {
        let names: Vec<&[u8]> = vec![b"Joe's Pizza", b"Pizza Hut"];
        let mut buf = Vec::new();
        let entries = write_name_index(&mut buf, &names).unwrap();
        assert_eq!(entries, 4, "two words each");
        assert_eq!(&buf[0..4], NAME_MAGIC);
        assert_eq!(u32_at(&buf, 8), 2, "record count");
        assert_eq!(u32_at(&buf, 12), 4, "entry count");

        // Sorted: "hut", "joe's", "pizza"(#0 word 1), "pizza"(#1 word 0).
        let ord = NAME_HEADER_BYTES;
        let widx = ord + 4 * entries;
        let got: Vec<(u32, u8)> =
            (0..entries).map(|i| (u32_at(&buf, ord + 4 * i), buf[widx + i])).collect();
        assert_eq!(got, vec![(1, 1), (0, 0), (0, 1), (1, 0)]);
    }

    /// Both words of a name resolve to the same record, which is what makes "pizza"
    /// find "Joe's Pizza" without a scan.
    #[test]
    fn a_trailing_word_still_points_at_its_record() {
        let names: Vec<&[u8]> = vec![b"Joe's Pizza"];
        let mut buf = Vec::new();
        let entries = write_name_index(&mut buf, &names).unwrap();
        assert_eq!(entries, 2);
        let widx = NAME_HEADER_BYTES + 4 * entries;
        assert_eq!(u32_at(&buf, NAME_HEADER_BYTES), 0);
        assert_eq!(buf[widx], 0, "\"joe's\" first");
        assert_eq!(buf[widx + 1], 1, "then \"pizza\"");
    }

    #[test]
    fn two_runs_over_the_same_input_are_byte_identical() {
        let names: Vec<&[u8]> = vec![b"Cafe Roma", b"Roma Cafe", b"Cafe Roma"];
        let coords = [(1_000, 2_000), (3_000, 4_000), (5_000, 6_000)];
        let (mut a, mut b) = (Vec::new(), Vec::new());
        write_name_index(&mut a, &names).unwrap();
        write_name_index(&mut b, &names).unwrap();
        assert_eq!(a, b);
        let (mut c, mut d) = (Vec::new(), Vec::new());
        write_spatial(&mut c, &coords).unwrap();
        write_spatial(&mut d, &coords).unwrap();
        assert_eq!(c, d);
    }

    #[test]
    fn an_unnamed_record_contributes_no_entries() {
        let names: Vec<&[u8]> = vec![b"", b"   ", b"Real Name"];
        let mut buf = Vec::new();
        assert_eq!(write_name_index(&mut buf, &names).unwrap(), 2);
        assert_eq!(u32_at(&buf, 8), 3, "record count still counts them");
    }
}
