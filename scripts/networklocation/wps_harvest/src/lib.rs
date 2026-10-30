//! Build the offline WiFi and cell beacon stores that `:networklocation` reads.
//!
//! Three stages, one binary each:
//!
//! | Binary | Does |
//! | --- | --- |
//! | `wps_seed` | Open bulk dumps (beacondb, OpenCelliD) -> record shards |
//! | `wps_crawl` | Apple gs-loc snowball -> record shards, resumably |
//! | `wps_build` | Shards -> a sorted, filtered, deduplicated `WPSDB2` store |
//!
//! The stages are separate processes because they have nothing in common operationally: a
//! seed ingest is minutes, a planet crawl is weeks, and a build is hours of IO. Passing record
//! shards between them means a crawl can be interrupted, resumed, extended, or merged with a
//! newer dump without any stage knowing about the others.
//!
//! ## Where the accuracy comes from
//!
//! The store this writes keeps each beacon's coordinates as integer degrees x 1e8 — bit-exact
//! against what the source reported — and its own horizontal accuracy, plus its altitude in
//! metres when the source reports one (Apple's WiFi `location.5`; cell responses have no
//! altitude field). The format it replaces quantized to a 20 m grid and carried no accuracy
//! at all, so the device substituted one constant for every beacon and the solver's
//! uncertainty weighting, which is fully implemented, had nothing to work with.

#![deny(missing_docs)]

pub mod frontier;
pub mod gsloc;
pub mod keys;
pub mod probe;
pub mod progress;
pub mod proto;
pub mod quality;
pub mod record;
pub mod seed;
pub mod sort;
pub mod store;
pub mod store_extra;
pub mod v1;

use std::io::{self, BufRead, BufReader};
use std::path::Path;

/// Open a path for buffered reading, or stdin when it is `-`.
///
/// Bulk dumps ship gzipped and are piped in (`gunzip -c dump.csv.gz | wps_seed -`) rather than
/// decompressed in process, which keeps a decompressor out of the dependency list.
pub fn open_input(path: &str) -> io::Result<Box<dyn BufRead>> {
    if path == "-" {
        Ok(Box::new(BufReader::new(io::stdin())))
    } else {
        Ok(Box::new(BufReader::with_capacity(
            1 << 20,
            std::fs::File::open(Path::new(path))?,
        )))
    }
}

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn byte_counts_are_readable() {
        assert_eq!(human_bytes(0), "0 B");
        assert_eq!(human_bytes(1023), "1023 B");
        assert_eq!(human_bytes(1024), "1.00 KiB");
        assert_eq!(human_bytes(13_400_000_000), "12.48 GiB");
    }
}
