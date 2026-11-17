//! Corridor grouping, ported from `scripts/maps/gtfs_ingest/src/bundle.rs`
//! and `bundle_part1.rs`.
//!
//! Decides which routes share a corridor and in what order their colours sit
//! across it. Single-threaded and silent: the tool spreads the probe and the
//! corridor loop across the machine with progress bars because a world feed
//! set carries hundreds of millions of samples; a viewport fetch carries
//! dozens of routes, so the same math runs inline with no threads and no
//! stderr traffic. Everything else — constants, the membership/run/corridor
//! shape, the reference pick, the canonical fold, the approach-side order —
//! is the tool's logic verbatim, so the lanes agree with the tile path.

use super::corridor_grid::{Grid, sample_all};
use std::collections::BTreeMap;

/// Two polylines closer than this everywhere are the same line, and two
/// tracks closer than this are one corridor.
pub const CORRIDOR_M: f64 = 30.0;

/// Spacing of the probe samples along a polyline.
pub const SAMPLE_M: f64 = 10.0;

/// How much track two routes have to share before it counts as a corridor,
/// and the smoothing window on the runs.
const MIN_SHARED_M: f64 = 300.0;

/// Cosine of the angle within which two bearings count as parallel (25°),
/// compared on `|u · v|` so opposite directions over one track are parallel.
pub const COS_FOLD: f64 = 0.906_307_787;

/// The shortest ease a member spends coming into its lane.
const TAPER_M: f64 = 100.0;

/// How much of a member's own survey, immediately outside the corridor
/// mouth, decides which side of the corridor it takes.
const APPROACH_M: f64 = 150.0;

/// Points sampled along the approach.
const APPROACH_SAMPLES: usize = 8;

/// One route's polyline, offered for slotting.
pub struct Candidate<'a> {
    /// e7 points in travel order.
    pub points: &'a [(i32, i32)],
    /// Which route this polyline belongs to. Lane membership is per route;
    /// the corridor order is per colour (see [`Corridor::colours`]).
    pub route: u32,
    /// `0xRRGGBB` effective colour (agency colour, or the mode fallback when
    /// the pack carries none).
    pub color: u32,
    /// The route's display name: tie-break keeping a rebuild deterministic.
    pub name: &'a str,
}

/// A maximal stretch of one candidate's samples holding the same membership,
/// as inclusive indices into that candidate's own block of samples.
#[derive(Clone, Copy)]
pub struct Run {
    /// First sample index in the candidate's block.
    pub from: usize,
    /// Last sample index in the candidate's block.
    pub to: usize,
    /// Interned membership-set id.
    pub set: u32,
}

/// A shared corridor: the geometry every member draws, and the lane order.
pub struct Corridor {
    /// One member's polyline, folded into the canonical direction, which the
    /// whole corridor draws slices of.
    pub points: Vec<(i32, i32)>,
    /// Cumulative ground metres along [`Corridor::points`].
    pub cum: Vec<f64>,
    /// Distinct colours ordered across the corridor: index 0 is the
    /// left-hand side of the reference's direction of travel.
    pub colours: Vec<u32>,
}

/// Cut a candidate's samples into runs of constant membership, then smooth
/// away the ones too short to be a corridor.
fn cut_runs(per_sample: &[u32], cum: &[f64]) -> Vec<Run> {
    let Some(&first) = per_sample.first() else {
        return Vec::new();
    };
    let mut runs = vec![Run { from: 0, to: 0, set: first }];
    for (at, &set) in per_sample.iter().enumerate().skip(1) {
        let Some(last) = runs.last_mut() else {
            break;
        };
        if last.set == set {
            last.to = at;
        } else {
            runs.push(Run { from: at, to: at, set });
        }
    }
    smooth(&mut runs, cum);
    runs
}

/// Absorb every run shorter than [`MIN_SHARED_M`] into a neighbour, longest
/// neighbour first, and coalesce neighbours that end up equal.
fn smooth(runs: &mut Vec<Run>, cum: &[f64]) {
    let length = |run: &Run| {
        cum.get(run.to).copied().unwrap_or(0.0) - cum.get(run.from).copied().unwrap_or(0.0)
    };
    while runs.len() > 1 {
        let Some(at) = (0..runs.len())
            .filter(|&i| length(&runs[i]) < MIN_SHARED_M)
            .min_by(|&a, &b| {
                length(&runs[a])
                    .total_cmp(&length(&runs[b]))
                    .then(a.cmp(&b))
            })
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
            let from = runs[at].from;
            if let Some(target) = runs.get_mut(into) {
                target.from = from;
            }
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

/// Fold a polyline into a fixed half-plane: northward, or exactly east-west
/// and eastward. The lane side is measured along the reference, so an
/// inherited direction would mirror the whole fan when a lower-coloured
/// route joins at a junction.
fn canonical(points: &[(i32, i32)]) -> Vec<(i32, i32)> {
    let (Some(&first), Some(&last)) = (points.first(), points.last()) else {
        return points.to_vec();
    };
    let (north, east) = (last.0 - first.0, last.1 - first.1);
    if north < 0 || (north == 0 && east < 0) {
        points.iter().rev().copied().collect()
    } else {
        points.to_vec()
    }
}

/// Which side of the corridor one member arrives on, in metres, positive to
/// the right of the reference's direction of travel. Measured on the
/// approach — the member's own untouched survey just outside the mouth —
/// where the sign is tens of metres, not survey noise.
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
        sum += super::corridor_geom::signed_offset_side(
            &corridor.points,
            &corridor.cum,
            extent,
            point_at_owned(points, own_cum, a + (b - a) * t),
        );
    }
    sum / APPROACH_SAMPLES as f64
}

/// Point query helpers over a candidate's own survey shared by grouping and
/// spans; thin wrappers over [`super::corridor_geom`].
fn point_at_owned(points: &[(i32, i32)], cum: &[f64], at: f64) -> (i32, i32) {
    super::corridor_geom::point_at(points, cum, at)
}

/// Every membership that survived as a multi-route run, with the geometry
/// and lane order its members share. No transitive closure: unioning
/// neighbouring corridors collapses whole networks into one.
pub fn corridors_of(
    candidates: &[Candidate],
    sets: &[Vec<u32>],
    runs: &[Vec<Run>],
    named: &[Option<(u32, &str)>],
    own_cum: &[Vec<f64>],
    probe_cum: &[Vec<f64>],
) -> BTreeMap<u32, Corridor> {
    let mut members: BTreeMap<u32, Vec<(usize, f64, f64)>> = BTreeMap::new();
    for (at, candidate_runs) in runs.iter().enumerate() {
        for run in candidate_runs {
            let Some(set) = sets.get(run.set as usize) else {
                continue;
            };
            if set.len() > 1 {
                let from = probe_cum
                    .get(at)
                    .and_then(|c| c.get(run.from))
                    .copied()
                    .unwrap_or(0.0);
                let to = probe_cum
                    .get(at)
                    .and_then(|c| c.get(run.to))
                    .copied()
                    .unwrap_or(0.0);
                members.entry(run.set).or_default().push((at, from, to));
            }
        }
    }
    let length = |at: usize| own_cum.get(at).and_then(|c| c.last()).copied().unwrap_or(0.0);
    let mut out = BTreeMap::new();
    for (set, in_corridor) in members {
        out.insert(
            set,
            one_corridor(set, &in_corridor, candidates, sets, named, own_cum, &length),
        );
    }
    out
}

/// One corridor's reference geometry and the order its colours sit in.
fn one_corridor(
    set: u32,
    in_corridor: &[(usize, f64, f64)],
    candidates: &[Candidate],
    sets: &[Vec<u32>],
    named: &[Option<(u32, &str)>],
    own_cum: &[Vec<f64>],
    length: &dyn Fn(usize) -> f64,
) -> Corridor {
    // Whose geometry the corridor draws: first member by colour then name
    // (stable across rebuilds), longest on ties (a shorter reference clips
    // every other member to itself).
    let pick = in_corridor
        .iter()
        .map(|&(at, _, _)| at)
        .reduce(|a, b| {
            let better = match (candidates[a].color, candidates[a].name)
                .cmp(&(candidates[b].color, candidates[b].name))
            {
                std::cmp::Ordering::Less => true,
                std::cmp::Ordering::Greater => false,
                std::cmp::Ordering::Equal => match length(b).total_cmp(&length(a)) {
                    std::cmp::Ordering::Less => true,
                    std::cmp::Ordering::Greater => false,
                    std::cmp::Ordering::Equal => candidates[a].points <= candidates[b].points,
                },
            };
            if better {
                a
            } else {
                b
            }
        })
        .unwrap_or(0);
    let points = canonical(candidates[pick].points);
    let cum = super::corridor_geom::cumulative(&points);
    let reference = Corridor { points, cum, colours: Vec::new() };

    let projected = |at: usize, along: f64| {
        distance_along_ref(&reference, point_at_owned(candidates[at].points, &own_cum[at], along))
    };
    let extent = in_corridor.iter().fold((f64::INFINITY, f64::NEG_INFINITY), |(lo, hi), &(at, from, to)| {
        let (a, b) = (projected(at, from), projected(at, to));
        (lo.min(a).min(b), hi.max(a).max(b))
    });
    // Less a taper at each end: a run boundary is only located to a sample
    // step, so the extent bleeds past the mouth, past which the reference is
    // no axis to measure a side against.
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
    // A route in the membership without a run of its own still takes a
    // place (the reference's, zero) since it still occupies a colour.
    let mut ordered: Vec<(f64, u32, &str)> = sets
        .get(set as usize)
        .map(|members| {
            members
                .iter()
                .map(|&member| {
                    let (colour, name) = named
                        .get(member as usize)
                        .copied()
                        .flatten()
                        .unwrap_or((0, ""));
                    let side = sides
                        .get(&colour)
                        .map(|&(sum, n)| sum / f64::from(n))
                        .unwrap_or(0.0);
                    (side, colour, name)
                })
                .collect()
        })
        .unwrap_or_default();
    // Ascending offset is ascending ordinal: ordinal zero takes the most
    // negative offset, the left of the reference's travel. Colour/name tail
    // keeps a rebuild deterministic.
    ordered.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.cmp(b.2)));
    let mut colours: Vec<u32> = ordered.into_iter().map(|(_, c, _)| c).collect();
    colours.dedup();
    Corridor { colours, ..reference }
}

/// How far along a corridor's reference the nearest point to `p` lies.
fn distance_along_ref(corridor: &Corridor, p: (i32, i32)) -> f64 {
    nearest_ref(corridor, p).1
}

/// Nearest point on a corridor's reference to `p`, as `(distance, along)`.
fn nearest_ref(corridor: &Corridor, p: (i32, i32)) -> (f64, f64) {
    let mut best = (f64::INFINITY, 0.0f64);
    for at in 0..corridor.points.len().saturating_sub(1) {
        let a = corridor.points[at];
        let b = corridor.points[at + 1];
        let (t, offset) = super::corridor_geom::project(p, a, b, 0.0);
        if offset < best.0 {
            let seg_len = corridor.cum[at + 1] - corridor.cum[at];
            best = (offset, corridor.cum[at] + t * seg_len);
        }
    }
    best
}

/// Full grouping pipeline: sample, probe memberships single-threaded,
/// intern in sample order, cut runs, build corridors. Returns the per-sample
/// set ids, the per-candidate runs, and the corridors keyed by set id.
#[allow(clippy::too_many_arguments)]
pub fn group(
    candidates: &[Candidate],
    named: &[Option<(u32, &str)>],
) -> (Vec<u32>, Vec<Vec<Run>>, BTreeMap<u32, Corridor>) {
    let (samples, blocks) = sample_all(candidates);
    let grid = Grid::build(&samples);
    let route_count = candidates.iter().map(|c| c.route as usize + 1).max().unwrap_or(0);

    // Single-threaded probe: viewport scale is dozens of routes, so the
    // tool's thread pool buys nothing and only risks logspam on Android.
    let mut sets: Vec<Vec<u32>> = Vec::new();
    let mut set_ids: BTreeMap<Vec<u32>, u32> = BTreeMap::new();
    let mut per_sample: Vec<u32> = Vec::with_capacity(samples.len());
    let mut seen = vec![0u32; route_count.max(1)];
    let mut stamp: u32 = 0;
    let mut near = Vec::new();
    for sample in &samples {
        stamp = stamp.wrapping_add(1);
        grid.routes_near(&samples, sample, &mut near, &mut seen, stamp);
        let id = match set_ids.get(&near) {
            Some(id) => *id,
            None => {
                let id = sets.len() as u32;
                sets.push(near.clone());
                set_ids.insert(near.clone(), id);
                id
            }
        };
        per_sample.push(id);
    }

    let own_cum: Vec<Vec<f64>> =
        candidates.iter().map(|c| super::corridor_geom::cumulative(c.points)).collect();
    let mut offset = 0usize;
    let mut probe_cums: Vec<Vec<f64>> = Vec::with_capacity(candidates.len());
    let mut runs = Vec::with_capacity(candidates.len());
    for (block, own) in blocks.iter().zip(&own_cum) {
        // Probe `k` sits exactly `k * SAMPLE_M` along, last one at the end.
        let total = own.last().copied().unwrap_or(0.0);
        let count = block.len();
        let cum: Vec<f64> = (0..count)
            .map(|k| {
                if k + 1 == count {
                    total
                } else {
                    (k as f64 * SAMPLE_M).min(total)
                }
            })
            .collect();
        let run_block: Vec<u32> = per_sample[offset..offset + block.len()].to_vec();
        runs.push(cut_runs(&run_block, &cum));
        probe_cums.push(cum);
        offset += block.len();
    }

    let corridors = corridors_of(candidates, &sets, &runs, named, &own_cum, &probe_cums);
    (per_sample, runs, corridors)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cand<'a>(
        points: &'a [(i32, i32)],
        route: u32,
        color: u32,
        name: &'a str,
    ) -> Candidate<'a> {
        Candidate { points, route, color, name }
    }

    #[test]
    fn disjoint_routes_share_no_corridor() {
        let a = [(0, 0), (0, 100_000)];
        let b = [(500_000, 0), (500_000, 100_000)];
        let named = [Some((1, "a")), Some((2, "b"))];
        let (_, _, corridors) =
            group(&[cand(&a, 0, 1, "a"), cand(&b, 1, 0xFF0001, "b")], &named);
        assert!(corridors.is_empty(), "far apart: no corridor");
    }

    #[test]
    fn overlapping_routes_share_one_corridor() {
        // Two ~1.1 km lines 5 m apart: well within CORRIDOR_M for their
        // whole shared kilometre, which clears MIN_SHARED_M.
        let a = [(0, 0), (0, 100_000)];
        let b = [(0, 450), (0, 100_450)];
        let named = [Some((1, "a")), Some((2, "b"))];
        let (_, _, corridors) =
            group(&[cand(&a, 0, 0xFF0001, "a"), cand(&b, 1, 0x00FF02, "b")], &named);
        assert_eq!(corridors.len(), 1, "one shared corridor");
        let corridor = corridors.values().next().expect("one corridor");
        assert_eq!(corridor.colours.len(), 2, "both colours ordered across it");
    }
}
