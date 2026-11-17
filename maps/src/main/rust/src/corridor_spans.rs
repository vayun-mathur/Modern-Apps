//! Corridor spans and duplicate suppression, ported from
//! `scripts/maps/gtfs_ingest/src/bundle_part2.rs` (`spans_of` and friends)
//! and `bundle_part3.rs` (`Covered`).
//!
//! Turns grouped runs into drawable spans: each candidate cut at corridor
//! mouths, drawing the corridor's reference geometry over the body with its
//! lane, its own survey across tapers, and welded seams between pieces.
//! Single-threaded like the grouping port: the tool spreads candidates
//! across cores, but a viewport fetch holds dozens of routes.

use super::corridor_geom::{point_at, slice_between};
use super::corridor_group::{Corridor, Run};
use std::collections::{BTreeMap, HashMap};

/// One stretch of a candidate as it should be drawn.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Span {
    /// e7 polyline to emit: own geometry outside a corridor and across the
    /// ease, the corridor's reference geometry over the body.
    pub points: Vec<(i32, i32)>,
    /// This colour's index among the corridor's distinct colours. Zero
    /// outside a corridor. Positional: ascending offset is ascending ordinal.
    pub ordinal: u8,
    /// Distinct colours the corridor carries. One outside a corridor.
    pub lanes: u8,
    /// How far into its lane this piece sits, over 255. 255 outside a
    /// corridor and on a body; less only across a mouth taper.
    pub taper: u8,
}

/// Where a run begins and ends on the ground, as `(entry, exit)` in the
/// candidate's own order of travel.
type Bounds = ((i32, i32), (i32, i32));

/// One run's placement decision, computed for every run before any of it is
/// emitted: a run has to know whether its neighbours ease to decide its own.
struct Placement<'a> {
    corridor: &'a Corridor,
    /// Whether the candidate travels in the reference's direction here.
    forward: bool,
    /// The run's extent along the reference.
    lo: f64,
    hi: f64,
    /// First and last sample indices within [`SNAP_M`] of the reference.
    /// `None` where it never comes that close: the run keeps its own survey
    /// across the whole run, fanned but not exactly parallel.
    snap: Option<(usize, usize)>,
}

/// How close a member's own survey must come to hand over to the reference.
const SNAP_M: f64 = 8.0;

/// Probe spacing the grouping sampled at.
const SAMPLE_M: f64 = 10.0;

/// The shortest ease a member spends coming into its lane.
const TAPER_M: f64 = 100.0;

/// Features in one taper; the fraction ramps equally so each jump is ~1 Dp.
const TAPER_STEPS: usize = 8;

/// The taper fraction of step `step`, climbing toward but never reaching
/// 255 — the full lane belongs to the body the taper leads into.
fn taper_fraction(step: usize) -> u8 {
    (255.0 * step as f64 / (TAPER_STEPS + 1) as f64).round() as u8
}

/// The points a run begins and ends at, which is what the runs either side
/// have to reach.
fn bounds_of(pieces: &[Span]) -> Option<Bounds> {
    let entry = *pieces.first()?.points.first()?;
    let exit = *pieces.last()?.points.last()?;
    Some((entry, exit))
}

/// One candidate's spans, in its own order of travel.
///
/// `runs` are the candidate's smoothed membership runs, `samples` its probe
/// points in block order, `probe_cum`/`own_cum` the probe-space and own-space
/// distances, and `corridors` the shared map from [`super::corridor_group`].
#[allow(clippy::too_many_arguments)]
pub fn spans_of(
    points: &[(i32, i32)],
    color: u32,
    runs: &[Run],
    samples: &[(i32, i32)],
    probe_cum: &[f64],
    own_cum: &[f64],
    corridors: &BTreeMap<u32, Corridor>,
) -> Vec<Span> {
    let whole = || {
        vec![Span { points: points.to_vec(), ordinal: 0, lanes: 1, taper: 255 }]
    };
    if runs.is_empty() || (runs.len() == 1 && !corridors.contains_key(&runs[0].set)) {
        return whole();
    }

    let placements: Vec<Option<Placement>> = runs
        .iter()
        .map(|run| {
            let corridor = corridors.get(&run.set)?;
            let a = distance_along(corridor, samples.get(run.from).copied()?);
            let b = distance_along(corridor, samples.get(run.to).copied()?);
            let (forward, lo, hi) = if a <= b { (true, a, b) } else { (false, b, a) };
            // A run with no usable extent on the reference keeps its own
            // geometry: nothing to snap to.
            if hi - lo < SAMPLE_M {
                return None;
            }
            let near = |at: usize| {
                samples.get(at).map(|&p| nearest_dist(corridor, p) <= SNAP_M).unwrap_or(false)
            };
            let snap = (run.from..=run.to).find(|&at| near(at)).map(|first| {
                (
                    first,
                    (first..=run.to).rev().find(|&at| near(at)).unwrap_or(first),
                )
            });
            Some(Placement { corridor, forward, lo, hi, snap })
        })
        .collect();

    let mut pieces: Vec<Vec<Span>> = Vec::with_capacity(runs.len());
    let mut ends: Vec<Option<Bounds>> = Vec::with_capacity(runs.len());
    let mut against: Vec<bool> = Vec::with_capacity(runs.len());

    for (at, run) in runs.iter().enumerate() {
        let (own_from, own_to) = (
            probe_cum.get(run.from).copied().unwrap_or(0.0),
            probe_cum.get(run.to).copied().unwrap_or(0.0),
        );
        let own_piece = |from: f64, to: f64, ordinal: u8, lanes: u8, taper: u8| -> Option<Span> {
            let points = slice_between(points, own_cum, from, to);
            (points.len() >= 2).then_some(Span { points, ordinal, lanes, taper })
        };

        let Some(place) = placements.get(at).and_then(|p| p.as_ref()) else {
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
            .position(|c| *c == color)
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

        // A taper opens the fan off the member's own alignment: only where a
        // lane exists to open into (more than one colour) and the neighbour
        // is not itself on a corridor (then the line steps lane to lane).
        let fanned = lanes > 1;
        let eases = |other: Option<usize>| {
            fanned && matches!(other, Some(i) if placements.get(i).is_none_or(|p| p.is_none()))
        };
        let at_start = eases(at.checked_sub(1));
        let at_end = eases((at + 1 < runs.len()).then_some(at + 1));

        // The ease runs from the run's start to where the survey first comes
        // within SNAP_M: adaptive (shallow convergence, long ease), at least
        // TAPER_M, never more than a quarter of the run.
        let extent = own_to - own_from;
        let ease = |to_snap: f64| to_snap.max(TAPER_M).min(extent / 4.0);
        let lead = if at_start {
            ease(probe_cum.get(first).copied().unwrap_or(own_from) - own_from)
        } else {
            0.0
        };
        let trail = if at_end {
            ease(own_to - probe_cum.get(last).copied().unwrap_or(own_to))
        } else {
            0.0
        };

        let ref_at =
            |own: f64| distance_along(corridor, point_at(points, own_cum, own));
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
            // The ease is on the member's own survey and the body on the
            // reference, up to SNAP_M apart: carry one vertex across so the
            // two meet.
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

    // Carry one vertex across each seam so pieces meet: the corridor's own
    // geometry where there is one to preserve, the following run otherwise.
    for at in 1..pieces.len() {
        let (Some(before), Some(after)) = (ends[at - 1], ends[at]) else { continue };
        if placements[at - 1].is_some() && placements[at].is_none() {
            let Some(head) = pieces[at].first_mut() else { continue };
            if head.points.first() != Some(&before.1) {
                head.points.insert(0, before.1);
            }
        } else {
            let Some(tail) = pieces[at - 1].last_mut() else { continue };
            if tail.points.last() != Some(&after.0) {
                tail.points.push(after.0);
            }
        }
    }

    // Corridor-run pieces are stored reference-oriented so members share one
    // left normal; ease pieces inherit the survey's direction, so
    // against-reference runs turn round here or the fan mirrors.
    for (run_pieces, is_against) in pieces.iter_mut().zip(&against) {
        if *is_against {
            for span in run_pieces.iter_mut() {
                span.points.reverse();
            }
        }
    }

    let flat: Vec<Span> = pieces.into_iter().flatten().collect();
    if flat.is_empty() {
        vec![Span { points: points.to_vec(), ordinal: 0, lanes: 1, taper: 255 }]
    } else {
        flat
    }
}

/// How far along a corridor's reference the nearest point to `p` lies.
fn distance_along(corridor: &Corridor, p: (i32, i32)) -> f64 {
    nearest_dist_along(corridor, p).1
}

/// Nearest distance to a corridor's reference.
fn nearest_dist(corridor: &Corridor, p: (i32, i32)) -> f64 {
    nearest_dist_along(corridor, p).0
}

/// Nearest point on a corridor's reference to `p`, as `(distance, along)`.
fn nearest_dist_along(corridor: &Corridor, p: (i32, i32)) -> (f64, f64) {
    use super::corridor_geom::project;
    let mut best = (f64::INFINITY, 0.0f64);
    for at in 0..corridor.points.len().saturating_sub(1) {
        let (t, offset) = project(p, corridor.points[at], corridor.points[at + 1], 0.0);
        if offset < best.0 {
            let seg_len = corridor.cum[at + 1] - corridor.cum[at];
            best = (offset, corridor.cum[at] + t * seg_len);
        }
    }
    best
}

/// Track already drawn, for suppressing a line that adds nothing.
///
/// Whole lines only: subtracting covered parts was tried and leaves holes.
/// A `HashMap` of cells is fine here — nothing iterates it, the answer is a
/// boolean per point, so no order leaks into the output.
#[derive(Default)]
pub struct Covered {
    points: Vec<((i32, i32), f64, f64)>,
    cells: HashMap<Cell, Vec<u32>>,
    /// Which service drew each sample.
    tags: Vec<u32>,
}

type Cell = (i32, i32);

/// The cell a point falls in, square in metres.
fn cell_of(point: (i32, i32)) -> Cell {
    const CELL_M: f64 = 40.0;
    let lat = f64::from(point.0) * 1e-7;
    let cos_lat = lat.to_radians().cos().max(1e-6);
    let y = lat * 111_320.0 / CELL_M;
    let x = f64::from(point.1) * 1e-7 * 111_320.0 * cos_lat / CELL_M;
    (x.floor() as i32, y.floor() as i32)
}

/// Cosine of the parallel angle (25°), compared folded so opposite
/// directions over one track count as parallel.
const COS_FOLD: f64 = 0.906_307_787;

/// Corridor half-width: samples within this running parallel count as drawn.
const CORRIDOR_M: f64 = 30.0;

impl Covered {
    /// Record every metre of `line` as drawn by service `tag`.
    pub fn add_tagged(&mut self, line: &[(i32, i32)], tag: u32) {
        for dp in super::corridor_geom::walk_directed(line, 10.0) {
            let at = self.points.len() as u32;
            self.cells.entry(cell_of(dp.point)).or_default().push(at);
            self.points.push((dp.point, dp.ux, dp.uy));
            self.tags.push(tag);
        }
    }

    /// The most distinct services already drawn over any one metre of `line`.
    /// A few services over one track is real; fifteen is a republished-feeds
    /// artefact, so the caller caps it. Stops once `limit` is reached.
    pub fn crowd_reaches(&self, line: &[(i32, i32)], limit: usize) -> bool {
        if limit == 0 {
            return true;
        }
        let mut seen: Vec<u32> = Vec::new();
        for dp in super::corridor_geom::walk_directed(line, 10.0) {
            seen.clear();
            self.tags_over(dp.point, dp.ux, dp.uy, &mut seen, limit);
            if seen.len() >= limit {
                return true;
            }
        }
        false
    }

    /// How much of `line` is already drawn, 0 to 1. Too short to walk covers
    /// nothing, so it reports 0 and is kept.
    pub fn covered_fraction(&self, line: &[(i32, i32)]) -> f64 {
        let walked = super::corridor_geom::walk_directed(line, 10.0);
        if walked.is_empty() {
            return 0.0;
        }
        let drawn = walked
            .iter()
            .filter(|dp| self.covers(dp.point, dp.ux, dp.uy))
            .count();
        drawn as f64 / walked.len() as f64
    }

    /// Every distinct service drawn within [`CORRIDOR_M`] of this point,
    /// running parallel to it. Stops once `limit` is reached.
    fn tags_over(
        &self,
        point: (i32, i32),
        ux: f64,
        uy: f64,
        out: &mut Vec<u32>,
        limit: usize,
    ) {
        let (cx, cy) = cell_of(point);
        for dx in -1..=1 {
            for dy in -1..=1 {
                let Some(bucket) = self.cells.get(&(cx + dx, cy + dy)) else {
                    continue;
                };
                for &i in bucket {
                    let Some(&(other, oux, ouy)) = self.points.get(i as usize) else {
                        continue;
                    };
                    if (ux * oux + uy * ouy).abs() < COS_FOLD {
                        continue;
                    }
                    if super::corridor_geom::distance_m(point, other) <= CORRIDOR_M {
                        let Some(&tag) = self.tags.get(i as usize) else {
                            continue;
                        };
                        if !out.contains(&tag) {
                            out.push(tag);
                            if out.len() >= limit {
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /// Is this point already drawn, by track running parallel to it?
    fn covers(&self, point: (i32, i32), ux: f64, uy: f64) -> bool {
        let (cx, cy) = cell_of(point);
        for dx in -1..=1 {
            for dy in -1..=1 {
                let Some(bucket) = self.cells.get(&(cx + dx, cy + dy)) else {
                    continue;
                };
                for &i in bucket {
                    let Some(&(other, oux, ouy)) = self.points.get(i as usize) else {
                        continue;
                    };
                    if (ux * oux + uy * ouy).abs() < COS_FOLD {
                        continue;
                    }
                    if super::corridor_geom::distance_m(point, other) <= CORRIDOR_M {
                        return true;
                    }
                }
            }
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn whole_lines_survive_untouched_without_corridors() {
        let line = [(0, 0), (0, 100_000)];
        let corridors = BTreeMap::new();
        let out = spans_of(
            &line,
            1,
            &[Run { from: 0, to: 0, set: 0 }],
            &[(0, 0)],
            &[0.0],
            &[0.0, 11_132.0],
            &corridors,
        );
        assert_eq!(out.len(), 1);
        assert_eq!((out[0].ordinal, out[0].lanes, out[0].taper), (0, 1, 255));
        assert_eq!(out[0].points, line);
    }

    #[test]
    fn covered_fraction_counts_drawn_metres() {
        let mut covered = Covered::default();
        let line = [(0, 0), (0, 100_000)];
        assert_eq!(covered.covered_fraction(&line), 0.0, "nothing drawn yet");
        covered.add_tagged(&line, 1);
        assert!((covered.covered_fraction(&line) - 1.0).abs() < 1e-9, "fully drawn");
        let far = [(500_000, 0), (500_000, 100_000)];
        assert_eq!(covered.covered_fraction(&far), 0.0, "far line untouched");
    }
}
