fn encode_batch(
    batch: Vec<(u64, Vec<ChunkEntry>)>,
    dem: &crate::dem::Dem,
    conventions: &crate::schema::boundaries::Conventions,
    region_links: &std::collections::HashMap<u64, u64>,
) -> Result<Vec<Encoded>> {
    // Split the batch into tasks by cumulative geometry cost rather than by tile count. A dense
    // city tile is orders of magnitude more work than a rural one, and `with_min_len` — which forces
    // contiguous runs of a fixed *count* — cannot give that tile a task of its own: it stays welded
    // to its cheap neighbours while the rest of the pool drains. Grouping by cost lets the biggest
    // tiles fall into their own tasks. Order is untouched: the groups are contiguous slices of the
    // batch in order, each group is encoded in order, and the per-group results are flattened in
    // group order — so `collect`'s positional guarantee still lands every tile where it was, and the
    // archive is byte for byte the same.
    let groups = partition_by_cost(batch, par::encode_task_count());
    par::install(|| {
        let grouped: Result<Vec<Vec<Encoded>>> = groups
            .into_par_iter()
            // One DEFLATE state and one serialisation buffer per worker, not one per tile.
            // `compress_to_vec` builds a 65,712-byte `CompressorOxide` every call, and at a tile
            // each across 64 cores that construction swamps the compression it exists to do -- see
            // `mamaps::write::compress_body_with`. The `Scratch` is there for the same reason on
            // the serialisation side: its allocations, not its encoding, were what made
            // serialisation cost 20.9 s of CPU at 16 threads and 1120 s at 64. Reused across every
            // tile in a group, not merely once per task.
            .map_init(
                || (tilecodec::gz::Compressor::new(), tilecodec::mamaps::body::Scratch::default()),
                |(deflate, scratch), group| {
                    group
                        .into_iter()
                        .map(|(id, layers)| {
                            encode_tile(id, layers, dem, conventions, region_links, deflate, scratch)
                        })
                        .collect::<Result<Vec<Encoded>>>()
                },
            )
            .collect();
        Ok(grouped?.into_iter().flatten().collect())
    })
}

/// Coalesce, stage C, serialise and DEFLATE one merged tile, reusing the worker's compressor and
/// serialisation scratch.
///
/// Pulled out of [`encode_batch`] so the batch can be split into cost-balanced groups and this run
/// per tile inside each group; the steps and their order are unchanged, so the encoded bytes are
/// too. A tile whose layers all empty out under coalescing and stage C comes back as
/// `(id, None, ..)` rather than being dropped, because its stage C and coalescing counters still
/// belong in the build report.
fn encode_tile(
    id: u64,
    mut layers: Vec<ChunkEntry>,
    dem: &crate::dem::Dem,
    conventions: &crate::schema::boundaries::Conventions,
    region_links: &std::collections::HashMap<u64, u64>,
    deflate: &mut tilecodec::gz::Compressor,
    scratch: &mut tilecodec::mamaps::body::Scratch,
) -> Result<Encoded> {
    // **Coalesce first.** An OSM way is an editing unit, not a rendering one, so a road arrives as
    // hundreds of two-point fragments each carrying its own 28 bytes of header -- see
    // `crate::coalesce`. Merging them before stage C is also what keeps stage C off tens of
    // thousands of features it would only copy through.
    let mut lines = crate::coalesce::Stats::default();
    for entry in &mut layers {
        // The traffic layer is one feature per component segment, each carrying a distinct
        // component_id in the id table. Coalescing merges a class's lines into one feature, which
        // would collapse every segment's id onto whichever came first — so it is skipped, keeping
        // the layer one-to-one with its ids.
        //
        // The junction layer is skipped for the neighbouring reason: it carries no ids, but each
        // feature is one lane's connector through an intersection and they all share a class, so
        // coalescing would chain unrelated connectors — a left turn and the through movement beside
        // it — into one polyline and draw a ribbon between them.
        if entry.layer.layer_id == tilecodec::mamaps::dict::LAYER_TRAFFIC
            || entry.layer.layer_id == tilecodec::mamaps::dict::LAYER_JUNCTION
        {
            continue;
        }
        lines.add(crate::coalesce::coalesce_lines_with_ids(
            &mut entry.layer,
            Some(&mut entry.ids),
            Some(&mut entry.turn_lanes),
            Some(&mut entry.buildings),
            Some(&mut entry.carriageways),
        ));
    }
    // **Stage C**, per tile: winding normalised, hole containment resolved, degenerate rings
    // dropped. Once, here, in `f64` with no frame budget, instead of every frame on device in `i32`
    // under one. This is what makes `FLAG_RINGS_VALIDATED` true.
    let mut rings = crate::rings::Stats::default();
    for entry in &mut layers {
        rings.add(crate::rings::normalise_with_ids(
            &mut entry.layer,
            Some(&mut entry.ids),
            Some(&mut entry.turn_lanes),
            Some(&mut entry.buildings),
        ));
    }
    // A layer that ended up empty - every feature in it fell below the minimum area after
    // clipping, or lost its exterior to stage C - costs bytes in the archive and a draw call on
    // device for nothing.
    layers.retain(|entry| !entry.layer.features.is_empty());
    if layers.is_empty() {
        return Ok((id, None, rings, lines));
    }
    // Phase B: a full-bleed single-water-polygon tile is water, not a tile. One
    // water polygon covering the whole tile draws exactly what the style's water
    // background already paints, so storing it buys a body, an index entry and a
    // fetch for zero pixels. `None` (not stored) like an emptied tile, and the
    // reader overzooms the parent whose background shows through.
    if is_water_background(&layers) {
        return Ok((id, None, rings, lines));
    }
    // What the `u16` feature index in the body format has to hold. Sampled here because this is the
    // shape that reaches the encoder: after coalescing merged the line fragments and after stage C
    // dropped what it drops.
    //
    // Read before the read-modify-write: the slot almost never grows, and an unconditional
    // `fetch_max` from every encode thread on every tile kept these few cache lines bouncing
    // across 192 cores on zooms of millions of small tiles. Same maximum either way.
    for entry in &layers {
        let slot = &WIDEST_LAYER[entry.layer.layer_id as usize];
        let n = entry.layer.features.len() as u64;
        if slot.load(Ordering::Relaxed) < n {
            slot.fetch_max(n, Ordering::Relaxed);
        }
    }
    // Fuse the layers' name tables into the body's one, in layer order, remapping every feature's
    // index as it goes. First-use order is deterministic (merge order within a layer, layer order
    // across them), so the same tile always carries the same table. A probe-only `HashMap` keeps the
    // fusion linear rather than quadratic in the tile's name count; it is never iterated, and a name
    // is cloned only the first time it is seen, so the table's order and bytes are exactly what a
    // linear `position` scan produced.
    let mut names: Vec<String> = Vec::new();
    let mut name_index: HashMap<String, u16> = HashMap::new();
    for entry in &mut layers {
        for feature in &mut entry.layer.features {
            if feature.name_idx != tilecodec::mamaps::body::NAME_NONE {
                let name = &entry.names[feature.name_idx as usize - 1];
                feature.name_idx = match name_index.get(name.as_str()) {
                    Some(&at) => at,
                    None => {
                        let idx = names.len() as u16 + 1;
                        names.push(name.clone());
                        name_index.insert(name.clone(), idx);
                        idx
                    }
                };
            }
        }
    }
    // Fuse the layers' id tables into the body's one. Unlike names these are values rather than
    // indices, so nothing is remapped and nothing is deduplicated — the table is simply the
    // surviving `places`/`poi` layers' vectors, in layer order, which `Merged` already delivers
    // ascending.
    //
    // Checked against the feature count rather than assumed. `coalesce_lines` rebuilds a layer's
    // features and `rings::normalise` retains over them, and both now rewrite the id table
    // alongside. That is easy to get wrong and silent when it is - every id after the first casualty
    // would describe the wrong feature - so this fails the build instead.
    let mut ids: Vec<(u8, Vec<u64>)> = Vec::new();
    for entry in &mut layers {
        if entry.ids.is_empty() {
            continue;
        }
        if entry.ids.len() != entry.layer.features.len() {
            return err(format!(
                "layer {} has {} id(s) for {} feature(s) after coalesce and \
                 stage C",
                entry.layer.layer_id,
                entry.ids.len(),
                entry.layer.features.len(),
            ));
        }
        ids.push((entry.layer.layer_id, std::mem::take(&mut entry.ids)));
    }
    // The region-link table: for each `places` label, the tagged relation id of the boundary it
    // names, looked up from stage A's link map by the label's own (tagged node) id — which is
    // exactly what the id table above holds. Dense-parallel to the layer's features like the id
    // table, with REGION_NONE where a label links to no region. Emitted only when at least one
    // label in the tile links somewhere, so a tile of unlinked labels (or no places at all) carries
    // no table.
    let mut region_links_table: Vec<(u8, Vec<u64>)> = Vec::new();
    if !region_links.is_empty() {
        for (layer_id, node_ids) in &ids {
            if *layer_id != tilecodec::mamaps::dict::LAYER_PLACES {
                continue;
            }
            let links: Vec<u64> = node_ids
                .iter()
                .map(|id| {
                    region_links
                        .get(id)
                        .copied()
                        .unwrap_or(tilecodec::mamaps::body::REGION_NONE)
                })
                .collect();
            if links.iter().any(|&l| l != tilecodec::mamaps::body::REGION_NONE) {
                region_links_table.push((*layer_id, links));
            }
        }
    }
    // The turn-lane table, built the same way as the id table and checked against the feature count
    // for the same reason: coalesce and stage C rebuild a layer's features and rewrite this
    // alongside, and a drift would misattribute every lane after the first. Only emitted when some
    // road actually has turn masks, so a tile with none carries no table at all.
    let mut turn_lanes: Vec<(u8, Vec<tilecodec::mamaps::body::LaneTurns>)> = Vec::new();
    for entry in &mut layers {
        if entry.turn_lanes.is_empty() {
            continue;
        }
        if entry.turn_lanes.len() != entry.layer.features.len() {
            return err(format!(
                "layer {} has {} turn record(s) for {} feature(s) after coalesce \
                 and stage C",
                entry.layer.layer_id,
                entry.turn_lanes.len(),
                entry.layer.features.len(),
            ));
        }
        if entry.turn_lanes.iter().all(|t| t.is_empty()) {
            entry.turn_lanes.clear();
            continue;
        }
        turn_lanes.push((entry.layer.layer_id, std::mem::take(&mut entry.turn_lanes)));
    }
    // The building side table, built the same way and checked against the feature count for the same
    // reason. A tile whose buildings all carried default attrs (no S3DB tags) emits no table at all
    // — the renderer falls back to its own default height and colour — so the table is paid for only
    // where 3D data exists.
    let mut buildings: Vec<(u8, Vec<BuildingAttrs>)> = Vec::new();
    for entry in &mut layers {
        if entry.buildings.is_empty() {
            continue;
        }
        if entry.buildings.len() != entry.layer.features.len() {
            return err(format!(
                "layer {} has {} building record(s) for {} feature(s) after \
                 coalesce and stage C",
                entry.layer.layer_id,
                entry.buildings.len(),
                entry.layer.features.len(),
            ));
        }
        if entry.buildings.iter().all(|b| *b == BuildingAttrs::default()) {
            entry.buildings.clear();
            continue;
        }
        buildings.push((entry.layer.layer_id, std::mem::take(&mut entry.buildings)));
    }
    // The carriageway side table, built like the two above and checked against the feature count for
    // the same reason.
    //
    // A tile where no road's split was ever surveyed usually emits no table at all, so it costs
    // nothing for lane data that does not exist. The exception is a tile whose country is not the
    // default one: the convention only reaches the wire alongside a table, and a tile of untagged
    // residential streets in Tokyo still has to say that traffic keeps left. So an all-default table
    // is kept exactly when there is a non-default convention to carry with it.
    let (z, x, y) = tilecodec::pmtiles::tile_zxy(id);
    let convention = conventions.at_tile(z, x, y);
    let mut carriageways: Vec<(u8, Vec<tilecodec::mamaps::body::Carriageway>)> = Vec::new();
    for entry in &mut layers {
        if entry.carriageways.is_empty() {
            continue;
        }
        if entry.carriageways.len() != entry.layer.features.len() {
            return err(format!(
                "layer {} has {} carriageway record(s) for {} feature(s) after \
                 coalesce and stage C",
                entry.layer.layer_id,
                entry.carriageways.len(),
                entry.layer.features.len(),
            ));
        }
        if convention == tilecodec::mamaps::body::MarkingConvention::default()
            && entry.carriageways.iter().all(|c| c.is_empty())
        {
            entry.carriageways.clear();
            continue;
        }
        carriageways.push((entry.layer.layer_id, std::mem::take(&mut entry.carriageways)));
    }
    let convention = (!carriageways.is_empty()).then_some(convention);
    let body = Body {
        extent: EXTENT as u16,
        layers: layers.into_iter().map(|entry| entry.layer).collect(),
        names,
        ids,
        turn_lanes,
        buildings,
        // The DEM heightmap is produced by a separate ingest stage keyed by tile, not from OSM
        // geometry. Sample its grid onto this tile's z/x/y; a tile with no DEM under it (ocean,
        // off-coverage) gets None and stays 16-byte.
        heightmap: dem.heightmap_for(z, x, y),
        carriageways,
        convention,
        region_links: region_links_table,
    };
    // v10 narrowed records + sparse side tables are the default: same
    // geometry either way — only the fixed records shrink — so this
    // changes bytes, never shapes. `MAMAPS_V8=1` opts back out to the
    // dense v8 bodies (escape hatch for bisecting a suspect build).
    let encoded_body = if std::env::var("MAMAPS_V8").is_ok() {
        tilecodec::mamaps::body::serialize_into(&body, scratch)
    } else {
        tilecodec::mamaps::body::serialize_into_v10(&body, scratch)
    }?;
    let raw_len = encoded_body.len();
    let stored = tilecodec::mamaps::write::compress_body_with(deflate, encoded_body);
    // Compute the dedup key here, in the parallel worker, rather than on the serial append thread.
    // It is a pure function of the stored bytes, so the key — and every dedup decision the writer
    // makes from it — is unchanged; it just no longer walks the whole body downstream of this phase.
    let hash = tilecodec::mamaps::write::hash64(&stored);
    Ok((id, Some((stored, raw_len, hash)), rings, lines))
}

/// Split a merged batch into contiguous groups of roughly equal cumulative geometry cost.
///
/// `target_tasks` is the number of groups aimed for — a few per thread. Cost is a tile's feature
/// and coordinate count, which is what stage C, serialisation and DEFLATE all scale with; a tile
/// heavier than the per-group budget on its own becomes its own group, which is the whole point.
/// The groups are contiguous and in batch order, so flattening their encoded results reproduces the
/// batch's tile order exactly — the archive does not move.
fn partition_by_cost(
    batch: Vec<(u64, Vec<ChunkEntry>)>,
    target_tasks: usize,
) -> Vec<Vec<(u64, Vec<ChunkEntry>)>> {
    let total: u64 = batch.iter().map(|(_, layers)| tile_cost(layers)).sum();
    let target = (total / target_tasks.max(1) as u64).max(1);
    let mut groups: Vec<Vec<(u64, Vec<ChunkEntry>)>> = Vec::new();
    let mut current: Vec<(u64, Vec<ChunkEntry>)> = Vec::new();
    let mut current_cost = 0u64;
    for (id, layers) in batch {
        let cost = tile_cost(&layers);
        // Flush before adding a tile that would push a non-empty group past the budget, so a heavy
        // tile does not drag its cheap predecessors into an oversized task.
        if !current.is_empty() && current_cost + cost > target {
            groups.push(std::mem::take(&mut current));
            current_cost = 0;
        }
        current.push((id, layers));
        current_cost += cost;
        // A group at or over budget is closed; a single tile heavier than the budget closes here on
        // its own, which is the isolation `with_min_len`'s fixed run length could not provide.
        if current_cost >= target {
            groups.push(std::mem::take(&mut current));
            current_cost = 0;
        }
    }
    if !current.is_empty() {
        groups.push(current);
    }
    groups
}

/// A tile's encode cost: its features plus its coordinates, summed across layers. Both stage C and
/// the compressor scale with these, and it is the ratio between tiles that matters, not the unit.
fn tile_cost(layers: &[ChunkEntry]) -> u64 {
    layers
        .iter()
        .map(|entry| entry.layer.features.len() as u64 + entry.layer.coords.len() as u64)
        .sum()
}
