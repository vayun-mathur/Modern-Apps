#[cfg(test)]
mod tests_extra {
    use super::tests::{drain, every_layer, feature, tmp};
    use super::*;
    use tilecodec::mamaps::body::{GEOM_LINE, WINDING_OUTER};

    /// Byte-identity, at the level the format can state it: what a reader yields does not depend on
    /// how much it buffers. This is what pins "the window size is not observable".
    #[test]
    fn a_chunk_reads_the_same_however_the_window_is_sized() {
        let spill = ChunkSpill::create(tmp("windows")).expect("create");
        let mut map: BTreeMap<(u64, u8), ChunkEntry> = every_layer().into_iter().collect();
        // One entry far larger than the smallest window, so at least one read has to be the
        // oversize path rather than the buffered one.
        let mut big = ChunkEntry::new(4);
        big.layer.features = vec![feature(1, 1, GEOM_LINE, 0, 0, 1)];
        big.layer.parts =
            vec![Part { coord_start: 0, point_count: 40_000, winding: WINDING_OUTER }];
        // The coordinates themselves are arbitrary; only the point count matters here. Kept
        // inside `i16`'s range rather than cast from the index, which wraps to `i16::MIN` at
        // 32 768 and then overflows on the negation.
        big.layer.coords = (0..40_000)
            .map(|i| {
                let v = (i % 30_000) as i16;
                (v, -v)
            })
            .collect();
        map.insert((6, 4), big);
        let at = spill.write_chunk(map).expect("write");

        let want = drain(&spill, &at, 1 << 20);
        for window in [1usize, 24, 25, 64, 1024, 1 << 14, 1 << 16, 1 << 22] {
            assert_eq!(drain(&spill, &at, window), want, "window {window} yielded something else");
        }
    }

    #[test]
    fn the_scratch_file_is_removed_on_drop() {
        let path = tmp("dropped");
        {
            let spill = ChunkSpill::create(&path).expect("create");
            spill.write_chunk(every_layer().into_iter().collect()).expect("write");
            // Unlinked at creation on Unix (a killed build leaves nothing); named until drop
            // elsewhere.
            assert_eq!(path.exists(), cfg!(not(unix)));
        }
        assert!(!path.exists(), "the scratch file outlived its spill");
    }

    /// The error path too: a spill dropped while a write is failing must not strand the file.
    #[test]
    fn the_scratch_file_is_removed_when_a_chunk_is_refused() {
        let path = tmp("refused");
        {
            let spill = ChunkSpill::create(&path).expect("create");
            // Keyed on one layer, carrying another.
            let mut map: BTreeMap<(u64, u8), ChunkEntry> = BTreeMap::new();
            map.insert((1, 2), ChunkEntry::new(3));
            assert!(spill.write_chunk(map).is_err(), "a mislabelled entry was accepted");
        }
        assert!(!path.exists(), "the scratch file outlived a failed write");
    }

    #[test]
    fn an_empty_chunk_is_zero_bytes_and_reads_as_nothing() {
        let spill = ChunkSpill::create(tmp("empty")).expect("create");
        let at = spill.write_chunk(BTreeMap::new()).expect("write");
        assert_eq!(at, ChunkRef { at: 0, len: 0, entries: 0 });
        assert!(drain(&spill, &at, 1 << 16).is_empty());
        spill.check_books().expect("the books balance");
    }

    #[test]
    fn the_books_catch_a_chunk_that_was_never_read() {
        let spill = ChunkSpill::create(tmp("unread")).expect("create");
        spill.write_chunk(every_layer().into_iter().collect()).expect("write");
        assert!(spill.check_books().is_err(), "an undrained spill balanced");
    }

    #[test]
    fn the_read_window_is_a_budget_divided_by_the_stream_count() {
        // `window_for` rather than `read_window_bytes`, so a test elsewhere holding the override
        // cannot make this one assert something else.
        assert_eq!(window_for(0), MAX_WINDOW, "no streams clamps up, not to zero");
        assert_eq!(window_for(1), MAX_WINDOW);
        // A north-america z14: the budget still binds, well clear of both clamps.
        let na = window_for(5_700);
        assert_eq!(na, READ_BUDGET / 5_700);
        assert!((MIN_WINDOW..=MAX_WINDOW).contains(&na));
        // A planet z14: the floor takes over, so the total is `streams * MIN_WINDOW`.
        assert_eq!(window_for(27_000), MIN_WINDOW);
        assert_eq!(window_for(10_000_000), MIN_WINDOW);
        for streams in [1usize, 2, 100, 5_700, 27_000, 10_000_000] {
            let window = window_for(streams);
            assert!(
                (MIN_WINDOW..=MAX_WINDOW).contains(&window),
                "{streams} streams gave a {window}-byte window"
            );
        }
        // The planet ceiling: streams x floor must stay a small fraction of a
        // constrained box's commit headroom (~50 GB here), not half of it.
        assert!(
            27_000u64 * MIN_WINDOW as u64 <= 512 << 20,
            "a planet z14's merge cursors must fit in ~512 MB"
        );
    }
}
