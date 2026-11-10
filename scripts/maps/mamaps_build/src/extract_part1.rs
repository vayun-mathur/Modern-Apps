/// Read `input` and spill every feature the schema classifies to `spill_path`.
///
/// Features go to disk rather than into a `Vec`, because holding them was 4.9 GB of a measured
/// 10.03 GB California peak and nothing reads them until the tiler does. Classified ways go to a
/// scratch file beside it for the same reason. See [`crate::store`].
///
/// `region` filters the **built** layers (`roads`, `poi`, `buildings`) to the
/// region's bbox. Every other layer is always included regardless. `None`
/// (world) disables the filter entirely.
pub fn extract(
    input: &Path,
    layers: Layers,
    coastline: &Path,
    transit_routes: &Path,
    graph: &Path,
    spill_path: &Path,
    region: Option<osm_ingest::bbox::BBox>,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<(Store, Stats, HashMap<u64, u64>, HashMap<u64, u64>)> {
    // Stage A's boundaries are subphase switches on the run's `PhaseTimer`, so an external RSS
    // sampler can say which of them the peak belongs to. Three candidates sit within seconds of
    // each other -- the ref vector, the id index built beside it, and the node pass's per-chunk
    // accumulators -- and guessing between them has already cost more than naming them does.
    let blobs = pbf::scan_blobs(input)?;
    let select = Select::parse(&schema::filters())?;
    let mut stats = Stats::default();
    // Scratch for this function alone: written in pass 1, read twice below, and removed as soon as
    // the last read is done. Beside the feature spill, so a build directed at a writable output
    // directory needs nothing else to be writable.
    let ways_path = spill_path.with_extension("ways.tmp");

    // A blob-kinds mask cached beside the routing graph -- `road_graph`'s pass 1 wrote it over this
    // same `.pbf` -- lets pass 1 skip node blobs outright rather than inflating them to learn they
    // hold no ways or relations. Guarded by blob count, file length and mtime, so a missing or stale
    // sidecar simply falls back to a full scan. Byte-neutral either way: the mask `run_pass1` returns
    // carries the skipped blobs' kinds, so it is a complete description of the file regardless.
    //
    // The sidecar also proves the graph was built over THIS pbf: when the
    // region filter is on, a world graph beside a california tile build (or
    // vice versa) would silently mismatch layers. A missing sidecar here only
    // warns — the blob mask itself is optional — but a region build still
    // validates the graph's own coverage below in `append_external_and_finish`.
    timer.switch("blob-kinds");
    let graph_kinds = pbf::load_blob_kinds(graph, input, &blobs);
    if let Some(b) = region.as_ref() {
        println!(
            "region filter: keeping roads/pois/buildings touching {:.3},{:.3} .. {:.3},{:.3}",
            b.min_lon, b.min_lat, b.max_lon, b.max_lat,
        );
    }

    let ways_plan = plan_ways_spill(input, &blobs, &ways_path)?;
    timer.switch("pass1");
    let pass1 = run_pass1(input, &blobs, graph_kinds.as_deref(), &select, layers, &ways_path, ways_plan, &mut stats, timer)?;
    timer.switch("member-ways");
    let members = load_member_ways(input, &blobs, &pass1.blob_kinds, &pass1.relations)?;
    // --- budget gate (phase 1: real counts) ------------------------------------------
    //
    // The planet build died at the junction tail with `cannot commit anon segment` (OS error
    // 1455): anonymous spills charge RAM+pagefile ("commit"), and nothing checked the charge
    // against the limit before asking. Pass 1 measured the real counts (classified ways and
    // relations, total refs, max ref), the ways spill sealed at an exact byte length, and the
    // graph files stat to theirs -- so the feature spill's backend is decided HERE, from those
    // counts, failing fast with the numbers when neither backend fits. Same records either way;
    // the backend never changes a hash.
    timer.switch("spill-budget");
    let spill_plan = plan_feature_spill(&pass1, &members, graph, spill_path)?;
    // Created before the fused node pass below, which spills `places`/`poi` label nodes straight
    // into it. This sits where pass 4's `Sink::create` used to, moved earlier so those labels reach
    // the sink in the same order -- chunk order, ahead of every way and relation -- they did when
    // classification was its own pass after the coordinate resolve. Nothing else touches
    // `spill_path` until then; the lane-fill temps hang off `with_extension`.
    let mut sink = Sink::create_planned(spill_path, spill_plan)?;
    // Take pass 1 apart so each piece can be freed the moment its last reader is done. `pass1`
    // itself is consumed here: nothing below sees it whole, which is what makes a use-after-free
    // a compile error rather than a peak.
    let Pass1Out {
        relations,
        promoted,
        blob_kinds,
        ways: _,
        way_refs,
        way_max_ref,
        ways_anon,
        way_blocks,
        region_label_nodes,
        mut needed,
    } = pass1;
    // The ways spill, shared by refcount with the readers below (lanefill
    // scan, materialise). Last use is `materialise_ways`; freed there.
    timer.switch("refs-collect");
    // Member ways' refs join the fused bitset: the great majority carry no
    // tags of their own, so nothing else asked for them.
    for refs in members.values() {
        for &id in refs {
            needed.set_growable(id)?;
        }
    }
    let table = build_resolved_table(
        input,
        &blobs,
        &blob_kinds,
        &members,
        needed,
        &select,
        layers,
        region.as_ref(),
        &mut sink,
        &mut stats,
        timer,
    )?;
    // The node pass was the last reader of the blob-kinds mask. Freed before lanefill so the
    // mask (one byte per blob, small) does not pin anything beside it -- and, more importantly,
    // so the next large allocation cannot sit beside a dead one.
    drop(blob_kinds);
    timer.switch("lane-inherit");
    let way_blocks = WayBlocks::open(&ways_path, &ways_anon, way_blocks)?;
    let inherited_lanes = inherit_lane_counts(
        spill_path,
        &way_blocks,
        &table,
        stats.ways_classified as usize,
        region.as_ref(),
    )?;
    stats.lanes_inherited = inherited_lanes.len() as u64;

    // --- materialise ----------------------------------------------------------------------
    //
    // Ways in **id order**, which is the order the spill file is already in. The label nodes were
    // already spilled into `sink` during the fused node pass above, so this only *adds* ways and
    // relations after them -- the order the tiler groups by layer id from.
    timer.switch("materialise-ways");
    materialise_ways(
        &ways_path,
        way_blocks,
        &promoted,
        &inherited_lanes,
        &table,
        region.as_ref(),
        &mut sink,
        &mut stats,
    )?;
    // The ways spill is dead from here: no later phase reads it. Dropping the `Arc`s unmaps the
    // anon segments, which releases pagefile commit immediately (unlike heap frees, which the
    // allocator may hold). This is the tens-of-GB ways spill, freed BEFORE the routing graph
    // is loaded below -- the two must never peak together.
    drop(ways_anon);
    drop(promoted);
    drop(inherited_lanes);
    timer.switch("materialise-relations");
    let conventions = materialise_relations(
        &relations,
        &members,
        &table,
        region.as_ref(),
        &mut sink,
        &mut stats,
    )?;
    // Country headcounts for label zoom scoring (see `extract_fallback::country_zoom`):
    // relation id (tagged) -> `population` tag. Node/way countries have no relation to
    // read it from and score as unpopulated; the area term still separates Russia from
    // Nauru. Built BEFORE `relations` is dropped below (it dies at "OSM tables freed").
    let country_populations: HashMap<u64, u64> = relations
        .iter()
        .filter_map(|r| {
            r.population.and_then(|pop| {
                (r.class.layer == tilecodec::mamaps::dict::LAYER_PLACES)
                    .then(|| (tagged_id(r.id, ELEMENT_RELATION), pop))
            })
        })
        .collect();
    // The OSM tables are dead from here: relations, member refs and the coordinate table serve
    // only materialisation. Dropping them before the externals (coastline, transit, graph)
    // keeps the RankIndex bitset (~1.5 GB on planet), the resolved bitset and the mapped locs
    // file out of the junction phase, where `Graph::load` needs its own gigabytes.
    drop(relations);
    drop(members);
    drop(table);
    timer.switch("land-polygons");
    let store = append_external_and_finish(
        coastline,
        transit_routes,
        graph,
        sink,
        &mut stats,
        conventions,
        spill_path,
        timer,
    )?;
    // The place-label -> boundary link, tagged into the id space the archive uses (a place carries
    // its node id tagged as a node; the mask keys on the boundary's relation id tagged as a
    // relation). The tiler stamps each `places` label with its linked id from this.
    let region_links: HashMap<u64, u64> = region_label_nodes
        .iter()
        .map(|(&node, &rel)| (tagged_id(node, ELEMENT_NODE), tagged_id(rel, ELEMENT_RELATION)))
        .collect();
    Ok((store, stats, region_links, country_populations))
}

/// Spill bytes per classified feature, calibrated from measured builds.
///
/// California (10.03 GB peak): ~15.5 M features spilled ~2.0 GB => ~135 B/feature raw. The record
/// is a varint stream (delta-coded refs were in the *ways* spill, not here; here it is e7
/// coordinates plus a packed class plus optional names/ids/lane data), so this varies by layer
/// mix -- but the gate only needs order-of-magnitude, and over-estimating pushes to files, which
/// is the safe side.
///
/// Stored compressed: the spill stages per-chunk DEFLATE frames (~3x on coordinate runs), so the
/// file holds roughly a third of the raw bytes. 96 B lands between the compressed California
/// figure (~45 B) and the raw one, leaning the safe way; the gate's job is only to pick the
/// backend, and files are the safe side.
const SPILL_BYTES_PER_FEATURE: u64 = 96;

/// Budget gate, phase 0: which backend the *ways* spill stages in.
///
/// Runs BEFORE pass 1, so no classified counts exist yet -- only the input size, the blob count
/// and the commit/disk limits. The ways spill holds one delta-coded record per classified way
/// (tens of bytes each: a varint id gap, a packed class, delta-coded refs at ~1 B each), and on
/// planet that is 1.1 B ways => tens of GB. The estimate is deliberately crude (input bytes / 32,
/// bounded below by 1 GB for planet-scale inputs): over-estimating files a build that would have
/// fit anon, under-estimating pages it. Files are the safe side -- same records, removed after
/// materialise -- so the bound leans that way past 8 GB of input.
fn plan_ways_spill(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    ways_path: &Path,
) -> Result<osm_ingest::mem::SpillPlan> {
    let input_bytes = std::fs::metadata(input)
        .map(|m| m.len())
        .unwrap_or(0);
    // No gate on small inputs: the historical anon path, no behaviour change.
    if input_bytes < (8u64 << 30) {
        return Ok(osm_ingest::mem::SpillPlan::Anon);
    }
    let estimate = (input_bytes / 32).max(1 << 30);
    let mut budget = osm_ingest::mem::StageBudget::default();
    budget.ways_spill_bytes = estimate;
    // Phase 0 knows nothing else yet; the rest is zero, so `anon_commit_bytes` is just this.
    let context = format!(
        "stage A ways spill (pre-pass estimate {} from {} input over {} blobs)",
        osm_ingest::mem::fmt_gb(estimate),
        osm_ingest::mem::fmt_gb(input_bytes),
        blobs.len(),
    );
    osm_ingest::mem::SpillPlan::decide(&budget, ways_path, &context)
        .map_err(osm_ingest::proto::Error)
}

/// Budget gate, phase 1: which backend the *feature* spill stages in.
///
/// Runs AFTER pass 1, so every count is real: classified ways/relations, total refs, max ref, the
/// sealed ways-spill length, member-way refs. The feature estimate is `ways + relations +
/// externals` times [`SPILL_BYTES_PER_FEATURE`]; the graph sizes from `metadata.bin`'s
/// 40-byte header before anything is mapped. Relations move the needle less (8.4 M vs 1.1 B ways
/// on planet) and are counted at struct overhead -- their member refs are already inside the
/// distinct-node estimate via the bitset walk.
///
/// `ways`, not refs: `Pass1Out::way_refs` is the ref *total* (duplicates included, ~12 G on
/// planet), which would size the spill 10x over and refuse, on estimates alone, builds whose
/// real spill fits. The externals headroom leans the safe way instead: the junction connectors
/// (~746 M on planet) arrive after this gate, so half the way count stands in for them plus
/// land/transit, pushing continent-scale builds to files (same records, removed on success).
fn plan_feature_spill(
    pass1: &Pass1Out,
    members: &MemberWays,
    graph: &Path,
    spill_path: &Path,
) -> Result<osm_ingest::mem::SpillPlan> {
    let member_refs: u64 = members.values().map(|refs| refs.len() as u64).sum();
    let refs_total = pass1.way_refs.saturating_add(member_refs);
    // Distinct nodes <= total refs; the collector dedups, so size the table on the upper bound.
    // `max_node_id` bounds the rank bitset; `distinct` (upper-bounded here) sizes the resolved
    // bit and the locs file.
    let mut budget = osm_ingest::mem::StageBudget::default();
    budget.distinct_nodes = refs_total;
    budget.max_node_id = pass1.way_max_ref.max(0) as u64;
    budget.ways_spill_bytes = pass1
        .ways_anon
        .as_ref()
        .map(|a| a.len())
        .unwrap_or_else(|| std::fs::metadata(spill_path.with_extension("ways.tmp")).map(|m| m.len()).unwrap_or(0));
    // Features to come: every classified way and relation materialises at most one feature, plus
    // the externals below (the junction connectors dominate: ~746 M on planet, arriving after
    // this gate, hence the ways/2 headroom on the safe side).
    budget.features_expected = pass1
        .ways
        .saturating_add(pass1.relations.len() as u64)
        .saturating_add(pass1.ways / 2)
        .saturating_add(2_000_000);
    budget.bytes_per_feature = SPILL_BYTES_PER_FEATURE;
    budget.relations = pass1.relations.len() as u64;
    budget.graph_bytes = graph_mapped_bytes(graph).unwrap_or(0);
    let context = format!(
        "stage A feature spill ({} ways, {} relations, {} refs, max node {})",
        pass1.ways, pass1.relations.len(), refs_total, pass1.way_max_ref,
    );
    // Warn-and-file rather than fail: the counts above are order-of-magnitude (the feature
    // estimate most of all), and refusing on them alone would abort builds whose real spill
    // fits. Files are the safe side -- same records, removed on success -- and a volume that
    // truly is too small still fails naturally, with its path in the error.
    match osm_ingest::mem::SpillPlan::decide(&budget, spill_path, &context) {
        Ok(plan) => Ok(plan),
        Err(e) => {
            eprintln!("WARN: {e}; continuing with file staging");
            Ok(osm_ingest::mem::SpillPlan::File)
        }
    }
}

/// Bytes the junction phase must address at once: the three mapped graph files plus `lanes.bin`,
/// plus the `InEdges` reverse index (`4 * (node_count + 1)` start array + 4 per drivable edge,
/// upper-bounded by 4 per edge). Sized from `metadata.bin`'s 40-byte header -- the same header
/// `check_graph_dir` already validates -- before anything is mapped.
fn graph_mapped_bytes(dir: &Path) -> Option<u64> {
    let meta = std::fs::read(dir.join("metadata.bin")).ok()?;
    if meta.len() < 40 {
        return None;
    }
    let u64_at = |at: usize| {
        u64::from_le_bytes([
            meta[at], meta[at + 1], meta[at + 2], meta[at + 3],
            meta[at + 4], meta[at + 5], meta[at + 6], meta[at + 7],
        ])
    };
    let node_count = u64_at(8);
    let edge_count = u64_at(16);
    let nodes = (node_count.saturating_add(1)).saturating_mul(12);
    let edges = std::fs::metadata(dir.join("edges.bin")).map(|m| m.len()).unwrap_or(edge_count.saturating_mul(7));
    let inter = std::fs::metadata(dir.join("intermediate.bin")).map(|m| m.len()).unwrap_or(0);
    let lanes = std::fs::metadata(dir.join("lanes.bin")).map(|m| m.len()).unwrap_or(0);
    let in_edges = (node_count.saturating_add(1)).saturating_mul(4).saturating_add(edge_count.saturating_mul(4));
    Some(nodes.saturating_add(edges).saturating_add(inter).saturating_add(lanes).saturating_add(in_edges))
}
/// What pass 1 hands the later phases: relations stay resident, corridors
/// collapse to (way id, zoom) overrides, and the ways spill stages on whichever backend the
/// budget gate chose, shared by refcount with every later reader (`Some`) or read back from
/// the file at `ways_path` (`None`).
struct Pass1Out {
    relations: Vec<Relation>,
    promoted: Vec<(i64, u8)>,
    blob_kinds: Vec<u8>,
    /// Classified way count (one materialised feature each, at most). Distinct from
    /// `way_refs` below: the ref total is ~10x the way count and must not size a feature
    /// estimate.
    ways: u64,
    /// Node ref total, duplicates included -- a capacity for the collector, NOT a feature
    /// count. Planet's ~12 G refs must never be multiplied by bytes-per-feature.
    way_refs: u64,
    way_max_ref: i64,
    /// The sealed ways spill, shared by refcount with every later reader; `None` on the file
    /// plan, where readers open `ways_path` instead.
    ways_anon: Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    /// The block index over the ways spill (either backend), for the parallel readers.
    way_blocks: Vec<WayBlockLoc>,
    /// `admin_centre`/`label` node member -> its boundary relation id, both raw OSM ids, for every
    /// relation that carries a region shape. The authoritative half of the place->boundary link:
    /// OSM points a boundary relation at its own label node directly, so no geometry test is needed.
    /// `label` wins over `admin_centre` when a relation lists both, since `label` is the place node
    /// the map actually renders.
    region_label_nodes: HashMap<i64, i64>,
    /// The needed-node bitset, collected during the drain above: every
    /// classified way's refs plus (set by the caller) the member ways' refs.
    /// Pass 3 resolves exactly these ids, without re-reading the ways spill.
    needed: NeededBits,
}

/// Pass 1 (ways + relations) plus the corridor promotion. Moved whole from
/// `extract` so part files can cut at fn boundaries; behavior identical.
///
/// `blob_kinds_in` is an optional pre-computed mask (the graph sidecar) that lets this pass skip
/// node blobs; `None` means scan the whole file. Either way the mask it returns is complete.
///
/// `spill_plan` is the budget gate's backend choice: the ways spill stages anonymously or through
/// the `.tmp` at `ways_path`. Decided by the caller from pass-0 counts, not here, because this
/// pass cannot know the graph size that shares its peak.
#[allow(clippy::too_many_arguments)]
fn run_pass1(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds_in: Option<&[u8]>,
    select: &Select,
    layers: Layers,
    ways_path: &Path,
    spill_plan: osm_ingest::mem::SpillPlan,
    stats: &mut Stats,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Pass1Out> {
    // --- pass 1: ways and relations -------------------------------------------------------
    //
    // `run_pass_sink` rather than `run_pass`, because `run_pass`'s contract is to hand back every
    // chunk at once and these chunks hold the node refs of every classified way — the 2.7 GB this
    // spill exists to be rid of. Here a chunk is appended to the file and freed while its
    // neighbours are still decoding.
    //
    // `blob_kinds` comes back from this pass and lets the later ones skip whole blobs, which on a
    // planet extract is most of the file.
    let mut ways = WaySink::create_planned(&ways_path, spill_plan)?;
    let mut relations: Vec<Relation> = Vec::new();
    // The needed-node bitset, fused into this pass: every classified way sets
    // its refs here in the drain below, so pass 3 never re-reads the ways
    // spill to collect them. Grows on demand (the maximum is not known until
    // the last push); member refs join after the drain.
    let mut needed = NeededBits::new_growable();
    // `admin_centre`/`label` node member -> boundary relation id (raw), gathered as region shapes
    // are classified below. See [`Pass1Out::region_label_nodes`].
    let mut region_label_nodes: HashMap<i64, i64> = HashMap::new();
    // Road ways carrying a `ref`, for the corridor pass below. A small minority of ways,
    // and the only thing pass 1 keeps in memory besides the relations.
    let mut corridors: Vec<crate::corridor::Segment> = Vec::new();
    let blob_kinds = pbf::run_pass_sink(
        input,
        &blobs,
        blob_kinds_in,
        KIND_WAYS | KIND_RELATIONS,
        "Pass 1: ways and relations",
        || {
            (
                Vec::<WayBlock>::new(),
                Vec::<Relation>::new(),
                Vec::<crate::corridor::Segment>::new(),
                Vec::<(i64, i64)>::new(),
                Vec::<i64>::new(),
            )
        },
        |state, block| {
            let mut kinds = 0u8;
            // This block's refs land past here; sorted and deduped below, so the drain sets
            // the needed bitset in ascending runs instead of chasing every ref.
            let refs_from = state.4.len();
            visit_block(block, KIND_WAYS | KIND_RELATIONS, &mut kinds, &mut |el| {
                match el {
                    Element::Way(way) => {
                        if !select.matches(|k| way.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&way.tags, true, layers) {
                            // A numbered road's `min_zoom` is decided per corridor, not per
                            // way, so that a route does not vanish where its class changes.
                            // Slip roads are left out: they carry the parent's `ref` and
                            // would be promoted into junction stubs at world zoom.
                            if class.layer == tilecodec::mamaps::dict::LAYER_ROADS
                                && class.flags & tilecodec::mamaps::body::FLAG_IS_LINK == 0
                                && way.refs.len() >= 2
                            {
                                if let Some(route) = crate::corridor::route_key(&way.tags) {
                                    state.2.push(crate::corridor::Segment {
                                        way_id: way.id,
                                        route,
                                        first_node: way.refs[0],
                                        last_node: way.refs[way.refs.len() - 1],
                                        min_zoom: class.min_zoom,
                                    });
                                }
                            }
                            let name = schema::display_name(&way.tags, class.layer);
                            // A road's carriageway lane count, per-lane turn masks and directional
                            // split, baked so the renderer can draw the lanes individually, place
                            // turn arrows and put the centre line where the traffic divides. Zero,
                            // empty and default for every other layer.
                            let is_road =
                                class.layer == tilecodec::mamaps::dict::LAYER_ROADS;
                            let lane_count = if is_road {
                                schema::roads::lane_count(&way.tags)
                            } else {
                                0
                            };
                            let (turn_fwd, turn_bwd) = if is_road {
                                schema::roads::turn_masks(&way.tags)
                            } else {
                                (Vec::new(), Vec::new())
                            };
                            let carriageway = if is_road {
                                schema::roads::carriageway(&way.tags)
                            } else {
                                Carriageway::default()
                            };
                            // A building's S3DB attributes, parsed once here beside its class.
                            // Zero-cost for the overwhelming majority of ways, which are not
                            // buildings.
                            let building = if class.layer == LAYER_BUILDINGS {
                                Some(schema::buildings::attrs(&way.tags))
                            } else {
                                None
                            };
                            // Encoded HERE, on the worker, into the chunk's ways-spill blocks: the
                            // drain only patches each block's first id and appends it. This was
                            // the serial drain's record-by-record encode, which held pass 1 to ~4
                            // of 192 cores on the planet (4m40s). Same bytes; see `WayBlock`.
                            if state.0.last().is_none_or(|b| b.len() >= WAY_BLOCK_WAYS) {
                                state.0.push(WayBlock::new());
                            }
                            let refs: &[i64] = &way.refs[..];
                            state.0.last_mut().expect("pushed above").push(
                                way.id,
                                &class,
                                refs,
                                name.as_deref(),
                                lane_count,
                                &turn_fwd,
                                &turn_bwd,
                                carriageway,
                                building,
                            )?;
                            state.4.extend_from_slice(refs);
                        }
                    }
                    Element::Relation(relation) => {
                        // Two relation types carry a shape. Anything else — a site, a
                        // public_transport — does not.
                        if !matches!(
                            relation.tags.get_str("type"),
                            Some("multipolygon" | "boundary")
                        ) {
                            return Ok(());
                        }
                        if !select.matches(|k| relation.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&relation.tags, false, layers) {
                            let members: Vec<_> = relation
                                .members
                                .iter()
                                .filter(|m| m.kind == MEMBER_WAY)
                                .map(|m| (m.id, m.role == b"inner"))
                                .collect();
                            // **The schema decides**, not the relation's `type` tag. Both an
                            // administrative border and a protected area are `type=boundary`, and
                            // one is a line while the other is a shape — which is exactly the
                            // distinction `Class::area` exists to make.
                            let area = class.area;
                            let name = schema::display_name(&relation.tags, class.layer);
                            // A multipolygon building (a footprint with a courtyard) carries S3DB
                            // attributes just as a building way does.
                            let building = if class.layer == LAYER_BUILDINGS {
                                Some(schema::buildings::attrs(&relation.tags))
                            } else {
                                None
                            };
                            // A country's code, kept so the region shape below can stamp its
                            // driving side and centre-line colour onto the tiles it covers.
                            let iso = schema::boundaries::country_code(&relation.tags)
                                .map(str::to_string);
                            // An administrative relation yields *two* features: the border, which
                            // is a line and is what the basemap draws, and the region's shape,
                            // which nothing draws and the region mask reads. They cannot be one
                            // feature — a clipped polygon grows tile-edge segments and the border
                            // layer strokes them into a grid. See `schema::boundaries`.
                            if let Some(shape) =
                                schema::boundaries::region_area(&relation.tags, false)
                            {
                                // The boundary points at its own label/centre node directly: record
                                // that node -> this relation so the place label it becomes can carry
                                // the id. `label` overwrites `admin_centre` (the label node is the
                                // one that renders; admin_centre is the capital, a different node).
                                for m in relation.members.iter() {
                                    if m.kind == MEMBER_NODE && m.role == b"admin_centre" {
                                        state.3.push((m.id, relation.id));
                                    }
                                }
                                for m in relation.members.iter() {
                                    if m.kind == MEMBER_NODE && m.role == b"label" {
                                        state.3.push((m.id, relation.id));
                                    }
                                }
                                state.1.push(Relation {
                                    class: shape,
                                    members: members.clone(),
                                    area: true,
                                    name: name.clone(),
                                    id: relation.id,
                                    building: None,
                                    population: None,
                                    iso: iso.clone(),
                                });
                            }
                            state.1.push(Relation {
                                class,
                                members,
                                area,
                                name,
                                id: relation.id,
                                building,
                                population: Some(schema::places::population_of(&relation.tags)),
                                iso: None,
                            });
                        }
                    }
                    Element::Node(_) => {}
                }
                Ok(())
            })?;
            sort_dedup_tail(&mut state.4, refs_from);
            Ok(kinds)
        },
        |(chunk_ways, chunk_relations, chunk_corridors, chunk_label_nodes, chunk_refs)| {
            // Chunks arrive in file order and a PBF's ways are sorted by id, so appending here
            // leaves the file in ascending id order. `WaySink::push_block` refuses an id that does
            // not advance rather than letting an unsorted file reorder the archive silently.
            for block in &chunk_ways {
                ways.push_block(block)?;
            }
            // Fused ref collection: the refs were decoded on the worker, so the bitset takes
            // them now instead of a later pass re-reading the spill to find them again. A bitset
            // is order- and duplicate-blind, so the per-block sort/dedup changes no bit.
            for &node in &chunk_refs {
                needed.set_growable(node)?;
            }
            relations.extend(chunk_relations);
            corridors.extend(chunk_corridors);
            // `label` was pushed after `admin_centre`, so inserting in order lets the label win.
            for (node_id, rel_id) in chunk_label_nodes {
                region_label_nodes.insert(node_id, rel_id);
            }
            Ok(())
        },
    )?;
    let (counts, store) = ways.finish_either()?;
    stats.ways_classified = counts.ways;
    let (ways, way_refs, way_max_ref, ways_anon, way_blocks) =
        (counts.ways, counts.refs, counts.max_ref, store.map(std::sync::Arc::new), counts.blocks);
    stats.relations_classified = relations.len() as u64;

    // A numbered road changes class along its length, so deciding `min_zoom` per way chops
    // a corridor into stubs at the zooms where only its motorway parts survive. Promote
    // each connected same-`ref` run to the shallowest zoom any of its ways asks for, then
    // drop the segments: only the (way id, zoom) overrides are needed from here.
    timer.switch("corridors");
    let promoted = crate::corridor::promote(&corridors);
    stats.corridor_promotions = promoted.len() as u64;
    drop(corridors);

    Ok(Pass1Out {
        relations,
        promoted,
        blob_kinds,
        ways,
        way_refs,
        way_max_ref,
        ways_anon,
        way_blocks,
        region_label_nodes,
        needed,
    })
}
/// Sort and dedup `v[from..]` in place, leaving `v[..from]` untouched.
fn sort_dedup_tail(v: &mut Vec<i64>, from: usize) {
    v[from..].sort_unstable();
    let mut write = from;
    for read in from..v.len() {
        if write == from || v[read] != v[write - 1] {
            v[write] = v[read];
            write += 1;
        }
    }
    v.truncate(write);
}
