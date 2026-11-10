/// Pass 2: refs of every relation member way. Moved whole from `extract`.
fn load_member_ways(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    relations: &[Relation],
) -> Result<MemberWays> {
    // --- pass 2: the refs of every relation member way ------------------------------------
    //
    // **Every** member, not just the ones pass 1 did not classify. A relation reaches its members by
    // id, in the order it lists them, which is the one random access in this stage and the one thing
    // a sequential spill file cannot serve. So the members — and only the members — stay resident,
    // and that is what lets the other several million classified ways go to disk. California has
    // 63 156 relations, so this table is small next to the one it replaces; planet has 8.4 M, so
    // it is a sorted vector rather than a hash map (see [`MemberWays`]).
    let wanted: Vec<i64> = {
        let mut wanted: Vec<i64> = relations
            .iter()
            .flat_map(|r| r.members.iter().map(|(id, _)| *id))
            .collect();
        wanted.sort_unstable();
        wanted.dedup();
        wanted
    };
    let mut members = MemberWays::empty();
    if !wanted.is_empty() {
        let (chunks, _) = pbf::run_pass(
            input,
            &blobs,
            Some(&blob_kinds),
            KIND_WAYS,
            "Pass 2: member ways",
            Vec::<(i64, Vec<i64>)>::new,
            |state, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_WAYS, &mut kinds, &mut |el| {
                    if let Element::Way(way) = el {
                        if wanted.binary_search(&way.id).is_ok() {
                            state.push((way.id, way.refs.to_vec()));
                        }
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
        )?;
        // Chunks arrive in file order and ways are sorted by id, so concatenation is sorted as
        // long as no way id appears in two chunks (ids are unique in the PBF). Sortedness is
        // asserted, not assumed: `MemberWays::build` sorts anyway, which is also what absorbs
        // the (impossible in practice) duplicate across chunks.
        let all: Vec<(i64, Vec<i64>)> = chunks.into_iter().flatten().collect();
        members = MemberWays::build(all);
    }

    Ok(members)
}

/// Relation member ways by id: the one random access in stage A.
///
/// A `HashMap<i64, Vec<i64>>` costs ~48 B overhead per entry plus the hasher's table slack --
/// at planet's 8.4 M member ways that is hundreds of MB beside the ref bytes themselves. This is
/// a sorted `Vec` with binary search instead: lookups are `O(log n)` rather than `O(1)`, but a
/// lookup is followed by cloning a ref vec and resolving every ref through the coordinate table,
/// so the search is noise next to the use. Built sorted once; every reader shares the shape.
pub struct MemberWays {
    entries: Vec<(i64, Vec<i64>)>,
}

impl MemberWays {
    fn empty() -> MemberWays {
        MemberWays { entries: Vec::new() }
    }

    /// Build from `(way id, refs)` pairs in any order. Sorts by id; a duplicated id keeps its
    /// first occurrence (ids are unique in the PBF, so this is defence, not logic).
    fn build(mut all: Vec<(i64, Vec<i64>)>) -> MemberWays {
        all.sort_by_key(|(id, _)| *id);
        all.dedup_by_key(|(id, _)| *id);
        MemberWays { entries: all }
    }

    /// The refs of member way `id`, or `None` if pass 2 never saw it (an extract cut it away).
    pub fn get(&self, id: i64) -> Option<&Vec<i64>> {
        self.entries
            .binary_search_by_key(&id, |(id, _)| *id)
            .ok()
            .map(|at| &self.entries[at].1)
    }

    /// Every member's refs, for the ref collectors (order irrelevant: both collectors only add).
    pub fn values(&self) -> impl Iterator<Item = &Vec<i64>> {
        self.entries.iter().map(|(_, refs)| refs)
    }

    /// How many member ways are held.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    #[cfg(test)]
    pub fn from_map(map: HashMap<i64, Vec<i64>>) -> MemberWays {
        MemberWays::build(map.into_iter().collect())
    }
}

#[cfg(test)]
mod member_ways_tests {
    use super::*;

    /// The whole contract: lookups answer like the map, whatever order the pairs arrived in, and
    /// a duplicated id keeps its first occurrence.
    #[test]
    fn lookups_match_the_map_regardless_of_input_order() {
        let table = MemberWays::build(vec![
            (9, vec![10_000, 9_999]),
            (1, vec![7, 8]),
            (9, vec![1, 2, 3]),
            (5, vec![]),
        ]);
        assert_eq!(table.len(), 3, "the duplicate id is absorbed");
        assert_eq!(table.get(1), Some(&vec![7, 8]));
        assert_eq!(table.get(5), Some(&vec![]), "an empty ref list is still a member");
        assert_eq!(table.get(9), Some(&vec![10_000, 9_999]), "first occurrence wins");
        assert_eq!(table.get(2), None, "not a member at all");
        assert_eq!(table.get(-1), None, "negative ids never resolve");
        assert_eq!(table.get(i64::MAX), None, "past the end");
        // `values` covers every entry exactly once, for the ref collectors.
        let mut seen: Vec<i64> = table.values().flatten().copied().collect();
        seen.sort_unstable();
        assert_eq!(seen, vec![7, 8, 9_999, 10_000]);
        assert!(MemberWays::empty().is_empty(), "empty is empty");
    }
}

/// Pass 3: id index plus resolved coordinates, with `places`/`poi` label nodes classified and
/// spilled in the same pass. Grew the sink, select and layers params when pass 4 was folded in.
///
/// `region` filters `poi` label NODES to the bbox. `places` nodes are always
/// kept. Ways and relations are filtered later at materialise time, where the
/// resolved coordinates are in hand.
#[allow(clippy::too_many_arguments)]
fn build_resolved_table(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    members: &MemberWays,
    needed: NeededBits,
    select: &Select,
    layers: Layers,
    region: Option<&osm_ingest::bbox::BBox>,
    sink: &mut Sink,
    stats: &mut Stats,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<NodeLocations> {
    // The fused bitset already holds every classified way's refs (set in pass
    // 1's drain); member ways join here. No ways-spill re-scan: the refs were
    // decoded and in hand where they were set.
    let mut needed = needed;
    for refs in members.values() {
        for &id in refs {
            needed.set_growable(id)?;
        }
    }
    // Small extracts take the exact vector; planet-scale takes the bitset the
    // words move straight into. The threshold now counts distinct ids (the old
    // one counted duplicates), which only moves borderline extracts toward the
    // vector path — whose input arrives sorted-unique, so its sort is trivial.
    let table = if needed.len() <= REFS_IN_MEMORY {
        let ids: Vec<i64> = needed.ids().collect();
        timer.switch("id-index");
        NodeLocations::new(ids)?
    } else {
        timer.switch("id-index");
        // The bitset's words move into the rank index directly (`from_bitset`),
        // not through `ids()`: walking them out as ids and re-setting them
        // would hold two ~1.5 GB words arrays at once.
        let (words, count) = needed.into_words();
        NodeLocations::from_bitset(words, count)?
    };
    timer.switch("coords-resolve");
    stats.nodes_needed = table.len() as u64;
    // The node region is the bulk of a planet PBF, so it is inflated once: the coordinate resolve
    // and the label-node classification share this pass. `classify` runs in the block visitor across
    // the pool and is pure -- it is exactly what the old pass 4 body did per node -- and the labels
    // it returns are drained to the sink **in chunk order**, ahead of every way and relation, which
    // is the order pass 4 spilled them in and what keeps the archive byte-identical.
    //
    // Only label layers are consulted: a node is never a road, a lake or a building, so running the
    // full schema over 2 B nodes would pay the tag scan for nothing.
    //
    // The region filter drops `poi` label nodes outside the bbox here. `places`
    // nodes are always kept — a region build still names its countries. A `poi`
    // node carries its own coordinates, so no location table is needed.
    let classify = |node: &NodeView| {
        if !select.matches(|k| node.tags.get_str(k)) {
            return None;
        }
        // A node no label claimed may still be road furniture. Safety is asked only here, in the
        // node pass, so a way or relation can never land in the point-only `safety` layer.
        let class = schema::classify(&node.tags, false, layers)
            .filter(|c| is_label(c.layer))
            .or_else(|| schema::safety::classify(&node.tags))?;
        let safety = class.layer == tilecodec::mamaps::dict::LAYER_SAFETY;
        if (safety || class.layer == tilecodec::mamaps::dict::LAYER_POI)
            && !osm_ingest::bbox::keep_e7(region, node.lat_e7, node.lon_e7)
        {
            return None;
        }
        let name = schema::display_name(&node.tags, class.layer);
        Some((
            class,
            node.lon_e7 as f64 * 1e-7,
            node.lat_e7 as f64 * 1e-7,
            name,
            if safety { tilecodec::mamaps::body::ID_NONE } else { tagged_id(node.id, ELEMENT_NODE) },
        ))
    };
    let drain = |hits: Vec<(Class, f64, f64, Option<String>, u64)>| -> Result<()> {
        for (class, lon, lat, name, id) in hits {
            let encoded =
                crate::store::EncodedGeom::live(&Geometry::Points(vec![(lon, lat)]))?;
            sink.push_named(&class, &encoded, name.as_deref(), id)?;
            stats.features += 1;
            stats.nodes_classified += 1;
        }
        Ok(())
    };
    let table = resolve_nodes_with(input, &blobs, &blob_kinds, "Pass 3: nodes", table, classify, drain)?;

    Ok(table)
}

/// The region bbox expanded for the lane join, in degrees.
///
/// A recipient is at most 50 m long (`lanefill::MAX_STUB_M`, enforced again in `push`),
/// and a donor must share an endpoint node with it — so the shared node sits within ~50 m
/// of the recipient, and any donor that can pair with a kept recipient touches the bbox
/// expanded by a small margin. 0.1° (~11 km) is two orders of magnitude past the ~100 m
/// worst case (a 50 m recipient straddling the border sharing its far endpoint), so the
/// fringe keeps every pair materialise can consult while dropping the rest of the planet.
/// `None` (world) disables the filter: every way can pair, everything resolves.
fn expanded_fringe(region: &osm_ingest::bbox::BBox) -> osm_ingest::bbox::BBox {
    const MARGIN_DEG: f64 = 0.1;
    osm_ingest::bbox::BBox {
        min_lon: (region.min_lon - MARGIN_DEG).max(-180.0),
        min_lat: (region.min_lat - MARGIN_DEG).max(-90.0),
        max_lon: (region.max_lon + MARGIN_DEG).min(180.0),
        max_lat: (region.max_lat + MARGIN_DEG).min(90.0),
    }
}

/// Whether the resolved line touches the fringe box. `None` (world) is always true.
fn fringe_touches(
    fringe: Option<&osm_ingest::bbox::BBox>,
    line: &[(f64, f64)],
) -> bool {
    let Some(fringe) = fringe else {
        return true;
    };
    line.iter().any(|&(lon, lat)| fringe.contains(lon, lat))
}

#[cfg(test)]
mod fringe_tests {
    use super::*;

    fn california() -> osm_ingest::bbox::BBox {
        osm_ingest::bbox::BBox {
            min_lon: -124.5,
            min_lat: 32.5,
            max_lon: -114.0,
            max_lat: 42.0,
        }
    }

    #[test]
    fn the_fringe_is_the_bbox_expanded_a_hair_in_every_direction() {
        let fringe = expanded_fringe(&california());
        assert!((fringe.min_lon - (-124.6)).abs() < 1e-9);
        assert!((fringe.min_lat - 32.4).abs() < 1e-9);
        assert!((fringe.max_lon - (-113.9)).abs() < 1e-9);
        assert!((fringe.max_lat - 42.1).abs() < 1e-9);
    }

    #[test]
    fn the_fringe_clamps_at_the_world_edges() {
        let world = osm_ingest::bbox::BBox {
            min_lon: -180.0,
            min_lat: -90.0,
            max_lon: 180.0,
            max_lat: 90.0,
        };
        assert_eq!(expanded_fringe(&world), world, "clamped, not wrapped");
    }

    #[test]
    fn world_keeps_everything_and_the_fringe_keeps_only_what_touches() {
        let fringe = expanded_fringe(&california());
        let inside = vec![(-122.4, 37.8)];
        let straddling = vec![(-124.55, 39.0)];
        let far = vec![(-100.0, 40.0)];
        assert!(fringe_touches(None, &far), "world disables the filter");
        assert!(fringe_touches(Some(&fringe), &inside));
        assert!(
            fringe_touches(Some(&fringe), &straddling),
            "a border-straddling recipient is within the margin",
        );
        assert!(!fringe_touches(Some(&fringe), &far), "Nevada never pairs with California");
        assert!(
            !fringe_touches(Some(&fringe), &[]),
            "a way with no resolved geometry joins nothing",
        );
    }

    /// The soundness case: a ≤50 m recipient straddling the border and its donor's
    /// endpoint at their shared node — both within the margin, so the pair survives.
    #[test]
    fn a_border_pair_survives_the_fringe() {
        let fringe = expanded_fringe(&california());
        // Recipient crossing the western edge; shared node 20 m outside.
        let recipient = vec![(-124.52, 39.0), (-124.4999, 39.0)];
        // Donor running outward from the shared node.
        let donor = vec![(-124.52, 39.0), (-124.53, 39.001)];
        assert!(fringe_touches(Some(&fringe), &recipient));
        assert!(fringe_touches(Some(&fringe), &donor));
    }
}

/// Lane inheritance over the ways spill. Moved whole from `extract`.
///
/// The scan is batched: the expensive per-way work (coordinate lookup through the node
/// table — a rank-bitset search plus a mapped-file read per node) runs on the pool, while
/// the collector push stays serial in spill order. `table.line` is shared-read-only (the
/// materialise pass already calls it from `par_iter`), and the collector's output order
/// never reaches the archive — `finish` sorts every partition — so the bytes are identical.
/// What this buys: the scan is ~1.1 B sequential ways on a planet input, each paying ~10
/// node lookups; serially that is tens of minutes of mapped-file random reads.
///
/// `region` limits the join to the ways that can pair with a materialised way (see
/// [`fringe_touches`]): on a `--region` build over a larger input, the ways outside the
/// fringe can never share an endpoint node with a kept recipient, so resolving them would
/// be pure waste. `None` (world) disables the filter entirely.
fn inherit_lane_counts(
    spill_path: &Path,
    blocks: &WayBlocks,
    table: &NodeLocations,
    ways_classified: usize,
    region: Option<&osm_ingest::bbox::BBox>,
) -> Result<Vec<(i64, u8)>> {
    // --- lane inheritance -------------------------------------------------------------------
    //
    // A junction stub OSM left untagged takes a lane count from the road it interrupts, instead of
    // the renderer's flat `oneway ? 1 : 2`. See [`crate::lanefill`] for the conditions and the
    // measurements behind each of them. It runs here rather than in pass 1 because the conditions
    // are geometric — a length and a turn angle — and coordinates are only resolved above.
    //
    // Block-parallel: every ways-spill block is decoded, filtered, resolved and turned into its
    // endpoint records on the pool ([`for_each_way_block`]); only the fixed-width record writes
    // stay serial, in block order -- which is spill order, so the partition files hold the same
    // bytes the one-cursor scan wrote. On the planet the one-cursor scan was 6 min at ~4 cores.
    let fringe = region.map(expanded_fringe);
    let mut collector = crate::lanefill::Collector::create(spill_path)?;
    let mut bar = Progress::new(
        "Lane inheritance: scan".to_string(),
        ways_classified,
        "way(s)",
        true,
    );
    for_each_way_block(
        blocks,
        |mut reader| -> Result<Vec<crate::lanefill::Prepared>> {
            let mut refs: Vec<i64> = Vec::new();
            let mut out = Vec::new();
            while let Some((id, class, name, lane_count, _, _, _, _)) = reader.next(&mut refs)? {
                // Roads, and not slip roads. A ramp leaves a junction carrying its parent's name on
                // very nearly its parent's heading, so it would inherit the mainline's width onto a
                // single-lane ramp; `corridor` leaves links out of a corridor for the same reason.
                if class.layer != tilecodec::mamaps::dict::LAYER_ROADS
                    || class.flags & tilecodec::mamaps::body::FLAG_IS_LINK != 0
                {
                    continue;
                }
                let Some(name) = name else { continue };
                let line = table.line(&refs);
                // A region build only joins the fringe: a recipient is at most 50 m long, so
                // a donor sharing an endpoint node with a kept recipient always touches the
                // expanded box (see `expanded_fringe`). Anything else can pair only with
                // ways materialise drops — work with no reader.
                if !fringe_touches(fringe.as_ref(), &line) {
                    continue;
                }
                let oneway = class.flags & tilecodec::mamaps::body::FLAG_IS_ONEWAY != 0;
                if let Some(prepared) = crate::lanefill::prepare(&crate::lanefill::Segment {
                    id,
                    name: &name,
                    lanes: lane_count,
                    oneway,
                    nodes: &refs,
                    line: &line,
                }) {
                    out.push(prepared);
                }
            }
            Ok(out)
        },
        |ways, prepared| {
            for p in &prepared {
                collector.push_prepared(p)?;
            }
            bar.tick_n(ways as usize, "way(s)");
            Ok(())
        },
    )?;
    bar.finish("way(s)");
    collector.finish()
}

/// Ways-spill blocks decoded per pool hand-off, per thread. Each block is at most
/// [`WAY_BLOCK_WAYS`] ways, so a group is ~1.5 M ways on 192 threads: enough to keep every core
/// busy with even load, and a bounded amount of finished work (endpoint records, spill records)
/// held while the previous group drains.
const BLOCKS_PER_THREAD: usize = 2;

/// Run `work` over every block of the ways spill on the pool and hand each block's result to
/// `drain` **in block order**, which is spill order.
///
/// Pipelined: while `drain` consumes group `k` on one thread, the pool computes group `k + 1`.
/// `work` gets a fresh cursor over its block; `drain` gets the block's way count (for progress)
/// and its result. The first error in block order wins, exactly as a one-cursor loop would
/// report it; work already computed past it is discarded.
fn for_each_way_block<T, W, D>(blocks: &WayBlocks, work: W, mut drain: D) -> Result<()>
where
    T: Send,
    W: Fn(WayReader) -> Result<T> + Sync,
    D: FnMut(u64, T) -> Result<()> + Send,
{
    let locs = blocks.locs();
    let group = (par::threads() * BLOCKS_PER_THREAD).max(1);
    pipeline_ordered(
        locs.len(),
        group,
        |i| blocks.reader(i).and_then(&work),
        |i, result| drain(locs[i].ways(), result?),
    )
}

/// Run `work(i)` for every `i in 0..len` on the pool, `group` items per hand-off, and hand each
/// result to `drain` **in index order**. While `drain` consumes one group on one thread, the pool
/// computes the next, so the serial drain overlaps the parallel work instead of alternating with
/// it -- and a slow item delays only its own group's drain, not a barrier every few thousand
/// items. The first error `drain` returns stops the loop; work already computed past it is
/// discarded.
pub(crate) fn pipeline_ordered<T, W, D>(len: usize, group: usize, work: W, mut drain: D) -> Result<()>
where
    T: Send,
    W: Fn(usize) -> T + Sync,
    D: FnMut(usize, T) -> Result<()> + Send,
{
    let group = group.max(1);
    par::install(|| {
        let mut start = 0usize;
        let mut pending: Option<(usize, Vec<T>)> = None;
        loop {
            let end = (start + group).min(len);
            let (drained, next) = rayon::join(
                || -> Result<()> {
                    if let Some((first, results)) = pending.take() {
                        for (k, result) in results.into_iter().enumerate() {
                            drain(first + k, result)?;
                        }
                    }
                    Ok(())
                },
                || {
                    if start < end {
                        Some((start..end).into_par_iter().map(&work).collect::<Vec<T>>())
                    } else {
                        None
                    }
                },
            );
            drained?;
            match next {
                Some(results) => pending = Some((start, results)),
                None => return Ok(()),
            }
            start = end;
        }
    })
}

/// One materialised way, decided on the pool: the serial drain only appends.
///
/// `Failed` (no geometry) counts `geometry_failed`; `Dropped` (region-filtered)
/// counts nothing — exactly the two non-pushed paths the inline drain had.
/// `Kept` carries the finished spill record ([`PreRecord`]), so the drain neither walks vertices
/// nor encodes.
enum BuiltWay {
    Failed,
    Dropped,
    Kept(PreRecord),
}

/// Resolve one way's fate: geometry, region keep/drop, and its spill record.
///
/// Pure over shared tables (`table`, `region`): safe across the pool. The record is built with
/// the same choice of shape the serial drain made -- a building carries its S3DB attributes, a
/// road with lane data carries those, everything else (a plain road included) goes the plain,
/// named way -- so `Sink::push_pre` stages exactly what `push_building` / `push_road` /
/// `push_named` would have.
#[allow(clippy::too_many_arguments)]
fn build_way(
    class: &Class,
    geometry: Option<Geometry>,
    region: Option<&osm_ingest::bbox::BBox>,
    name: Option<&str>,
    id: u64,
    lane_count: u8,
    turn_fwd: &[u16],
    turn_bwd: &[u16],
    carriageway: Carriageway,
    building: Option<BuildingAttrs>,
) -> Result<BuiltWay> {
    let Some(geometry) = geometry else {
        return Ok(BuiltWay::Failed);
    };
    // Region filter: roads, pois and buildings outside the bbox are dropped.
    // The geometry is resolved by now, so this is a direct touches-box test
    // on the line the way produced (a centroided point for labels).
    if is_region_filtered(class.layer) && !geometry_touches(&geometry, region) {
        return Ok(BuiltWay::Dropped);
    }
    let encoded = crate::store::EncodedGeom::live(&geometry)?;
    let record = if let Some(b) = building {
        PreRecord::building(class, &encoded, name, b)?
    } else if lane_count > 0 || !turn_fwd.is_empty() || !turn_bwd.is_empty() || !carriageway.is_empty() {
        PreRecord::road(class, &encoded, name, lane_count, turn_fwd, turn_bwd, carriageway)?
    } else {
        PreRecord::named(class, &encoded, name, id)?
    };
    Ok(BuiltWay::Kept(record))
}

/// Materialise classified ways off disk, in id order. Moved whole from `extract`.
///
/// `region` filters the built layers (`roads`, `poi`, `buildings`): a way in
/// one of those layers whose resolved line touches no bbox point is dropped.
/// Every other layer is always kept. Kept whole, never clipped — the tiler
/// clips per tile later.
///
/// Consumes `blocks`: it is the spill's last reader, and the handle must be closed before the
/// file is removed (Windows refuses to delete an open file).
#[allow(clippy::too_many_arguments)]
fn materialise_ways(
    ways_path: &Path,
    blocks: WayBlocks,
    promoted: &[(i64, u8)],
    inherited_lanes: &[(i64, u8)],
    table: &NodeLocations,
    region: Option<&osm_ingest::bbox::BBox>,
    sink: &mut Sink,
    stats: &mut Stats,
) -> Result<()> {
    // Silent until now, and it is not a short step: on a north-america extract this loop ran 623
    // seconds on one thread with nothing on stdout, which is indistinguishable from a hang.
    let mut bar = Progress::new(
        "Materialise: ways".to_string(),
        stats.ways_classified as usize,
        "way(s)",
        true,
    );
    // Block-parallel end to end: decoding the spill, the coordinate lookups (`table.line`, an id
    // search plus a random read of a mapped file per node), ring closing, the region test and the
    // spill record itself all run on the pool, per block; the sink then appends the finished
    // records **in block order** -- spill order -- which is what keeps the archive byte-identical:
    // the spill's feature order is the archive's. Measured before this on California: decode
    // (`fill`) 5.5 s and record encode + DEFLATE (`push`) 19.0 s of 26.6 s were serial.
    let mut drain_wall = std::time::Duration::ZERO;
    let wall = std::time::Instant::now();
    let (mut failed, mut kept) = (0u64, 0u64);
    for_each_way_block(
        &blocks,
        |mut reader| -> Result<Vec<Result<BuiltWay>>> {
            let mut refs: Vec<i64> = Vec::new();
            let mut out = Vec::new();
            while let Some((id, class, name, lane_count, turn_fwd, turn_bwd, carriageway, building)) =
                reader.next(&mut refs)?
            {
                let mut class = class;
                // The corridor's zoom, where it is shallower than this way's own. Corridors
                // are built from numbered roads alone, so a non-road way can never have an
                // entry — searching for one is ~17 wasted compares per boundary, building
                // and waterway on a region build.
                if class.layer == tilecodec::mamaps::dict::LAYER_ROADS {
                    if let Ok(at) = promoted.binary_search_by_key(&id, |(id, _)| *id) {
                        class.min_zoom = promoted[at].1;
                    }
                }
                // A neighbour's lane count, where OSM tagged none on this way. Only ever consulted
                // for a way that has none of its own, so a tag is never overridden.
                let lane_count = if lane_count == 0 {
                    inherited_lanes
                        .binary_search_by_key(&id, |(id, _)| *id)
                        .map_or(0, |at| inherited_lanes[at].1)
                } else {
                    lane_count
                };
                // Only a label way carries its id onward. A road or a building is merged with its
                // neighbours by `coalesce`, which leaves the survivor's id arbitrary, and the id
                // table exists for `poi` and `places` alone.
                let id = if is_label(class.layer) {
                    tagged_id(id, ELEMENT_WAY)
                } else {
                    tilecodec::mamaps::body::ID_NONE
                };
                let line = table.line(&refs);
                // A label layer's ways are centroided to points: a town mapped as an area
                // is still one label, not a loop. Everything else keeps its geometry.
                let geometry = if is_label(class.layer) {
                    centroid(&line).map(|point| Geometry::Points(vec![point]))
                } else {
                    way_geometry(&line, class.area)
                };
                out.push(build_way(
                    &class,
                    geometry,
                    region,
                    name.as_deref(),
                    id,
                    lane_count,
                    &turn_fwd,
                    &turn_bwd,
                    carriageway,
                    building,
                ));
            }
            Ok(out)
        },
        |ways, built| {
            let t = std::time::Instant::now();
            for built in built {
                // `?` in spill order: the first encode failure in feature order,
                // exactly as inline encoding would report it.
                match built? {
                    BuiltWay::Failed => failed += 1,
                    BuiltWay::Dropped => {}
                    BuiltWay::Kept(record) => {
                        sink.push_pre(&record)?;
                        kept += 1;
                    }
                }
            }
            bar.tick_n(ways as usize, "way(s)");
            drain_wall += t.elapsed();
            Ok(())
        },
    )?;
    stats.geometry_failed += failed;
    stats.features += kept;
    // TEMP-PROFILE: the serial drain's share of the phase wall. Removed with the other probes.
    eprintln!(
        "  [profile] materialise-ways wall={:.1}s drain={:.1}s spill={:.2}GB",
        wall.elapsed().as_secs_f64(),
        drain_wall.as_secs_f64(),
        sink.staged_bytes() as f64 / 1e9
    );
    bar.finish("way(s)");
    // Nothing reads the ways spill after this: the relations below reach their members through
    // `members`, which is why that table is kept at all. The file backend's `.tmp` is removed
    // (after its handle closes); the anon backend's segments unmap with the last `Arc`.
    drop(blocks);
    let _ = std::fs::remove_file(ways_path);

    Ok(())
}
