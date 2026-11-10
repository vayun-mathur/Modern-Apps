/// Absorb every run shorter than [`MIN_SHARED_M`] into a neighbour, longest neighbour first,
/// and coalesce the neighbours that end up equal.
///
/// Without this a route drifting in and out of another's tolerance flips membership sample
/// to sample and shatters into dozens of features, most of them a few metres long.
fn smooth(runs: &mut Vec<Run>, cum: &[f64]) {
    let length = |run: &Run| cum[run.to] - cum[run.from];
    while runs.len() > 1 {
        let Some(at) = (0..runs.len())
            .filter(|&i| length(&runs[i]) < MIN_SHARED_M)
            .min_by(|&a, &b| length(&runs[a]).total_cmp(&length(&runs[b])).then(a.cmp(&b)))
        else {
            break;
        };
        let into = match (at.checked_sub(1), runs.get(at + 1)) {
            (None, _) => at + 1,
            (Some(before), None) => before,
            (Some(before), Some(after)) => {
                if length(after) > length(&runs[before]) {
                    at + 1
                } else {
                    before
                }
            }
        };
        if into < at {
            runs[into].to = runs[at].to;
        } else {
            runs[into].from = runs[at].from;
        }
        runs.remove(at);
        let mut i = 1;
        while i < runs.len() {
            if runs[i].set == runs[i - 1].set {
                runs[i - 1].to = runs[i].to;
                runs.remove(i);
            } else {
                i += 1;
            }
        }
    }
}

/// Every membership that survived as a run of more than one route, with the geometry and the
/// lane order its members share.
///
/// No transitive closure. Unioning the members of neighbouring corridors was tried and is
/// badly wrong: BART shares Market Street with Muni Metro, Millbrae with Caltrain, Caltrain
/// shares San Jose with Capitol Corridor and ACE, and following that chain collapsed the
/// whole west-coast rail network into one corridor of twenty-three. A corridor is a local
/// thing, and two routes that share one see the same local membership, so they agree on the
/// reference and on the lane order without anything having to reconcile them.
///
/// Nor is there a corridor adjacency graph to propagate an order along. The order is read off
/// the geometry at each corridor independently, from the members' own untouched surveys, and
/// two abutting corridors agree because the ground does — the routes really are arranged that
/// way at the seam.
fn corridors_of(
    candidates: &[Candidate],
    sets: &[Vec<u32>],
    runs: &[Vec<Run>],
    named: &[Option<(u32, &str)>],
    own_cum: &[Vec<f64>],
    probe_cum: &[Vec<f64>],
) -> BTreeMap<u32, Corridor> {
    // Per corridor, each member run as the candidate it belongs to and where the run begins
    // and ends along that candidate's own survey — which is what the approach is measured
    // just outside of.
    let mut members: BTreeMap<u32, Vec<(usize, f64, f64)>> = BTreeMap::new();
    for (at, candidate_runs) in runs.iter().enumerate() {
        for run in candidate_runs {
            if sets[run.set as usize].len() > 1 {
                members
                    .entry(run.set)
                    .or_default()
                    .push((at, probe_cum[at][run.from], probe_cum[at][run.to]));
            }
        }
    }
    let length = |at: usize| own_cum[at].last().copied().unwrap_or(0.0);
    // Every corridor projects every member onto the reference geometry, so the work is members
    // times reference length per corridor, and on a world set that ran for the better part of
    // an hour on one core.
    //
    // Each corridor is an independent pure function of read-only shared state — it reads
    // `candidates`, `sets`, `named` and `own_cum` and writes only its own entry — so the loop
    // spreads across the machine with no coordination beyond handing out indices. Results go
    // into a `BTreeMap` keyed by set id, which is ordered by key rather than by insertion, so
    // the output is byte-identical however the threads interleave.
    let entries: Vec<(u32, Vec<(usize, f64, f64)>)> = members.into_iter().collect();
    let total = entries.len();
    let threads = crate::par::threads();
    let next = AtomicUsize::new(0);
    let finished = AtomicUsize::new(0);
    let collected: Mutex<Vec<(u32, Corridor)>> = Mutex::new(Vec::with_capacity(total));
    std::thread::scope(|scope| {
        for _ in 0..threads.min(total.max(1)) {
            scope.spawn(|| {
                // Accumulated per thread and merged once at the end: locking per corridor
                // would serialise the very loop this is spreading out.
                let mut local: Vec<(u32, Corridor)> = Vec::new();
                loop {
                    let i = next.fetch_add(1, AtomicOrdering::Relaxed);
                    if i >= total {
                        break;
                    }
                    let (set, in_corridor) = &entries[i];
                    local.push((
                        *set,
                        one_corridor(*set, in_corridor, candidates, sets, named, own_cum, &length),
                    ));
                    // Redrawn on a count, not a percentage: the threads finish out of order, so
                    // "the whole number changed" is not a thing any one of them can see.
                    let n = finished.fetch_add(1, AtomicOrdering::Relaxed) + 1;
                    if n % 128 == 0 {
                        eprint!(
                            "\r{:<28} [{:>3}%] {total} corridor(s)",
                            "Corridor order",
                            n * 100 / total.max(1)
                        );
                    }
                }
                collected.lock().expect("corridor pool").extend(local);
            });
        }
    });
    eprintln!("\r{:<28} [100%] {total} corridor(s)", "Corridor order");
    collected.into_inner().expect("corridor pool").into_iter().collect()
}

/// One corridor's reference geometry and the order its colours sit in across it.
///
/// Split out of [`corridors_of`] so the loop there can run on every core: this reads only
/// shared immutable state and returns an owned value, which is what makes that safe.
#[allow(clippy::too_many_arguments)]
fn one_corridor(
    set: u32,
    in_corridor: &[(usize, f64, f64)],
    candidates: &[Candidate],
    sets: &[Vec<u32>],
    named: &[Option<(u32, &str)>],
    own_cum: &[Vec<f64>],
    length: &dyn Fn(usize) -> f64,
) -> Corridor {
    {
        {
            // Whose geometry the corridor draws — and only that. It no longer decides the
            // direction, which is canonical, nor the order, which is the approaches. The
            // first member by colour and then name, so it does not move when a feed reorders
            // its routes. Between two polylines of that one member — a trunk and the
            // short-turn that runs half of it — the longer, because a shorter reference clips
            // every other member to itself.
            let pick = in_corridor
                .iter()
                .map(|&(at, _, _)| at)
                .reduce(|a, b| {
                    let better = match (candidates[a].color, candidates[a].name)
                        .cmp(&(candidates[b].color, candidates[b].name))
                    {
                        Ordering::Less => true,
                        Ordering::Greater => false,
                        Ordering::Equal => match length(b).total_cmp(&length(a)) {
                            Ordering::Less => true,
                            Ordering::Greater => false,
                            Ordering::Equal => candidates[a].points <= candidates[b].points,
                        },
                    };
                    if better {
                        a
                    } else {
                        b
                    }
                })
                .expect("a corridor has at least one member");
            let points = canonical(candidates[pick].points);
            let cum = cumulative(&points);
            let reference = Corridor::new(points, cum, Vec::new());

            // Where each member sits across the corridor, averaged over the colour: two
            // routes of one colour share a lane, so they share one place in the order.
            // Measured against the corridor's own stretch of the reference, which is every
            // member's run projected onto it.
            let projected = |at: usize, along: f64| {
                distance_along(&reference, point_at(candidates[at].points, &own_cum[at], along))
            };
            let extent = in_corridor.iter().fold(
                (f64::INFINITY, f64::NEG_INFINITY),
                |(lo, hi), &(at, from, to)| {
                    let (a, b) = (projected(at, from), projected(at, to));
                    (lo.min(a).min(b), hi.max(a).max(b))
                },
            );
            // Less a taper at each end. A run boundary is only located to a sample step, so
            // the extent bleeds a little past the mouth — and one segment past the mouth the
            // reference has left the corridor too and is heading wherever it goes next, which
            // is not an axis to measure anyone's side against.
            let margin = TAPER_M.min((extent.1 - extent.0) / 4.0);
            let extent = (extent.0 + margin, extent.1 - margin);
            let mut sides: BTreeMap<u32, (f64, u32)> = BTreeMap::new();
            for &(at, from, to) in in_corridor {
                let side = approach_offset(
                    &reference,
                    extent,
                    candidates[at].points,
                    &own_cum[at],
                    from,
                    to,
                );
                let seen = sides.entry(candidates[at].color).or_insert((0.0, 0));
                seen.0 += side;
                seen.1 += 1;
            }
            // A route can be in the membership without having a run of its own here, in which
            // case it draws nothing in this corridor and there is no approach to measure. It
            // still occupies a colour, so it still takes a place: the reference's own, zero.
            let mut ordered: Vec<(f64, u32, &str)> = sets[set as usize]
                .iter()
                .map(|&member| {
                    let (colour, name) = named[member as usize].unwrap_or((0, ""));
                    let side = sides
                        .get(&colour)
                        .map(|&(sum, n)| sum / f64::from(n))
                        .unwrap_or(0.0);
                    (side, colour, name)
                })
                .collect();
            // Ascending offset is ascending ordinal, because `lane_offset_px` gives ordinal
            // zero the most negative offset and a negative offset is the left of the
            // reference's travel. The `(colour, name)` tail is the tie-break for two surveys
            // that genuinely coincide, and is what keeps a rebuild byte-identical.
            ordered.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.cmp(b.2)));
            let mut colours: Vec<u32> = ordered.into_iter().map(|(_, c, _)| c).collect();
            colours.dedup();
            Corridor { colours, ..reference }
        }
    }
}

/// Fold a polyline into a fixed half-plane: northward, or exactly east-west and eastward.
///
/// The lane side is measured along the reference, so the direction the reference is stored in
/// mirrors the whole fan. Inheriting it from whichever member won the name tie-break means a
/// lower-coloured route joining at a junction can flip the arrangement of everyone else.
/// Abutting corridors run roughly parallel where they meet, so folding both by their own
/// first-to-last chord makes them fold the same way and the fan does not mirror across the
/// seam. The same idea as [`COS_FOLD`]'s fold to `[0, 180)`, applied to a whole polyline.
fn canonical(points: &[(i32, i32)]) -> Vec<(i32, i32)> {
    let (Some(first), Some(last)) = (points.first(), points.last()) else {
        return points.to_vec();
    };
    // Signs only, so the longitude scaling that would make these metres cannot change the
    // answer and is not worth doing.
    let (north, east) = (last.0 - first.0, last.1 - first.1);
    if north < 0 || (north == 0 && east < 0) {
        points.iter().rev().copied().collect()
    } else {
        points.to_vec()
    }
}

/// Which side of the corridor one member arrives on, in metres, positive to the right of the
/// reference's direction of travel.
///
/// The **approach** — the stretch of the member's own untouched survey immediately before it
/// enters the corridor — rather than the shared stretch, where every member is within a
/// couple of metres of the reference and the sign is noise. A run that starts at the line's
/// own start has no approach and uses the departure instead; one that is the whole line has
/// neither, and falls back to the mean across the run, which is weaker but still correct.
fn approach_offset(
    corridor: &Corridor,
    extent: (f64, f64),
    points: &[(i32, i32)],
    own_cum: &[f64],
    from: f64,
    to: f64,
) -> f64 {
    let total = own_cum.last().copied().unwrap_or(0.0);
    let (lo, hi) = if from <= to { (from, to) } else { (to, from) };
    let (a, b) = if lo > 0.0 {
        ((lo - APPROACH_M).max(0.0), lo)
    } else if hi < total {
        (hi, (hi + APPROACH_M).min(total))
    } else {
        (lo, hi)
    };
    let mut sum = 0.0;
    for k in 0..APPROACH_SAMPLES {
        let t = k as f64 / (APPROACH_SAMPLES - 1) as f64;
        sum += signed_offset(corridor, extent, point_at(points, own_cum, a + (b - a) * t));
    }
    sum / APPROACH_SAMPLES as f64
}

/// The taper fraction of step `step` of [`TAPER_STEPS`], climbing toward but never reaching
/// 255 — the full lane belongs to the corridor span the taper leads into.
fn taper_fraction(step: usize) -> u8 {
    (255.0 * step as f64 / (TAPER_STEPS + 1) as f64).round() as u8
}

/// Where a run begins and ends on the ground, as `(entry, exit)` in the candidate's own
/// order of travel.
type Bounds = ((i32, i32), (i32, i32));

/// Where one run of a candidate is drawn, decided for every run before any of it is emitted.
///
/// A run has to know whether its *neighbours* are on a corridor to know whether to ease into
/// its lane, so the placement is a pass of its own. Testing `corridors.contains_key` at the
/// neighbour is not enough: a run whose two ends project to one place on the reference draws
/// its own geometry despite being a corridor set, and easing against that would leave a step.
struct Placement<'a> {
    corridor: &'a Corridor,
    /// Whether the candidate travels in the reference's direction over this run.
    forward: bool,
    /// The run's extent along the reference.
    lo: f64,
    hi: f64,
    /// The first and last of the run's samples within [`SNAP_M`] of the reference, as indices
    /// into the candidate's own block of samples. `None` where it never comes that close, in
    /// which case it keeps its own survey across the whole run — fanned, but not exactly
    /// parallel, which it was never going to be honestly at that separation.
    snap: Option<(usize, usize)>,
}

/// The points a run begins and ends at, which is what the runs either side have to reach.
fn bounds_of(pieces: &[Span]) -> Option<Bounds> {
    let entry = *pieces.first()?.points.first()?;
    let exit = *pieces.last()?.points.last()?;
    Some((entry, exit))
}
