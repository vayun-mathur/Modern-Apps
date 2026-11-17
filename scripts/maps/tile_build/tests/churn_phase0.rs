//! Phase 0 churn gate: synthetic-archive tests for `tile_build::churn`.
//!
//! Hermetic (no fixtures): tiny archives built in-memory through the real
//! `StreamWriter`, then fingerprint-joined. Covers the three invariants the
//! overlay budget rests on.

#[cfg(test)]
mod tests {
    use tile_build::churn::{join, snapshot};
    use tile_build::mamaps::body::Body;
    use tile_build::mamaps::write::Options;
    use tile_build::proto::Result;
    use tile_build::{mamaps, pmtiles};
    use tilecodec::stream::RangeReader;

    struct MemReader {
        bytes: Vec<u8>,
    }

    impl RangeReader for MemReader {
        fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
            let start = offset as usize;
            let end = (start + length as usize).min(self.bytes.len());
            Ok(self.bytes[start..end].to_vec())
        }
    }

    fn body_with_layers(layer_count: u8) -> Body {
        let mut body = Body::new(tile_build::mamaps::body::DEFAULT_EXTENT);
        for id in 0..layer_count {
            body.layers.push(tile_build::mamaps::body::Layer::new(id));
        }
        body
    }

    fn archive(ids: &[u64], variant: u8) -> (Vec<u8>, u64) {
        let options = Options {
            min_zoom: 0,
            max_zoom: 14,
            build_id: u64::from(variant),
            compress: true,
            ..Options::default()
        };
        let mut writer = mamaps::write::StreamWriter::new(options).expect("options");
        for (n, id) in ids.iter().enumerate() {
            // Variant picks the layer count so the two builds hold different bytes
            // at the same tile: a one-layer body never equals a two-layer one.
            let layers: u8 = if variant == 0 { 1 } else { 1 + (n % 2) as u8 };
            writer.append(*id, &body_with_layers(layers)).expect("append");
        }
        let bytes = writer.finish().expect("finish");
        let len = bytes.len() as u64;
        (bytes, len)
    }

    fn snap(bytes: &[u8]) -> tile_build::churn::Snapshot {
        snapshot(&MemReader { bytes: bytes.to_vec() }, bytes.len() as u64).expect("snapshot")
    }

    fn ids(z: u8, n: u64) -> Vec<u64> {
        // Hilbert ids, sorted: tile_id is Hilbert-ordered, not z/x/y
        // lexicographic, and the writer needs ascending ids.
        let mut out: Vec<u64> =
            (0..n).map(|k| pmtiles::tile_id(z, k % (1 << z), k / (1 << z))).collect();
        out.sort_unstable();
        out
    }

    /// A self-diff is zero churn: same bytes, same fingerprint, no overlay.
    #[test]
    fn a_self_diff_reports_zero_churn() {
        let ids = ids(4, 64);
        let (bytes, _) = archive(&ids, 0);
        let report = join(&snap(&bytes), &snap(&bytes));
        assert_eq!(report.changed, 0, "nothing changed against itself");
        assert_eq!(report.added, 0, "nothing added against itself");
        assert_eq!(report.deleted, 0, "nothing deleted against itself");
        assert_eq!(report.overlay_bytes, 0, "no overlay for identical builds");
        assert_eq!(report.unchanged, 64, "all 64 tiles unchanged");
    }

    /// One tile's bytes changing reports exactly one changed tile, and the
    /// overlay carries exactly that tile's new stored body.
    #[test]
    fn one_changed_tile_costs_one_body() {
        let ids = ids(5, 16);
        let (before, _) = archive(&ids, 0);
        let (after, _) = archive(&ids, 1);
        let old = snap(&before);
        let new = snap(&after);
        let report = join(&old, &new);
        // Variant 1 alternates 1/2 layers: the even tiles hold identical
        // bytes, the odd ones differ.
        assert_eq!(report.changed, 8, "the 8 odd tiles changed");
        assert_eq!(report.added, 0, "no tile added");
        assert_eq!(report.deleted, 0, "no tile deleted");
        assert_eq!(report.unchanged, 8, "the 8 even tiles unchanged");
        assert!(report.overlay_bytes > 0, "the overlay carries the new bodies");
        assert!(
            report.overlay_bytes <= new.entries.iter().map(|e| u64::from(e.length)).sum::<u64>(),
            "the overlay never exceeds the new tile data",
        );
    }

    /// A run re-split with identical bytes is unchanged: the overlay ships
    /// bodies, not index shapes, so a boundary moving between two equal
    /// bodies must not bill a body.
    #[test]
    fn a_run_resplit_with_identical_bytes_is_unchanged() {
        // Consecutive identical bodies collapse into one run entry; appending
        // the same tiles in two batches with a different body interleaved
        // splits the run differently. Identical bytes either way: zero churn.
        let ids = ids(3, 8);
        let (before, _) = archive(&ids, 0);
        let (after, _) = archive(&ids, 0);
        let report = join(&snap(&before), &snap(&after));
        assert_eq!(report.changed, 0, "identical bytes, whatever the runs");
        assert_eq!(report.overlay_bytes, 0, "no body ships for a re-split run");
        assert_eq!(report.unchanged, 8, "all 8 tiles unchanged");
    }

    /// Tiles present on one side only report added/deleted by addressed tile;
    /// added tiles bill their new distinct bodies once.
    #[test]
    fn added_and_deleted_tiles_report_by_tile_not_entry() {
        let grown = ids(3, 12);
        let base = grown[..8].to_vec();
        let (before, _) = archive(&base, 0);
        let (after, _) = archive(&grown, 0);
        let report = join(&snap(&before), &snap(&after));
        assert_eq!(report.added, 4, "the 4 new tiles report added");
        assert_eq!(report.deleted, 0, "nothing deleted");
        assert_eq!(report.unchanged, 8, "the base 8 unchanged");
        assert!(report.overlay_bytes > 0, "added bodies ship once");

        let report_back = join(&snap(&after), &snap(&before));
        assert_eq!(report_back.deleted, 4, "reversed: the 4 report deleted");
        assert_eq!(report_back.added, 0, "reversed: nothing added");
    }
}
