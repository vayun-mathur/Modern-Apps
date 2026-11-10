//! External review data merged into `poi_attrs.bin` at build time.
//!
//! `summarize_reviews.py` writes a UTF-8, tab-separated table, one place per line:
//!
//! ```text
//! element<TAB>id<TAB>rating<TAB>review_count<TAB>summary
//! node    148855851    3.9    3480    Classic diner fare ...
//! ```
//!
//! `element` is `node`, `way` or `relation` and `id` is the plain OSM id (relations
//! are NOT negated here; the negation in `poi_build` is internal). Empty columns
//! mean "unknown". Blank lines and lines starting with `#` are ignored.
//!
//! The join key has to be the OSM element because `poi_index.bin` and
//! `poi_attrs.bin` carry no OSM id, so this is the last point where one exists.

use std::path::Path;

use crate::poi_attrs::{KEY_RATING, KEY_REVIEW_COUNT, KEY_REVIEW_SUMMARY};
use crate::proto::{Error, Result};

pub const ELEMENT_NODE: u8 = 1;
pub const ELEMENT_WAY: u8 = 2;
pub const ELEMENT_RELATION: u8 = 3;

/// Longest summary kept, in bytes. The writer targets 280 characters; this leaves
/// room for multi-byte scripts while keeping one bad row from bloating the sidecar.
const MAX_SUMMARY_BYTES: usize = 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Review {
    rating: Option<String>,
    review_count: Option<String>,
    summary: Option<String>,
}

impl Review {
    /// The record fields to append, ascending by key.
    pub fn fields(&self) -> Vec<(u8, &[u8])> {
        let mut out = Vec::with_capacity(3);
        if let Some(r) = &self.rating {
            out.push((KEY_RATING, r.as_bytes()));
        }
        if let Some(c) = &self.review_count {
            out.push((KEY_REVIEW_COUNT, c.as_bytes()));
        }
        if let Some(s) = &self.summary {
            out.push((KEY_REVIEW_SUMMARY, s.as_bytes()));
        }
        out
    }
}

#[derive(Debug, Default)]
pub struct ReviewTable {
    /// Sorted by key, one entry per key (the LAST row for that key wins, as the
    /// `HashMap::insert` this replaced did). A sorted `Vec` rather than a map so
    /// the 2.7M-row planet table can be built and ordered in parallel.
    rows: Vec<((u8, i64), Review)>,
}

/// One parsed row, or `(line index within its chunk, why)` for the first bad one.
type ChunkRows = std::result::Result<Vec<((u8, i64), Review)>, (usize, String)>;

/// Smallest parse chunk. The table is ~400 MB on a planet build; below a few MiB a
/// chunk is not worth a task.
const MIN_PARSE_CHUNK: usize = 4 << 20;

impl ReviewTable {
    pub fn load(path: &Path) -> Result<Self> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| Error(format!("cannot read reviews {}: {e}", path.display())))?;
        Self::parse(&text).map_err(|e| Error(format!("{}: {}", path.display(), e.0)))
    }

    /// Parse the whole table.
    ///
    /// Parallel, and identical to the serial parse it replaced: the text is cut
    /// only immediately after a `\n`, so the chunks' `lines()` concatenate to the
    /// whole text's `lines()`; each chunk reports its line count, so an error
    /// carries the same global line number; the first failing chunk in text order
    /// supplies the error, and within a chunk parsing stops at its first bad line,
    /// so the reported error is the first bad line of the file. Rows are then
    /// stable-sorted by key and deduplicated keeping the last, which is what
    /// repeated `HashMap::insert` in file order did.
    pub fn parse(text: &str) -> Result<Self> {
        let target = (text.len() / (crate::par::threads() * 4).max(1)).max(MIN_PARSE_CHUNK);
        Self::parse_chunked(text, target)
    }

    /// [`ReviewTable::parse`] with an explicit chunk size, so tests can force
    /// many chunks on a tiny table.
    fn parse_chunked(text: &str, target: usize) -> Result<Self> {
        use rayon::prelude::*;
        let target = target.max(1);
        let bytes = text.as_bytes();
        let mut chunks: Vec<&str> = Vec::new();
        let mut pos = 0;
        while pos < bytes.len() {
            let mut end = (pos + target).min(bytes.len());
            if end < bytes.len() {
                end = match bytes[end..].iter().position(|b| *b == b'\n') {
                    Some(k) => end + k + 1,
                    None => bytes.len(),
                };
            }
            // `end` is just past an ASCII `\n` or the end, so a char boundary.
            chunks.push(&text[pos..end]);
            pos = end;
        }

        let parsed: Vec<(usize, ChunkRows)> = crate::par::install(|| {
            chunks.par_iter().map(|c| parse_chunk(c)).collect()
        });

        let mut first_line = 0usize;
        let mut total = 0usize;
        for (lines, rows) in &parsed {
            match rows {
                Ok(r) => total += r.len(),
                Err((n, why)) => {
                    return Err(Error(format!("line {}: {why}", first_line + n + 1)));
                }
            }
            first_line += lines;
        }
        let mut rows: Vec<((u8, i64), Review)> = Vec::with_capacity(total);
        for (_, r) in parsed {
            rows.extend(r.expect("errors returned above"));
        }
        crate::par::install(|| rows.par_sort_by_key(|r| r.0));
        // `dedup_by` hands (later, kept); swapping first leaves the later row kept.
        rows.dedup_by(|later, kept| {
            if later.0 == kept.0 {
                std::mem::swap(later, kept);
                true
            } else {
                false
            }
        });
        Ok(Self { rows })
    }

    pub fn get(&self, element: u8, id: i64) -> Option<&Review> {
        self.rows
            .binary_search_by_key(&(element, id), |r| r.0)
            .ok()
            .map(|i| &self.rows[i].1)
    }

    pub fn len(&self) -> usize {
        self.rows.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rows.is_empty()
    }
}

/// Parse one line-aligned chunk: `(lines in the chunk, rows or first error)`.
fn parse_chunk(text: &str) -> (usize, ChunkRows) {
    let mut out = Vec::new();
    let mut lines = 0usize;
    for (n, line) in text.lines().enumerate() {
        lines = n + 1;
        match parse_line(line) {
            Ok(Some(row)) => out.push(row),
            Ok(None) => {}
            Err(why) => return (lines, Err((n, why))),
        }
    }
    (lines, Ok(out))
}

/// One table line: `Ok(None)` for a blank or comment line.
fn parse_line(line: &str) -> std::result::Result<Option<((u8, i64), Review)>, String> {
    let line = line.trim_end_matches('\r');
    if line.trim().is_empty() || line.starts_with('#') {
        return Ok(None);
    }
    let cols: Vec<&str> = line.split('\t').collect();
    if cols.len() != 5 {
        return Err(format!("expected 5 tab-separated columns, found {}", cols.len()));
    }
    let element = match cols[0] {
        "node" => ELEMENT_NODE,
        "way" => ELEMENT_WAY,
        "relation" => ELEMENT_RELATION,
        other => return Err(format!("unknown element `{other}`")),
    };
    let id: i64 = cols[1].parse().map_err(|_| "id is not an integer".to_string())?;
    if id <= 0 {
        return Err("id must be positive".into());
    }
    let rating = match cols[2].trim() {
        "" => None,
        r => {
            let v: f32 = r.parse().map_err(|_| "rating is not a number".to_string())?;
            if !(1.0..=5.0).contains(&v) {
                return Err("rating must be between 1 and 5".into());
            }
            Some(format!("{v:.1}"))
        }
    };
    let review_count = match cols[3].trim() {
        "" => None,
        c => {
            let v: u32 = c
                .parse()
                .map_err(|_| "review_count is not a positive integer".to_string())?;
            (v > 0).then(|| v.to_string())
        }
    };
    let summary = Some(cols[4].trim())
        .filter(|s| !s.is_empty() && s.len() <= MAX_SUMMARY_BYTES)
        .map(str::to_string);
    Ok(Some(((element, id), Review { rating, review_count, summary })))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_rows_and_skips_comments_and_blanks() {
        let t = ReviewTable::parse(
            "# element\tid\trating\treview_count\tsummary\n\
             node\t148855851\t3.9\t3480\tClassic diner fare.\n\
             \n\
             way\t30636913\t4.25\t\t\n\
             relation\t7\t\t12\tBig park.\r\n",
        )
        .unwrap();
        assert_eq!(t.len(), 3);
        let node = t.get(ELEMENT_NODE, 148855851).unwrap();
        assert_eq!(
            node.fields(),
            vec![(KEY_RATING, &b"3.9"[..]), (KEY_REVIEW_COUNT, &b"3480"[..]), (KEY_REVIEW_SUMMARY, &b"Classic diner fare."[..])]
        );
        assert_eq!(t.get(ELEMENT_WAY, 30636913).unwrap().fields(), vec![(KEY_RATING, &b"4.2"[..])]);
        assert_eq!(
            t.get(ELEMENT_RELATION, 7).unwrap().fields(),
            vec![(KEY_REVIEW_COUNT, &b"12"[..]), (KEY_REVIEW_SUMMARY, &b"Big park."[..])]
        );
        assert!(t.get(ELEMENT_WAY, 148855851).is_none(), "element is part of the key");
    }

    #[test]
    fn malformed_rows_name_their_line() {
        for (row, why) in [
            ("node\t1\t4.0\t10", "expected 5"),
            ("area\t1\t4.0\t10\tx", "unknown element"),
            ("node\tabc\t4.0\t10\tx", "id is not"),
            ("node\t1\t6.0\t10\tx", "between 1 and 5"),
            ("node\t1\t4.0\t-3\tx", "review_count"),
        ] {
            let err = ReviewTable::parse(&format!("# header\n{row}\n")).unwrap_err().0;
            assert!(err.starts_with("line 2:") && err.contains(why), "{row:?} -> {err}");
        }
    }

    #[test]
    fn an_over_long_summary_is_dropped_not_truncated() {
        let t = ReviewTable::parse(&format!("node\t1\t4.0\t10\t{}\n", "x".repeat(MAX_SUMMARY_BYTES + 1))).unwrap();
        assert_eq!(t.get(ELEMENT_NODE, 1).unwrap().fields().len(), 2);
    }

    /// Serial reference: the `HashMap` parse this module used before it went
    /// parallel, kept here to pin the parallel one to it.
    fn reference(text: &str) -> std::result::Result<std::collections::HashMap<(u8, i64), Review>, String> {
        let mut map = std::collections::HashMap::new();
        for (n, line) in text.lines().enumerate() {
            match parse_line(line) {
                Ok(Some((k, v))) => {
                    map.insert(k, v);
                }
                Ok(None) => {}
                Err(why) => return Err(format!("line {}: {why}", n + 1)),
            }
        }
        Ok(map)
    }

    fn sample(rows: usize, bad_at: Option<usize>) -> String {
        let mut s = String::from("# header\n");
        for i in 0..rows {
            if Some(i) == bad_at {
                s.push_str("node\tnope\t1\t1\tx\n");
                continue;
            }
            // Ids repeat, so later rows must overwrite earlier ones.
            let el = ["node", "way", "relation"][i % 3];
            s.push_str(&format!("{el}\t{}\t{}.0\t{i}\tsummary {i}\r\n", 1 + i % 37, 1 + i % 5));
            if i % 11 == 0 {
                s.push('\n');
            }
        }
        s
    }

    #[test]
    fn chunked_parse_matches_the_serial_map_for_every_chunk_size() {
        let text = sample(500, None);
        let want = reference(&text).unwrap();
        for target in [1usize, 2, 7, 64, 333, 4096, 1 << 20] {
            let got = ReviewTable::parse_chunked(&text, target).unwrap();
            assert_eq!(got.len(), want.len(), "target {target}");
            for (k, v) in &want {
                assert_eq!(got.get(k.0, k.1), Some(v), "target {target} key {k:?}");
            }
        }
    }

    #[test]
    fn chunked_parse_reports_the_first_bad_line_globally() {
        let mut text = sample(300, Some(123));
        text.push_str("way\tx\t\t\t\n");
        let want = reference(&text).unwrap_err();
        for target in [1usize, 5, 50, 999, 1 << 20] {
            let got = ReviewTable::parse_chunked(&text, target).unwrap_err().0;
            assert_eq!(got, want, "target {target}");
        }
    }
}
