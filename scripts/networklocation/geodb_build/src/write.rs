//! Assembling extracted rows into a `geocoder.db`.
//!
//! Three orderings decide whether the finished database works, and none of them fails loudly:
//!
//! 1. **Records are ordered by grid cell, then Z-order within the cell.** Reverse lookup walks
//!    outward from the query's cell, so this ordering *is* the spatial index.
//! 2. **Searchable dictionaries are UTF-16 sorted**, because the reader binary-searches them
//!    with a UTF-16 comparator. See `codec::cmp_utf16`.
//! 3. **The forward and name indexes point into grid order**, each sorted by its own key. Get
//!    one out of step with its dictionary and forward search returns the wrong rows.
//!
//! [`verify`] reads the finished file back through a path that shares no code with the writer,
//! which is the only real check that all three held.

use std::collections::HashSet;
use std::fs::File;
use std::io::{self, BufWriter, Read, Write};
use std::path::Path;

use rayon::prelude::*;

use crate::codec::{
    cmp_utf16, column_blocks, encode_column_with, encode_dict, encode_grid, write_section,
};
use crate::extract::{Row, Strings};
use crate::format::*;
use crate::progress::Progress;

/// Sizes of each part of the finished file, for the build report.
#[derive(Debug, Default, Clone)]
pub struct Report {
    /// Records written.
    pub n: usize,
    /// Total file size.
    pub total: u64,
    /// `(name, bytes)` per dictionary.
    pub dicts: Vec<(&'static str, u64)>,
    /// `(name, bytes)` per column.
    pub columns: Vec<(&'static str, u64)>,
    /// Grid directory size.
    pub grid: u64,
    /// Forward index size.
    pub fwd: u64,
    /// Name index size, both sections.
    pub name_index: u64,
    /// Distinct strings per dictionary.
    pub dict_counts: Vec<(&'static str, usize)>,
}

impl Report {
    /// Bits per record, the number worth watching between builds.
    pub fn bits_per_record(&self) -> f64 {
        if self.n == 0 {
            0.0
        } else {
            (self.total as f64 * 8.0) / self.n as f64
        }
    }
}

const DICT_NAME: [&str; DICTS] =
    ["name", "house", "street", "city", "state", "country", "postcode"];

/// Sort `rows` into grid order, drop exact duplicates, and write the database to `out_path`.
///
/// `strings` holds the dictionaries the rows' ids refer to, filled during extraction. Consumes
/// both in place: at planet scale this is tens of gigabytes and copying would double the peak.
/// On return `rows` is exactly what the file holds, in file order.
pub fn write(out_path: &Path, rows: &mut Vec<Row>, strings: &mut Strings) -> io::Result<Report> {
    // Record order is decided once, after the dictionaries, by `sort_rows_deterministically`:
    // only then are ids content-derived, and nothing before it depends on the order.

    // --- dictionaries ---------------------------------------------------
    let p = Progress::coarse("dictionaries", DICTS as u64);
    let mut dict_bodies: Vec<Vec<u8>> = Vec::with_capacity(DICTS);
    let mut dict_counts = Vec::with_capacity(DICTS);
    let mut remaps: Vec<Vec<u32>> = Vec::with_capacity(DICTS);
    let mut empty_ids: Vec<Option<u32>> = Vec::with_capacity(DICTS);
    for d in 0..DICTS {
        let (sorted, remap) = strings.dicts[d].finish(SEARCHABLE[d]);
        dict_counts.push((DICT_NAME[d], sorted.len()));
        empty_ids.push(sorted.iter().position(|s| s.is_empty()).map(|i| i as u32));
        dict_bodies.push(encode_dict(&sorted)?);
        remaps.push(remap);
        p.inc();
    }
    p.finish();
    // Interned ids are first-seen order; the file stores final order.
    let p = Progress::new("remap ids", rows.len() as u64);
    rows.par_chunks_mut(1 << 16).for_each(|chunk| {
        for row in chunk.iter_mut() {
            for d in 0..DICTS {
                row.ids[d] = remaps[d][row.ids[d] as usize];
            }
        }
        p.add(chunk.len() as u64);
    });
    p.finish();
    drop(remaps);
    strings.dicts = Vec::new();

    let p = Progress::stage("sort records + dedup");
    sort_rows_deterministically(rows);
    // Adjacent ways of one street share their end node, and at e5 a building's node and its
    // outline often land on the same point; both arrive as identical rows, now adjacent.
    rows.dedup();
    p.finish();
    let n = rows.len();

    // --- columns --------------------------------------------------------
    let p = Progress::new("columns", n as u64);
    let mut cols: Vec<Vec<i32>> = (0..COLUMNS).map(|_| Vec::with_capacity(n)).collect();
    for r in rows.iter() {
        cols[C_LAT].push(r.lat);
        cols[C_LON].push(r.lon);
        cols[C_NAME].push(r.ids[D_NAME] as i32);
        cols[C_HOUSE].push(r.ids[D_HOUSE] as i32);
        cols[C_STREET].push(r.ids[D_STREET] as i32);
        cols[C_CITY].push(r.ids[D_CITY] as i32);
        cols[C_STATE].push(r.ids[D_STATE] as i32);
        cols[C_COUNTRY].push(r.ids[D_COUNTRY] as i32);
        cols[C_POSTCODE].push(r.ids[D_POSTCODE] as i32);
        cols[C_KIND].push(r.kind as i32);
        p.inc();
    }
    p.finish();

    // --- grid directory --------------------------------------------------
    let mut grid: Vec<(i64, u32)> = Vec::new();
    for (i, r) in rows.iter().enumerate() {
        let cell = cell_id(r.lat, r.lon);
        if grid.last().map(|&(c, _)| c) != Some(cell) {
            grid.push((cell, i as u32));
        }
    }

    // --- forward index: records with a street, by (country, state, city, street, house) ---
    // A forward query always names a street, so a record without one can never match. Keying
    // on the street rather than on kind keeps a POI or place that carries a full address but
    // no house number findable, which an address-only index silently lost.
    let p = Progress::stage("forward index");
    let empty_street = empty_ids[D_STREET];
    let mut fwd: Vec<u32> =
        (0..n as u32).filter(|&i| Some(rows[i as usize].ids[D_STREET]) != empty_street).collect();
    fwd.par_sort_unstable_by(|&a, &b| {
        let (x, y) = (&rows[a as usize].ids, &rows[b as usize].ids);
        x[D_COUNTRY]
            .cmp(&y[D_COUNTRY])
            .then(x[D_STATE].cmp(&y[D_STATE]))
            .then(x[D_CITY].cmp(&y[D_CITY]))
            .then(x[D_STREET].cmp(&y[D_STREET]))
            .then(x[D_HOUSE].cmp(&y[D_HOUSE]))
            .then(a.cmp(&b))
    });
    p.finish();

    // --- name index: named records, by name id ---------------------------
    let p = Progress::stage("name index");
    let empty_name = empty_ids[D_NAME];
    let mut named = name_index_records(rows, empty_name);
    named.par_sort_unstable_by(|&a, &b| {
        rows[a as usize].ids[D_NAME].cmp(&rows[b as usize].ids[D_NAME]).then(a.cmp(&b))
    });
    let nm_name: Vec<i32> = named.iter().map(|&i| rows[i as usize].ids[D_NAME] as i32).collect();
    let nm_rec: Vec<i32> = named.iter().map(|&i| i as i32).collect();
    drop(named);
    p.finish();

    // --- write ------------------------------------------------------------
    // Compression is the slow step, so it gets a meter over every block.
    let p = Progress::coarse(
        "compress + write",
        COLUMNS as u64 * column_blocks(n) + column_blocks(fwd.len()) + 2 * column_blocks(nm_name.len()),
    );
    let mut out = BufWriter::with_capacity(1 << 22, File::create(out_path)?);
    out.write_all(&MAGIC.to_be_bytes())?;
    out.write_all(&VERSION.to_be_bytes())?;
    out.write_all(&(n as u32).to_be_bytes())?;

    let mut report = Report { n, ..Report::default() };
    for d in 0..DICTS {
        report.dicts.push((DICT_NAME[d], write_section(&mut out, &dict_bodies[d])?));
    }
    drop(dict_bodies);
    for c in 0..COLUMNS {
        let body = encode_column_with(&cols[c], DELTA[c], Some(&p))?;
        report.columns.push((COL_NAME[c], write_section(&mut out, &body)?));
        cols[c] = Vec::new();
    }
    report.grid = write_section(&mut out, &encode_grid(&grid))?;

    let fwd_col: Vec<i32> = fwd.iter().map(|&v| v as i32).collect();
    report.fwd = write_section(&mut out, &encode_column_with(&fwd_col, true, Some(&p))?)?;
    report.name_index = write_section(&mut out, &encode_column_with(&nm_name, true, Some(&p))?)?
        + write_section(&mut out, &encode_column_with(&nm_rec, false, Some(&p))?)?;

    out.flush()?;
    p.finish();
    report.dict_counts = dict_counts;
    report.total = std::fs::metadata(out_path)?.len();
    Ok(report)
}

/// Records that belong in the name index, in grid order.
///
/// Unnamed records can never match a name query. A street is sampled every few hundred metres,
/// so indexing every sample would repeat one name dozens of times; one entry per street name per
/// grid cell still finds the street and still puts the hit near the right place.
fn name_index_records(rows: &[Row], empty_name: Option<u32>) -> Vec<u32> {
    let mut out = Vec::new();
    let mut cell = i64::MIN;
    let mut streets_in_cell: HashSet<u32> = HashSet::new();
    for (i, r) in rows.iter().enumerate() {
        if Some(r.ids[D_NAME]) == empty_name {
            continue;
        }
        if r.kind == K_STREET {
            // Rows are in cell order, so a per-cell set stays small at planet scale.
            let c = cell_id(r.lat, r.lon);
            if c != cell {
                cell = c;
                streets_in_cell.clear();
            }
            if !streets_in_cell.insert(r.ids[D_NAME]) {
                continue;
            }
        }
        out.push(i as u32);
    }
    out
}

/// Order records by grid cell, then Z-order within the cell, then every remaining field.
///
/// Cell first so the grid directory is contiguous, Z-order so both coordinate columns delta
/// well. The key is total over the row's content, and the ids are already the final,
/// content-derived ones, so two runs over the same input produce identical bytes whatever
/// order the PBF was read in.
fn sort_rows_deterministically(rows: &mut [Row]) {
    rows.par_sort_unstable_by_key(|r| {
        (cell_id(r.lat, r.lon), morton_in_cell(r.lat, r.lon), r.kind, r.lat, r.lon, r.ids)
    });
}

// --------------------------------------------------------------------------- verify
/// Read a finished database back and check it against the rows it was built from.
///
/// Independent of the writer: it walks the file sequentially, decodes every column, and
/// reconstructs each record's strings through the dictionaries. That catches the failures the
/// writer cannot see in itself — a dictionary sorted one way and indexed another, a column
/// written in a different order from the one the grid describes, an off-by-one in a block
/// directory.
pub fn verify(path: &Path, expected: &[Row]) -> io::Result<()> {
    let p = Progress::stage("verify: read back");
    let mut buf = Vec::new();
    let _ = File::open(path)?.read_to_end(&mut buf)?;
    p.finish();
    let bad = |m: String| io::Error::new(io::ErrorKind::InvalidData, m);

    let rd32 = |p: usize| -> u32 {
        u32::from_be_bytes([buf[p], buf[p + 1], buf[p + 2], buf[p + 3]])
    };
    if rd32(0) != MAGIC {
        return Err(bad("bad magic".into()));
    }
    if rd32(4) != VERSION {
        return Err(bad(format!("version {}, expected {VERSION}", rd32(4))));
    }
    let n = rd32(8) as usize;
    if n != expected.len() {
        return Err(bad(format!("{n} records, expected {}", expected.len())));
    }

    let mut cursor = 12usize;
    let mut sections: Vec<&[u8]> = Vec::with_capacity(SECTIONS);
    for s in 0..SECTIONS {
        if cursor + 4 > buf.len() {
            return Err(bad(format!("file ends before section {s}")));
        }
        let size = rd32(cursor) as usize;
        cursor += 4;
        let end = cursor.checked_add(size).ok_or_else(|| bad("section size overflow".into()))?;
        if end > buf.len() {
            return Err(bad(format!("section {s} runs past the end of the file")));
        }
        sections.push(&buf[cursor..end]);
        cursor = end;
    }
    if cursor != buf.len() {
        return Err(bad(format!("{} trailing byte(s)", buf.len() - cursor)));
    }

    let p = Progress::stage("verify: dictionaries");
    let dicts: Vec<Vec<String>> =
        sections[..DICTS].iter().map(|s| decode_dict(s)).collect::<io::Result<_>>()?;
    for (d, dict) in dicts.iter().enumerate() {
        if SEARCHABLE[d] {
            for w in dict.windows(2) {
                if cmp_utf16(&w[0], &w[1]) != std::cmp::Ordering::Less {
                    return Err(bad(format!(
                        "dictionary {} is not in UTF-16 order: {:?} then {:?}",
                        DICT_NAME[d], w[0], w[1]
                    )));
                }
            }
        }
    }
    p.finish();

    // One column at a time: each is decoded, compared field by field with `expected`, and
    // dropped. Holding all ten at once doubled the planet's peak memory (10 x 4 bytes x n on
    // top of the rows themselves) and pushed the read-back into paging. Once every column has
    // matched, `expected` *is* the file's content, so the index checks below read from it.
    let col_sections = &sections[DICTS..DICTS + COLUMNS];
    let blocks: u64 = col_sections
        .iter()
        .map(|s| s.get(4..8).map_or(0, |b| u32::from_be_bytes(b.try_into().unwrap()) as u64))
        .sum();
    let p = Progress::coarse("verify: columns", blocks);
    for c in 0..COLUMNS {
        let col = decode_column_with(col_sections[c], DELTA[c], Some(&p))?;
        if col.len() != n {
            return Err(bad(format!("column {} has {} values, expected {n}", COL_NAME[c], col.len())));
        }
        let dict = (C_NAME..=C_POSTCODE).contains(&c).then(|| &dicts[c - C_NAME]);
        let mismatch = col.par_iter().zip(expected.par_iter()).position_first(|(&v, e)| {
            let want = match c {
                C_LAT => e.lat,
                C_LON => e.lon,
                C_KIND => e.kind as i32,
                _ => e.ids[c - C_NAME] as i32,
            };
            v != want || dict.is_some_and(|d| v < 0 || v as usize >= d.len())
        });
        if let Some(i) = mismatch {
            return Err(bad(format!(
                "record {i}: {} is {} on disk; expected {:?}",
                COL_NAME[c], col[i], expected[i]
            )));
        }
    }
    p.finish();

    // The grid must describe the record order that was actually written.
    let p = Progress::stage("verify: indexes");
    let grid = decode_grid(sections[DICTS + COLUMNS])?;
    let mut prev_cell = i64::MIN;
    for &(cell, start) in &grid {
        if cell <= prev_cell {
            return Err(bad(format!("grid cells not ascending at {cell}")));
        }
        prev_cell = cell;
        let s = start as usize;
        if s >= n || cell_id(expected[s].lat, expected[s].lon) != cell {
            return Err(bad(format!("grid entry for cell {cell} points at the wrong record")));
        }
    }
    for i in 1..n {
        let (a, b) = (&expected[i - 1], &expected[i]);
        if cell_id(b.lat, b.lon) < cell_id(a.lat, a.lon) {
            return Err(bad(format!("records are not in cell order at {i}")));
        }
    }

    // The forward index must hold every record with a street exactly once and nothing else,
    // sorted by its key; the name index must hold valid named records, sorted by name id.
    let empty_street = dicts[D_STREET].iter().position(|s| s.is_empty()).map(|i| i as u32);
    let has_street = |r: &Row| Some(r.ids[D_STREET]) != empty_street;
    let fwd = decode_column(sections[DICTS + COLUMNS + 1], true)?;
    let with_street = expected.iter().filter(|r| has_street(r)).count();
    if fwd.len() != with_street {
        return Err(bad(format!(
            "forward index has {} entries, expected {with_street} records with a street",
            fwd.len()
        )));
    }
    let mut seen = vec![false; n];
    let mut prev_key: Option<[u32; 5]> = None;
    for &r in &fwd {
        let i = r as usize;
        if i >= n || seen[i] {
            return Err(bad("forward index repeats or overruns a record".into()));
        }
        let e = &expected[i];
        if !has_street(e) {
            return Err(bad(format!("forward index points at record {i}, which has no street")));
        }
        seen[i] = true;
        let key = [e.ids[D_COUNTRY], e.ids[D_STATE], e.ids[D_CITY], e.ids[D_STREET], e.ids[D_HOUSE]];
        if prev_key.is_some_and(|p| key < p) {
            return Err(bad("forward index is not sorted by its key".into()));
        }
        prev_key = Some(key);
    }
    drop(seen);

    let nm_name = decode_column(sections[DICTS + COLUMNS + 2], true)?;
    let nm_rec = decode_column(sections[DICTS + COLUMNS + 3], false)?;
    if nm_name.len() != nm_rec.len() {
        return Err(bad("name index halves disagree on length".into()));
    }
    let mut prev = i32::MIN;
    for (k, (&name_id, &rec)) in nm_name.iter().zip(nm_rec.iter()).enumerate() {
        if name_id < prev {
            return Err(bad(format!("name index not ascending at {k}")));
        }
        prev = name_id;
        let i = rec as usize;
        if i >= n {
            return Err(bad(format!("name index entry {k} points past the end")));
        }
        if expected[i].ids[D_NAME] as i32 != name_id {
            return Err(bad(format!("name index entry {k} disagrees with the name column")));
        }
        if dicts[D_NAME][name_id as usize].is_empty() {
            return Err(bad(format!("name index entry {k} points at an unnamed record")));
        }
    }
    p.finish();
    Ok(())
}

/// Decode a whole blocked dictionary (see `codec::encode_dict`), checking that every block
/// holds the expected number of strings and that the uncompressed first-string table agrees
/// with what each block actually starts with, since the reader trusts that table to search.
pub(crate) fn decode_dict(body: &[u8]) -> io::Result<Vec<String>> {
    let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
    let rd32 = |p: usize| -> io::Result<usize> {
        let b = body.get(p..p + 4).ok_or_else(|| bad("dictionary section truncated"))?;
        Ok(u32::from_be_bytes(b.try_into().map_err(|_| bad("short dict"))?) as usize)
    };
    let (count, per, blocks) = (rd32(0)?, rd32(4)?, rd32(8)?);
    if per == 0 || blocks != count.div_ceil(per) {
        return Err(bad("dictionary block count does not match its string count"));
    }
    let first_dir = 12;
    let firsts = first_dir + blocks * 4;
    let firsts_len = if blocks == 0 { 0 } else { rd32(firsts - 4)? };
    let block_dir = firsts + firsts_len;
    let data = block_dir + blocks * 4;

    let mut out = Vec::with_capacity(count);
    for b in 0..blocks {
        let start = if b == 0 { 0 } else { rd32(block_dir + (b - 1) * 4)? };
        let end = rd32(block_dir + b * 4)?;
        let comp = body.get(data + start..data + end).ok_or_else(|| bad("dictionary block truncated"))?;
        let raw = zstd::decode_all(comp)?;
        let want = per.min(count - b * per);
        let mut p = 0usize;
        for k in 0..want {
            let len = raw.get(p..p + 4).ok_or_else(|| bad("dictionary block ran out"))?;
            let len = u32::from_be_bytes(len.try_into().map_err(|_| bad("short dict"))?) as usize;
            p += 4;
            let s = raw.get(p..p + len).ok_or_else(|| bad("dictionary block ran out"))?;
            let s = String::from_utf8(s.to_vec()).map_err(|_| bad("dictionary entry is not UTF-8"))?;
            p += len;
            if k == 0 {
                let f0 = if b == 0 { 0 } else { rd32(first_dir + (b - 1) * 4)? };
                let f1 = rd32(first_dir + b * 4)?;
                if body.get(firsts + f0..firsts + f1) != Some(s.as_bytes()) {
                    return Err(bad("first-string table disagrees with its block"));
                }
            }
            out.push(s);
        }
        if p != raw.len() {
            return Err(bad("dictionary block has trailing bytes"));
        }
    }
    if data + if blocks == 0 { 0 } else { rd32(data - 4)? } != body.len() {
        return Err(bad("dictionary section has trailing bytes"));
    }
    Ok(out)
}

pub(crate) fn decode_column(body: &[u8], delta: bool) -> io::Result<Vec<i32>> {
    decode_column_with(body, delta, None)
}

fn decode_column_with(body: &[u8], delta: bool, progress: Option<&Progress>) -> io::Result<Vec<i32>> {
    let bad = |m: &str| io::Error::new(io::ErrorKind::InvalidData, m.to_string());
    let rd32 = |p: usize| -> u32 {
        u32::from_be_bytes([body[p], body[p + 1], body[p + 2], body[p + 3]])
    };
    if body.len() < 8 {
        return Err(bad("column section too short"));
    }
    let n = rd32(0) as usize;
    let blocks = rd32(4) as usize;
    let data = 8 + blocks * 8;
    let mut out = Vec::with_capacity(n);
    let mut start = 0usize;
    for b in 0..blocks {
        let end = rd32(8 + b * 8 + 4) as usize;
        let comp = body.get(data + start..data + end).ok_or_else(|| bad("column block out of range"))?;
        let raw = zstd::decode_all(comp)?;
        start = end;
        let mut p = 0usize;
        let mut prev = 0i32;
        let count = n.saturating_sub(b * BLOCK).min(BLOCK);
        for _ in 0..count {
            let mut v = 0u32;
            let mut shift = 0;
            loop {
                let byte = *raw.get(p).ok_or_else(|| bad("block ran out of bytes"))?;
                p += 1;
                v |= ((byte & 0x7F) as u32) << shift;
                if byte & 0x80 == 0 {
                    break;
                }
                shift += 7;
            }
            let dec = ((v >> 1) as i32) ^ -((v & 1) as i32);
            let value = if delta { prev.wrapping_add(dec) } else { dec };
            out.push(value);
            prev = value;
        }
        if let Some(p) = progress {
            p.inc();
        }
    }
    Ok(out)
}

fn decode_grid(body: &[u8]) -> io::Result<Vec<(i64, u32)>> {
    let count = u32::from_be_bytes(
        body[0..4].try_into().map_err(|_| io::Error::other("short grid"))?,
    ) as usize;
    let mut out = Vec::with_capacity(count);
    let mut p = 4usize;
    for _ in 0..count {
        let cell = i64::from_be_bytes(
            body[p..p + 8].try_into().map_err(|_| io::Error::other("short grid"))?,
        );
        let start = u32::from_be_bytes(
            body[p + 8..p + 12].try_into().map_err(|_| io::Error::other("short grid"))?,
        );
        out.push((cell, start));
        p += 12;
    }
    Ok(out)
}
