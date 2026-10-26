//! Build `geocoder.db` from an OpenStreetMap `.osm.pbf`.
//!
//! Replaces the `osmium tags-filter | osmium export | geocoder_gen.cpp` chain, which needed
//! osmium, g++, OpenMP, simdjson and POSIX mmap — so WSL only — and wrote a 101 GB GeoJSONSeq
//! intermediate on the way. This reads the PBF natively through `osm_ingest` and holds no
//! intermediate at all.
//!
//! It also indexes far more: the old database held only objects carrying both
//! `addr:housenumber` and `addr:street`. See [`extract`] for what that left out and why it
//! mattered.

#![deny(missing_docs)]

pub mod codec;
pub mod extract;
pub mod extract_extra;
pub mod format;
pub mod progress;
pub mod write;
pub mod write_extra;

/// Format a byte count for a status line.
pub fn human_bytes(n: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut v = n as f64;
    let mut u = 0;
    while v >= 1024.0 && u + 1 < UNITS.len() {
        v /= 1024.0;
        u += 1;
    }
    if u == 0 {
        format!("{n} B")
    } else {
        format!("{v:.2} {}", UNITS[u])
    }
}

/// Format a count with thousands separators, so a planet build's numbers are readable.
pub fn commas(n: usize) -> String {
    let s = n.to_string();
    let mut out = String::with_capacity(s.len() + s.len() / 3);
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn byte_counts_are_readable() {
        assert_eq!(human_bytes(0), "0 B");
        assert_eq!(human_bytes(1023), "1023 B");
        assert_eq!(human_bytes(1024), "1.00 KiB");
        assert_eq!(human_bytes(5_368_709_120), "5.00 GiB");
    }

    #[test]
    fn counts_are_grouped() {
        assert_eq!(commas(0), "0");
        assert_eq!(commas(999), "999");
        assert_eq!(commas(1000), "1,000");
        assert_eq!(commas(260_800_000), "260,800,000");
    }
}
