//! `poi_admin.bin`: the administrative hierarchy behind `poi_region.bin`.
//!
//! `poi_region.bin` gives every POI ONE tagged boundary id — its native label
//! link or the smallest administrative boundary containing it — but nothing
//! says what that boundary is called, what level it is, or what contains it, so
//! the app could name at most the city and never the state or country. This
//! sidecar is the missing table: for every boundary a POI links to, and every
//! boundary above those, its name, its `admin_level` and its parent.
//!
//! ## Layout (little-endian)
//!
//! ```text
//! magic "PAD1" | u32 version | u32 entry_count | u32 names_len   (16-byte header)
//! entry[entry_count], 32 bytes each, ascending by tagged_id:
//!     u64 tagged_id       same tagging as poi_region.bin ((osm_id << 2) | space)
//!     u64 parent_tagged   the containing boundary one level up, 0 at the top
//!     u32 name_off        byte offset of `name` in the pool
//!     u32 name_en_off     byte offset of `name:en`, or u32::MAX when absent
//!                         or identical to `name`
//!     u8  admin_level     OSM admin_level (2 country, 4 state/province, ...)
//!     u8[7] reserved      zero
//! names: names_len bytes of NUL-terminated UTF-8, deduplicated
//! ```
//!
//! A reader binary-searches `tagged_id` (fixed-width entries) and follows
//! `parent_tagged` to the top. The parent is the smallest-area boundary of a
//! LOWER `admin_level` that contains a point strictly inside the child (see
//! `poi_build::admin_table`), so a city's parent is its county where one exists
//! and its state otherwise; a reader wanting "the state" walks up to the first
//! level 3-4 entry.
//!
//! Additive and versioned: an archive packs it as its own section kind
//! (`ARCHIVE_KIND_POI_ADMIN`, 16), which older apps skip like any unknown kind,
//! and a reader refuses a version it does not know.

use std::collections::HashMap;
use std::io::{self, Write};

pub const ADMIN_MAGIC: &[u8; 4] = b"PAD1";
pub const ADMIN_VERSION: u32 = 1;
pub const ADMIN_HEADER_BYTES: usize = 16;
pub const ADMIN_ENTRY_BYTES: usize = 32;
/// `name_en_off` for an entry without a distinct English name.
pub const ADMIN_NO_NAME: u32 = u32::MAX;

/// One boundary of the hierarchy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdminEntry {
    /// Tagged OSM id, as in `poi_region.bin`.
    pub tagged: u64,
    /// Tagged id of the containing boundary one level up, or 0.
    pub parent: u64,
    pub admin_level: u8,
    /// The `name` tag (may be empty when OSM has none).
    pub name: Vec<u8>,
    /// The `name:en` tag, empty when absent.
    pub name_en: Vec<u8>,
}

/// Write `poi_admin.bin` for `entries` (any order; written sorted by tagged id,
/// one entry per id). Returns the entry count.
pub fn write_admin<W: Write>(out: &mut W, entries: &[AdminEntry]) -> io::Result<usize> {
    let mut sorted: Vec<&AdminEntry> = entries.iter().collect();
    sorted.sort_by_key(|e| e.tagged);
    sorted.dedup_by_key(|e| e.tagged);

    let mut pool: Vec<u8> = Vec::new();
    let mut offsets: HashMap<&[u8], u32> = HashMap::new();

    let mut body: Vec<u8> = Vec::with_capacity(sorted.len() * ADMIN_ENTRY_BYTES);
    for e in &sorted {
        let name_off = *offsets
            .entry(e.name.as_slice())
            .or_insert_with(|| intern_owned(&e.name, &mut pool));
        let name_en_off = if e.name_en.is_empty() || e.name_en == e.name {
            ADMIN_NO_NAME
        } else {
            *offsets
                .entry(e.name_en.as_slice())
                .or_insert_with(|| intern_owned(&e.name_en, &mut pool))
        };
        body.extend_from_slice(&e.tagged.to_le_bytes());
        body.extend_from_slice(&e.parent.to_le_bytes());
        body.extend_from_slice(&name_off.to_le_bytes());
        body.extend_from_slice(&name_en_off.to_le_bytes());
        body.push(e.admin_level);
        body.extend_from_slice(&[0u8; 7]);
    }

    out.write_all(ADMIN_MAGIC)?;
    out.write_all(&ADMIN_VERSION.to_le_bytes())?;
    out.write_all(&(sorted.len() as u32).to_le_bytes())?;
    out.write_all(&(pool.len() as u32).to_le_bytes())?;
    out.write_all(&body)?;
    out.write_all(&pool)?;
    Ok(sorted.len())
}

fn intern_owned(s: &[u8], pool: &mut Vec<u8>) -> u32 {
    let off = pool.len() as u32;
    // A NUL inside a name would cut it short for the reader; OSM never has one,
    // but the pool's framing must not depend on that.
    pool.extend(s.iter().copied().filter(|b| *b != 0));
    pool.push(0);
    off
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u32_at(b: &[u8], at: usize) -> u32 {
        u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
    }

    fn u64_at(b: &[u8], at: usize) -> u64 {
        u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
    }

    fn cstr(pool: &[u8], off: u32) -> &[u8] {
        let s = &pool[off as usize..];
        &s[..s.iter().position(|b| *b == 0).unwrap()]
    }

    #[test]
    fn layout_is_sorted_fixed_width_with_a_shared_pool() {
        let e = |tagged, parent, level, name: &str, en: &str| AdminEntry {
            tagged,
            parent,
            admin_level: level,
            name: name.as_bytes().to_vec(),
            name_en: en.as_bytes().to_vec(),
        };
        let entries = vec![
            e(903, 902, 8, "Köln", "Cologne"),
            e(901, 0, 2, "Deutschland", "Germany"),
            e(902, 901, 4, "Nordrhein-Westfalen", "North Rhine-Westphalia"),
            // Same name and name:en: stored once, no English offset.
            e(907, 902, 8, "Bonn", "Bonn"),
            // A duplicate id is written once.
            e(901, 0, 2, "Deutschland", "Germany"),
        ];
        let mut out = Vec::new();
        assert_eq!(write_admin(&mut out, &entries).unwrap(), 4);
        assert_eq!(&out[0..4], ADMIN_MAGIC);
        assert_eq!(u32_at(&out, 4), ADMIN_VERSION);
        assert_eq!(u32_at(&out, 8), 4);
        let names_len = u32_at(&out, 12) as usize;
        let pool_at = ADMIN_HEADER_BYTES + 4 * ADMIN_ENTRY_BYTES;
        assert_eq!(out.len(), pool_at + names_len);
        let pool = &out[pool_at..];

        let at = |i: usize| ADMIN_HEADER_BYTES + i * ADMIN_ENTRY_BYTES;
        let ids: Vec<u64> = (0..4).map(|i| u64_at(&out, at(i))).collect();
        assert_eq!(ids, vec![901, 902, 903, 907], "ascending tagged ids");
        // Köln: parent, level, both names.
        assert_eq!(u64_at(&out, at(2) + 8), 902);
        assert_eq!(out[at(2) + 24], 8);
        assert_eq!(cstr(pool, u32_at(&out, at(2) + 16)), "Köln".as_bytes());
        assert_eq!(cstr(pool, u32_at(&out, at(2) + 20)), b"Cologne");
        assert!(out[at(2) + 25..at(2) + 32].iter().all(|b| *b == 0), "reserved bytes are zero");
        // Bonn: identical English name is not stored twice.
        assert_eq!(u32_at(&out, at(3) + 20), ADMIN_NO_NAME);
        // The country is the top: parent 0.
        assert_eq!(u64_at(&out, at(0) + 8), 0);
    }

    #[test]
    fn an_empty_table_is_just_a_header() {
        let mut out = Vec::new();
        assert_eq!(write_admin(&mut out, &[]).unwrap(), 0);
        assert_eq!(out.len(), ADMIN_HEADER_BYTES);
    }
}
