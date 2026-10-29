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

use std::collections::HashMap;
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
    map: HashMap<(u8, i64), Review>,
}

impl ReviewTable {
    pub fn load(path: &Path) -> Result<Self> {
        let text = std::fs::read_to_string(path)
            .map_err(|e| Error(format!("cannot read reviews {}: {e}", path.display())))?;
        Self::parse(&text).map_err(|e| Error(format!("{}: {}", path.display(), e.0)))
    }

    pub fn parse(text: &str) -> Result<Self> {
        let mut map = HashMap::new();
        for (n, line) in text.lines().enumerate() {
            let line = line.trim_end_matches('\r');
            if line.trim().is_empty() || line.starts_with('#') {
                continue;
            }
            let bad = |why: &str| Error(format!("line {}: {why}", n + 1));
            let cols: Vec<&str> = line.split('\t').collect();
            if cols.len() != 5 {
                return Err(bad(&format!("expected 5 tab-separated columns, found {}", cols.len())));
            }
            let element = match cols[0] {
                "node" => ELEMENT_NODE,
                "way" => ELEMENT_WAY,
                "relation" => ELEMENT_RELATION,
                other => return Err(bad(&format!("unknown element `{other}`"))),
            };
            let id: i64 = cols[1].parse().map_err(|_| bad("id is not an integer"))?;
            if id <= 0 {
                return Err(bad("id must be positive"));
            }
            let rating = match cols[2].trim() {
                "" => None,
                r => {
                    let v: f32 = r.parse().map_err(|_| bad("rating is not a number"))?;
                    if !(1.0..=5.0).contains(&v) {
                        return Err(bad("rating must be between 1 and 5"));
                    }
                    Some(format!("{v:.1}"))
                }
            };
            let review_count = match cols[3].trim() {
                "" => None,
                c => {
                    let v: u32 = c.parse().map_err(|_| bad("review_count is not a positive integer"))?;
                    (v > 0).then(|| v.to_string())
                }
            };
            let summary = Some(cols[4].trim())
                .filter(|s| !s.is_empty() && s.len() <= MAX_SUMMARY_BYTES)
                .map(str::to_string);
            map.insert((element, id), Review { rating, review_count, summary });
        }
        Ok(Self { map })
    }

    pub fn get(&self, element: u8, id: i64) -> Option<&Review> {
        self.map.get(&(element, id))
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }
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
}
