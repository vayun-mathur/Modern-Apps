//! Phase 1 overlay gate: synthetic-archive tests for `tile_build::overlay`.
//!
//! Hermetic (no fixtures): tiny archives through the real `StreamWriter`,
//! planned and emitted through the real overlay path. Covers the three
//! invariants the no-2x + insertion-efficiency guarantees rest on.

#[cfg(test)]
mod tests {
    use tile_build::mamaps::body::{Body, Layer};
    use tile_build::mamaps::read::MamapsArchive;
    use tile_build::mamaps::write::Options;
    use tile_build::overlay::{emit_segment, open_pair, plan};
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

    fn ids(z: u8, n: u64) -> Vec<u64> {
        let mut out: Vec<u64> =
            (0..n).map(|k| pmtiles::tile_id(z, k % (1 << z), k / (1 << z))).collect();
        out.sort_unstable();
        out
    }

    fn body_with_layers(layer_count: u8) -> Body {
        let mut body = Body::new(tile_build::mamaps::body::DEFAULT_EXTENT);
        for id in 0..layer_count {
            body.layers.push(Layer::new(id));
        }
        body
    }

    fn archive(ids: &[u64], variant: u8) -> Vec<u8> {
        let options = Options {
            min_zoom: 0,
            max_zoom: 14,
            build_id: u64::from(variant),
            compress: true,
            ..Options::default()
        };
        let mut writer = mamaps::write::StreamWriter::new(options).expect("options");
        for (n, id) in ids.iter().enumerate() {
            let layers: u8 = if variant == 0 { 1 } else { 1 + (n % 2) as u8 };
            writer.append(*id, &body_with_layers(layers)).expect("append");
        }
        writer.finish().expect("finish")
    }

    fn open(bytes: &[u8]) -> MamapsArchive<MemReader> {
        MamapsArchive::open(MemReader { bytes: bytes.to_vec() }).expect("open")
    }

    /// The plan's core promise: one tile's bytes changing plans exactly that
    /// tile's new body, and the emitted segment serves it back.
    #[test]
    fn a_changed_tile_round_trips_through_a_segment() {
        let all = ids(5, 16);
        let before = archive(&all, 0);
        let after = archive(&all, 1);
        let dir = std::env::temp_dir().join(format!(
            "overlay-roundtrip-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos(),
        ));
        std::fs::create_dir_all(&dir).expect("fixture dir");
        std::fs::write(dir.join("before.mamaps"), &before).expect("fixture before");
        std::fs::write(dir.join("after.mamaps"), &after).expect("fixture after");
        let pair = open_pair(&dir.join("before.mamaps"), &dir.join("after.mamaps"))
            .expect("open pair");
        let overlay = plan(&pair.old_snapshot, &pair.new_snapshot, &pair.old_archive, &pair.new_archive);
        // Variant 1 alternates 1/2 layers: the 8 odd tiles carry new bodies.
        assert_eq!(overlay.carries.len(), 8, "the 8 odd tiles carry");
        assert!(overlay.tombstones.is_empty(), "nothing deleted");
        assert_eq!(overlay.segments.len(), 1, "tiny overlay is one segment");
        let out = dir.join("overlay-0000.mamaps");
        emit_segment(&pair.new_archive, &overlay, overlay.segments[0], &out)
            .expect("emit");
        // The segment opens as a real archive and serves every carried tile
        // with the NEW bytes.
        let segment = MamapsArchive::open(tile_build::churn::FileReader::open(&out).expect("open"))
            .expect("segment opens");
        let new_archive = open(&after);
        for carry in &overlay.carries {
            let (z, x, y) = tile_build::pmtiles::tile_zxy(carry.tile_id);
            let got = segment
                .tile_bytes(z, x as u32, y as u32)
                .expect("segment read")
                .expect("carried tile present");
            let want = new_archive
                .tile_bytes(z, x as u32, y as u32)
                .expect("new read")
                .expect("new tile present");
            assert_eq!(got, want, "segment serves the new bytes");
        }
        // And the base file is byte-untouched (insertion efficiency: no
        // rewrite, no fixup — the test re-reads the file it wrote).
        assert_eq!(std::fs::read(dir.join("before.mamaps")).expect("reread"), before);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Deleted tiles plan tombstones, not carries: no body ships for a tile
    /// with no new bytes.
    #[test]
    fn deleted_tiles_plan_tombstones_not_carries() {
        let grown = ids(3, 12);
        let base = grown[..8].to_vec();
        let before = archive(&base, 0);
        let after = archive(&grown, 0);
        let dir = std::env::temp_dir().join(format!(
            "overlay-tombstone-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock")
                .as_nanos(),
        ));
        std::fs::create_dir_all(&dir).expect("fixture dir");
        std::fs::write(dir.join("before.mamaps"), &before).expect("fixture before");
        std::fs::write(dir.join("after.mamaps"), &after).expect("fixture after");
        let pair = open_pair(&dir.join("before.mamaps"), &dir.join("after.mamaps"))
            .expect("open pair");
        let overlay = plan(&pair.old_snapshot, &pair.new_snapshot, &pair.old_archive, &pair.new_archive);
        assert_eq!(overlay.carries.len(), 4, "the 4 new tiles carry");
        assert!(overlay.tombstones.is_empty(), "nothing deleted going up");
        let back = plan(&pair.new_snapshot, &pair.old_snapshot, &pair.new_archive, &pair.old_archive);
        assert!(back.carries.is_empty(), "going down carries nothing");
        assert_eq!(back.tombstones.len(), 4, "the 4 dropped tiles tombstone");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The manifest renders canonical JSON with the fields the client gates on.
    #[test]
    fn the_manifest_renders_its_schema() {
        let manifest = tile_build::manifest::Manifest {
            version: tile_build::manifest::MANIFEST_VERSION,
            base: tile_build::manifest::BaseRef {
                url: "https://data.maproj.org/planet4.mamaps".to_string(),
                build_id: 0x1234,
                len: 78017648928,
            },
            overlay_seq: 7,
            overlay_build_id: 0xabcd,
            segments: vec![tile_build::manifest::SegmentRef {
                url: "https://data.maproj.org/overlays/0007-0000.mamaps".to_string(),
                len: 123456,
                sha256: "abc".to_string(),
                seq: 0,
            }],
            sections: vec![],
            tombstones: vec![85, 86],
            min_client: tile_build::manifest::MIN_CLIENT_COMPAT,
        };
        let text = tile_build::manifest::render(&manifest);
        for field in ["version", "base", "overlay_seq", "segments", "tombstones", "min_client", "sha256"] {
            assert!(text.contains(field), "manifest names {field}");
        }
        assert!(text.contains("0x0000000000001234"), "build ids render hex");
    }
}
