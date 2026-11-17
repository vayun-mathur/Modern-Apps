#[cfg(test)]
mod tests {
    use tile_build::simplify::*;
    use tile_build::geom::{Geometry, Pt, SigPt, Vertex, ALWAYS};

    /// Annotate a path and hand back the vertices a tolerance keeps, as plain pairs.
    fn thin(pts: &[Pt], tolerance: f64) -> Vec<Pt> {
        let mut g = line_of(pts);
        annotate(&mut g);
        let Geometry::Lines(out) = filter(&g, tolerance) else { panic!() };
        match out.first() {
            Some(l) => l.iter().map(|v| v.xy()).collect(),
            None => Vec::new(),
        }
    }

    fn line_of(pts: &[Pt]) -> Geometry<SigPt> {
        Geometry::Lines(vec![pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()])
    }

    fn ring_of(pts: &[Pt]) -> Vec<SigPt> {
        pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()
    }

    /// Annotate one ring on its own and filter it, as the polygon path would.
    fn thin_ring(pts: &[Pt], tolerance: f64) -> Option<Vec<Pt>> {
        let mut ring = ring_of(pts);
        annotate_path(&mut ring);
        filter_ring(&ring, tolerance * tolerance)
            .map(|r| r.iter().map(|v| v.xy()).collect())
    }

    #[test]
    fn a_collinear_run_collapses_to_its_endpoints() {
        let line = [(0.0, 0.0), (10.0, 0.0), (20.0, 0.0), (30.0, 0.0), (40.0, 0.0)];
        assert_eq!(thin(&line, 1.0), vec![(0.0, 0.0), (40.0, 0.0)]);
    }

    #[test]
    fn a_deviation_above_tolerance_is_kept_and_below_is_dropped() {
        // The middle vertex sits 5 units off the chord.
        let line = [(0.0, 0.0), (50.0, 5.0), (100.0, 0.0)];
        assert_eq!(thin(&line, 1.0), line.to_vec(), "5 > 1, so it stays");
        assert_eq!(
            thin(&line, 10.0),
            vec![(0.0, 0.0), (100.0, 0.0)],
            "5 < 10, so it goes"
        );
    }

    #[test]
    fn endpoints_are_never_removed() {
        // Even a line whose interior is entirely within tolerance keeps its ends.
        let line = [(0.0, 0.0), (1.0, 0.0), (2.0, 0.0), (100.0, 0.0)];
        let out = thin(&line, 50.0);
        assert_eq!(out.first(), Some(&(0.0, 0.0)));
        assert_eq!(out.last(), Some(&(100.0, 0.0)));
        assert_eq!(out.len(), 2);
    }

    #[test]
    fn thinning_is_monotonic_in_the_tolerance() {
        // A zigzag of decreasing amplitude: a larger tolerance must never keep more
        // vertices than a smaller one.
        let line: Vec<Pt> = (0..40)
            .map(|i| {
                let a = (40 - i) as f64;
                ((i * 10) as f64, if i % 2 == 0 { a } else { -a })
            })
            .collect();
        let mut prev = usize::MAX;
        for tol in [0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0] {
            let n = thin(&line, tol).len();
            assert!(
                n <= prev,
                "tolerance {tol} kept {n} vertices, more than the {prev} before it"
            );
            prev = n;
        }
        // And the extremes behave.
        assert_eq!(thin(&line, 0.0), line, "no tolerance, no change");
        assert_eq!(thin(&line, 1e9).len(), 2, "everything but the ends");
    }

    #[test]
    fn thinning_never_adds_a_vertex_or_reorders_them() {
        let line: Vec<Pt> = (0..50).map(|i| ((i * 7) as f64, ((i * i) % 23) as f64)).collect();
        for tol in [1.0, 3.0, 9.0] {
            let out = thin(&line, tol);
            assert!(out.len() <= line.len());
            // The output is a subsequence of the input.
            let mut it = line.iter();
            for p in &out {
                assert!(it.any(|q| q == p), "{p:?} is not in the input, or is out of order");
            }
        }
    }

    /// The property the whole design exists for. Two rings that share an edge --
    /// which is what a clip against a tile boundary produces -- must agree on it
    /// vertex for vertex after filtering, whatever else happens to either ring.
    #[test]
    fn a_vertex_shared_between_two_rings_is_kept_or_dropped_in_both() {
        use tile_build::clip::clip_polygon;
        use tile_build::geom::Rect;

        let rect = Rect { min_x: 0.0, min_y: 0.0, max_x: 100.0, max_y: 100.0 };
        // An exterior and a hole that both hang out over the eastern boundary, so
        // the clip gives them a shared run of vertices along x = 100.
        let exterior: Vec<Pt> = vec![
            (10.0, 10.0),
            (150.0, 10.0),
            (150.0, 90.0),
            (10.0, 90.0),
            (10.0, 10.0),
        ];
        let hole: Vec<Pt> = vec![
            (40.0, 30.0),
            (150.0, 30.0),
            (150.0, 70.0),
            (40.0, 70.0),
            (40.0, 30.0),
        ];
        let mut g = Geometry::Polygons(vec![vec![ring_of(&exterior), ring_of(&hole)]]);
        annotate(&mut g);
        let Geometry::Polygons(polys) = &g else { panic!() };
        let clipped = clip_polygon(&polys[0], &rect);
        assert_eq!(clipped.len(), 2, "exterior and hole both survive the clip");

        // Every vertex the clip put on the eastern boundary appears in both rings.
        let on_edge = |rings: &Vec<Vec<SigPt>>| -> Vec<Pt> {
            rings
                .iter()
                .flatten()
                .map(|v| v.xy())
                .filter(|(x, _)| *x == 100.0)
                .collect()
        };
        for tol in [0.5, 1.0, 8.0, 64.0] {
            let g = Geometry::Polygons(vec![clipped.clone()]);
            let Geometry::Polygons(out) = filter(&g, tol) else { panic!() };
            let kept = on_edge(&out[0]);
            let before = on_edge(&vec![clipped[0].clone(), clipped[1].clone()]);
            assert_eq!(
                kept, before,
                "tolerance {tol} moved a boundary vertex: {kept:?} vs {before:?}"
            );
        }
    }

    /// Clip-introduced vertices carry `ALWAYS`, so no tolerance can remove them --
    /// which is the only thing holding two clipped rings to the same boundary edge.
    #[test]
    fn boundary_vertices_survive_the_coarsest_tolerance() {
        use tile_build::clip::clip_ring;
        use tile_build::geom::Rect;

        let rect = Rect { min_x: 0.0, min_y: 0.0, max_x: 10.0, max_y: 10.0 };
        let mut ring = ring_of(&[(-5.0, -5.0), (15.0, -5.0), (15.0, 15.0), (-5.0, 15.0), (-5.0, -5.0)]);
        annotate_path(&mut ring);
        let clipped = clip_ring(&ring, &rect);
        // Every surviving vertex is a crossing the clipper invented.
        assert!(clipped.iter().all(|v| v.sig == ALWAYS), "{clipped:?}");
        let out = filter_ring(&clipped, 1e18).expect("nothing to drop");
        assert_eq!(out.len(), clipped.len());
    }

    #[test]
    fn a_closed_ring_stays_closed() {
        // A square with a redundant midpoint on each side.
        let ring = [
            (0.0, 0.0),
            (50.0, 0.0),
            (100.0, 0.0),
            (100.0, 50.0),
            (100.0, 100.0),
            (50.0, 100.0),
            (0.0, 100.0),
            (0.0, 50.0),
            (0.0, 0.0),
        ];
        let out = thin_ring(&ring, 1.0).expect("a square survives");
        assert_eq!(out.first(), out.last(), "closure preserved");
        assert_eq!(out.len(), 5, "four corners plus the close: {out:?}");
    }

    #[test]
    fn an_unclosed_ring_stays_unclosed() {
        let ring = [(0.0, 0.0), (50.0, 0.0), (100.0, 0.0), (100.0, 100.0), (0.0, 100.0)];
        let out = thin_ring(&ring, 1.0).unwrap();
        assert_ne!(out.first(), out.last());
        assert_eq!(out.len(), 4, "the collinear midpoint goes: {out:?}");
    }

    #[test]
    fn a_ring_is_dropped_rather_than_collapsed_below_four_vertices() {
        // A very thin sliver: at a large tolerance every vertex but the two ends is
        // sub-tolerance, which leaves 2 and encloses no area. Drop it instead.
        let ring = [(0.0, 0.0), (500.0, 1.0), (1000.0, 0.0), (500.0, -1.0), (0.0, 0.0)];
        assert_eq!(thin_ring(&ring, 1000.0), None, "a collapsed ring is dropped");
        // At a tolerance it can actually survive, it does.
        assert!(thin_ring(&ring, 0.5).is_some());
    }

    #[test]
    fn a_ring_that_is_already_too_small_is_rejected() {
        assert_eq!(thin_ring(&[], 1.0), None);
        assert_eq!(thin_ring(&[(0.0, 0.0)], 1.0), None);
        assert_eq!(thin_ring(&[(0.0, 0.0), (1.0, 1.0)], 1.0), None);
        // Closed, but only two distinct vertices.
        assert_eq!(thin_ring(&[(0.0, 0.0), (1.0, 1.0), (0.0, 0.0)], 1.0), None);
        // The minimum viable closed ring, at zero tolerance.
        let tri = [(0.0, 0.0), (10.0, 0.0), (0.0, 10.0), (0.0, 0.0)];
        assert!(thin_ring(&tri, 0.0).is_some());
    }

    #[test]
    fn a_ring_at_the_floor_is_dropped_only_when_it_is_below_tolerance() {
        // Four vertices is the floor, but filtering can still remove the two
        // interior ones: whether the ring survives is a question about its size,
        // not its vertex count.
        let ring = [(0.0, 0.0), (1000.0, 0.0), (0.0, 1000.0), (0.0, 0.0)];
        assert_eq!(
            thin_ring(&ring, 1.0),
            Some(ring.to_vec()),
            "1000 units across, 1 unit tolerance"
        );
        assert_eq!(thin_ring(&ring, 1e9), None, "sub-tolerance, so dropped not collapsed");
    }

    #[test]
    fn the_geometry_entry_point_drops_lines_that_fall_below_two_vertices() {
        let mut g = Geometry::Lines(vec![
            ring_of(&[(0.0, 0.0), (10.0, 0.0), (20.0, 0.0)]),
            ring_of(&[(5.0, 5.0)]),
        ]);
        annotate(&mut g);
        let Geometry::Lines(out) = filter(&g, 1.0) else { panic!() };
        assert_eq!(out.len(), 1);
        assert_eq!(
            out[0].iter().map(|v| v.xy()).collect::<Vec<_>>(),
            vec![(0.0, 0.0), (20.0, 0.0)]
        );
    }

    #[test]
    fn losing_the_exterior_ring_drops_the_whole_polygon() {
        // A 1000x2 sliver and a 100000-unit square, at a 1000-unit tolerance: the
        // sliver is entirely sub-tolerance and the square is nowhere near it.
        let sliver = [(0.0, 0.0), (500.0, 1.0), (1000.0, 0.0), (500.0, -1.0), (0.0, 0.0)];
        let fat = [
            (0.0, 0.0),
            (100_000.0, 0.0),
            (100_000.0, 100_000.0),
            (0.0, 100_000.0),
            (0.0, 0.0),
        ];
        // Exterior collapses: the polygon goes, hole and all.
        let mut g = Geometry::Polygons(vec![vec![ring_of(&sliver), ring_of(&fat)]]);
        annotate(&mut g);
        let Geometry::Polygons(out) = filter(&g, 1000.0) else { panic!() };
        assert!(out.is_empty(), "{out:?}");
        // A hole collapsing leaves the exterior intact.
        let mut g = Geometry::Polygons(vec![vec![ring_of(&fat), ring_of(&sliver)]]);
        annotate(&mut g);
        let Geometry::Polygons(out) = filter(&g, 1000.0) else { panic!() };
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].len(), 1, "the hole was dropped, the exterior kept");
    }

    #[test]
    fn points_pass_through_untouched() {
        // Thinning points is the drop policy's job, not simplification's.
        let mut g = Geometry::Points(ring_of(&[(0.0, 0.0), (1.0, 1.0), (2.0, 2.0)]));
        annotate(&mut g);
        assert_eq!(filter(&g, 1e9), g);
    }

    #[test]
    fn a_zero_tolerance_is_the_identity() {
        let mut g = line_of(&[(0.0, 0.0), (1.0, 0.0), (2.0, 0.0), (3.0, 0.0)]);
        annotate(&mut g);
        assert_eq!(filter(&g, 0.0), g);
        assert_eq!(filter(&g, -1.0), g, "a negative tolerance is not a licence");
    }

    #[test]
    fn the_tolerance_policy_spares_the_deepest_zoom() {
        assert_eq!(tolerance_for(14, 14, 1.0), 0.0, "max zoom keeps full detail");
        assert_eq!(tolerance_for(15, 14, 1.0), 0.0, "and so does anything past it");
        assert_eq!(tolerance_for(13, 14, 1.0), DEFAULT_TOLERANCE);
        assert_eq!(tolerance_for(6, 14, 4.0), DEFAULT_TOLERANCE * 4.0);
    }

    #[test]
    fn the_distance_is_to_the_segment_not_the_line_it_lies_on() {
        let p = |x: f64, y: f64| SigPt::new(x, y);
        // Beyond `b`: the perpendicular to the infinite line would say 0.
        assert_eq!(seg_dist_sq(p(20.0, 0.0), p(0.0, 0.0), p(10.0, 0.0)), 100.0);
        assert_eq!(seg_dist_sq(p(-5.0, 0.0), p(0.0, 0.0), p(10.0, 0.0)), 25.0);
        // Between them, it is the perpendicular.
        assert_eq!(seg_dist_sq(p(5.0, 3.0), p(0.0, 0.0), p(10.0, 0.0)), 9.0);
        assert_eq!(seg_dist_sq(p(5.0, 0.0), p(0.0, 0.0), p(10.0, 0.0)), 0.0);
        // A degenerate chord, which is what a closed ring's first pass measures
        // against, is point-to-point.
        assert_eq!(seg_dist_sq(p(5.0, 5.0), p(0.0, 0.0), p(0.0, 0.0)), 50.0);
    }

    #[test]
    fn significance_is_scale_invariant() {
        // The same shape at two scales must rank its vertices the same way, which is
        // what lets one annotation pass serve every zoom.
        let shape: Vec<Pt> = vec![
            (0.0, 0.0),
            (10.0, 3.0),
            (20.0, 1.0),
            (30.0, 12.0),
            (40.0, 2.0),
            (50.0, 0.0),
        ];
        let mut small = line_of(&shape);
        let mut big = line_of(&shape.iter().map(|(x, y)| (x * 8.0, y * 8.0)).collect::<Vec<_>>());
        annotate(&mut small);
        annotate(&mut big);
        let sig = |g: &Geometry<SigPt>| -> Vec<f64> {
            let Geometry::Lines(l) = g else { panic!() };
            l[0].iter().map(|v| v.sig).collect()
        };
        for (a, b) in sig(&small).iter().zip(sig(&big)) {
            if a.is_finite() {
                assert!((b - a * 64.0).abs() < 1e-9, "{a} scaled to {b}, not {}", a * 64.0);
            } else {
                assert!(b.is_infinite());
            }
        }
        // And a threshold scaled with them keeps the same vertices.
        let kept = |g: &Geometry<SigPt>, tol: f64| -> usize {
            let Geometry::Lines(l) = filter(g, tol) else { panic!() };
            l[0].len()
        };
        for tol in [0.5, 1.0, 2.0, 4.0] {
            assert_eq!(kept(&small, tol), kept(&big, tol * 8.0), "at tolerance {tol}");
        }
    }

    /// A coincident shore and its water edge must thin to the SAME vertices, or the
    /// two drift apart and the renderer paints earth over sea between them. Both rings
    /// carry the same boundary crossings (ALWAYS), which the filter must respect.
    #[test]
    fn coincident_rings_thin_to_the_same_vertices() {
        use tile_build::clip::clip_ring;
        use tile_build::geom::Rect;
        // A land edge and a water edge sharing the segment x=0..20 at y=0.
        let rect = Rect { min_x: 0.0, min_y: -10.0, max_x: 20.0, max_y: 10.0 };
        let land = [(0.0, -10.0), (20.0, -10.0), (20.0, 0.0), (0.0, 0.0), (0.0, -10.0)];
        let water = [(0.0, 10.0), (20.0, 10.0), (20.0, 0.0), (0.0, 0.0), (0.0, 10.0)];
        let land_ring: Vec<SigPt> =
            clip_ring(&land.iter().map(|&(x, y)| SigPt::new(x, y)).collect::<Vec<_>>(), &rect);
        let water_ring: Vec<SigPt> =
            clip_ring(&water.iter().map(|&(x, y)| SigPt::new(x, y)).collect::<Vec<_>>(), &rect);
        let mut land_g = Geometry::Polygons(vec![vec![land_ring]]);
        let mut water_g = Geometry::Polygons(vec![vec![water_ring]]);
        annotate(&mut land_g);
        annotate(&mut water_g);
        let Geometry::Polygons(land_out) = filter(&land_g, 5.0) else { panic!() };
        let Geometry::Polygons(water_out) = filter(&water_g, 5.0) else { panic!() };
        // The shared edge y=0 survives identically in both: same vertices, so no
        // sliver opens between them at any zoom.
        let edge_of = |rings: &[Vec<Vec<SigPt>>]| -> Vec<Pt> {
            rings[0][0].iter().filter(|v| v.y == 0.0).map(|v| v.xy()).collect()
        };
        assert_eq!(edge_of(&land_out), edge_of(&water_out), "shared edge diverged");
    }
}

