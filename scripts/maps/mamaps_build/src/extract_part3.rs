/// What one relation contributes, decided on the pool. See [`BuiltRelation`].
enum RelationOutcome {
    /// Region-filtered (a `poi` label outside the bbox, a building outside it): counts nothing.
    Skip,
    /// No geometry: counts `geometry_failed`.
    Failed,
    /// A finished spill record: counts a feature.
    Kept(PreRecord),
}

/// One relation's geometry and spill record, built off the shared tables with nothing mutable
/// touched, so relations fan out across the pool exactly as [`materialise_ways`]' blocks do. The
/// sink and the conventions grid are fed from these **in relation order** by the serial drain,
/// which is what keeps the archive byte-identical: the drain order is the spill order is the
/// archive's.
///
/// The record itself ([`PreRecord`]) is built here too -- props, header, geometry bytes -- so the
/// drain only appends; it used to encode every relation (and DEFLATE its frames) on the one
/// serial thread, between pool batches of 4096 that each waited on their slowest relation. On
/// the planet that phase ran 4 minutes at ~8 cores.
struct BuiltRelation {
    /// An area relation's ring stats, summed into [`Stats::rings`] whether or not the stitch
    /// produced anything -- four additive counts, so the order cannot show.
    rings: Option<RingStats>,
    outcome: Result<RelationOutcome>,
    /// A kept country shape's assembled rings, for `conventions.add` -- which has precedence rules
    /// (an interior claim outranks a border one) and so stays serial and in relation order.
    conventions: Option<Vec<rings::Polygon>>,
}

/// Build one relation's geometry and record. Reads only the shared `members` map and the
/// resolved `table`, both immutable, so it is safe to call across the pool. The decisions --
/// label point or member lines or stitched rings, region keep/drop, record shape -- are the ones
/// the serial drain used to make, in the same order.
fn build_relation(
    relation: &Relation,
    members: &MemberWays,
    table: &NodeLocations,
    region: Option<&osm_ingest::bbox::BBox>,
) -> BuiltRelation {
    // A `places` relation (a country, a region) is labelled at its centroid: one point, not a
    // stitched shape. The border itself lives in `boundaries`; this is the name.
    if is_label(relation.class.layer) {
        let line: Vec<(f64, f64)> = relation
            .members
            .iter()
            .filter_map(|(id, _)| members.get(*id))
            .flat_map(|refs| table.line(refs))
            .collect();
        let outcome = match centroid(&line) {
            None => Ok(RelationOutcome::Failed),
            // `poi` labels are region-filtered; `places` always kept. O(1) point test.
            Some(point)
                if relation.class.layer == tilecodec::mamaps::dict::LAYER_POI
                    && !point_touches(&point, region) =>
            {
                Ok(RelationOutcome::Skip)
            }
            Some(point) => crate::store::EncodedGeom::live(&Geometry::Points(vec![point]))
                .and_then(|encoded| {
                    PreRecord::named(
                        &relation.class,
                        &encoded,
                        relation.name.as_deref(),
                        tagged_id(relation.id, ELEMENT_RELATION),
                    )
                })
                .map(RelationOutcome::Kept),
        };
        return BuiltRelation { rings: None, outcome, conventions: None };
    }
    // A boundary relation's members are the border. Each is emitted as its own line rather than
    // stitched: the renderer strokes them, and a gap between two member ways is invisible in a
    // stroke while a failed stitch would drop the whole border.
    if !relation.area {
        let lines: Vec<Vec<(f64, f64)>> = relation
            .members
            .iter()
            .filter_map(|(id, _)| members.get(*id))
            .map(|refs| table.line(refs))
            .filter(|line| line.len() >= 2)
            .collect();
        // `empty` is the pre-encode shape test: an encoded empty still carries count bytes, and
        // an empty border counts `geometry_failed` rather than pushing an empty record.
        let empty = lines.is_empty();
        let outcome = crate::store::EncodedGeom::live(&Geometry::Lines(lines)).and_then(|encoded| {
            if empty {
                Ok(RelationOutcome::Failed)
            } else {
                PreRecord::named(&relation.class, &encoded, None, tilecodec::mamaps::body::ID_NONE)
                    .map(RelationOutcome::Kept)
            }
        });
        return BuiltRelation { rings: None, outcome, conventions: None };
    }
    let member_ways: Vec<MemberWay> = relation
        .members
        .iter()
        .filter_map(|(id, inner)| {
            Some(MemberWay { refs: members.get(*id)?.clone(), outer: !inner })
        })
        .collect();
    let mut ring_stats = RingStats::default();
    let polygons = rings::assemble(&member_ways, |id| locate(table, id), &mut ring_stats);
    // Buildings are region-filtered; every other area layer is always kept.
    let keep = relation.class.layer != tilecodec::mamaps::dict::LAYER_BUILDINGS
        || polygons_touch(&polygons, region);
    let empty = polygons.is_empty();
    let geometry = Geometry::Polygons(polygons);
    let encoded = crate::store::EncodedGeom::live(&geometry);
    let Geometry::Polygons(polygons) = geometry else {
        unreachable!("just built as Polygons");
    };
    let mut conventions = None;
    let outcome = encoded.and_then(|encoded| {
        if empty {
            return Ok(RelationOutcome::Failed);
        }
        if !keep {
            return Ok(RelationOutcome::Skip);
        }
        // A multipolygon building carries its S3DB attributes into the building side table, just
        // as a building way does.
        if let Some(building) = relation.building {
            return PreRecord::building(&relation.class, &encoded, None, building).map(RelationOutcome::Kept);
        }
        // The id is what lets the mask gather a region's tile-clipped pieces back together.
        let id = if tracks_ids(&relation.class) {
            tagged_id(relation.id, ELEMENT_RELATION)
        } else {
            tilecodec::mamaps::body::ID_NONE
        };
        let record = PreRecord::named(&relation.class, &encoded, None, id)?;
        // The country's own shape is also what says how its roads are marked: stamped by the
        // drain, from these assembled rings, so the grid and the border line agree.
        if relation.iso.is_some() {
            conventions = Some(polygons);
        }
        Ok(RelationOutcome::Kept(record))
    });
    BuiltRelation { rings: Some(ring_stats), outcome, conventions }
}

/// Relations per pool hand-off in [`materialise_relations`]. Large, because one country-sized
/// multipolygon can take seconds to stitch and a group's drain waits for its slowest relation;
/// with the next group computing while this one drains, that wait is mostly hidden.
const RELATIONS_PER_GROUP: usize = 32 * 1024;

/// Materialise relations; returns the driving-side conventions.
///
/// `region` filters the built layers (`roads`, `poi`, `buildings`) the same
/// way [`materialise_ways`] does. `boundaries` region shapes are always kept
/// — the driving-side grid must cover the world even in a region build, or
/// the tiler mis-marks roads at the region's edge.
fn materialise_relations(
    relations: &[Relation],
    members: &MemberWays,
    table: &NodeLocations,
    region: Option<&osm_ingest::bbox::BBox>,
    sink: &mut Sink,
    stats: &mut Stats,
) -> Result<schema::boundaries::Conventions> {
    let mut bar = Progress::new(
        "Materialise: relations".to_string(),
        relations.len(),
        "relation(s)",
        true,
    );
    // Which driving side and centre-line colour applies where, accumulated as the country shapes
    // go past.
    let mut conventions = schema::boundaries::Conventions::default();
    pipeline_ordered(
        relations.len(),
        RELATIONS_PER_GROUP,
        |i| build_relation(&relations[i], members, table, region),
        |i, built| {
            if let Some(r) = built.rings {
                stats.rings.outer_rings += r.outer_rings;
                stats.rings.inner_rings += r.inner_rings;
                stats.rings.unclosed += r.unclosed;
                stats.rings.orphan_inner += r.orphan_inner;
            }
            // `?` in spill order: the first failure in relation order, exactly as inline encoding
            // would report it.
            match built.outcome? {
                RelationOutcome::Skip => {}
                RelationOutcome::Failed => stats.geometry_failed += 1,
                RelationOutcome::Kept(record) => {
                    if let (Some(polygons), Some(iso)) = (&built.conventions, &relations[i].iso) {
                        conventions.add(iso, polygons);
                    }
                    sink.push_pre(&record)?;
                    stats.features += 1;
                }
            }
            bar.tick("relation(s)");
            Ok(())
        },
    )?;
    bar.finish("relation(s)");

    Ok(conventions)
}

/// Coastline, transit, graph, then finish the store. Moved whole from `extract`.
fn append_external_and_finish(
    coastline: &Path,
    transit_routes: &Path,
    graph: &crate::GraphGate,
    mut sink: Sink,
    stats: &mut Stats,
    conventions: schema::boundaries::Conventions,
    spill_path: &Path,
    timer: &mut crate::timing::PhaseTimer,
) -> Result<Store> {
    // The mainland, last, because clipping it needs the extract's own bounding box and that is
    // only known once every OSM feature has been through the sink. Order in the file does not
    // matter: the tiler groups by layer id, so `earth` is the first layer of every body whenever it
    // was written.
    match sink.bbox_degrees() {
        Some(bbox) => {
            println!(
                "reading land polygons within {:.3},{:.3} .. {:.3},{:.3}",
                bbox.0, bbox.1, bbox.2, bbox.3,
            );
            stats.land_polygons = schema::landtype::stream_prepared(coastline, bbox, &mut sink)?;
            stats.features += stats.land_polygons;
        }
        // Nothing to clip against. Land alone would be an archive of one layer, and the caller
        // almost certainly pointed at the wrong extract.
        None => return err("the extract produced no features to place land against".to_string()),
    }
    // The `transit` layer, for the same reason and against the same bounding box: its geometry is
    // GTFS rather than OSM (see [`schema::transit`]), so it is read here rather than classified in
    // any of the passes above.
    timer.switch("transit-routes");
    match sink.bbox_degrees() {
        Some(bbox) => {
            println!(
                "reading transit routes within {:.3},{:.3} .. {:.3},{:.3}",
                bbox.0, bbox.1, bbox.2, bbox.3,
            );
            stats.transit_routes = schema::transit::stream_routes(transit_routes, bbox, &mut sink)?;
            stats.features += stats.transit_routes;
        }
        None => {
            return err("the extract produced no features to place transit routes against"
                .to_string())
        }
    }
    // The `traffic` layer is dropped as of the NA diet: its 12.37GB (360M
    // per-segment features, 5x the roads layer) cost more than the overlay earns,
    // and the live-traffic display path needs a server contract change before a
    // cheaper per-edge form can replace it. The layer stays in the format
    // (layer_count 12, dict entry kept) so readers need no change — with zero
    // features no body carries it and it costs nothing. `junction` below still
    // reads the same graph, which is why the graph build and its validation stay.
    // (Formerly: `stats.traffic_segments = schema::traffic::stream_graph(...)`.)
    // The `junction` layer rides the graph: a lane connector is built from the junction
    // node's own incident edges, so there is nothing to read that the traffic pass did not
    // already need. `conventions` decides which side of a road the direction of travel sits
    // on, and is borrowed here because it is moved into the store below.
    // The first stage that reads `--graph`: with `--graph-ready-file`, everything above ran while
    // `road_graph` was still working, and this is where the build waits for it.
    if !graph.ready_now() {
        timer.switch("graph-wait");
    }
    graph.wait().map_err(osm_ingest::proto::Error)?;
    timer.switch("junctions");
    println!("building lane connectors from the v6 routing graph for the junction layer");
    stats.junction_connectors =
        schema::junction::stream_junctions(graph.dir(), &conventions, &mut sink)?;
    stats.features += stats.junction_connectors;
    println!("  {} lane connector(s)", stats.junction_connectors);
    // `sink.finish` itself is a flush, not a loop: every feature was serialised and its coordinates
    // e7-quantised in the `push` that spilled it, back in `materialise_ways`/`materialise_relations`
    // (both parallel, both under a "Materialise:" bar) and in the streams above. The spill's byte
    // order is the archive's, so that write stays serial by construction — there is nothing here to
    // parallelise. The bar makes the finalise step visible rather than a silent tail of the ~25 s
    // this phase costs; `stats.features` is the count the sink is closing over.
    timer.switch("finalise");
    let mut bar = Progress::new(
        "Finalise: features".to_string(),
        stats.features as usize,
        "feature(s)",
        true,
    );
    let store = sink.finish(spill_path)?;
    bar.finish("feature(s)");
    let store = store.with_conventions(conventions);

    Ok(store)
}

/// Node refs, duplicates included, above which the bitset replaces the sorted vector.
///
/// 256 M refs is a 2 GB `Vec<i64>` plus a sort of 256 M elements, and it is roughly a us-west
/// extract. Below it the vector is smaller than the bitset and this stays exactly as it always was;
/// above it the vector is the largest thing in stage A and the bitset is a constant.
const REFS_IN_MEMORY: usize = 256 << 20;

/// The node id a bitset will not be sized past.
///
/// OSM assigns node ids monotonically and is near 13 G, so this is roughly 4x today's high-water
/// mark and 2.6 GB of bitset. It is a bound on the *data*, and a synthetic or non-OSM input with one
/// sparse enormous id would otherwise ask for a bitset the size of the id, so it is an error rather
/// than a clamp: a clamp would silently drop the nodes above it.
const MAX_NODE_ID: i64 = 48 << 30;

/// The ids pass 3 must resolve, collected the way stage A always did.
///
/// One bit per node id in `0..=max_id`.
struct NeededBits {
    words: Vec<u64>,
    /// `max_id + 1`. Kept because the last word is padded and a ref landing in that padding is out
    /// of range, not merely addressable.
    bits: usize,
    count: usize,
}

impl NeededBits {
    fn new(max_id: i64) -> Result<NeededBits> {
        if max_id > MAX_NODE_ID {
            return err(format!(
                "node id {max_id} is past the {MAX_NODE_ID} this build will size a bitset for; \
                 OSM's own ids are near 13 billion, so this input is not an OSM extract"
            ));
        }
        // `max_id` is a maximum, not a count, so the space is one larger.
        let bits = max_id.max(0) as usize + 1;
        Ok(NeededBits { words: vec![0u64; bits.div_ceil(64)], bits, count: 0 })
    }

    /// An unsized bitset that grows as larger ids arrive, for fusing collection
    /// into pass 1's drain: the maximum is not known until the last way is
    /// pushed, so the words extend on demand instead. Same guards as [`new`];
    /// the final size is identical to a sized bitset over the same ids.
    fn new_growable() -> NeededBits {
        NeededBits { words: Vec::new(), bits: 0, count: 0 }
    }

    /// Set a bit, growing the words when the id lands past them.
    ///
    /// Refused rather than grown past [`MAX_NODE_ID`], like [`new`]: an id out
    /// there is not an OSM node and growing for it would size the bitset for
    /// one corrupt ref.
    fn set_growable(&mut self, id: i64) -> Result<()> {
        if id < 0 {
            return err(format!("node ref {id} is negative"));
        }
        if id > MAX_NODE_ID {
            return err(format!(
                "node ref {id} is past the {MAX_NODE_ID} this build will size a bitset for"
            ));
        }
        let at = id as usize;
        if at >= self.bits {
            self.bits = at + 1;
            self.words.resize(self.bits.div_ceil(64), 0);
        }
        let word = &mut self.words[at / 64];
        let bit = 1u64 << (at % 64);
        if *word & bit == 0 {
            *word |= bit;
            self.count += 1;
        }
        Ok(())
    }

    fn set(&mut self, id: i64) -> Result<()> {
        // A negative ref is not a node this planet has. Refused rather than skipped: it means the
        // spill or the PBF is not what it claims, and skipping would show as a way with a gap in it.
        if id < 0 {
            return err(format!("node ref {id} is negative"));
        }
        let at = id as usize;
        if at >= self.bits {
            return err(format!(
                "node ref {id} is past the {} the bitset was sized for",
                self.bits - 1
            ));
        }
        let word = &mut self.words[at / 64];
        let bit = 1u64 << (at % 64);
        if *word & bit == 0 {
            *word |= bit;
            self.count += 1;
        }
        Ok(())
    }

    /// How many distinct ids were set. The `len` [`NodeLocations::from_bitset`] sizes from.
    fn len(&self) -> usize {
        self.count
    }

    /// Hand the words over to the rank index: the collector is done setting bits and the index is
    /// done needing a settable bitset, so one moves into the other rather than both living at once.
    /// Consumes `self`; the count travels beside the words so the index need not recount them.
    fn into_words(self) -> (Vec<u64>, usize) {
        (self.words, self.count)
    }

    /// Every set id, ascending. `trailing_zeros` on a word at a time, so an empty stretch of the id
    /// space -- which is most of it, since a needed set is a subset of a subset -- costs one compare
    /// per 64 ids rather than one per id.
    fn ids(&self) -> impl Iterator<Item = i64> + '_ {
        self.words.iter().enumerate().flat_map(|(w, word)| {
            let mut rest = *word;
            std::iter::from_fn(move || {
                if rest == 0 {
                    return None;
                }
                let bit = rest.trailing_zeros() as usize;
                rest &= rest - 1;
                Some((w * 64 + bit) as i64)
            })
        })
    }
}

