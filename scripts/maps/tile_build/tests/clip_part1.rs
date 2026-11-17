#[cfg(test)]
mod tests {
    use tile_build::clip::*; use tile_build::geom::{Pt, Rect, Geometry};

    /// A 10x10 rect at the origin, so every expected coordinate is readable.
    fn r() -> Rect {
        Rect { min_x: 0.0, min_y: 0.0, max_x: 10.0, max_y: 10.0 }
    }

    fn close(a: Pt, b: Pt) -> bool {
        (a.0 - b.0).abs() < 1e-9 && (a.1 - b.1).abs() < 1e-9
    }

    fn seg(a: Pt, b: Pt) -> Option<(Pt, Pt)> {
        clip_segment(a, b, &r())
    }

    // --- the clip-region matrix -------------------------------------------

    #[test]
    fn a_segment_wholly_inside_is_untouched() {
        let (a, b) = ((2.0, 2.0), (8.0, 7.0));
        let (ca, cb) = seg(a, b).unwrap();
        assert!(close(ca, a) && close(cb, b));
    }

    #[test]
    fn a_segment_wholly_outside_is_rejected_from_every_direction() {
        // The eight regions around the rect, plus two that straddle an axis
        // without ever entering (the case a naive "both endpoints outside" test
        // gets wrong is covered separately below).
        for (a, b) in [
            ((-5.0, 5.0), (-1.0, 5.0)),    // west
            ((11.0, 5.0), (20.0, 5.0)),    // east
            ((5.0, -5.0), (5.0, -1.0)),    // south
            ((5.0, 11.0), (5.0, 20.0)),    // north
            ((-5.0, -5.0), (-1.0, -1.0)),  // south-west
            ((11.0, -5.0), (20.0, -1.0)),  // south-east
            ((-5.0, 11.0), (-1.0, 20.0)),  // north-west
            ((11.0, 11.0), (20.0, 20.0)),  // north-east
            // Spans the rect's x range entirely above it.
            ((-5.0, 15.0), (15.0, 15.0)),
            // Passes diagonally past the north-west corner without touching: it is
            // already above the rect by the time it reaches x = 0.
            ((-5.0, 8.0), (2.0, 15.0)),
        ] {
            assert!(seg(a, b).is_none(), "{a:?} -> {b:?} must miss the rect");
        }
    }

    #[test]
    fn a_segment_crossing_one_boundary_is_cut_at_it() {
        // In from the west.
        let (ca, cb) = seg((-5.0, 5.0), (5.0, 5.0)).unwrap();
        assert!(close(ca, (0.0, 5.0)) && close(cb, (5.0, 5.0)));
        // Out to the east.
        let (ca, cb) = seg((5.0, 5.0), (15.0, 5.0)).unwrap();
        assert!(close(ca, (5.0, 5.0)) && close(cb, (10.0, 5.0)));
        // In from the south.
        let (ca, cb) = seg((5.0, -5.0), (5.0, 5.0)).unwrap();
        assert!(close(ca, (5.0, 0.0)) && close(cb, (5.0, 5.0)));
        // Out to the north.
        let (ca, cb) = seg((5.0, 5.0), (5.0, 15.0)).unwrap();
        assert!(close(ca, (5.0, 5.0)) && close(cb, (5.0, 10.0)));
    }

    #[test]
    fn a_segment_spanning_the_rect_is_cut_at_both_ends() {
        let (ca, cb) = seg((-10.0, 5.0), (20.0, 5.0)).unwrap();
        assert!(close(ca, (0.0, 5.0)) && close(cb, (10.0, 5.0)));
        // Diagonally corner to corner, both endpoints well outside.
        let (ca, cb) = seg((-10.0, -10.0), (20.0, 20.0)).unwrap();
        assert!(close(ca, (0.0, 0.0)) && close(cb, (10.0, 10.0)));
    }

    #[test]
    fn a_segment_clipping_a_corner_keeps_only_the_corner_sliver() {
        // Passes through the north-east corner region, entering and leaving.
        let (ca, cb) = seg((5.0, 15.0), (15.0, 5.0)).unwrap();
        assert!(close(ca, (10.0, 10.0)), "{ca:?}");
        assert!(close(cb, (10.0, 10.0)), "{cb:?}");
    }

    #[test]
    fn a_segment_parallel_to_a_boundary_is_handled_without_dividing_by_zero() {
        // Exactly along the southern edge: inside (the rect is closed).
        let (ca, cb) = seg((-5.0, 0.0), (15.0, 0.0)).unwrap();
        assert!(close(ca, (0.0, 0.0)) && close(cb, (10.0, 0.0)));
        // Parallel but outside.
        assert!(seg((-5.0, -1.0), (15.0, -1.0)).is_none());
        // Vertical along the western edge.
        let (ca, cb) = seg((0.0, -5.0), (0.0, 15.0)).unwrap();
        assert!(close(ca, (0.0, 0.0)) && close(cb, (0.0, 10.0)));
    }

    #[test]
    fn a_degenerate_segment_is_kept_only_if_inside() {
        let (ca, cb) = seg((5.0, 5.0), (5.0, 5.0)).unwrap();
        assert!(close(ca, (5.0, 5.0)) && close(cb, (5.0, 5.0)));
        assert!(seg((-5.0, -5.0), (-5.0, -5.0)).is_none());
        // Exactly on a corner counts as inside.
        assert!(seg((0.0, 0.0), (0.0, 0.0)).is_some());
    }

    #[test]
    fn a_non_finite_segment_is_rejected_rather_than_producing_nan() {
        assert!(seg((f64::NAN, 5.0), (5.0, 5.0)).is_none());
        assert!(seg((5.0, 5.0), (f64::INFINITY, 5.0)).is_none());
    }

    // --- polylines ---------------------------------------------------------

    #[test]
    fn a_polyline_inside_survives_as_one_piece() {
        let line = vec![(1.0, 1.0), (5.0, 5.0), (9.0, 2.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0], line);
    }

    #[test]
    fn a_polyline_leaving_and_re_entering_yields_two_pieces() {
        // Out to the east and back: one input line, two output lines. Welding them
        // would draw a straight edge across ground the line never covered.
        let line = vec![(5.0, 2.0), (15.0, 2.0), (15.0, 8.0), (5.0, 8.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 2, "{out:?}");
        assert!(close(out[0][0], (5.0, 2.0)) && close(out[0][1], (10.0, 2.0)));
        assert!(close(out[1][0], (10.0, 8.0)) && close(out[1][1], (5.0, 8.0)));
    }

    #[test]
    fn consecutive_surviving_segments_are_welded_into_one_line() {
        // Three segments, all inside: the output must be one 4-vertex line, not
        // three 2-vertex ones.
        let line = vec![(1.0, 1.0), (3.0, 3.0), (5.0, 2.0), (7.0, 6.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].len(), 4);
    }

    #[test]
    fn a_polyline_wholly_outside_yields_nothing() {
        let line = vec![(-5.0, -5.0), (-3.0, -4.0), (-1.0, -6.0)];
        assert!(clip_line(&line, &r()).is_empty());
    }

    #[test]
    fn a_polyline_that_only_grazes_a_corner_yields_a_degenerate_piece_or_nothing() {
        // A single touching point cannot make a line, so it must not be emitted as
        // a one-vertex "line" -- that encodes as a MoveTo with no LineTo.
        let line = vec![(5.0, 15.0), (15.0, 5.0)];
        for piece in clip_line(&line, &r()) {
            assert!(piece.len() >= 2, "no one-vertex lines: {piece:?}");
        }
    }

    #[test]
    fn a_dropped_vertex_breaks_the_run_rather_than_bridging_it() {
        let line = vec![(1.0, 1.0), (3.0, 3.0), (f64::NAN, 0.0), (7.0, 7.0), (9.0, 9.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 2, "{out:?}");
        assert_eq!(out[0], vec![(1.0, 1.0), (3.0, 3.0)]);
        assert_eq!(out[1], vec![(7.0, 7.0), (9.0, 9.0)]);
    }

    #[test]
    fn a_single_vertex_line_yields_nothing() {
        assert!(clip_line(&[(5.0, 5.0)], &r()).is_empty());
        assert!(clip_line::<Pt>(&[], &r()).is_empty());
    }

    // --- polygons ----------------------------------------------------------

    fn square(min: f64, max: f64) -> Vec<Pt> {
        vec![(min, min), (max, min), (max, max), (min, max), (min, min)]
    }

    fn area(ring: &[Pt]) -> f64 {
        let n = ring.len();
        let mut a = 0.0;
        for i in 0..n {
            let (x1, y1) = ring[i];
            let (x2, y2) = ring[(i + 1) % n];
            a += x1 * y2 - x2 * y1;
        }
        (a / 2.0).abs()
    }

    #[test]
    fn a_ring_inside_is_untouched_apart_from_its_closure_convention() {
        let ring = square(2.0, 8.0);
        let out = clip_ring(&ring, &r());
        assert_eq!(area(&out), area(&ring));
        assert_eq!(out.first(), out.last(), "a closed ring stays closed");
    }

    #[test]
    fn an_unclosed_ring_stays_unclosed() {
        let ring = vec![(2.0, 2.0), (8.0, 2.0), (8.0, 8.0), (2.0, 8.0)];
        let out = clip_ring(&ring, &r());
        assert_ne!(out.first(), out.last());
        assert_eq!(out.len(), 4);
    }

    #[test]
    fn a_ring_containing_the_rect_becomes_the_rect() {
        let ring = square(-100.0, 100.0);
        let out = clip_ring(&ring, &r());
        assert_eq!(area(&out), 100.0, "the whole 10x10 rect: {out:?}");
    }

    #[test]
    fn a_ring_half_outside_keeps_half_its_area() {
        // A 10x10 square offset 5 east: exactly half of it is in the rect.
        let ring = vec![(5.0, 0.0), (15.0, 0.0), (15.0, 10.0), (5.0, 10.0), (5.0, 0.0)];
        let out = clip_ring(&ring, &r());
        assert_eq!(area(&out), 50.0, "{out:?}");
        assert_eq!(out.first(), out.last());
    }

    #[test]
    fn a_ring_wholly_outside_disappears() {
        assert!(clip_ring(&square(20.0, 30.0), &r()).is_empty());
        // Touching the boundary but enclosing no area inside it.
        assert!(clip_ring(&square(10.0, 20.0), &r()).is_empty());
    }

    #[test]
    fn a_degenerate_ring_disappears() {
        assert!(clip_ring::<Pt>(&[], &r()).is_empty());
        assert!(clip_ring(&[(1.0, 1.0), (2.0, 2.0)], &r()).is_empty());
        // A closed ring of two distinct vertices is a line, not an area.
        assert!(clip_ring(&[(1.0, 1.0), (2.0, 2.0), (1.0, 1.0)], &r()).is_empty());
    }

    #[test]
    fn a_hole_survives_a_clip_that_does_not_reach_it() {
        let rings = vec![square(-5.0, 15.0), square(3.0, 6.0)];
        let out = clip_polygon(&rings, &r());
        assert_eq!(out.len(), 2, "exterior plus its hole");
        assert_eq!(area(&out[0]), 100.0, "the exterior became the rect");
        assert_eq!(area(&out[1]), 9.0, "the hole is untouched");
    }

    #[test]
    fn a_hole_clipped_away_is_dropped_but_the_polygon_stays() {
        let rings = vec![square(-5.0, 15.0), square(20.0, 25.0)];
        let out = clip_polygon(&rings, &r());
        assert_eq!(out.len(), 1, "only the exterior remains");
        assert_eq!(area(&out[0]), 100.0);
    }

    /// A hole straddling the tile edge must survive as a hole, not vanish and not
    /// escape its exterior. The tile clip cuts both rings against the same rect, so
    /// a hole poking outside is truncated to the rect — but it must still lie inside
    /// the clipped exterior, or stage C drops it and the island floods.
    #[test]
    fn a_hole_straddling_the_tile_edge_stays_inside_its_exterior() {
        // Exterior covers the rect's west half and beyond; hole crosses the east edge.
        let exterior = vec![(-5.0, 2.0), (15.0, 2.0), (15.0, 8.0), (-5.0, 8.0), (-5.0, 2.0)];
        let hole = vec![(5.0, 4.0), (15.0, 4.0), (15.0, 6.0), (5.0, 6.0), (5.0, 4.0)];
        let out = clip_polygon(&[exterior, hole], &r());
        assert_eq!(out.len(), 2, "exterior plus its truncated hole: {out:?}");
        // The hole's clipped area is the 5x2 rect surviving inside: 5..10 x 4..6.
        assert!((area(&out[1]) - 10.0).abs() < 1e-9, "hole area {} from {out:?}", area(&out[1]));
        // Every hole vertex is inside (or on) the clipped exterior: the exact
        // invariant stage C's strictly_inside demands before the boundary rule.
        for &(x, y) in &out[1] {
            assert!(
                (0.0..=10.0).contains(&x) && (2.0..=8.0).contains(&y),
                "hole vertex ({x}, {y}) escaped the clipped exterior"
            );
        }
    }

    #[test]
    fn losing_the_exterior_ring_drops_the_holes_too() {
        // A hole with no surrounding area is not a shape. Emitting it would render
        // as a solid patch of whatever colour the layer paints.
        let rings = vec![square(20.0, 30.0), square(3.0, 6.0)];
        assert!(clip_polygon(&rings, &r()).is_empty());
    }

    #[test]
    fn a_concave_ring_clips_to_one_ring_with_the_right_total_area() {
        // A U shape straddling the eastern boundary: the clipped result is two
        // disjoint prongs, which Sutherland-Hodgman returns as ONE ring joined by a
        // zero-area sliver along the boundary. The area is still correct, which is
        // what the renderer cares about. See the module docs.
        let u = vec![
            (5.0, 0.0),
            (15.0, 0.0),
            (15.0, 3.0),
            (8.0, 3.0),
            (8.0, 7.0),
            (15.0, 7.0),
            (15.0, 10.0),
            (5.0, 10.0),
            (5.0, 0.0),
        ];
        let out = clip_ring(&u, &r());
        assert!(!out.is_empty());
        // Inside the rect: the left bar (5..10 x 0..10 = 50) minus the notch
        // (8..10 x 3..7 = 8) = 42.
        assert!((area(&out) - 42.0).abs() < 1e-9, "area {} from {out:?}", area(&out));
    }

    // --- the geometry-level entry point -----------------------------------

    #[test]
    fn clip_geometry_filters_points_whole() {
        let g = Geometry::Points(vec![(5.0, 5.0), (15.0, 5.0), (0.0, 0.0), (f64::NAN, 1.0)]);
        let Geometry::Points(out) = clip_geometry(&g, &r()) else { panic!() };
        assert_eq!(out, vec![(5.0, 5.0), (0.0, 0.0)]);
    }

    #[test]
    fn clip_geometry_flattens_partitioned_lines_into_one_list() {
        // Two input lines, the second of which splits in two: three parts out.
        let g = Geometry::Lines(vec![
            vec![(1.0, 1.0), (2.0, 2.0)],
            vec![(5.0, 2.0), (15.0, 2.0), (15.0, 8.0), (5.0, 8.0)],
        ]);
        let Geometry::Lines(out) = clip_geometry(&g, &r()) else { panic!() };
        assert_eq!(out.len(), 3);
    }

    #[test]
    fn clip_geometry_drops_polygons_that_vanish_entirely() {
        let g = Geometry::Polygons(vec![vec![square(2.0, 8.0)], vec![square(20.0, 30.0)]]);
        let Geometry::Polygons(out) = clip_geometry(&g, &r()) else { panic!() };
        assert_eq!(out.len(), 1);
    }

    #[test]
    fn clipping_to_a_buffered_tile_rect_keeps_the_overspill() {
        use tile_build::geom::{tile_rect, DEFAULT_BUFFER};
        // A line 3 units past tile 0's eastern edge, with a 5-unit buffer: the
        // overspill survives, which is what stops a seam at the tile join.
        let rect = tile_rect(0, 0, 4096, DEFAULT_BUFFER);
        let line = vec![(4090.0, 100.0), (4099.0, 100.0)];
        let out = clip_line(&line, &rect);
        assert_eq!(out.len(), 1);
        assert!(close(out[0][1], (4099.0, 100.0)), "inside the buffer: {out:?}");
        // Past the buffer, it is cut at buffer's edge.
        let line = vec![(4090.0, 100.0), (4200.0, 100.0)];
        let out = clip_line(&line, &rect);
        assert!(close(out[0][1], (4096.0 + DEFAULT_BUFFER, 100.0)), "{out:?}");
    }

    // --- significance ------------------------------------------------------

    /// Every vertex the clip interpolates is marked as one no threshold may drop,
    /// and every vertex that merely passes through keeps whatever it arrived with.
    /// That is the contract [`tile_build::simplify::filter`] relies on to keep two rings
    /// sharing a boundary edge on the same vertices.
    #[test]
    fn a_crossing_is_marked_unremovable_and_a_pass_through_keeps_its_significance() {
        use tile_build::geom::{SigPt, ALWAYS};

        let at = |x: f64, y: f64, sig: f64| SigPt { x, y, sig };
        // In from the west, out to the east: both ends are crossings, the middle
        // vertex is the source's own and keeps its score.
        let line = [at(-5.0, 5.0, 7.0), at(5.0, 5.0, 3.0), at(15.0, 5.0, 9.0)];
        let out = clip_line(&line, &r());
        assert_eq!(out.len(), 1, "{out:?}");
        let piece = &out[0];
        assert_eq!(piece.len(), 3);
        assert_eq!(piece[0], at(0.0, 5.0, ALWAYS), "the western crossing");
        assert_eq!(piece[1], at(5.0, 5.0, 3.0), "untouched, score and all");
        assert_eq!(piece[2], at(10.0, 5.0, ALWAYS), "the eastern crossing");

        // A segment entirely inside is handed back as the very same vertices, not
        // as recomputed ones: `a + 1.0 * (b - a)` is not always `b`.
        let inside = [at(2.0, 2.0, 4.0), at(8.0, 7.0, 6.0)];
        assert_eq!(clip_line(&inside, &r())[0], inside.to_vec());
    }

    /// A ring re-closed after clipping must close on a **copy** of its first
    /// surviving vertex. Two ends with different significance is how a filter opens
    /// a ring: it keeps one and drops the other.
    #[test]
    fn a_re_closed_ring_closes_on_a_copy_of_its_first_vertex() {
        use tile_build::geom::SigPt;

        let at = |x: f64, y: f64, sig: f64| SigPt { x, y, sig };
        // Half in, half out, so the ring is genuinely rebuilt by the clip.
        let ring = [
            at(5.0, 2.0, 1.0),
            at(15.0, 2.0, 2.0),
            at(15.0, 8.0, 3.0),
            at(5.0, 8.0, 4.0),
            at(5.0, 2.0, 1.0),
        ];
        let out = clip_ring(&ring, &r());
        assert!(out.len() > 3, "{out:?}");
        assert_eq!(out.first(), out.last(), "closed, on the same vertex exactly");
    }
}


