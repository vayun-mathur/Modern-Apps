#[cfg(test)]
/// A 6 km line, shared with one route over its first half and another over its second,
/// on opposite sides of it, so the lane it takes really does have to change at the seam.
fn back_to_back_corridors() -> Vec<Fixture> {
    vec![
        (0u32, 0x00_00_10u32, "Main", north(37.70, -122.40, 100.0, 61)),
        (1, 0x00_00_20, "First", shifted(&north(37.70, -122.40, 100.0, 31), 8.0)),
        (
            2,
            0x00_00_05,
            "Last",
            shifted(&north(37.70 + 3000.0 / 111_320.0, -122.40, 100.0, 31), -8.0),
        ),
    ]
}

#[cfg(test)]
/// Two lines closing at a shallow angle: one runs 4 km north, the other comes in from a
/// hundred metres west over the first kilometre of it and then runs alongside. They are
/// one corridor from wherever they first come within [`CORRIDOR_M`], which is a long way
/// before either could draw the other's geometry without it showing.
fn a_shallow_merge() -> Vec<Fixture> {
    let main = north(37.70, -122.40, 100.0, 41);
    let cos_lat = 37.70_f64.to_radians().cos();
    let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
    let joining: Vec<(i32, i32)> = main
        .iter()
        .enumerate()
        .map(|(i, &(lat, lon))| {
            let closed = (i as f64 * 100.0 / 1000.0).min(1.0);
            (lat, lon + east(8.0 - 108.0 * (1.0 - closed)))
        })
        .collect();
    vec![(0u32, 0x00_00_10u32, "Main", main), (1, 0x00_00_20, "Join", joining)]
}

#[cfg(test)]
/// The corridor a polyline would be the reference of, for measuring the output against.
fn corridor_of(points: &[(i32, i32)]) -> Corridor {
    let points = canonical(points);
    let cum = cumulative(&points);
    Corridor::new(points, cum, Vec::new())
}

#[cfg(test)]
/// `n` services sharing `metres` of trunk running north and then leaving it in
/// different directions for 20 km.
fn trunk_then_branches(metres: f64, n: usize) -> Vec<Fixture> {
    let steps = (metres / 100.0).round() as usize + 1;
    let trunk = north(37.70, -122.40, 100.0, steps);
    let top = *trunk.last().expect("a trunk");
    let cos_lat = (top.0 as f64 * 1e-7).to_radians().cos();
    let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
    let up = |m: f64| (m / 111_320.0 * 1e7) as i32;
    let branch = |dx: f64, dy: f64| -> Vec<(i32, i32)> {
        let mut line = trunk.clone();
        for i in 1..=200 {
            line.push((top.0 + up(dy * i as f64), top.1 + east(dx * i as f64)));
        }
        line
    };
    let away = [(100.0, 0.0), (0.0, 100.0), (-100.0, 0.0), (70.0, 70.0)];
    let names = ["A", "B", "C", "D"];
    (0..n)
        .map(|i| {
            let (dx, dy) = away[i];
            (i as u32, 0x11u32 * (i as u32 + 1), names[i], branch(dx, dy))
        })
        .collect()
}

#[cfg(test)]
mod tests_continued {
    use super::*;

    /// The pieces of one route meet: the span before a corridor is carried across to the
    /// point on the reference the corridor span begins at, and so is the ease across to the
    /// body it leads into.
    #[test]
    fn the_spans_of_one_route_share_their_boundary_vertex() {
        for lines in
            [trunk_then_branches(2000.0, 4), back_to_back_corridors(), a_shallow_merge()]
        {
            let spans = assign(&candidates(&lines));
            for candidate in &spans {
                for pair in candidate.windows(2) {
                    assert_eq!(
                        pair[0].points.last(),
                        pair[1].points.first(),
                        "a gap between two spans of one route",
                    );
                }
            }
        }
    }

    /// A route can step straight out of one corridor and into the next, with nothing of its
    /// own in between and two different references either side of the seam.
    #[test]
    fn a_route_passing_from_one_corridor_into_the_next_changes_lane_where_they_meet() {
        let spans = assign(&candidates(&back_to_back_corridors()));
        let places: Vec<(u8, u8)> = spans[0].iter().map(|s| (s.ordinal, s.lanes)).collect();
        assert!(places.contains(&(0, 2)), "the first corridor's lower ordinal: {places:?}");
        assert!(places.contains(&(1, 2)), "the second corridor's higher ordinal: {places:?}");
        assert!(
            !places.iter().any(|p| p.1 == 1),
            "and no stretch of its own between them: {places:?}",
        );
    }

    /// A 6 km line, shared with one route over its first half and another over its second,
    /// on opposite sides of it, so the lane it takes really does have to change at the seam.
    fn back_to_back_corridors() -> Vec<Fixture> {
        vec![
            (0u32, 0x00_00_10u32, "Main", north(37.70, -122.40, 100.0, 61)),
            (1, 0x00_00_20, "First", shifted(&north(37.70, -122.40, 100.0, 31), 8.0)),
            (
                2,
                0x00_00_05,
                "Last",
                shifted(&north(37.70 + 3000.0 / 111_320.0, -122.40, 100.0, 31), -8.0),
            ),
        ]
    }

    /// Two lines closing at a shallow angle: one runs 4 km north, the other comes in from a
    /// hundred metres west over the first kilometre of it and then runs alongside. They are
    /// one corridor from wherever they first come within [`CORRIDOR_M`], which is a long way
    /// before either could draw the other's geometry without it showing.
    fn a_shallow_merge() -> Vec<Fixture> {
        let main = north(37.70, -122.40, 100.0, 41);
        let cos_lat = 37.70_f64.to_radians().cos();
        let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
        let joining: Vec<(i32, i32)> = main
            .iter()
            .enumerate()
            .map(|(i, &(lat, lon))| {
                let closed = (i as f64 * 100.0 / 1000.0).min(1.0);
                (lat, lon + east(8.0 - 108.0 * (1.0 - closed)))
            })
            .collect();
        vec![(0u32, 0x00_00_10u32, "Main", main), (1, 0x00_00_20, "Join", joining)]
    }

    /// The corridor a polyline would be the reference of, for measuring the output against.
    fn corridor_of(points: &[(i32, i32)]) -> Corridor {
        let points = canonical(points);
        let cum = cumulative(&points);
        Corridor::new(points, cum, Vec::new())
    }

    /// `n` services sharing `metres` of trunk running north and then leaving it in
    /// different directions for 20 km.
    fn trunk_then_branches(metres: f64, n: usize) -> Vec<Fixture> {
        let steps = (metres / 100.0).round() as usize + 1;
        let trunk = north(37.70, -122.40, 100.0, steps);
        let top = *trunk.last().expect("a trunk");
        let cos_lat = (top.0 as f64 * 1e-7).to_radians().cos();
        let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
        let up = |m: f64| (m / 111_320.0 * 1e7) as i32;
        let branch = |dx: f64, dy: f64| -> Vec<(i32, i32)> {
            let mut line = trunk.clone();
            for i in 1..=200 {
                line.push((top.0 + up(dy * i as f64), top.1 + east(dx * i as f64)));
            }
            line
        };
        let away = [(100.0, 0.0), (0.0, 100.0), (-100.0, 0.0), (70.0, 70.0)];
        let names = ["A", "B", "C", "D"];
        (0..n)
            .map(|i| {
                let (dx, dy) = away[i];
                (i as u32, 0x11u32 * (i as u32 + 1), names[i], branch(dx, dy))
            })
            .collect()
    }

    /// A second copy of a line adds nothing to the map, whichever way round it is drawn.
    /// THE case: a feed publishes each direction of a service as its own shape, and a
    /// regional feed republishes the lot re-surveyed a few metres off.
    #[test]
    fn track_already_drawn_is_not_carried_again() {
        let line = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        assert!(!covered.contains(&line), "the first copy is all new");
        covered.add(&line);
        assert!(covered.contains(&line), "the same line again");
        let reversed: Vec<(i32, i32)> = line.iter().rev().copied().collect();
        assert!(covered.contains(&reversed), "the other direction");
        assert!(covered.contains(&shifted(&line, 9.0)), "the adjacent track");
        // A short-turn lies on its parent for its whole length, so it adds nothing either.
        assert!(covered.contains(&north(37.7, -122.4, 100.0, 30)), "a short-turn");
    }

    /// How many distinct services already draw over a stretch of track.
    ///
    /// The colour-scoped gates let two services share a track on purpose, and that is right for a
    /// city. On a planet one alignment is republished by a city feed, the regional feed containing
    /// it and a national feed on top, each under its own colour, and every one of them claims a
    /// lane — which is what draws one railway as fifteen jagged parallel lines.
    #[test]
    fn crowd_counts_the_distinct_services_over_a_track() {
        let line = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        assert_eq!(covered.crowd(&line), 0, "empty track carries nobody");

        covered.add_tagged(&line, 0xE31E24);
        assert_eq!(covered.crowd(&line), 1);
        // A second service on the same track is real and must be counted, not merged.
        covered.add_tagged(&line, 0x0054A5);
        assert_eq!(covered.crowd(&line), 2);
        // The same service again is not a third.
        covered.add_tagged(&line, 0xE31E24);
        assert_eq!(covered.crowd(&line), 2, "one service counted twice");
        // Slightly off, still the same corridor.
        covered.add_tagged(&shifted(&line, 9.0), 0x00A650);
        assert_eq!(covered.crowd(&line), 3, "a re-survey a few metres off is the same track");
        // Track nobody has drawn is uncrowded however busy its neighbour is.
        assert_eq!(covered.crowd(&north(37.9, -122.9, 100.0, 60)), 0, "elsewhere");
    }

    /// The worst point along the line, not the average: a branch joining a busy trunk for part of
    /// its length is exactly the line worth suppressing.
    #[test]
    fn crowd_reports_the_busiest_point_not_the_whole_line() {
        let trunk = north(37.7, -122.4, 100.0, 30);
        let longer = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        for colour in [1u32, 2, 3] {
            covered.add_tagged(&trunk, colour);
        }
        assert_eq!(
            covered.crowd(&longer),
            3,
            "a line overlapping a crowded trunk for half its length is crowded"
        );
    }

    /// A re-publication that runs a little further is still a duplicate of the part it shares.
    ///
    /// The case that drew the LA A line as two strands a metre apart: two feeds publish the same
    /// alignment at slightly different lengths, the longer one "adds new track" at one end, and an
    /// all-or-nothing rule therefore keeps it whole — duplicating everything they share. Each copy
    /// then gets its own corridor lane and taper, so one line reads as two that diverge and
    /// reconverge.
    #[test]
    fn a_longer_republication_of_the_same_track_is_mostly_drawn() {
        let short = north(37.7, -122.4, 100.0, 60);
        let long = north(37.7, -122.4, 100.0, 63);
        let mut covered = Covered::default();
        covered.add(&short);
        assert!(!covered.contains(&long), "it does add a little new track at the end");
        let fraction = covered.covered_fraction(&long);
        assert!(fraction > 0.9, "but nearly all of it is already drawn: {fraction}");
        assert!(fraction < 1.0, "and not quite all");
    }

    /// A branch is not a duplicate. Half shared, half genuinely new track, so it must survive
    /// whatever threshold the duplicate test uses.
    #[test]
    fn a_route_that_branches_away_is_not_mostly_drawn() {
        let trunk = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&trunk);
        // Same start, then off to its own alignment well outside the corridor.
        let mut branch: Vec<(i32, i32)> = trunk[..30].to_vec();
        branch.extend(shifted(&north(37.72, -122.4, 100.0, 30), 500.0));
        let fraction = covered.covered_fraction(&branch);
        assert!(fraction < 0.9, "half of it is new track: {fraction}");
    }

    /// Survey noise is not new track. Two agencies' surveys of one track disagree by a few
    /// metres, and a wobble inside the tolerance must not resurrect a duplicate line.
    #[test]
    fn a_wobble_inside_the_tolerance_is_the_same_track() {
        let straight = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&straight);
        let cos_lat = (straight[0].0 as f64 * 1e-7).to_radians().cos();
        let east = |m: f64| (m / (111_320.0 * cos_lat) * 1e7) as i32;
        let wobbled: Vec<(i32, i32)> = straight
            .iter()
            .enumerate()
            .map(|(i, &(lat, lon))| (lat, lon + east(if i % 2 == 0 { 9.0 } else { -9.0 })))
            .collect();
        assert!(covered.contains(&wobbled), "9 m of survey drift is the same track");
    }

    /// A branch adds track, so it is carried **whole** rather than trimmed to the part that
    /// diverges. Trimming was tried and is wrong: it leaves a route in fragments, and a
    /// stretch too short to be worth emitting leaves a hole that nothing else draws. The
    /// trunk it shares is over-drawn in its own colour at its own offset, which is invisible.
    #[test]
    fn a_branch_adds_track_and_is_carried_whole() {
        let trunk = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&trunk);
        let mut branch = north(37.7, -122.4, 100.0, 30);
        let split_at = *branch.last().unwrap();
        let cos_lat = (split_at.0 as f64 * 1e-7).to_radians().cos();
        for i in 1..=20 {
            branch.push((
                split_at.0,
                split_at.1 + (i as f64 * 100.0 / (111_320.0 * cos_lat) * 1e7) as i32,
            ));
        }
        assert!(!covered.contains(&branch), "it leaves the trunk, so it is not redundant");
    }

    #[test]
    fn a_different_alignment_is_all_new_track() {
        let line = north(37.7, -122.4, 100.0, 60);
        let mut covered = Covered::default();
        covered.add(&line);
        assert!(!covered.contains(&shifted(&line, 1000.0)));
    }

    #[test]
    fn a_line_with_fewer_than_two_points_covers_nothing() {
        let line = north(37.7, -122.4, 100.0, 10);
        let covered = Covered::default();
        assert!(!covered.contains(&line[..1]));
        assert!(!covered.contains(&[]));
    }

    /// The two halves of the slicing primitive: measuring along a polyline and cutting it
    /// between two of those measurements, with both ends landing exactly on the line.
    #[test]
    fn a_slice_of_a_polyline_lands_exactly_on_it_at_both_ends() {
        let line = north(37.7, -122.4, 100.0, 11);
        let cum = cumulative(&line);
        assert!((cum[10] - 1000.0).abs() < 1.0, "{cum:?}");
        assert_eq!(slice_between(&line, &cum, 0.0, cum[10]), line, "the whole thing");
        let middle = slice_between(&line, &cum, 250.0, 750.0);
        assert_eq!(middle.len(), 7, "both cut ends plus the five vertices between");
        assert!(distance_m(middle[0], line[2]) < 60.0, "a quarter of the way along");
        // Two adjacent slices meet exactly, which is what keeps a route's pieces joined.
        let before = slice_between(&line, &cum, 0.0, 250.0);
        assert_eq!(before.last(), middle.first());
        // Nothing between the two distances is nothing at all.
        assert!(slice_between(&line, &cum, 400.0, 400.0).is_empty());
    }

    #[test]
    fn nothing_in_nothing_out() {
        assert!(assign(&[]).is_empty());
    }

    /// The block-pruned [`nearest`] and [`within`] against the plain walk of every segment they
    /// replaced, over long, kinked, self-approaching references and probes near and far —
    /// including probes exactly on a vertex, where several segments tie.
    #[test]
    fn pruned_nearest_is_the_full_walk_exactly() {
        let full = |c: &Corridor, p: (i32, i32)| -> (f64, f64) {
            let mut best = (f64::INFINITY, 0.0f64);
            for at in 0..c.points.len().saturating_sub(1) {
                let (t, offset) = project(p, c.points[at], c.points[at + 1], 0.0);
                if offset < best.0 {
                    best = (offset, c.cum[at] + t * (c.cum[at + 1] - c.cum[at]));
                }
            }
            best
        };
        let mut x = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move || {
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            x
        };
        for case in 0..40 {
            let n = [2usize, 3, 33, 64, 65, 500, 3000][case % 7];
            let (mut lat, mut lon) = (600_000_000i32 - (next() % 1_200_000_000) as i32, (next() % 1_000_000_000) as i32);
            let mut points = vec![(lat, lon)];
            for _ in 1..n {
                // Mostly onward, sometimes doubling back, so distant segments come close.
                lat += (next() % 20_001) as i32 - 6_000;
                lon += (next() % 20_001) as i32 - 10_000;
                points.push((lat, lon));
            }
            let c = Corridor::new(points.clone(), cumulative(&points), Vec::new());
            for probe in 0..200 {
                let base = points[(next() % points.len() as u64) as usize];
                let p = if probe % 10 == 0 {
                    base
                } else {
                    let r = [50i32, 500, 5_000, 200_000][probe % 4];
                    (base.0 + (next() % (2 * r as u64 + 1)) as i32 - r, base.1 + (next() % (2 * r as u64 + 1)) as i32 - r)
                };
                let (want, got) = (full(&c, p), nearest(&c, p));
                assert_eq!(want.0.to_bits(), got.0.to_bits(), "distance, case {case} probe {probe}");
                assert_eq!(want.1.to_bits(), got.1.to_bits(), "along, case {case} probe {probe}");
                for radius in [SNAP_M, CORRIDOR_M, 0.0] {
                    assert_eq!(within(&c, p, radius), want.0 <= radius, "case {case} probe {probe} r {radius}");
                }
            }
        }
    }
}
