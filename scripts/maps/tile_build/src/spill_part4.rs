

    #[test]
    fn the_normalized_file_round_trips_and_folds_its_summary() {
        let dir = tmp("normalized");
        let path = dir.join("features.bin");
        let features = vec![
            NormalizedFeature {
                geometry: Geometry::Lines(vec![vec![(-122.42, 37.77), (-122.40, 37.79)]]),
                props: every_value(),
            },
            NormalizedFeature {
                geometry: Geometry::Lines(vec![vec![(-74.01, 40.71), (-73.99, 40.73)]]),
                props: vec![],
            },
            NormalizedFeature {
                geometry: Geometry::Points(vec![(2.35, 48.85)]),
                props: vec![("x".to_string(), Value::Bool(true))],
            },
        ];

        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        w.skip();
        w.skip();
        let summary = w.finish().unwrap();

        assert_eq!(summary.count, 3);
        assert_eq!(summary.skipped, 2);
        // The FIRST feature's kind, even though a point follows.
        assert_eq!(summary.geom_kind, Some(GeomKind::Lines));
        let b = summary.bounds.expect("bounds");
        assert_eq!((b.min_x, b.max_x), (-122.42, 2.35));
        assert_eq!((b.min_y, b.max_y), (37.77, 48.85));

        let mut r = NormalizedReader::open(&path).unwrap();
        let mut back = Vec::new();
        while let Some(f) = r.next().unwrap() {
            back.push(f);
        }
        let want = snapped_all(&features);
        assert_eq!(back, want);

        // Every zoom re-reads it from the front.
        r.rewind().unwrap();
        assert_eq!(r.next().unwrap().as_ref(), Some(&want[0]));

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The chunked reader must yield exactly the sequential reader's features in exactly
    /// its order, and its chunk boundaries must land on multiples of
    /// `NORM_CHUNK_FEATURES` — that alignment is what the bucket pass relies on to
    /// reconstruct `seq` without reading the whole file.
    #[test]
    fn the_chunked_reader_yields_what_the_sequential_one_does() {
        let dir = tmp("normchunks");
        let path = dir.join("features.bin");

        // Deliberately not a multiple of the stride, so the last chunk is partial, and
        // varied enough that a record-length mistake cannot cancel out.
        let n = 2 * NORM_CHUNK_FEATURES as usize + 7;
        let features: Vec<NormalizedFeature> = (0..n)
            .map(|i| {
                let f = i as f64;
                if i % 3 == 0 {
                    NormalizedFeature {
                        geometry: Geometry::Points(vec![(f * 0.01, -f * 0.02)]),
                        props: vec![("i".to_string(), Value::Uint(i as u64))],
                    }
                } else if i % 3 == 1 {
                    NormalizedFeature {
                        geometry: Geometry::Lines(vec![vec![(f * 0.01, 1.0), (f * 0.01, 2.0)]]),
                        props: every_value(),
                    }
                } else {
                    NormalizedFeature {
                        geometry: Geometry::Lines(vec![vec![(0.0, f * 0.01), (1.0, f * 0.01)]]),
                        props: vec![],
                    }
                }
            })
            .collect();

        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();
        assert_eq!(summary.count, n as u64);
        assert_eq!(summary.chunk_count(), 3, "two full chunks and a partial one");
        assert_eq!(
            *summary.chunks.last().unwrap(),
            std::fs::metadata(&path).unwrap().len(),
            "the sentinel must be the file's length"
        );

        let chunks = NormalizedChunks::open(&path, summary.chunks.clone()).unwrap();
        let mut scratch = Vec::new();
        let mut out = Vec::new();
        let mut back = Vec::new();
        for i in 0..chunks.chunk_count() {
            chunks.read_into(i, &mut scratch, &mut out).unwrap();
            // The alignment the `seq` reconstruction depends on.
            assert_eq!(
                back.len() as u64,
                i as u64 * NORM_CHUNK_FEATURES,
                "chunk {i} must start at feature {}",
                i as u64 * NORM_CHUNK_FEATURES
            );
            back.extend(out.iter().cloned());
        }
        assert_eq!(back, snapped_all(&features), "the chunked read must match the input");

        // And the sequential reader, so neither can drift from the other.
        let mut r = NormalizedReader::open(&path).unwrap();
        let mut seq = Vec::new();
        while let Some(f) = r.next().unwrap() {
            seq.push(f);
        }
        assert_eq!(back, seq, "the two readers must agree");

        assert!(
            chunks
                .read_into(chunks.chunk_count(), &mut scratch, &mut out)
                .is_err(),
            "a chunk past the end must error rather than read nothing"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The anon backend stages byte-identical chunks: write through
    /// `create_anon`, read back through `open_anon`, and the records plus the
    /// chunk index match the file path exactly. This is what lets -Verify hold
    /// the anon build to the same hash as the file build.
    #[test]
    fn the_anon_backend_stages_what_the_file_does() {
        let n = 2 * NORM_CHUNK_FEATURES as usize + 7;
        let features: Vec<NormalizedFeature> = (0..n)
            .map(|i| {
                let f = i as f64;
                NormalizedFeature {
                    geometry: Geometry::Lines(vec![vec![(f * 0.01, 1.0), (f * 0.01, 2.0)]]),
                    props: vec![("i".to_string(), Value::Uint(i as u64))],
                }
            })
            .collect();

        // File path (reference).
        let dir = tmp("normanonfile");
        let path = dir.join("features.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let file_summary = w.finish().unwrap();

        // Anon path.
        let mut a = NormalizedWriter::create_anon("anon-test").unwrap();
        for f in &features {
            a.push(&f.geometry, &f.props).unwrap();
        }
        let (anon_summary, store) = a.finish_anon().unwrap();

        assert_eq!(anon_summary, file_summary, "summaries must match");
        assert_eq!(
            store.len(),
            std::fs::metadata(&path).unwrap().len(),
            "staged bytes must match"
        );

        let chunks = NormalizedChunks::open_anon(
            "anon-test",
            anon_summary.chunks.clone(),
            std::sync::Arc::new(store),
        )
        .unwrap();
        let mut scratch = Vec::new();
        let mut out = Vec::new();
        let mut back = Vec::new();
        for i in 0..chunks.chunk_count() {
            chunks.read_into(i, &mut scratch, &mut out).unwrap();
            back.extend(out.iter().cloned());
        }
        assert_eq!(back, snapped_all(&features), "anon reads must match the input");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A corrupt chunk index must be reported, not silently mis-decoded: the offsets are
    /// the one part of the format the records themselves cannot validate.
    #[test]
    fn a_chunk_index_pointing_into_a_record_errors() {
        let dir = tmp("normchunkbad");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        for i in 0..NORM_CHUNK_FEATURES + 3 {
            w.push(
                &Geometry::Lines(vec![vec![(i as f64, 1.0), (i as f64, 2.0)]]),
                &every_value(),
            )
            .unwrap();
        }
        let summary = w.finish().unwrap();

        let mut scratch = Vec::new();
        let mut out = Vec::new();

        // Nudge a boundary into the middle of a record.
        let mut bad = summary.chunks.clone();
        bad[1] += 3;
        let chunks = NormalizedChunks::open(&path, bad).unwrap();
        assert!(
            chunks.read_into(0, &mut scratch, &mut out).is_err()
                || chunks.read_into(1, &mut scratch, &mut out).is_err(),
            "a misaligned boundary must error"
        );

        // A boundary that runs backwards.
        let mut backwards = summary.chunks.clone();
        backwards[1] = backwards[0];
        backwards[0] = *summary.chunks.last().unwrap();
        let chunks = NormalizedChunks::open(&path, backwards).unwrap();
        assert!(
            chunks.read_into(0, &mut scratch, &mut out).is_err(),
            "a backwards range must error"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The summary's bounds must be exactly what the in-memory path computes, or the
    /// two producers write different header bytes for the same input.
    #[test]
    fn the_summary_bounds_match_the_in_memory_fold() {
        let dir = tmp("bounds");
        let path = dir.join("f.bin");
        let geoms = vec![
            Geometry::Lines(vec![vec![(-10.0, -5.0), (3.0, 8.0)]]),
            Geometry::Points(vec![(20.0, -30.0)]),
            // No vertices at all, so it must not move the box.
            Geometry::Lines(vec![]),
        ];
        let mut w = NormalizedWriter::create(&path).unwrap();
        for g in &geoms {
            w.push(g, &[]).unwrap();
        }
        let summary = w.finish().unwrap();

        let want = geoms.iter().fold(None, crate::pyramid::fold_bounds);
        assert_eq!(summary.bounds, want);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_empty_normalized_file_reads_as_no_features() {
        let dir = tmp("empty");
        let path = dir.join("f.bin");
        let summary = NormalizedWriter::create(&path).unwrap().finish().unwrap();
        assert_eq!(summary, NormalizedSummary::default());
        assert!(NormalizedReader::open(&path).unwrap().next().unwrap().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_truncated_normalized_record_errors() {
        let dir = tmp("normtrunc");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        w.push(
            &Geometry::Lines(vec![vec![(1.0, 2.0), (3.0, 4.0)]]),
            &every_value(),
        )
        .unwrap();
        w.finish().unwrap();

        let bytes = std::fs::read(&path).unwrap();
        // Layout is the 8-byte spill header, then one frame (`u32` len + DEFLATE). Truncations
        // are relative to that: cutting the frame is a truncated file, cutting into the spill
        // header is a file that is not a spill at all.
        assert!(bytes.len() > 8 + 4, "the fixture must hold a frame");
        std::fs::write(&path, &bytes[..bytes.len() - 8]).unwrap();
        assert!(NormalizedReader::open(&path).unwrap().next().is_err(), "short payload");
        std::fs::write(&path, &bytes[..8 + 4 - 1]).unwrap();
        assert!(NormalizedReader::open(&path).unwrap().next().is_err(), "short frame header");
        // A file shorter than the spill header is not a spill file at all.
        std::fs::write(&path, &bytes[..8 - 1]).unwrap();
        assert!(NormalizedReader::open(&path).is_err(), "short spill header");
        // Corrupt the first record's reserved tail *inside* the frame: inflate the frame,
        // dirty byte 15 of the record header, recompress, and rewrite the file. The reader
        // must refuse the record, not decode past it.
        let frame_len =
            u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
        let mut raw =
            miniz_oxide::inflate::decompress_to_vec(&bytes[12..12 + frame_len]).unwrap();
        raw[NORM_HEADER_BYTES - 1] = 1;
        let sealed = miniz_oxide::deflate::compress_to_vec(&raw, 6);
        let mut dirty = bytes[..12].to_vec();
        dirty[8..12].copy_from_slice(&(sealed.len() as u32).to_le_bytes());
        dirty.extend_from_slice(&sealed);
        std::fs::write(&path, &dirty).unwrap();
        assert!(
            NormalizedReader::open(&path).unwrap().next().is_err(),
            "nonzero reserved tail"
        );
        // And the chunked reader agrees on the truncated file: a frame that will not inflate
        // is corruption, not a short chunk.
        std::fs::write(&path, &bytes[..bytes.len() - 8]).unwrap();
        let summary = NormalizedSummary { count: 1, chunks: vec![8, bytes.len() as u64 - 8], ..Default::default() };
        let chunks = NormalizedChunks::open(&path, summary.chunks.clone()).unwrap();
        let mut scratch = Vec::new();
        let mut out = Vec::new();
        assert!(chunks.read_into(0, &mut scratch, &mut out).is_err(), "short frame");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A file with the wrong magic or version is refused on open, not decoded as frames.
    #[test]
    fn a_spill_with_bad_magic_or_version_is_refused() {
        let dir = tmp("normmagic");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        w.push(&Geometry::Points(vec![(1.0, 2.0)]), &[]).unwrap();
        w.finish().unwrap();
        let bytes = std::fs::read(&path).unwrap();

        let mut bad = bytes.clone();
        bad[0] = b'X';
        std::fs::write(&path, &bad).unwrap();
        assert!(NormalizedReader::open(&path).is_err(), "bad magic decodes");

        let mut bad = bytes.clone();
        bad[4] = 9;
        std::fs::write(&path, &bad).unwrap();
        assert!(NormalizedReader::open(&path).is_err(), "bad version decodes");

        let mut bad = bytes.clone();
        bad[7] = 1;
        std::fs::write(&path, &bad).unwrap();
        assert!(NormalizedReader::open(&path).is_err(), "nonzero reserved decodes");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Compression is the point: 64 repetitive line features must stage at a fraction of
    /// their raw records. A regression to uncompressed staging fails here rather than on a
    /// planet disk at z14.
    #[test]
    fn a_chunk_of_repetitive_features_stages_compressed() {
        let dir = tmp("normratio");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        for i in 0..NORM_CHUNK_FEATURES {
            let f = i as f64;
            w.push(
                &Geometry::Lines(vec![vec![(f * 0.001, 1.0), (f * 0.001, 2.0)]]),
                &[("i".to_string(), Value::Uint(i))],
            )
            .unwrap();
        }
        let summary = w.finish().unwrap();
        assert_eq!(summary.chunk_count(), 1);
        let staged = std::fs::metadata(&path).unwrap().len();
        // Raw records: each ~50+ bytes, so ~3.2 KB minimum unstaged. The frame must be
        // well under that; 1 KB is generous to DEFLATE on coordinate runs.
        assert!(staged < 8 + 4 + 1024, "{staged} staged bytes for one chunk is not compressed");
        // And it still reads back exactly.
        let chunks = NormalizedChunks::open(&path, summary.chunks.clone()).unwrap();
        let mut scratch = Vec::new();
        let mut out = Vec::new();
        chunks.read_into(0, &mut scratch, &mut out).unwrap();
        assert_eq!(out.len(), NORM_CHUNK_FEATURES as usize);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_normalized_file_guard_removes_it_on_drop() {
        let dir = tmp("normguard");
        let path = dir.join("f.bin");
        {
            let guard = NormalizedFile::new(&path);
            NormalizedWriter::create(guard.path()).unwrap().finish().unwrap();
            assert!(path.exists());
        }
        assert!(!path.exists(), "the guard must remove it");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Runs pushed whole ([`NormalizedWriter::push_run`]) -- of every length, starting mid-frame
    /// and on frame boundaries, mixed with single records and with enough frames to cross a
    /// background batch -- write the same file and the same summary as pushing record by record.
    #[test]
    fn pushing_runs_writes_what_pushing_records_writes() {
        let dir = tmp("runs");
        let mut records: Vec<(GeomKind, Vec<u8>, Option<crate::geom::Rect>)> = Vec::new();
        for i in 0..(NORM_CHUNK_FEATURES as usize * 4200 + 37) {
            let g = Geometry::Lines(vec![vec![(i as f64 * 1e-4, 1.0), (i as f64 * 1e-4 + 0.5, (i % 7) as f64)]]);
            let mut geom = Vec::new();
            encode_geometry(&g, &mut geom).unwrap();
            let mut rec = Vec::new();
            let props = vec![("c".to_string(), Value::Uint(i as u64))];
            encode_normalized_record(GeomKind::of(&g), &geom, &props, &mut rec).unwrap();
            records.push((GeomKind::of(&g), rec, crate::geom::bounds(&g)));
        }
        for anon in [false, true] {
            let (a, b) = (dir.join(format!("one{anon}.bin")), dir.join(format!("run{anon}.bin")));
            let mk = |p: &std::path::Path| {
                if anon { NormalizedWriter::create_anon(p) } else { NormalizedWriter::create(p) }.unwrap()
            };
            let mut one = mk(&a);
            for (k, r, bd) in &records {
                one.push_record(*k, r, *bd).unwrap();
            }
            let mut run = mk(&b);
            let mut i = 0usize;
            let mut step = 1usize;
            while i < records.len() {
                let n = step.min(records.len() - i);
                let mut bytes = Vec::new();
                let mut ends = Vec::new();
                let mut bounds = None;
                for (_, r, bd) in &records[i..i + n] {
                    bytes.extend_from_slice(r);
                    ends.push(bytes.len());
                    bounds = match (bounds, *bd) {
                        (None, x) => x,
                        (x, None) => x,
                        (Some(p), Some(q)) => Some(crate::geom::Rect {
                            min_x: f64::min(p.min_x, q.min_x),
                            min_y: f64::min(p.min_y, q.min_y),
                            max_x: f64::max(p.max_x, q.max_x),
                            max_y: f64::max(p.max_y, q.max_y),
                        }),
                    };
                }
                run.push_run(bytes, &ends, Some(records[i].0), bounds).unwrap();
                i += n;
                step = [1, 63, 64, 65, 200, 4096 * 64 + 3][(step + i) % 6];
            }
            let (s1, st1) = one.finish_either().unwrap();
            let (s2, st2) = run.finish_either().unwrap();
            assert_eq!(s1, s2, "summaries (chunk index included) agree, anon={anon}");
            let bytes = |p: &std::path::Path, st: Option<crate::anon::AnonStore>| match st {
                Some(s) => {
                    let mut v = vec![0u8; s.len() as usize];
                    s.read_at(0, &mut v).unwrap();
                    v
                }
                None => std::fs::read(p).unwrap(),
            };
            assert_eq!(bytes(&a, st1), bytes(&b, st2), "same bytes, anon={anon}");
        }
    }
