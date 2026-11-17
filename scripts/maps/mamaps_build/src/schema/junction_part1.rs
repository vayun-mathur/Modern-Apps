/// Bucket a signed turn angle (degrees, positive clockwise/right) into a [`Turn`].
///
/// The thresholds are OSM's own reading of the `turn` vocabulary: `slight` up to 45°, the plain
/// direction out to 135°, `sharp` beyond that, and a reversal past 170°.
fn classify_turn(delta_degrees: f64) -> Turn {
    let magnitude = delta_degrees.abs();
    let right = delta_degrees > 0.0;
    if magnitude <= 20.0 {
        Turn::Through
    } else if magnitude > 170.0 {
        Turn::Reverse
    } else if magnitude <= 45.0 {
        if right {
            Turn::SlightRight
        } else {
            Turn::SlightLeft
        }
    } else if magnitude <= 135.0 {
        if right {
            Turn::Right
        } else {
            Turn::Left
        }
    } else if right {
        Turn::SharpRight
    } else {
        Turn::SharpLeft
    }
}

/// Wrap an angle difference into `(-180, 180]`, so a turn's sign is its direction.
fn normalise_degrees(mut d: f64) -> f64 {
    while d > 180.0 {
        d -= 360.0;
    }
    while d <= -180.0 {
        d += 360.0;
    }
    d
}

// --- local geometry -------------------------------------------------------------------------

/// Bearing from `a` to `b` in degrees clockwise from north, on the local tangent plane.
///
/// The `e7` units cancel in the ratio; only the `cos(lat)` correction on the east component
/// matters, and without it every heading north of the tropics comes out rotated.
fn bearing_degrees(a: (i32, i32), b: (i32, i32)) -> f64 {
    let mid_lat = (f64::from(a.0) + f64::from(b.0)) * 0.5 * 1e-7;
    let north = f64::from(b.0) - f64::from(a.0);
    let east = (f64::from(b.1) - f64::from(a.1)) * mid_lat.to_radians().cos();
    east.atan2(north).to_degrees()
}

/// Ground distance between two `e7` points, in metres.
fn distance_m(a: (i32, i32), b: (i32, i32)) -> f64 {
    let mid_lat = (f64::from(a.0) + f64::from(b.0)) * 0.5 * 1e-7;
    let north = (f64::from(b.0) - f64::from(a.0)) * 1e-7 * METRES_PER_DEGREE;
    let east =
        (f64::from(b.1) - f64::from(a.1)) * 1e-7 * METRES_PER_DEGREE * mid_lat.to_radians().cos();
    (north * north + east * east).sqrt()
}

/// The length of a polyline, in metres.
fn polyline_length_m(verts: &[(i32, i32)]) -> f64 {
    verts.windows(2).map(|w| distance_m(w[0], w[1])).sum()
}

/// A point offset from `origin` by `(east, north)` metres, as `(lon, lat)` degrees.
fn offset_degrees(origin: (i32, i32), east_m: f64, north_m: f64) -> (f64, f64) {
    let lat = f64::from(origin.0) * 1e-7;
    let lon = f64::from(origin.1) * 1e-7;
    let d_lat = north_m / METRES_PER_DEGREE;
    // At the poles this divisor collapses; the Mercator clamp means no tile is drawn there anyway,
    // and a floor keeps a degenerate coordinate from becoming an infinity in the output.
    let d_lon = east_m / (METRES_PER_DEGREE * lat.to_radians().cos().abs().max(1e-6));
    (lon + d_lon, lat + d_lat)
}

/// The unit vector pointing along `heading` (degrees clockwise from north), as `(east, north)`.
fn forward(heading_degrees: f64) -> (f64, f64) {
    let r = heading_degrees.to_radians();
    (r.sin(), r.cos())
}

/// The unit vector 90° clockwise of `heading` — "right of the direction of travel".
fn rightward(heading_degrees: f64) -> (f64, f64) {
    let r = heading_degrees.to_radians();
    (r.cos(), -r.sin())
}

// --- the graph, one junction at a time --------------------------------------------------------

/// Is this road class one a car drives on? The same `1..=9` (motorway..living_street) the traffic
/// layer uses, masked so the reverse-geometry flag cannot trip the range test.
fn is_drivable(type_: u8) -> bool {
    (1..=9).contains(&(type_ & ROAD_TYPE_MASK))
}

/// Incoming drivable edges per node, as a CSR built in one pass over the graph.
///
/// `nodes.bin` is out-edges only and there is no reverse index on disk, so this is the whole reason
/// approaches can be found at all. [`Graph::has_drivable_twin`] is the existing primitive and would
/// do for a two-way street, but it can only find an approach from a node the junction also has an
/// out-edge *to* — which misses every one-way approach, and a one-way approach is exactly the
/// motorway slip road this layer is meant to get right.
///
/// Costs `4 * (node_count + 1)` bytes plus 4 per drivable edge, and stores only the edge index: an
/// in-edge's source node is recovered by [`source_of`], which is a binary search over the same
/// `edge_ptr` array rather than another 4 bytes an edge.
struct InEdges {
    start: Vec<u32>,
    edges: Vec<u32>,
}

impl InEdges {
    fn build(graph: &Graph) -> Result<InEdges> {
        let node_count = graph.node_count;
        // Parallel counting sort, not a naive `par_iter`: the serial
        // `start[target + 1] += 1` is a cross-iteration hazard, so the count
        // goes through one shared atomic slot per target instead — spread
        // over `node_count + 1` slots, so the updates are essentially
        // uncontended — and the fill through the same slots as cursors.
        //
        // Memory is the serial shape, not per-shard histograms (shards x
        // nodes would be tens of GB at planet scale): one atomic slot per
        // node plus the edge table itself, the same peak as the serial
        // `start` + `fill` pair this replaces.
        //
        // Order is the serial order: sources are visited `n` ascending,
        // `edge_ptr` ascending, and each node's slice is restored to
        // ascending `edge_idx` after the fill, so `incoming.of(n)` holds
        // exactly what the serial loop built and downstream pairing is
        // unchanged at every thread count.
        use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
        use std::sync::Mutex;
        let slots = node_count as usize + 2;
        let counts: Vec<AtomicU32> = (0..slots).map(|_| AtomicU32::new(0)).collect();
        // The first corrupt edge met, serialised: a bounds failure and an
        // escape-row failure are both fatal, and the message keeps the
        // serial wording. Checked between phases, so a corrupt graph never
        // fills (or sorts) a partial index.
        let first_err: Mutex<Option<osm_ingest::proto::Error>> = Mutex::new(None);
        let failed = AtomicBool::new(false);
        // Shards partition the *source* nodes: every edge is visited exactly
        // once however the ranges fall, so each target's total is exact.
        let threads = par::threads().max(1);
        let want_tasks = threads.saturating_mul(4).max(1);
        let shard_len = ((node_count as usize) / want_tasks).max(1);
        let mut shards: Vec<std::ops::Range<u64>> = Vec::new();
        let mut lo = 0u64;
        while lo < node_count {
            let hi = (lo + shard_len as u64).min(node_count);
            shards.push(lo..hi);
            lo = hi;
        }
        // Phase 1: count one slot high, so the prefix sum below leaves
        // `start[t]` pointing at node `t`'s first slot.
        par::install(|| {
            shards.par_iter().for_each(|range| {
                for n in range.clone() {
                    // Another shard already found corruption: stop feeding
                    // counts nobody will read. Checked per node, not per
                    // edge, so the fast path pays one relaxed load.
                    if failed.load(Ordering::Relaxed) {
                        break;
                    }
                    for idx in graph.edge_ptr(n)..graph.edge_ptr(n + 1) {
                        if !is_drivable(graph.edge_type(idx)) {
                            continue;
                        }
                        let target = match graph.edge_target(idx, n as u32) {
                            Ok(t) => t,
                            Err(e) => {
                                failed.store(true, Ordering::Relaxed);
                                let mut guard = first_err
                                    .lock()
                                    .unwrap_or_else(|p| p.into_inner());
                                if guard.is_none() {
                                    *guard = Some(e);
                                }
                                break;
                            }
                        };
                        if u64::from(target) >= node_count {
                            failed.store(true, Ordering::Relaxed);
                            let mut guard =
                                first_err.lock().unwrap_or_else(|p| p.into_inner());
                            if guard.is_none() {
                                *guard = Some(osm_ingest::proto::Error(format!(
                                    "edge {idx} targets node {target}, past the {node_count} in the graph"
                                )));
                            }
                            break;
                        }
                        counts[target as usize + 1].fetch_add(1, Ordering::Relaxed);
                    }
                }
            });
        });
        if let Some(e) = first_err.lock().unwrap_or_else(|p| p.into_inner()).take() {
            return Err(e);
        }
        // Serial prefix sum, in place: the same array then serves as the
        // phase-2 fill cursors with no second allocation. The add comes
        // *before* the snapshot — slot i counts edges targeting node i-1,
        // so the running total *with* this slot folded in is node i's
        // block start (slot 0 targets no node and is always empty, pinning
        // start[0] at 0). Snapshotting before the add shifts every block
        // one slot down and empties node 0, which is exactly the failure
        // mode to watch for here.
        let mut total = 0u32;
        let mut start = vec![0u32; slots];
        for (i, slot) in counts.iter().enumerate() {
            let c = slot.load(Ordering::Relaxed);
            total = total.checked_add(c).expect(
                "drivable edges fit u32: Graph::load refuses an edge_count past u32::MAX",
            );
            slot.store(total, Ordering::Relaxed);
            start[i] = total;
        }
        // Phase 2: disjoint-slice fill. Every slot written is claimed by
        // exactly one `fetch_add`, so no two threads share one; phase 1
        // validated every edge, so the defensive skips below are unreachable
        // and only guard against filling a corrupt index silently.
        //
        // `Vec<AtomicU32>` rather than `&mut Vec<u32>`: the table is shared
        // across workers, and atomics are `Sync` where a unique borrow is
        // not. Same 4 bytes a slot, converted back after the join.
        let edges: Vec<AtomicU32> =
            std::iter::repeat_with(|| AtomicU32::new(0)).take(total as usize).collect();
        failed.store(false, Ordering::Relaxed);
        par::install(|| {
            shards.par_iter().for_each(|range| {
                for n in range.clone() {
                    if failed.load(Ordering::Relaxed) {
                        break;
                    }
                    for idx in graph.edge_ptr(n)..graph.edge_ptr(n + 1) {
                        if !is_drivable(graph.edge_type(idx)) {
                            continue;
                        }
                        let Ok(target) = graph.edge_target(idx, n as u32) else {
                            failed.store(true, Ordering::Relaxed);
                            continue;
                        };
                        if u64::from(target) >= node_count {
                            failed.store(true, Ordering::Relaxed);
                            continue;
                        }
                        let at =
                            counts[target as usize].fetch_add(1, Ordering::Relaxed) as usize;
                        // `at` is in-bounds by construction (cursor < start
                        // + count); the check keeps a logic bug a panic
                        // rather than memory unsafety.
                        if let Some(slot) = edges.get(at) {
                            slot.store(idx, Ordering::Relaxed);
                        }
                    }
                }
            });
        });
        if failed.load(Ordering::Relaxed) {
            return err(
                "the graph changed between the junction count and fill passes".to_string(),
            );
        }
        let mut edges: Vec<u32> =
            edges.into_iter().map(AtomicU32::into_inner).collect();
        // Restore ascending `edge_idx` within each node: the serial visit
        // order, and what `incoming.of(n)` has always held. Slices average a
        // couple of edges, so this pass is a linear walk, not a sort cost.
        for n in 0..node_count as usize {
            let (lo, hi) = (start[n] as usize, start[n + 1] as usize);
            edges[lo..hi].sort_unstable();
        }
        start.truncate(node_count as usize + 1);
        Ok(InEdges { start, edges })
    }

    fn of(&self, n: u64) -> &[u32] {
        let lo = self.start[n as usize] as usize;
        let hi = self.start[n as usize + 1] as usize;
        &self.edges[lo..hi]
    }
}

/// The node edge `idx` leaves from: the last node whose `edge_ptr` is at or below it.
///
/// `edge_ptr` ascends across `nodes.bin` and the sentinel record holds `edge_count`, so the answer
/// is always in `0..node_count` for a valid edge index.
fn source_of(graph: &Graph, idx: u32) -> u64 {
    let (mut lo, mut hi) = (1u64, graph.node_count + 1);
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        if graph.edge_ptr(mid) > idx {
            hi = mid;
        } else {
            lo = mid + 1;
        }
    }
    lo - 1
}

/// An edge's polyline in the order it is travelled, `from -> to`.
///
/// A reverse-geometry edge stores no shape of its own — its twin running the other way owns it — so
/// the twin's polyline is found and reversed. Without this every curved two-way approach would get
/// its heading from the straight chord instead of the road, which at a bend is a wrong turn angle
/// and therefore a wrong lane pairing.
fn travel_polyline(graph: &Graph, idx: u32, from: u32, to: u32) -> Vec<(i32, i32)> {
    if graph.geom_contains(idx) {
        return graph.polyline(idx, from, to);
    }
    for twin in graph.edge_ptr(u64::from(to))..graph.edge_ptr(u64::from(to) + 1) {
        if !graph.geom_contains(twin) || !is_drivable(graph.edge_type(twin)) {
            continue;
        }
        if graph.edge_target(twin, to).is_ok_and(|t| t == from) {
            let mut verts = graph.polyline(twin, to, from);
            verts.reverse();
            return verts;
        }
    }
    // A genuine straight chord: source and target, and nothing between them.
    graph.polyline(idx, from, to)
}

/// One movement into or out of a junction, resolved into everything a connector needs.
struct Arm {
    /// The node at the far end.
    neighbour: u32,
    /// Degrees clockwise from north, always in the direction of travel — so for an approach this is
    /// the heading traffic *arrives* on, and for an exit the heading it *departs* on.
    heading: f64,
    /// The edge's own length in metres, which caps how far the connector may reach along it.
    length_m: f64,
    /// Whether the road carries traffic the other way too, which decides whether this direction's
    /// lanes sit on the centreline or a half-carriageway to one side of it.
    two_way: bool,
    /// Per-lane `turn:lanes` masks, left→right, when `lanes.bin` has them. `None` is the common
    /// case. This is also the arm's only lane-count source — see [`effective_lanes`], which is
    /// deliberately the one way to ask, so a count can never be fabricated from something that
    /// merely happened to be in scope.
    masks: Option<Vec<u16>>,
    /// The edge's road class (`graph.edge_type & ROAD_TYPE_MASK`: motorway=1 …
    /// tertiary=5, unclassified=6, …). Read off the edge, never inferred, so the
    /// arterial gate in `junctions_for_node` is data rather than a second guess.
    road_class: u8,
}

/// Resolve one incident edge, or `None` when it is not usable as an arm of this junction.
fn arm(
    graph: &Graph,
    lanes: Option<&LaneTable>,
    idx: u32,
    from: u32,
    to: u32,
    outgoing: bool,
) -> Option<Arm> {
    let verts = travel_polyline(graph, idx, from, to);
    // The heading is the first geometry step leaving the junction, or the last arriving at it. A
    // repeated vertex has no direction, so the scan walks past duplicates rather than dividing by
    // zero — the graph does contain them at edges that were split at a coincident point.
    let node = if outgoing { verts.first().copied()? } else { verts.last().copied()? };
    let (heading, ok) = if outgoing {
        match verts.iter().skip(1).find(|v| **v != node) {
            Some(next) => (bearing_degrees(node, *next), true),
            None => (0.0, false),
        }
    } else {
        match verts.iter().rev().skip(1).find(|v| **v != node) {
            Some(prev) => (bearing_degrees(*prev, node), true),
            None => (0.0, false),
        }
    };
    if !ok {
        return None;
    }
    let length_m = polyline_length_m(&verts);
    if length_m < MIN_SETBACK_M {
        return None;
    }
    let masks = lanes.and_then(|table| table.masks(idx)).filter(|m| !m.is_empty());
    let two_way = graph.has_drivable_twin(to, from).unwrap_or(false);
    let road_class = graph.edge_type(idx) & ROAD_TYPE_MASK;
    Some(Arm { neighbour: if outgoing { to } else { from }, heading, length_m, two_way, masks, road_class })
}

/// A lane's centre, in metres right of the road's centreline as seen by traffic travelling this
/// arm's direction.
///
/// Two offsets stacked. A two-way road's centreline runs between the two directions, so the
/// carriageway a driver is on sits a half-carriageway to their own side of it — right in a
/// right-hand-traffic country, left in a left-hand one. Within that carriageway lane `k` of `count`
/// sits at the usual centred spacing, counted left→right in the direction of travel, which is the
/// order `lanes.bin` stores masks in.
///
/// `width` is the lane width in ground metres at this junction's latitude — see
/// [`lane_width_ground_m`], and the module docs for why it is not simply [`LANE_WIDTH_MERCATOR_M`].
///
/// The opposite direction is assumed to have the same lane count, because the graph gives no way to
/// ask. An asymmetric road — three lanes one way and one the other — puts its connectors half a
/// carriageway off.
fn lane_offset_m(k: usize, count: usize, two_way: bool, left_hand: bool, width: f64) -> f64 {
    let count = count.max(1);
    let side = if left_hand { -1.0 } else { 1.0 };
    let carriageway = if two_way { side * count as f64 * 0.5 * width } else { 0.0 };
    let within = (k as f64 - (count as f64 - 1.0) * 0.5) * width;
    carriageway + within
}

/// How many lanes to spread one arm's connector ends across, in its direction of travel.
///
/// **A lane count is a property of the arm and of nothing else.** That is why this takes only an
/// [`Arm`] and why it is the single way to ask: both ends of a connector read it, so neither can
/// drift from the other, and no caller can substitute a number that merely happened to be in scope.
/// An earlier version took the junction's shape as a fallback and produced a count that varied with
/// which approach was looking at the arm.
///
/// `turn:lanes` from `lanes.bin` is the only real value reachable here — the v6 routing graph
/// carries no lane count, and OSM's own `lanes` tag lives in the extract path keyed by way rather
/// than by directed edge, so joining to it is a separate piece of work.
///
/// **The fallback of 1 is the carriageway renderer's own default, not a guess.** `tile/geometry.rs`
/// turns `lane_count 0` into `oneway ? 1 : 2`, counted across *both* directions; a two-way road is
/// therefore one lane each way and a one-way road one lane. [`lane_offset_m`] counts per direction
/// and adds the opposing half-carriageway itself, so both of those cases are 1 in this function's
/// units. Matching it is the whole point: a connector then starts and ends where the asphalt is
/// actually painted instead of where a second, disagreeing default put it.
///
/// The cost is honest and worth stating. At an untagged junction every movement out of an arm
/// shares that arm's single lane, so the connectors meet at the arm's mouth rather than fanning.
/// That is what a single-lane road *is*. Inventing a wider fan to separate them draws lanes that
/// are not there and puts the endpoints off the carriageway, which is the defect this replaces.
fn effective_lanes(arm: &Arm) -> usize {
    arm.masks.as_ref().map_or(1, Vec::len)
}

/// Sample the connector from lane `in_lane` of `approach` into lane `out_lane` of `exit`.
///
/// A cubic bezier in a local tangent plane centred on the junction node, with its handles along the
/// two headings so the curve leaves the approach and meets the exit tangentially. Sampled here, in
/// `f64` and once per build, rather than on device once per frame — which is the whole point of
/// carrying a polyline instead of control points.
/// Inputs for [`connector`], grouped so the one call site names them.
struct ConnectorArgs<'a> {
    node: (i32, i32),
    approach: &'a Arm,
    in_lane: usize,
    approach_lanes: usize,
    exit: &'a Arm,
    out_lane: usize,
    exit_lanes: usize,
    turn: Turn,
    left_hand: bool,
}

fn connector(args: ConnectorArgs<'_>) -> Option<Vec<(f64, f64)>> {
    let ConnectorArgs { node, approach, in_lane, approach_lanes, exit, out_lane, exit_lanes, turn, left_hand } = args;
    let back = (SETBACK_M).min(approach.length_m * SETBACK_EDGE_FRACTION);
    let ahead = (SETBACK_M).min(exit.length_m * SETBACK_EDGE_FRACTION);
    if back < MIN_SETBACK_M || ahead < MIN_SETBACK_M {
        return None;
    }
    // The setback is real ground distance: the intersection is that big and the roads it joins are
    // where they are. The lateral offset is not — it shrinks with `cos φ` so that it stays a fixed
    // size in projected units, and therefore a fixed size in pixels, at every latitude.
    //
    // Both ends are placed by the same call with the same rule. Taking the counts as arguments
    // rather than off each `Arm` is what makes that structural: an arm's own `lanes` is whatever
    // `lanes.bin` happened to say, and reading it directly at one end while the caller overrode it
    // at the other is exactly how every connector into an exit came to share one endpoint.
    let width = lane_width_ground_m(f64::from(node.0) * 1e-7);
    let in_off = lane_offset_m(in_lane, approach_lanes, approach.two_way, left_hand, width);
    let out_off = lane_offset_m(out_lane, exit_lanes, exit.two_way, left_hand, width);

    let (fin_e, fin_n) = forward(approach.heading);
    let (rin_e, rin_n) = rightward(approach.heading);
    let (fout_e, fout_n) = forward(exit.heading);
    let (rout_e, rout_n) = rightward(exit.heading);

    // Start: back along the approach from the node, then across to the lane's centre.
    let p0 = (-fin_e * back + rin_e * in_off, -fin_n * back + rin_n * in_off);
    // End: forward along the exit, then across.
    let p3 = (fout_e * ahead + rout_e * out_off, fout_n * ahead + rout_n * out_off);
    let p1 = (p0.0 + fin_e * back * HANDLE, p0.1 + fin_n * back * HANDLE);
    let p2 = (p3.0 - fout_e * ahead * HANDLE, p3.1 - fout_n * ahead * HANDLE);

    // A straight-through movement needs two points; a U-turn needs every one of these. Anything
    // flatter than the sampling would resolve is dropped by the tiler's simplifier anyway.
    //
    // Sampled at roughly half the historical density: a z14 connector spans
    // tens of metres, so ~5 points resolve its curve at display scale and the
    // rest were coordinate bytes. Minimum 3 keeps a curve a curve.
    let sweep = normalise_degrees(exit.heading - approach.heading).abs();
    let samples = if turn == Turn::Through && sweep < 3.0 && (in_off - out_off).abs() < 0.05 {
        2
    } else {
        (3.0 + sweep / 36.0).round().clamp(3.0, 7.0) as usize
    };

    let mut out = Vec::with_capacity(samples);
    for i in 0..samples {
        let t = i as f64 / (samples - 1) as f64;
        let u = 1.0 - t;
        let (w0, w1, w2, w3) = (u * u * u, 3.0 * u * u * t, 3.0 * u * t * t, t * t * t);
        let east = w0 * p0.0 + w1 * p1.0 + w2 * p2.0 + w3 * p3.0;
        let north = w0 * p0.1 + w1 * p1.1 + w2 * p2.1 + w3 * p3.1;
        out.push(offset_degrees(node, east, north));
    }
    Some(out)
}

/// Which lane of the exit a connector lands in.
///
/// Inference, and the ordinary road convention; the rule is written out in full in the module docs.
/// `rank` is this lane's place, left→right, among the `siblings` approach lanes making the same
/// movement into the same exit — which is what keeps a dual left turn two ribbons wide instead of
/// two ribbons on one point. A movement only one lane makes passes `rank` 0 and `siblings` 1, and
/// reduces to "the exit's leftmost" or "its rightmost".
fn exit_lane(
    turn: Turn,
    in_lane: usize,
    in_lanes: usize,
    out_lanes: usize,
    rank: usize,
    siblings: usize,
) -> usize {
    let last = out_lanes.saturating_sub(1);
    if turn.is_left() {
        rank.min(last)
    } else if turn.is_right() {
        last - siblings.saturating_sub(1).saturating_sub(rank).min(last)
    } else {
        (in_lane * out_lanes / in_lanes.max(1)).min(last)
    }
}

/// The exits a lane tagged for `turn` should connect to.
///
/// Exact bucket first: every legal exit the junction's own geometry classes as this movement. When
/// none matches — a `turn:lanes=right` onto a road that leaves at 140°, say — the single closest
/// exit by angle is used instead, because a tagged indication with nowhere to go is far more likely
/// to be a threshold disagreement than a lane that leads nowhere.
fn exits_for(turn: Turn, legal: &[(usize, f64, Turn)]) -> Vec<usize> {
    // A lane tagged `turn:lanes=reverse` has nowhere to go, because reversals are not legal
    // movements here. Returning empty rather than falling through matters: `legal` holds no
    // reversal, so the closest-by-angle fallback below would attach the lane to whatever sharp
    // turn happens to be nearest and emit a connector into a road the lane does not feed.
    if turn == Turn::Reverse {
        return Vec::new();
    }
    let exact: Vec<usize> =
        legal.iter().filter(|(_, _, t)| *t == turn).map(|(i, _, _)| *i).collect();
    if !exact.is_empty() {
        return exact;
    }
    let nominal = turn.nominal_degrees();
    legal
        .iter()
        .min_by(|a, b| {
            normalise_degrees(a.1 - nominal)
                .abs()
                .total_cmp(&normalise_degrees(b.1 - nominal).abs())
        })
        .map(|(i, _, _)| vec![*i])
        .unwrap_or_default()
}
