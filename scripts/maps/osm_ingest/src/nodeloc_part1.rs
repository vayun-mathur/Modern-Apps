/// The node pass: fill in every coordinate the earlier passes asked for.
///
/// Uses [`pbf::run_pass_sink`] rather than [`pbf::run_pass`], because `run_pass`'s contract is to
/// hand every chunk's accumulator back at once and these accumulators are the largest thing this
/// pass touches. On California they are 154 M nodes at 16 bytes -- about 2.5 GB -- and they would be
/// alive next to the 2.5 GB table they are about to be folded into, so the two peak together. Here a
/// chunk is folded and freed while its neighbours are still decoding, which also overlaps the merge
/// with the I/O. `pbf.rs` names this exact hazard in `run_pass_sink`'s own doc comment.
///
/// The merge order is unchanged: `run_pass_sink` calls its sink in chunk order, which is the order
/// `run_pass` returned chunks in, so two blocks reporting the same node still resolve to the same
/// coordinate.
pub fn resolve_nodes(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    label: &str,
    table: NodeLocations,
) -> Result<NodeLocations> {
    if table.is_empty() {
        return Ok(table);
    }
    // The plain node pass: coordinates only, no per-node classification. `road_graph` and the
    // `osm_ingest` extract take this path; `mamaps_build` takes [`resolve_nodes_with`] to fold its
    // label-node classification into the same inflate.
    resolve_nodes_with(
        input,
        blobs,
        blob_kinds,
        label,
        table,
        |_: &NodeView| Option::<()>::None,
        |_: Vec<()>| Ok(()),
    )
}

/// [`resolve_nodes`] plus a per-node classification folded into the same pass.
///
/// The node region of a planet PBF is most of the file, so a caller that also needs to classify
/// nodes -- `mamaps_build` spilling `places`/`poi` label points -- would otherwise inflate it a
/// second time. `classify` runs inside the block visitor, in parallel with every other block, and
/// must be pure: it sees one [`NodeView`] and returns an owned item or `None`. `drain` then receives
/// each chunk's collected items **in chunk order**, serialised behind the same lock the coordinate
/// writes go through, so a caller can push them straight to a sink without reordering the output.
///
/// The classification runs over every node blob regardless of whether the coordinate table is empty,
/// because a label set is independent of the way-ref set the coordinates serve.
pub fn resolve_nodes_with<L, C, D>(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    label: &str,
    mut table: NodeLocations,
    classify: C,
    mut drain: D,
) -> Result<NodeLocations>
where
    L: Send,
    C: Fn(&NodeView) -> Option<L> + Sync,
    D: FnMut(Vec<L>) -> Result<()> + Send,
{
    // Moved out so the workers can search it while the sink writes coordinates; put back below.
    let ids = std::mem::take(&mut table.ids);
    let locs = &mut table.locs;
    pbf::run_pass_sink(
        input,
        blobs,
        pbf::PassSpec { blob_kinds: Some(blob_kinds), want: KIND_NODES, label },
        || (Vec::<(u64, i32, i32)>::new(), Vec::<L>::new()),
        |state: &mut (Vec<(u64, i32, i32)>, Vec<L>), block| {
            let mut kinds = 0u8;
            // One cursor per block, because that is the run of ascending ids: a PBF block's dense
            // nodes are delta-encoded and therefore sorted, so this pays one binary search here and
            // walks the rest.
            let mut cursor = ids.cursor();
            visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
                if let Element::Node(n) = el {
                    if let Some(idx) = cursor.find(n.id) {
                        state.0.push((idx, n.lat_e7, n.lon_e7));
                    }
                    if let Some(item) = classify(&n) {
                        state.1.push(item);
                    }
                }
                Ok(())
            })?;
            Ok(kinds)
        },
        |(coords, items)| {
            // Sorted by table index before the stores, so the mmap writes walk forward
            // instead of scattering: a chunk's ids arrive in block order (ascending within
            // a block, but blocks interleave across the chunk), and 8-byte stores to
            // 1.23 GB touch one page per store when they jump. Ascending stores fault
            // pages in order and reuse the TLB entry. Byte-identical: the table holds
            // one coordinate per index and the bitset is order-independent, so write
            // order is not observable. `items` (the classification drain) keeps its
            // own order — only the coordinate writes are reordered.
            let mut coords = coords;
            coords.sort_unstable_by_key(|c| c.0);
            for (idx, lat, lon) in coords {
                locs.set(idx as usize, lat, lon);
            }
            // After the coordinate writes, so a caller that both resolves and classifies sees the
            // two in the order this pass names them.
            drain(items)?;
            Ok(())
        },
    )?;
    table.ids = ids;
    Ok(table)
}

#[cfg(test)]
mod tests {
    /// **The test the cursor lives or dies by.** It has to answer exactly what [`IdIndex::find`] does
    /// for every id, in ascending order (its fast path), in shuffled order (where it must re-seek),
    /// and for ids that are absent — including ones below the first and above the last.
    ///
    /// Ascending alone would not catch it: the whole risk of a cursor is that it is correct while
    /// walking forwards and wrong the moment something asks out of order, and nothing in the PBF
    /// format actually promises sorted ids.
    #[test]
    fn the_cursor_answers_exactly_what_a_fresh_search_would() {
        let mut state = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        // Spans several blocks, with gaps too large for a one-byte varint so the walk has to handle
        // multi-byte deltas, and a run of dense ids so it handles the cheap case too.
        let mut ids: Vec<i64> = Vec::new();
        let mut id = 5i64;
        for i in 0..1000 {
            id += if i % 7 == 0 { (next() % 300_000) as i64 + 1 } else { (next() % 4) as i64 + 1 };
            ids.push(id);
        }
        ids.sort_unstable();
        ids.dedup();
        let index = super::IdIndex::build(&ids);

        // Every id, ascending: the path the node pass actually takes.
        let mut cursor = index.cursor();
        for &want in &ids {
            assert_eq!(cursor.find(want), index.find(want), "ascending, id {want}");
        }

        // Absent ids interleaved with present ones, still ascending.
        let mut cursor = index.cursor();
        for &want in &ids {
            assert_eq!(cursor.find(want - 1), index.find(want - 1), "ascending gap below {want}");
            assert_eq!(cursor.find(want), index.find(want), "ascending, id {want}");
        }

        // Shuffled, which forces a re-seek on most calls.
        let mut shuffled = ids.clone();
        for i in (1..shuffled.len()).rev() {
            shuffled.swap(i, (next() % (i as u64 + 1)) as usize);
        }
        let mut cursor = index.cursor();
        for &want in &shuffled {
            assert_eq!(cursor.find(want), index.find(want), "shuffled, id {want}");
        }

        // Strictly descending, the worst order for a forward cursor.
        let mut cursor = index.cursor();
        for &want in ids.iter().rev() {
            assert_eq!(cursor.find(want), index.find(want), "descending, id {want}");
        }

        // Outside the set at both ends, and far enough past the end to trip the scan bound.
        let first = ids[0];
        let last = ids[ids.len() - 1];
        let mut cursor = index.cursor();
        for want in [i64::MIN, first - 1, 0, last + 1, last + 10_000_000, i64::MAX] {
            assert_eq!(cursor.find(want), index.find(want), "outside, id {want}");
        }

        // A jump far ahead then far back, which is what work stealing does to a live cursor.
        let mut cursor = index.cursor();
        assert_eq!(cursor.find(ids[0]), index.find(ids[0]));
        assert_eq!(cursor.find(last), index.find(last));
        assert_eq!(cursor.find(ids[0]), index.find(ids[0]));
        assert_eq!(cursor.find(ids[ids.len() / 2]), index.find(ids[ids.len() / 2]));
    }

    /// The cursor must be right on a set small enough to have one partial block, where `position`
    /// arithmetic that assumed full blocks would still look plausible.
    #[test]
    fn the_cursor_is_right_on_a_set_smaller_than_one_block() {
        for count in [1usize, 2, 63, 64, 65, 129] {
            let ids: Vec<i64> = (0..count as i64).map(|i| i * 3 + 11).collect();
            let index = super::IdIndex::build(&ids);
            let mut cursor = index.cursor();
            for &want in &ids {
                assert_eq!(cursor.find(want), index.find(want), "{count} id(s), id {want}");
            }
            let mut cursor = index.cursor();
            for &want in &ids {
                let miss = want + 1;
                assert_eq!(cursor.find(miss), index.find(miss), "{count} id(s), absent {miss}");
            }
        }
    }

    /// **The test the compressed index lives or dies by.** It has to answer exactly what a plain
    /// `binary_search` over the same ids would, for ids that are present and ids that are not, and it
    /// has to hold at block boundaries and across gaps too large for a one-byte varint.
    ///
    /// Driven by a deterministic LCG rather than a fixed list so it covers thousands of cases and
    /// still fails identically every run.
    #[test]
    fn the_compressed_index_answers_exactly_what_a_binary_search_would() {
        let mut state = 0x2545_F491_4F6C_DD1Du64;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };

        // Gaps drawn so most are tiny (the real distribution) and some are enormous, which is what
        // forces multi-byte varints.
        let mut ids: Vec<i64> = Vec::new();
        let mut id: i64 = 1;
        for _ in 0..5_000 {
            let r = next();
            let gap = match r % 100 {
                0 => (r >> 8) % 5_000_000 + 1,
                1..=9 => (r >> 8) % 1_000 + 1,
                _ => (r >> 8) % 4 + 1,
            };
            id += gap as i64;
            ids.push(id);
        }
        let index = IdIndex::build(&ids);
        assert_eq!(index.len(), ids.len());

        // Every id present resolves to its own position.
        for (position, &id) in ids.iter().enumerate() {
            assert_eq!(index.find(id), Some(position as u64), "id {id} at {position}");
        }
        // And nothing else resolves at all. Probing every id +/- 1 covers the interesting misses:
        // just below a hit, just above one, and inside a gap.
        for &id in &ids {
            for probe in [id - 1, id + 1] {
                let expected = ids.binary_search(&probe).ok().map(|i| i as u64);
                assert_eq!(index.find(probe), expected, "probe {probe}");
            }
        }
        // Outside the set entirely, at both ends.
        assert_eq!(index.find(0), None);
        assert_eq!(index.find(i64::MIN), None);
        assert_eq!(index.find(ids[ids.len() - 1] + 1), None);
        assert_eq!(index.find(i64::MAX), None);
    }

    /// A block boundary is the one place an off-by-one would hide, so it is checked directly rather
    /// than left to the random case.
    #[test]
    fn a_block_boundary_resolves_on_both_sides() {
        // Three full blocks and a partial one, with a gap of exactly one so positions and ids differ
        // by a constant and an off-by-one is unmissable.
        let ids: Vec<i64> = (0..BLOCK as i64 * 3 + 7).map(|i| 1_000 + i).collect();
        let index = IdIndex::build(&ids);
        for (position, &id) in ids.iter().enumerate() {
            assert_eq!(index.find(id), Some(position as u64));
        }
        // The first id of each block is a base, which is the branch that returns without scanning.
        for block in 0..4 {
            let position = block * BLOCK;
            if position < ids.len() {
                assert_eq!(index.find(ids[position]), Some(position as u64));
            }
        }
        assert_eq!(index.find(999), None, "below the set");
        assert_eq!(index.find(1_000 + ids.len() as i64), None, "above the set");
    }

    #[test]
    fn an_empty_or_single_id_set_is_not_a_special_case_that_panics() {
        let empty = IdIndex::build(&[]);
        assert_eq!(empty.len(), 0);
        assert_eq!(empty.find(1), None);

        let one = IdIndex::build(&[42]);
        assert_eq!(one.len(), 1);
        assert_eq!(one.find(42), Some(0));
        assert_eq!(one.find(41), None);
        assert_eq!(one.find(43), None);
    }

    /// The saving this was done for. A plain `Vec<i64>` is 8 bytes an id; this has to be a small
    /// fraction of that or it is not worth the scan.
    #[test]
    fn the_index_costs_about_one_byte_per_id() {
        // Gaps of one to four, which is roughly what a real needed-node set looks like.
        let ids: Vec<i64> = (0..100_000i64).map(|i| 5 + i * 3).collect();
        let index = IdIndex::build(&ids);
        let bytes = index.bases.len() * 8 + index.offsets.len() * 8 + index.deltas.len();
        let plain = ids.len() * 8;
        assert!(
            bytes * 4 < plain,
            "the index is {bytes} bytes against {plain} for a plain vector, which is not worth it",
        );
    }

    /// What replaces the two `u32` guards that used to live in [`IdIndex::build`].
    ///
    /// They errored when the id count or the delta bytes passed `u32::MAX`, and their own doc
    /// comment said both were within reach at planet scale. They were, so the fields were widened
    /// instead -- and a guard removed with nothing put in its place is how a ceiling comes back.
    ///
    /// Two halves, because only one of them is affordable. The reachable half is ids whose *values*
    /// are past `u32::MAX`: OSM node ids are near 13 G today, so this is the real input, and it
    /// exercises the multi-byte varint gaps a sparse set produces. The unreachable half is the
    /// cardinality itself -- 4.29 G ids is ~4.7 GB of deltas and 67 M blocks, which no test can
    /// allocate -- so it is pinned as the types rather than as an allocation.
    #[test]
    fn ids_past_the_old_u32_ceilings_build_and_resolve() {
        // Spread from just under `u32::MAX` to past OSM's own high-water mark, with gaps large
        // enough that most encode to several varint bytes.
        let mut ids: Vec<i64> = Vec::new();
        let mut id = u32::MAX as i64 - 5;
        for i in 0..5_000i64 {
            ids.push(id);
            id += 1 + (i % 7) * 1_000_003;
        }
        assert!(*ids.last().expect("ids") > 13_000_000_000, "the set must reach planet-scale ids");
        let index = IdIndex::build(&ids);
        assert_eq!(index.len(), ids.len());

        // Every id resolves to its own position, by search and by cursor, and nothing else resolves.
        let mut cursor = index.cursor();
        for (position, &id) in ids.iter().enumerate() {
            assert_eq!(index.find(id), Some(position as u64), "id {id}");
            assert_eq!(cursor.find(id), Some(position as u64), "id {id} by cursor");
        }
        for &id in &ids {
            assert_eq!(index.find(id - 1), ids.binary_search(&(id - 1)).ok().map(|i| i as u64));
        }

        // And the fields themselves. A position and a delta offset must both be `u64`, or the
        // ceiling is back and only a planet-sized allocation would find it.
        let _: Option<u64> = index.find(ids[0]);
        let _: Option<u64> = index.cursor().find(ids[0]);
        let _: &Vec<u64> = &index.offsets;
    }

    #[test]
    fn a_varint_round_trips_at_every_width() {
        for value in [0u64, 1, 0x7f, 0x80, 0x3fff, 0x4000, u32::MAX as u64, u64::MAX] {
            let mut bytes = Vec::new();
            put_uvarint(value, &mut bytes);
            let (read, used) = get_uvarint(&bytes);
            assert_eq!((read, used), (value, bytes.len()), "value {value}");
        }
    }
    use super::*;

    #[test]
    fn node_locations_are_keyed_by_a_sorted_table_not_by_raw_node_id() {
        // The point of the pattern: memory is O(needed), so ids can be as large as
        // OSM's real ones without allocating anything proportional to them.
        let huge = 12_345_678_901i64;
        let mut table = NodeLocations::new(vec![huge, 5, 5, 1]).expect("map the coordinate file");
        assert_eq!(table.len(), 3, "sorted and deduped");
        assert_eq!(table.get(5), None, "unresolved until the node pass fills it");
        // Fill by index, as the merge does.
        let idx = table.index_of(5).unwrap() as usize;
        table.locs.set(idx, 377_900_000, -1_224_200_000);
        assert_eq!(table.get(5), Some((377_900_000, -1_224_200_000)));
        assert_eq!(table.get(huge), None);
        assert_eq!(table.get(999), None, "not in the table at all");
    }

    #[test]
    fn an_unresolved_ref_leaves_a_gap_rather_than_dropping_the_way() {
        // An extract that cuts through a way leaves some of its nodes out of the
        // file. Keeping the rest is what osmium's complete_ways produces too.
        let mut table = NodeLocations::new(vec![1, 2, 3]).expect("map the coordinate file");
        for (id, lat, lon) in [(1i64, 370_000_000, -1_220_000_000), (3, 370_020_000, -1_220_020_000)] {
            let idx = table.index_of(id).unwrap() as usize;
            table.locs.set(idx, lat, lon);
        }
        let line = table.line(&[1, 2, 3]);
        assert_eq!(line.len(), 2, "the middle vertex is missing, not the way");
        // lon/lat order, as GeoJSON wants.
        assert!((line[0].0 - -122.0).abs() < 1e-9);
        assert!((line[0].1 - 37.0).abs() < 1e-9);
    }
}
