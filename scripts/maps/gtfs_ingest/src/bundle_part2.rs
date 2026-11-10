/// Run length, in samples, from which the snap search in [`spans_of`] goes wide. Changes no
/// answer.
const PAR_SNAP: usize = 64;

/// One candidate's spans, in its own order of travel.
fn spans_of(
    candidate: &Candidate,
    runs: &[Run],
    samples: &[Sample],
    probe_cum: &[f64],
    own_cum: &[f64],
    corridors: &BTreeMap<u32, Corridor>,
) -> Vec<Span> {
    let whole = || {
        vec![Span { points: candidate.points.to_vec(), ordinal: 0, lanes: 1, taper: 255 }]
    };
    if runs.is_empty() || (runs.len() == 1 && !corridors.contains_key(&runs[0].set)) {
        return whole();
    }

    // Per run independent, and collected in run order.
    let placements: Vec<Option<Placement>> = runs
        .par_iter()
        .map(|run| {
            let corridor = corridors.get(&run.set)?;
            let a = distance_along(corridor, samples[run.from].point);
            let b = distance_along(corridor, samples[run.to].point);
            let (forward, lo, hi) = if a <= b { (true, a, b) } else { (false, b, a) };
            // A run with no usable extent on the reference — the two ends project to one
            // place — has nothing to snap to, so the candidate keeps its own geometry there.
            if hi - lo < SAMPLE_M {
                return None;
            }
            let near = |at: usize| within(corridor, samples[at].point, SNAP_M);
            // Each probe is a walk of the whole reference, so a long run on a long corridor is
            // the single most expensive thing in the cut. `find_first` / `find_last` are the
            // sequential first and last match exactly, just searched wide.
            let snap = if run.to - run.from < PAR_SNAP {
                (run.from..=run.to).find(|&at| near(at)).map(|first| {
                    (first, (first..=run.to).rev().find(|&at| near(at)).unwrap_or(first))
                })
            } else {
                (run.from..run.to + 1).into_par_iter().with_min_len(8).find_first(|&at| near(at)).map(|first| {
                    (first, (first..run.to + 1).into_par_iter().with_min_len(8).find_last(|&at| near(at)).unwrap_or(first))
                })
            };
            Some(Placement { corridor, forward, lo, hi, snap })
        })
        .collect();

    // Per run: its pieces and the points it begins and ends at, both in the candidate's order
    // of travel, and whether that order runs against the reference's.
    let mut pieces: Vec<Vec<Span>> = Vec::with_capacity(runs.len());
    let mut ends: Vec<Option<Bounds>> = Vec::with_capacity(runs.len());
    let mut against: Vec<bool> = Vec::with_capacity(runs.len());

    for (at, run) in runs.iter().enumerate() {
        let (own_from, own_to) = (probe_cum[run.from], probe_cum[run.to]);
        let own_piece = |from: f64, to: f64, ordinal: u8, lanes: u8, taper: u8| -> Option<Span> {
            let points = slice_between(candidate.points, own_cum, from, to);
            (points.len() >= 2).then_some(Span { points, ordinal, lanes, taper })
        };

        let Some(place) = &placements[at] else {
            let out: Vec<Span> = own_piece(own_from, own_to, 0, 1, 255).into_iter().collect();
            ends.push(bounds_of(&out));
            pieces.push(out);
            against.push(false);
            continue;
        };
        let corridor = place.corridor;
        let lanes = u8::try_from(corridor.colours.len()).unwrap_or(u8::MAX);
        let ordinal = corridor
            .colours
            .iter()
            .position(|c| *c == candidate.color)
            .and_then(|at| u8::try_from(at).ok())
            .unwrap_or(0);

        let Some((first, last)) = place.snap else {
            let out: Vec<Span> =
                own_piece(own_from, own_to, ordinal, lanes, 255).into_iter().collect();
            ends.push(bounds_of(&out));
            pieces.push(out);
            against.push(!place.forward);
            continue;
        };

        // A taper means one thing: opening the fan off the member's own alignment. A corridor
        // of one colour has no fan to open, and where the neighbouring run is on a corridor
        // too there is no own alignment to leave — the line is in a lane on both sides and
        // steps from one to the other, which is a fraction of the fan rather than the whole
        // of it dipping through zero.
        let fanned = lanes > 1;
        let eases = |other: Option<usize>| {
            fanned && matches!(other, Some(i) if placements[i].is_none())
        };
        let at_start = eases(at.checked_sub(1));
        let at_end = eases((at + 1 < runs.len()).then_some(at + 1));

        // The ease runs from the run's start to where the member's own survey first comes
        // within `SNAP_M`, which makes it adaptive: a shallow convergence gets a long ease
        // and a sharp junction a short one. At least `TAPER_M` so the offset has somewhere to
        // ramp when the member converges at once, and never more than a quarter of the run so
        // the body keeps a usable share of it.
        let extent = own_to - own_from;
        let ease = |to_snap: f64| to_snap.max(TAPER_M).min(extent / 4.0);
        let lead = if at_start { ease(probe_cum[first] - own_from) } else { 0.0 };
        let trail = if at_end { ease(own_to - probe_cum[last]) } else { 0.0 };

        // Where the body meets the reference: the handover point where there is an ease, and
        // the run boundary where there is not.
        let ref_at =
            |own: f64| distance_along(corridor, point_at(candidate.points, own_cum, own));
        let (from_end, to_end) =
            if place.forward { (place.lo, place.hi) } else { (place.hi, place.lo) };
        let body_from = if lead > 0.0 { ref_at(own_from + lead) } else { from_end };
        let body_to = if trail > 0.0 { ref_at(own_to - trail) } else { to_end };

        let mut out: Vec<Span> = Vec::new();
        if lead > 0.0 {
            let step = lead / TAPER_STEPS as f64;
            for k in 0..TAPER_STEPS {
                let base = own_from + k as f64 * step;
                out.extend(own_piece(base, base + step, ordinal, lanes, taper_fraction(k + 1)));
            }
        }
        let mut body = slice_between(&corridor.points, &corridor.cum, body_from, body_to);
        if !place.forward {
            body.reverse();
        }
        let mut body_exit = None;
        if body.len() >= 2 {
            let (head, tail) = (body[0], body[body.len() - 1]);
            // The ease is on the member's own survey and the body is on the reference, up to
            // `SNAP_M` apart. Carry one vertex across so the two meet, the same trick the
            // inter-run weld below uses. The ease pieces themselves are adjoining slices of
            // one polyline and already share their boundary vertex exactly.
            if let Some(lead_end) = out.last_mut() {
                if lead_end.points.last() != Some(&head) {
                    lead_end.points.push(head);
                }
            }
            out.push(Span { points: body, ordinal, lanes, taper: 255 });
            body_exit = Some(tail);
        }
        if trail > 0.0 {
            let step = trail / TAPER_STEPS as f64;
            let starts_at = out.len();
            for k in 0..TAPER_STEPS {
                let base = own_to - trail + k as f64 * step;
                out.extend(own_piece(
                    base,
                    base + step,
                    ordinal,
                    lanes,
                    taper_fraction(TAPER_STEPS - k),
                ));
            }
            if let (Some(tail), Some(trail_start)) = (body_exit, out.get_mut(starts_at)) {
                if trail_start.points.first() != Some(&tail) {
                    trail_start.points.insert(0, tail);
                }
            }
        }

        ends.push(bounds_of(&out));
        pieces.push(out);
        against.push(!place.forward);
    }

    // Two runs meet at a point each of them located only to a sample step, and on two
    // different polylines: a member's own survey and a reference, or two references where a
    // route steps straight from one corridor into the next. Carry one across to the other so
    // the pieces meet rather than leaving a gap at the seam — the corridor's own geometry
    // where there is one to preserve, and the run that follows otherwise.
    for at in 1..pieces.len() {
        let (Some(before), Some(after)) = (ends[at - 1], ends[at]) else { continue };
        if placements[at - 1].is_some() && placements[at].is_none() {
            let head = pieces[at].first_mut().expect("bounds imply a piece");
            if head.points.first() != Some(&before.1) {
                head.points.insert(0, before.1);
            }
        } else {
            let tail = pieces[at - 1].last_mut().expect("bounds imply a piece");
            if tail.points.last() != Some(&after.0) {
                tail.points.push(after.0);
            }
        }
    }

    // Every piece of a corridor run is stored in the reference's direction, so all its
    // members share one left-hand normal and ordinal `i` is the same physical side for all of
    // them. An ease piece is cut from the member's own survey and inherits that survey's
    // direction, so for a member travelling against the reference it has to be turned round
    // here or the fan mirrors across the ease. Only the order the pieces are listed in
    // follows the candidate.
    for (run_pieces, against) in pieces.iter_mut().zip(&against) {
        if *against {
            for span in run_pieces.iter_mut() {
                span.points.reverse();
            }
        }
    }

    let flat: Vec<Span> = pieces.into_iter().flatten().collect();
    if flat.is_empty() {
        whole()
    } else {
        flat
    }
}

/// Cumulative ground distance to each vertex of a polyline.
fn cumulative(points: &[(i32, i32)]) -> Vec<f64> {
    let mut out = Vec::with_capacity(points.len());
    let mut total = 0.0;
    for (at, point) in points.iter().enumerate() {
        if at > 0 {
            total += distance_m(points[at - 1], *point);
        }
        out.push(total);
    }
    out
}

/// The point `at` metres along a polyline, interpolated within its segment.
fn point_at(points: &[(i32, i32)], cum: &[f64], at: f64) -> (i32, i32) {
    if points.len() < 2 {
        return points.first().copied().unwrap_or((0, 0));
    }
    let last = points.len() - 1;
    if at <= 0.0 {
        return points[0];
    }
    if at >= cum[last] {
        return points[last];
    }
    let seg = cum.partition_point(|d| *d <= at).max(1) - 1;
    let span = cum[seg + 1] - cum[seg];
    let t = if span > 0.0 { (at - cum[seg]) / span } else { 0.0 };
    let (a, b) = (points[seg], points[seg + 1]);
    (
        (a.0 as f64 + (b.0 - a.0) as f64 * t).round() as i32,
        (a.1 as f64 + (b.1 - a.1) as f64 * t).round() as i32,
    )
}

/// The part of a polyline between two distances along it, with both ends interpolated.
///
/// Both ends land exactly on the polyline, so two adjacent slices share their boundary
/// vertex and the pieces of one route meet. Empty when the two distances leave nothing
/// between them.
fn slice_between(points: &[(i32, i32)], cum: &[f64], from: f64, to: f64) -> Vec<(i32, i32)> {
    if points.len() < 2 {
        return Vec::new();
    }
    let total = cum[cum.len() - 1];
    let (from, to) = (from.clamp(0.0, total), to.clamp(0.0, total));
    let (from, to) = if from <= to { (from, to) } else { (to, from) };
    let mut out = vec![point_at(points, cum, from)];
    for (at, along) in cum.iter().enumerate() {
        if *along > from && *along < to && out[out.len() - 1] != points[at] {
            out.push(points[at]);
        }
    }
    let end = point_at(points, cum, to);
    if out[out.len() - 1] != end {
        out.push(end);
    }
    if out.len() < 2 {
        Vec::new()
    } else {
        out
    }
}

/// How far along a corridor's reference the nearest point to `p` lies.
fn distance_along(corridor: &Corridor, p: (i32, i32)) -> f64 {
    nearest(corridor, p).1
}

/// Segments per [`SegBlock`].
const SEG_BLOCK: usize = 32;

/// Slack, in metres, taken off every [`SegBlock::lower_bound`]: far above the nanometres of
/// rounding [`project`] can introduce at planetary coordinates, far below anything a skip
/// could save.
const BOUND_SLACK_M: f64 = 0.01;

/// The extent of a run of consecutive reference segments, in the terms [`project`] measures in.
struct SegBlock {
    lat_lo: i32,
    lat_hi: i32,
    lon_lo: i32,
    lon_hi: i32,
    /// The smallest of the run's per-segment `cos_lat`s, computed exactly as [`project`] does.
    cos_min: f64,
}

impl SegBlock {
    /// A floor under [`project`]'s distance from `p` to any segment of the run.
    ///
    /// `project` measures in metres north (`lat × k`, the same for every segment) and metres
    /// east scaled by the segment's own `cos_lat`. The closest point of a segment lies inside
    /// the segment's box, so its northing is at least the latitude gap from `p` to the run's
    /// box away, and its easting at least the longitude gap times the smallest `cos_lat` in the
    /// run. Less the slack, so the floor holds through rounding.
    fn lower_bound(&self, p: (i32, i32)) -> f64 {
        let k = 1e-7 * 111_320.0;
        let (lat, lon) = (p.0 as f64, p.1 as f64);
        let gy = (self.lat_lo as f64 - lat).max(lat - self.lat_hi as f64).max(0.0) * k;
        let gx = (self.lon_lo as f64 - lon).max(lon - self.lon_hi as f64).max(0.0) * k * self.cos_min.max(0.0);
        (gx * gx + gy * gy).sqrt() - BOUND_SLACK_M
    }
}

fn seg_blocks(points: &[(i32, i32)]) -> Vec<SegBlock> {
    let segs = points.len().saturating_sub(1);
    (0..segs.div_ceil(SEG_BLOCK))
        .map(|b| {
            let (lo, hi) = (b * SEG_BLOCK, ((b + 1) * SEG_BLOCK).min(segs));
            let run = &points[lo..=hi];
            let cos_min = (lo..hi)
                .map(|at| ((points[at].0 as f64 + points[at + 1].0 as f64) * 0.5 * 1e-7).to_radians().cos())
                .fold(f64::INFINITY, f64::min);
            SegBlock {
                lat_lo: run.iter().map(|q| q.0).min().expect("two points"),
                lat_hi: run.iter().map(|q| q.0).max().expect("two points"),
                lon_lo: run.iter().map(|q| q.1).min().expect("two points"),
                lon_hi: run.iter().map(|q| q.1).max().expect("two points"),
                cos_min,
            }
        })
        .collect()
}

/// The segments of block `b` of a polyline with `segs` segments.
fn block_segs(b: usize, segs: usize) -> std::ops::Range<usize> {
    b * SEG_BLOCK..((b + 1) * SEG_BLOCK).min(segs)
}

/// The nearest point on a corridor's reference to `p`, as `(distance to it, distance along)`.
///
/// The first segment, in reference order, at the smallest [`project`] distance — what a walk
/// of every segment keeping strict improvements returns, which is what this used to be. That
/// walk was most of the corridor cut on a world set: one per probe, over references of tens of
/// thousands of points. Now the block with the lowest floor is measured first; its best
/// distance bounds the answer from above, every block whose floor is over that bound is
/// skipped — it cannot hold the minimum, nor tie it — and the rest are walked in reference
/// order exactly as before.
fn nearest(corridor: &Corridor, p: (i32, i32)) -> (f64, f64) {
    let (points, cum) = (&corridor.points, &corridor.cum);
    let segs = points.len().saturating_sub(1);
    let blocks = &corridor.blocks;
    let mut bound = f64::INFINITY;
    if blocks.len() > 2 {
        let mut seed = (f64::INFINITY, 0usize);
        for (b, block) in blocks.iter().enumerate() {
            let lb = block.lower_bound(p);
            if lb < seed.0 {
                seed = (lb, b);
            }
        }
        for at in block_segs(seed.1, segs) {
            let offset = project(p, points[at], points[at + 1], 0.0).1;
            if offset < bound {
                bound = offset;
            }
        }
    }
    let mut best = (f64::INFINITY, 0.0f64);
    for (b, block) in blocks.iter().enumerate() {
        if block.lower_bound(p) > bound {
            continue;
        }
        for at in block_segs(b, segs) {
            let (t, offset) = project(p, points[at], points[at + 1], 0.0);
            if offset < best.0 {
                best = (offset, cum[at] + t * (cum[at + 1] - cum[at]));
            }
        }
    }
    best
}

/// `nearest(corridor, p).0 <= radius`, deciding as soon as any segment is that close and
/// skipping every block whose floor is already further.
fn within(corridor: &Corridor, p: (i32, i32), radius: f64) -> bool {
    let (points, segs) = (&corridor.points, corridor.points.len().saturating_sub(1));
    corridor.blocks.iter().enumerate().any(|(b, block)| {
        block.lower_bound(p) <= radius
            && block_segs(b, segs).any(|at| project(p, points[at], points[at + 1], 0.0).1 <= radius)
    })
}

/// How far to the side of a corridor's reference `p` lies, in metres, signed positive to the
/// right of the reference's direction of travel.
///
/// The same nearest-segment walk [`distance_along`] does, because [`project`] returns an
/// unsigned distance and no side. Against the nearest segment's unit tangent `(ux, uy)` in an
/// (east, north) frame — the frame [`directed`] already produces — the side of the offset `d`
/// is `d.east * uy - d.north * ux`. Positive is the right-hand side because `tess::stroke`
/// emits `(0, +1)` as the normal of an eastward segment and `line.vert` shifts along it, so
/// ascending offset is ascending ordinal: `lane_offset_px` gives ordinal zero the most
/// negative offset, which is the left of the reference's travel.
///
/// Only the reference's own stretch of the corridor, `extent`, is searched. Past the mouth the
/// reference has left the corridor too, and the segment nearest an approaching member is then
/// as likely to be the reference's departure as the corridor — which reads a member leaving
/// west as having no side at all. Clamped, the approach is measured against the reference's
/// tangent at the mouth, which is the axis the lanes are laid out across.
fn signed_offset(corridor: &Corridor, extent: (f64, f64), p: (i32, i32)) -> f64 {
    let (points, cum) = (&corridor.points, &corridor.cum);
    let mut best = (f64::INFINITY, 0.0f64);
    for at in 0..points.len().saturating_sub(1) {
        if cum[at + 1] < extent.0 || cum[at] > extent.1 {
            continue;
        }
        let (a, b) = (points[at], points[at + 1]);
        let (_, distance) = project(p, a, b, 0.0);
        if distance >= best.0 {
            continue;
        }
        let cos_lat = ((a.0 as f64 + b.0 as f64) * 0.5 * 1e-7).to_radians().cos();
        let (east, north) = ((b.1 - a.1) as f64 * cos_lat, (b.0 - a.0) as f64);
        let length = (east * east + north * north).sqrt();
        if length <= 0.0 {
            continue;
        }
        let (ux, uy) = (east / length, north / length);
        let d_east = (p.1 - a.1) as f64 * 1e-7 * 111_320.0 * cos_lat;
        let d_north = (p.0 - a.0) as f64 * 1e-7 * 111_320.0;
        best = (distance, d_east * uy - d_north * ux);
    }
    best.1
}

/// One probe point along a candidate, with the unit direction of travel there.
struct Sample {
    route: u32,
    point: (i32, i32),
    ux: f64,
    uy: f64,
}

/// One walked sample of a line: its point and the unit direction of travel there.
///
/// Exposed so a caller that runs several of [`Covered`]'s gates over one line can walk it
/// once and hand the samples to each, rather than every gate re-walking the same points.
pub type Walked = ((i32, i32), f64, f64);

/// Walk a polyline at [`SAMPLE_M`], returning each sample with its unit direction of travel.
pub fn walk(points: &[(i32, i32)]) -> Vec<Walked> {
    let walked = resample(points, SAMPLE_M);
    if walked.len() < 2 {
        return Vec::new();
    }
    directed(&walked)
}

/// Each point of `line` with the unit direction of travel there. One entry per input point:
/// a degenerate segment carries the previous direction forward rather than dropping the
/// point, so the result stays index-aligned with its input.
fn directed(line: &[(i32, i32)]) -> Vec<((i32, i32), f64, f64)> {
    let mut out = Vec::with_capacity(line.len());
    let (mut ux, mut uy) = (1.0f64, 0.0f64);
    for i in 0..line.len() {
        let (a, b) = if i + 1 < line.len() { (line[i], line[i + 1]) } else { (line[i.max(1) - 1], line[i]) };
        let cos_lat = ((a.0 as f64 + b.0 as f64) * 0.5 * 1e-7).to_radians().cos();
        let dy = (b.0 - a.0) as f64;
        let dx = (b.1 - a.1) as f64 * cos_lat;
        let length = (dx * dx + dy * dy).sqrt();
        if length > 0.0 {
            (ux, uy) = (dx / length, dy / length);
        }
        out.push((line[i], ux, uy));
    }
    out
}

/// The nine cells a corridor-radius match can live in, centre first.
///
/// Returning on the first match means visit order decides how much of the neighbourhood is
/// scanned. The point's own cell is by far the likeliest to hold it — a corridor of 30 m inside a
/// cell of 40 m — so it goes first, then the four edge neighbours, then the corners, which can
/// only match across a cell join. Visiting them in `-1..=1` order put a corner first and scanned
/// most of the neighbourhood before reaching the answer.
///
/// A pure reordering: the set of samples examined is unchanged, so every result is too.
const NEIGHBOURHOOD: [(i32, i32); 9] =
    [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)];

/// Track already drawn, for suppressing a line that adds nothing.
///
/// Two services of one colour must never draw as parallel lines: nothing distinguishes them,
/// so the second is pure over-draw. What stops that is the lane, which is assigned per
/// **colour** — two lines of one colour in one corridor take the same offset over the same
/// reference geometry and coincide exactly, reading as the one line they are.
///
/// This is the other half: a line wholly on top of track already drawn in its colour is not
/// worth carrying at all. A route publishes its two directions, its short-turns and its
/// branch variants as separate shapes, and every other feed covering the city republishes
/// the lot re-surveyed a few metres off.
///
/// **Whole lines only.** Subtracting the covered *parts* of a line was tried and is wrong: it
/// cut every route into fragments, and a stretch too short to be worth emitting left a hole
/// that nothing else drew. A line either adds something or it does not.
///
/// The lookup is a `HashMap` of cells rather than the sorted vector the rest of this module
/// uses. Nothing iterates it — the answer is a boolean about one point — so it cannot leak an
/// order into the output.
pub struct Covered {
    /// Every drawn sample, bucketed by its cell.
    ///
    /// **One representative per cell per service**: a bucket holds at most one sample of each
    /// tag. That is enough for [`covers`](Covered::covers) and [`tags_over`](Covered::tags_over):
    /// both ask whether *any* sample of a service is within a corridor of a point, and every
    /// sample of one service in one cell answers that identically. Keeping only the first turns
    /// a cell's occupancy from overlap depth — a trunk republished by a dozen feeds is a dozen
    /// sample runs in the same cells — into distinct services, which is what those gates read.
    /// It also makes "is this service already in this cell" a scan of a handful of entries in a
    /// bucket already being looked up, rather than a second set of `(cell, tag)` pairs.
    ///
    /// Split into [`COVER_SHARDS`] maps by [`shard_of`], so a long line's add fills them all
    /// at once. A pure partition: every cell lives in exactly one shard, and each shard sees its
    /// cells' samples in line order, so every bucket ends up holding exactly what one map would.
    cells: Vec<std::collections::HashMap<Cell, Bucket, CellHash>>,
    /// How many `add` calls this cover has taken; see [`Covered::mark`].
    adds: u32,
}

impl Default for Covered {
    fn default() -> Self {
        Covered { cells: (0..COVER_SHARDS).map(|_| Default::default()).collect(), adds: 0 }
    }
}

/// Shards per [`Covered`]. Changes no answer; only how wide an add can go.
const COVER_SHARDS: usize = 64;

/// The shard holding `cell`. Mixed independently of [`CellHasher`], so the cells of one shard
/// still spread over its own table.
fn shard_of((x, y): Cell) -> usize {
    let h = (x as u32).wrapping_mul(0x9E37_79B1) ^ (y as u32).wrapping_mul(0x85EB_CA77);
    (h.wrapping_mul(0xC2B2_AE35) >> 26) as usize % COVER_SHARDS
}

/// One cell's representatives, in the order they were added.
struct Bucket {
    /// The `adds` count of the last add that appended here.
    added: u32,
    /// Inline, because most buckets — every bucket of a colour's cover — hold exactly one.
    first: Drawn,
    rest: Vec<Drawn>,
}

impl Bucket {
    fn iter(&self) -> impl Iterator<Item = &Drawn> {
        std::iter::once(&self.first).chain(self.rest.iter())
    }
}

/// One drawn sample and the service that drew it.
#[derive(Clone, Copy)]
struct Drawn {
    point: (i32, i32),
    ux: f64,
    uy: f64,
    tag: u32,
}
