/// Spread `lanes` across `exits`, both already in left→right order, as `(lane, exit)` pairs.
///
/// Whichever side is larger drives the pairing, so every lane gets a connector and every exit gets
/// fed. Two left-turn lanes onto one road give two connectors into the same exit, which is right —
/// a dual left turn is two ribbons. One lane onto a two-lane exit gives two connectors out of the
/// same lane, which is also right: the lane fans.
fn distribute(lanes: &[usize], exits: &[usize]) -> Vec<(usize, usize)> {
    if lanes.is_empty() || exits.is_empty() {
        return Vec::new();
    }
    if lanes.len() >= exits.len() {
        lanes
            .iter()
            .enumerate()
            .map(|(i, &lane)| (lane, exits[i * exits.len() / lanes.len()]))
            .collect()
    } else {
        exits
            .iter()
            .enumerate()
            .map(|(j, &exit)| (lanes[j * lanes.len() / exits.len()], exit))
            .collect()
    }
}

/// The lane connectors through the junction at node `n`, in emission order.
///
/// The whole of [`stream_junctions`]'s loop body except the `sink.push` /
/// `emitted` counting: pure per-node work over shared-read-only tables
/// (`Graph`/`LaneTable` are file mappings with `&self` accessors,
/// `InEdges::of` is a pure slice, `Conventions::at_tile` is a
/// `HashMap::get`), so a batch of node ids fans out across the pool exactly
/// as `materialise_ways` does and the serial drain pushes the results in `n`
/// order — which is what keeps the spill, and therefore the archive,
/// byte-identical at every thread count.
///
/// Within a node the order is exactly the serial loop's: `incoming` order,
/// `legal.sort_by(angle)`, `pairs.sort_unstable(); dedup()`.
fn junctions_for_node(
    n: u64,
    graph: &Graph,
    lanes: Option<&LaneTable>,
    incoming: &InEdges,
    conventions: &Conventions,
) -> Vec<Vec<(f64, f64)>> {
    let mut out: Vec<Vec<(f64, f64)>> = Vec::new();
    let node = graph.node(n);
    let in_arms: Vec<Arm> = incoming
        .of(n)
        .iter()
        .filter_map(|&idx| {
            arm(graph, lanes, idx, source_of(graph, idx) as u32, n as u32, false)
        })
        .collect();
    let out_arms: Vec<Arm> = (graph.edge_ptr(n)..graph.edge_ptr(n + 1))
        .filter(|&idx| is_drivable(graph.edge_type(idx)))
        .filter_map(|idx| {
            let target = graph.edge_target(idx, n as u32).ok()?;
            arm(graph, lanes, idx, n as u32, target, true)
        })
        .collect();
    if in_arms.is_empty() || out_arms.len() < 2 {
        return out;
    }
    // A junction is a node three or more distinct roads meet at. Degree-2 chains are collapsed
    // by the generator, so the survivors below this bar are dead ends, attribute breaks and
    // transit stops — none of which anything turns at.
    let degree: BTreeSet<u32> =
        in_arms.iter().chain(&out_arms).map(|a| a.neighbour).collect();
    if degree.len() < 3 {
        return out;
    }
    // Thinning rule: a junction with no arterial arm emits nothing. Quiet
    // residential crossings keep their carriageways; they lose only the lane
    // markings, which is where the layer's bytes concentrate.
    if !in_arms
        .iter()
        .chain(&out_arms)
        .any(|a| a.road_class <= super::junction::ARTERIAL_MAX_CLASS)
    {
        return out;
    }

    let (tx, ty) = tile_build::geom::project(
        f64::from(node.1) * 1e-7,
        f64::from(node.0) * 1e-7,
        super::boundaries::COARSE_ZOOM,
    );
    let left_hand = conventions
        .at_tile(
            super::boundaries::COARSE_ZOOM,
            tx.max(0.0) as u64,
            ty.max(0.0) as u64,
        )
        .left_hand;

    for approach in &in_arms {
        // Every exit but the one back the way you came. The graph carries no turn restrictions,
        // so this is the whole legality model: inference, and generous.
        let mut legal: Vec<(usize, f64, Turn)> = out_arms
            .iter()
            .enumerate()
            .filter(|(_, exit)| exit.neighbour != approach.neighbour)
            .map(|(i, exit)| {
                let delta = normalise_degrees(exit.heading - approach.heading);
                (i, delta, classify_turn(delta))
            })
            // A reversal is not a movement this layer draws, and the neighbour test above does
            // not catch it. On a divided highway the median slot reverses onto the *opposing
            // carriageway*, which is a different node, so it survives that filter and arrives
            // here as a legal 180-degree movement. The curve it produces never enters the
            // junction at all: with `f_out = -f_in` all four bezier control points sit behind
            // the node, so it draws as a flat hook doubling back along the approach.
            .filter(|(_, _, turn)| *turn != Turn::Reverse)
            .collect();
        if legal.is_empty() {
            continue;
        }
        // Left→right as the driver sees them, which is the order lane masks are stored in.
        legal.sort_by(|a, b| a.1.total_cmp(&b.1));

        // Both ends read the same function of their own arm, so neither can drift from the
        // other and neither depends on who is looking. No count is derived from the junction's
        // shape: `legal.len()` counts exits, not lanes, and using it as a width fanned the
        // endpoints across phantom lanes and off the carriageway.
        let approach_lanes = effective_lanes(approach);

        // `turn:lanes` where it exists, the arm's own lane count where it does not.
        let mut pairs: Vec<(usize, usize)> = match &approach.masks {
            Some(masks) => {
                let mut pairs = Vec::new();
                for turn in Turn::ALL {
                    let bit = turn.bit();
                    let claimants: Vec<usize> = masks
                        .iter()
                        .enumerate()
                        .filter(|(_, m)| {
                            // A lane whose only marking is "none" is treated as a through lane,
                            // which is what an unmarked lane at a junction means in practice.
                            **m & bit != 0
                                || (turn == Turn::Through
                                    && (**m == 0 || **m == LANE_NONE))
                        })
                        .map(|(k, _)| k)
                        .collect();
                    if claimants.is_empty() {
                        continue;
                    }
                    pairs.extend(distribute(&claimants, &exits_for(turn, &legal)));
                }
                pairs
            }
            // Untagged, which is the overwhelming majority of roads. The arm's real lanes are
            // spread over its legal exits — and since an untagged arm has exactly one, every
            // movement leaves from lane 0 and the connectors share the arm's mouth. Assigning
            // a lane *index* per exit here was the other half of the fabricated width: it put
            // the third movement 1.5 lane widths out on a road with one lane.
            None => {
                let lanes: Vec<usize> = (0..approach_lanes).collect();
                let exits: Vec<usize> = legal.iter().map(|(i, _, _)| *i).collect();
                distribute(&lanes, &exits)
            }
        };

        // Ordered so a movement's lanes rank left→right, and deduplicated because two tagged
        // indications can fall back to the same exit and would otherwise draw one connector
        // twice on top of itself.
        pairs.sort_unstable();
        pairs.dedup();

        for &(in_lane, exit_index) in &pairs {
            let exit = &out_arms[exit_index];
            let Some((_, _, turn)) = legal.iter().find(|(i, _, _)| *i == exit_index) else {
                continue;
            };
            // Through movements need no connector: the approach lane's own
            // carriageway ribbon already paints the straight path, and the
            // connector draws as an exact identity over it (same style entry,
            // same colour — see the module docs on reading a screenshot).
            // Dropping them keeps only turns, which is where the markings
            // actually differ from the road beneath.
            if *turn == Turn::Through {
                continue;
            };
            // Thinning rule: a slight (20–45°) movement at a three-way node is
            // a road bend, not a turn — the carriageway already shows the path.
            // Kept at degree 4+, where a slight exit is a real choice.
            if degree.len() == 3
                && matches!(*turn, Turn::SlightLeft | Turn::SlightRight)
            {
                continue;
            }
            let out_lanes = effective_lanes(exit);
            // This lane's place among the lanes making the same movement into the same exit.
            let siblings: Vec<usize> =
                pairs.iter().filter(|(_, e)| *e == exit_index).map(|&(l, _)| l).collect();
            let rank = siblings.iter().position(|&l| l == in_lane).unwrap_or(0);
            let out_lane =
                exit_lane(*turn, in_lane, approach_lanes, out_lanes, rank, siblings.len());
            let Some(points) = connector(
                node,
                approach,
                in_lane,
                approach_lanes,
                exit,
                out_lane,
                out_lanes,
                *turn,
                left_hand,
            ) else {
                continue;
            };
            out.push(points);
        }
    }
    out
}

/// Read the v6 graph at `dir` and push one line feature per lane connector into `sink`. Returns the
/// number of connectors emitted.
///
/// `conventions` decides which side of a road's centreline the direction of travel sits on, so a
/// left-hand-traffic country's connectors are not mirrored. It is resolved from each junction's own
/// coordinate at [`super::boundaries::COARSE_ZOOM`], which is the granularity the grid holds.
///
/// Not clipped to a bounding box, for the same reason [`crate::schema::traffic::stream_graph`] is
/// not: the graph is already the built region and the tiler clips each connector per tile.
pub fn stream_junctions(dir: &Path, conventions: &Conventions, sink: &mut Sink) -> Result<u64> {
    let graph = Graph::load(dir)?;
    let lanes = LaneTable::load(dir)?;
    let incoming = InEdges::build(&graph)?;
    let class = junction_class();
    let mut emitted = 0u64;

    // Pipelined, the `materialise_ways` pattern: `junctions_for_node` is pure per-node work over
    // shared-read-only tables, and so is building each connector's spill record, so both run on
    // the pool while the drain only appends **in `n` order** -- which is what keeps the spill, and
    // therefore the archive, byte-identical at every thread count. The bar ticks in the drain, per
    // node, so it spans the whole graph and this phase is never silent.
    //
    // Was batches of 32 Ki nodes with the records encoded in the drain: on the planet (440 M
    // nodes, 177 M connectors) that ran 4m40s at ~28 cores, the drain and the per-batch barrier
    // being the limit.
    const JUNCTION_GROUP: usize = 256 * 1024;
    let mut bar = Progress::new(
        "Junctions".to_string(),
        graph.node_count as usize,
        "node(s)",
        true,
    );
    // One task per run of `JUNCTION_NODES` consecutive nodes, each producing one `RecordRun`, so
    // the serial drain appends a run at a time rather than a record at a time (~177 M connectors
    // on the planet).
    const JUNCTION_NODES: usize = 1024;
    let node_count = graph.node_count as usize;
    let units = node_count.div_ceil(JUNCTION_NODES);
    crate::extract::pipeline_ordered(
        units,
        JUNCTION_GROUP / JUNCTION_NODES,
        |u| -> Result<crate::store::RecordRun> {
            let mut run = crate::store::RecordRun::default();
            let lo = u * JUNCTION_NODES;
            for n in lo..(lo + JUNCTION_NODES).min(node_count) {
                for points in junctions_for_node(n as u64, &graph, lanes.as_ref(), &incoming, conventions) {
                    let encoded = crate::store::EncodedGeom::live(&Geometry::Lines(vec![points]))?;
                    run.add(crate::store::PreRecord::named(
                        &class,
                        &encoded,
                        None,
                        tilecodec::mamaps::body::ID_NONE,
                    )?);
                }
            }
            Ok(run)
        },
        |u, run| {
            let run = run?;
            emitted += run.len() as u64;
            sink.push_run(run)?;
            let lo = u * JUNCTION_NODES;
            bar.tick_n((lo + JUNCTION_NODES).min(node_count) - lo, "node(s)");
            Ok(())
        },
    )?;
    bar.finish("node(s)");
    Ok(emitted)
}
