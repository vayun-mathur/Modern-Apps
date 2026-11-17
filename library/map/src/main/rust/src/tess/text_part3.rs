
    /// The anchor decides which side of the point the label sits on, and the offset pushes
    /// it further that way. `Left` means the block's left edge is at the point, so the text
    /// runs to the **right** — MapLibre's sense, and the one that reads backwards.
    #[test]
    fn the_anchor_puts_the_block_on_the_right_side_of_the_point() {
        let Some(atlas) = atlas() else { return };
        let (text_px, span) = (32.0f32, 512.0f32);
        let lines = shape_wrapped(&atlas, Weight::Regular, "Cafe", false, 8.0);
        let bounds = |anchor: Anchor, offset: f32| {
            let (mut v, mut idx) = (Vec::new(), Vec::new());
            emit(
                &atlas,
                Weight::Regular,
                &lines,
                (0.5, 0.5),
                anchor,
                (offset, 0.0),
                text_px,
                span,
                &|_, _| 0.0,
                &mut v,
                &mut idx,
            );
            let xs: Vec<f32> = v.chunks(FLOATS_PER_VERTEX).map(|c| c[0]).collect();
            (
                xs.iter().cloned().fold(f32::INFINITY, f32::min),
                xs.iter().cloned().fold(f32::NEG_INFINITY, f32::max),
            )
        };
        let (left_lo, _) = bounds(Anchor::Left, 1.1);
        let (_, right_hi) = bounds(Anchor::Right, 1.1);
        let em = text_px / span;
        // The SDF spread pads the ink a little past the pen, so the edges land near the
        // offset rather than exactly on it.
        assert!(left_lo > 0.5, "a left-anchored label must sit right of its point");
        assert!(right_hi < 0.5, "a right-anchored label must sit left of its point");
        assert!(
            (left_lo - (0.5 + 1.1 * em)).abs() < 0.3 * em,
            "left edge {left_lo} is not ~1.1 em right of the point",
        );
        assert!(
            (right_hi - (0.5 - 1.1 * em)).abs() < 0.3 * em,
            "right edge {right_hi} is not ~1.1 em left of the point",
        );
        // Same block either side of the point: the two placements are a translation of
        // each other, so their ink spans match. (Their *edges* are not mirror images —
        // the first glyph's left bearing and the last one's right overhang differ.)
        let (left_lo2, left_hi) = bounds(Anchor::Left, 1.1);
        let (right_lo, right_hi2) = bounds(Anchor::Right, 1.1);
        assert!(
            ((left_hi - left_lo2) - (right_hi2 - right_lo)).abs() < 1e-6,
            "the two anchors drew different-sized blocks",
        );
    }

    /// The offset's sign comes from the anchor, not from the style: MapLibre takes its
    /// magnitude. A negative `text-offset` must not flip a left-anchored label across its
    /// own point.
    #[test]
    fn a_negative_offset_still_pushes_away_from_the_point() {
        let Some(atlas) = atlas() else { return };
        let lines = shape_wrapped(&atlas, Weight::Regular, "Bar", false, 8.0);
        let left_edge = |offset: f32| {
            let (mut v, mut idx) = (Vec::new(), Vec::new());
            emit(
                &atlas, Weight::Regular, &lines, (0.5, 0.5), Anchor::Left, (offset, 0.0),
                32.0, 512.0, &|_, _| 0.0, &mut v, &mut idx,
            );
            v.chunks(FLOATS_PER_VERTEX).map(|c| c[0]).fold(f32::MAX, f32::min)
        };
        assert!((left_edge(-1.1) - left_edge(1.1)).abs() < 1e-6);
    }

    // --- upright labels under a rotated camera ------------------------------

    /// The rotation the clip matrix applies, so the round trip below is checked against
    /// the real thing rather than against a restatement of it. Mirrors
    /// `Camera::world_quad_to_clip`.
    fn to_screen(offset: (f32, f32), rotation: (f32, f32)) -> (f32, f32) {
        let (cos, sin) = rotation;
        (cos * offset.0 + sin * offset.1, -sin * offset.0 + cos * offset.1)
    }

    #[test]
    fn a_counter_rotated_quad_comes_out_screen_aligned() {
        // The point of `upright`: whatever the bearing, the glyph the driver sees is the
        // same glyph in the same place as at bearing zero.
        for degrees in [0.0f32, 30.0, 90.0, 180.0, 275.0] {
            let radians = degrees.to_radians();
            let rotation = (radians.cos(), radians.sin());
            let pivot = (0.5f32, 0.5f32);
            // One quad (6 floats/vertex: x, y, u, v, anchor.x, anchor.y), offset a known
            // amount right of and above the anchor. `upright` moves only x, y.
            let mut quad = vec![
                0.6, 0.45, 0.0, 0.0, 0.5, 0.5, //
                0.7, 0.45, 1.0, 0.0, 0.5, 0.5, //
                0.7, 0.55, 1.0, 1.0, 0.5, 0.5, //
                0.6, 0.55, 0.0, 1.0, 0.5, 0.5,
            ];
            let before: Vec<(f32, f32)> = quad
                .chunks_exact(FLOATS_PER_VERTEX)
                .map(|v| (v[0] - pivot.0, v[1] - pivot.1))
                .collect();
            upright(&mut quad, pivot, rotation);
            for (at, vertex) in quad.chunks_exact(FLOATS_PER_VERTEX).enumerate() {
                let (sx, sy) =
                    to_screen((vertex[0] - pivot.0, vertex[1] - pivot.1), rotation);
                let (wx, wy) = before[at];
                assert!((sx - wx).abs() < 1e-5, "{degrees} deg, vertex {at}: {sx} vs {wx}");
                assert!((sy - wy).abs() < 1e-5, "{degrees} deg, vertex {at}: {sy} vs {wy}");
            }
        }
    }

    #[test]
    fn a_north_up_camera_leaves_the_quad_byte_identical() {
        // The whole phone path runs at bearing zero, so this must be a no-op there rather
        // than a rotation by an angle that happens to round to nothing.
        let mut quad = vec![0.6, 0.45, 0.0, 0.0, 0.5, 0.5, 0.7, 0.55, 1.0, 1.0, 0.5, 0.5];
        let original = quad.clone();
        upright(&mut quad, (0.5, 0.5), (1.0, 0.0));
        assert_eq!(quad, original);
    }

    #[test]
    fn the_anchor_itself_never_moves() {
        // The pivot is where the label is glued to the ground; if it drifted, every label
        // would slide off its own feature as the camera turned.
        let mut at_pivot = vec![0.25, 0.75, 0.0, 0.0, 0.25, 0.75];
        upright(&mut at_pivot, (0.25, 0.75), (0.5, 3f32.sqrt() / 2.0));
        assert!((at_pivot[0] - 0.25).abs() < 1e-6);
        assert!((at_pivot[1] - 0.75).abs() < 1e-6);
    }

    // --- curved labels along a line -----------------------------------------

    /// One shaped line from a string, for the curved-layout tests.
    fn one_line(atlas: &GlyphAtlas, text: &str) -> ShapedLine {
        let (glyphs, advance) = shape(atlas, Weight::Regular, text, false);
        ShapedLine { glyphs, advance }
    }

    /// A tangent's heading in radians, for comparing how far the run turned.
    fn heading(t: (f32, f32)) -> f32 {
        t.1.atan2(t.0)
    }

    #[test]
    fn a_straight_line_lays_glyphs_left_to_right_and_upright() {
        // The core straight-line guarantee: on a horizontal centreline every glyph faces the
        // same way (tangent ~ (1, 0), i.e. upright) and the pens march left to right, so the run
        // reads exactly as a point label would — just anchored to the road instead of a point.
        let Some(atlas) = atlas() else { return };
        let line = one_line(&atlas, "Main Street");
        // px_per_font_unit small enough that the run fits inside the 0.8-long centreline.
        let ppfu = 16.0 / UP_EM as f32 / 512.0;
        let centreline = [(0.1f32, 0.5f32), (0.9, 0.5)];
        let placed = layout_along_line(&line, &centreline, ppfu);
        assert_eq!(placed.len(), line.glyphs.len(), "every glyph is placed on a line that fits");
        for cg in &placed {
            assert!((cg.tangent.0 - 1.0).abs() < 1e-4, "cos {}", cg.tangent.0);
            assert!(cg.tangent.1.abs() < 1e-4, "a straight run is upright, sin {}", cg.tangent.1);
            assert!((cg.pen.1 - 0.5).abs() < 1e-4, "the baseline follows the centreline");
        }
        for pair in placed.windows(2) {
            assert!(pair[1].pen.0 > pair[0].pen.0, "pens advance left to right");
        }
    }

    #[test]
    fn a_curved_line_rotates_glyphs_along_it() {
        // The whole point of curved labels: on a bending line the glyphs turn to follow it, so
        // the run's tangent is not constant. An arc guarantees a continuously varying tangent, so
        // whichever centred slice the run occupies still spans a range of headings.
        let Some(atlas) = atlas() else { return };
        let line = one_line(&atlas, "River Road");
        // A quarter-circle arc, radius 0.35 about the tile centre, sampled finely.
        let mut centreline = Vec::new();
        for i in 0..=48 {
            let theta = std::f32::consts::FRAC_PI_2 * i as f32 / 48.0;
            centreline.push((0.5 + 0.35 * theta.cos(), 0.5 + 0.35 * theta.sin()));
        }
        let ppfu = 16.0 / UP_EM as f32 / 512.0;
        let placed = layout_along_line(&line, &centreline, ppfu);
        assert!(placed.len() >= 2, "the arc is long enough to place the run");
        let headings: Vec<f32> = placed.iter().map(|c| heading(c.tangent)).collect();
        let lo = headings.iter().cloned().fold(f32::INFINITY, f32::min);
        let hi = headings.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        assert!(hi - lo > 0.1, "glyphs must rotate along the curve: heading spread {}", hi - lo);
        // And the tangents are unit length — a bad normalisation would smear the glyphs.
        for cg in &placed {
            let len = (cg.tangent.0 * cg.tangent.0 + cg.tangent.1 * cg.tangent.1).sqrt();
            assert!((len - 1.0).abs() < 1e-4, "tangent not unit: {len}");
        }
    }

    #[test]
    fn a_run_longer_than_its_line_is_not_placed() {
        // A long name on a short road does not fit, so it drops entirely rather than spilling off
        // the ends — the caller then places no label for it.
        let Some(atlas) = atlas() else { return };
        let line = one_line(&atlas, "A Very Long Street Name Indeed");
        let ppfu = 64.0 / UP_EM as f32 / 256.0; // big text
        let centreline = [(0.48f32, 0.5f32), (0.52, 0.5)]; // a stub 0.04 long
        assert!(layout_along_line(&line, &centreline, ppfu).is_empty());
    }

    #[test]
    fn emit_curved_makes_two_finite_triangles_per_placed_glyph() {
        let Some(atlas) = atlas() else { return };
        let line = one_line(&atlas, "Bay");
        let centreline = [(0.1f32, 0.5f32), (0.9, 0.5)];
        let ppfu = 24.0 / UP_EM as f32 / 512.0;
        let placed = layout_along_line(&line, &centreline, ppfu);
        let (mut v, mut idx) = (Vec::new(), Vec::new());
        emit_curved(
            &atlas,
            Weight::Regular,
            &line,
            &centreline,
            &CurvedScale {
                text_px: 24.0,
                tile_span_px: 512.0,
                ground: &|_, _| 0.0,
            },
            &mut v,
            &mut idx,
        );
        // A glyph with no ink (a space) draws no quad, so bound by placed glyphs that have UVs.
        let drawable = placed.iter().filter(|c| atlas.uv(Weight::Regular, c.glyph.ch).is_some()).count();
        assert_eq!(idx.len(), drawable * 6, "six indices per drawable glyph");
        assert_eq!(v.len(), drawable * 4 * FLOATS_PER_VERTEX);
        for f in &v {
            assert!(f.is_finite(), "curved emission produced a non-finite vertex");
        }
        // UVs still address the atlas.
        for chunk in v.chunks(FLOATS_PER_VERTEX) {
            assert!((0.0..=1.0).contains(&chunk[2]) && (0.0..=1.0).contains(&chunk[3]));
        }
    }

    /// A synthetic run of `n` identical square glyphs, each one font-unit-em wide. Lets the
    /// along-line layout be tested without staged fonts, since it never touches the atlas.
    fn block_run(n: usize) -> ShapedLine {
        let em = UP_EM as f32;
        let glyphs: Vec<ShapedGlyph> = (0..n)
            .map(|i| ShapedGlyph {
                pen_x: i as f32 * em,
                ch: 'x',
                advance: em,
                bearing_x: 0.0,
                top: em,
                w: em,
                h: em,
            })
            .collect();
        ShapedLine { glyphs, advance: n as f32 * em }
    }

    #[test]
    fn a_join_between_two_parts_lends_no_length_to_the_fit_test() {
        // The reported truncation: a feature's parts are concatenated into one polyline, so a
        // multi-part road carries a phantom connector between them. Its length used to count
        // toward the fit test, letting a name that fits on neither part be accepted and then
        // run off the end of the first one. The name must now be rejected outright.
        let run = block_run(10); // ten ems long
        let ppfu = 1.0 / UP_EM as f32; // one em == one tile-local unit, so run_len == 10.0
        // Two collinear-but-disjoint parts of 6, bridged by a connector that doubles back.
        let two_parts = [(0.0f32, 0.0), (6.0, 0.0), (0.0, 4.0), (6.0, 4.0)];
        assert!(
            polyline_length(&two_parts) > 10.0,
            "the concatenated length alone would pass the fit test"
        );
        assert!(
            layout_along_line(&run, &two_parts, ppfu).is_empty(),
            "a run that fits no single part of the feature must not be drawn at all"
        );
    }

    #[test]
    fn a_run_is_laid_on_the_longer_part_not_across_the_join() {
        // When one part *is* long enough, the label goes there — the cheap version of "put it
        // where it fits" — and no glyph lands on the connector between the parts.
        let run = block_run(4);
        let ppfu = 1.0 / UP_EM as f32;
        // A short stub, then a sharp turn, then the real road: y == 4 for its whole length.
        let path = [(0.0f32, 0.0), (2.0, 0.0), (0.0, 4.0), (9.0, 4.0)];
        let placed = layout_along_line(&run, &path, ppfu);
        assert_eq!(placed.len(), 4, "the run fits the longer part");
        for cg in &placed {
            assert!((cg.pen.1 - 4.0).abs() < 1e-4, "glyph left the long part at y {}", cg.pen.1);
            assert!((cg.tangent.0 - 1.0).abs() < 1e-4, "and faces along it");
        }
    }

    #[test]
    fn a_single_sharp_corner_does_not_carry_glyphs() {
        // A right-angle bend is the degenerate case of the same rule: the run takes one arm.
        let run = block_run(3);
        let ppfu = 1.0 / UP_EM as f32;
        let elbow = [(0.0f32, 0.0), (4.0, 0.0), (4.0, 9.0)];
        let placed = layout_along_line(&run, &elbow, ppfu);
        assert_eq!(placed.len(), 3);
        let spread = placed
            .iter()
            .map(|c| heading(c.tangent))
            .fold(f32::NEG_INFINITY, f32::max)
            - placed.iter().map(|c| heading(c.tangent)).fold(f32::INFINITY, f32::min);
        assert!(spread < 1e-3, "no glyph straddles the corner, heading spread {spread}");
    }

    #[test]
    fn a_gentle_bend_turns_glyph_by_glyph_instead_of_in_steps() {
        // The kinked-rotation symptom. Two long segments meeting at a shallow angle used to
        // give every glyph on a segment one identical heading and then jump, so the run read as
        // separately-rotated letters. Sampling the chord each glyph spans turns it continuously:
        // consecutive headings differ, and none differs by the whole corner at once.
        let run = block_run(8);
        let ppfu = 1.0 / UP_EM as f32;
        // A 30-degree bend — inside the max turn, so it stays one run.
        let corner = 30.0f32.to_radians();
        let bend = [(0.0f32, 0.0), (6.0, 0.0), (6.0 + 6.0 * corner.cos(), 6.0 * corner.sin())];
        let placed = layout_along_line(&run, &bend, ppfu);
        assert_eq!(placed.len(), 8);
        let headings: Vec<f32> = placed.iter().map(|c| heading(c.tangent)).collect();
        let steps: Vec<f32> =
            headings.windows(2).map(|w| (w[1] - w[0]).abs()).collect();
        assert!(
            headings.last().unwrap() - headings[0] > 1e-3,
            "the run still follows the bend overall"
        );
        assert!(
            steps.iter().all(|s| *s < corner * 0.75),
            "no single glyph absorbs the whole corner: steps {steps:?}"
        );
        assert!(
            steps.iter().filter(|s| **s > 1e-4).count() >= 2,
            "the turn is shared across glyphs rather than taken in one step: {steps:?}"
        );
    }

    /// A representative digitised road centreline in tile-local units: a 12-point
    /// gently bending run about 0.55 units long with no turn sharper than ~8° —
    /// a single smooth run in production terms, exercising the same longest-
    /// smooth-run selection and per-glyph turn-sharing as the old MVT fixture.
    fn real_road_centreline() -> Vec<(f32, f32)> {
        let pts: [(i32, i32); 12] = [
            (100, 900), (280, 880), (460, 855), (640, 825), (820, 790), (1000, 750),
            (1180, 705), (1360, 660), (1540, 615), (1720, 575), (1900, 540), (2080, 510),
        ];
        pts.iter().map(|&(x, y)| (x as f32 / 4096.0, y as f32 / 4096.0)).collect()
    }

    /// The curved-label fix, reproduced against digitised road coordinates instead of a
    /// synthetic polyline.
    ///
    /// The road is a 24-point gently bending run, ~1.1 tile-local units long
    /// with no turn sharper than ~8° — a single smooth run in production terms. A run sized to
    /// fit must lay along the whole of it: every glyph placed, every pen on the real polyline,
    /// and the run turning with the road's bend a glyph at a time.
    ///
    /// Coverage split, stated honestly: this pins the longest-smooth-run selection and the
    /// per-glyph turn-sharing on real digitised coordinates. The chord-vs-segment-tangent half
    /// is not observable on this road — its bends are too gentle to separate the two at any
    /// tolerance that is not noise — and stays pinned by the synthetic 30° bend in
    /// `a_gentle_bend_turns_glyph_by_glyph_instead_of_in_steps`. A segment-tangent revert passes
    /// this test and fails that one; a whole-length fit revert fails the joined-parts test below.
    #[test]
    fn the_real_tile_road_carries_a_run_along_its_whole_smooth_length() {
        let centreline = real_road_centreline();
        assert!(centreline.len() >= 10, "the road has {} points", centreline.len());
        // 10 ems at 16 px on a 512 px tile: ~0.31 tile-local units, inside the road's ~0.55.
        let run = block_run(10);
        let ppfu = 16.0 / UP_EM as f32 / 512.0;
        let placed = layout_along_line(&run, &centreline, ppfu);
        assert_eq!(placed.len(), run.glyphs.len(), "every glyph of a fitting run is placed");
        // Pens march along the road in order, never leaving it.
        for pair in placed.windows(2) {
            let (a, b) = (pair[0].pen, pair[1].pen);
            let step = ((b.0 - a.0).powi(2) + (b.1 - a.1).powi(2)).sqrt();
            assert!(step > 0.0 && step < 0.1, "pens advance along the road, step {step}");
        }
        // Chord smoothing on real quantised coordinates: the run follows the road's bend
        // overall, but no single glyph absorbs the whole of it at once (the faceted,
        // individually-rotated look the chord tangents fix) — and the turn is shared across
        // glyphs rather than taken in one step.
        let headings: Vec<f32> = placed.iter().map(|c| heading(c.tangent)).collect();
        let steps: Vec<f32> =
            headings.windows(2).map(|w| (w[1] - w[0]).abs()).collect();
        let spread = headings.iter().cloned().fold(f32::NEG_INFINITY, f32::max)
            - headings.iter().cloned().fold(f32::INFINITY, f32::min);
        assert!(spread > 1e-3, "the real road bends, so the run must turn with it");
        assert!(
            steps.iter().all(|s| *s < spread),
            "no single glyph absorbs the whole bend: steps {steps:?} spread {spread}",
        );
        assert!(
            steps.iter().filter(|s| **s > 1e-4).count() >= 2,
            "the turn is shared across glyphs rather than taken in one step: {steps:?}"
        );
        let span = (placed.last().expect("placed").pen.0 - placed.first().expect("placed").pen.0).abs()
            + (placed.last().expect("placed").pen.1 - placed.first().expect("placed").pen.1).abs();
        assert!(span > 0.3, "the run spans the road, span {span}");
    }

    /// Production joins a feature's parts in order, so a multi-part road arrives with a phantom
    /// connector bridging the gap — lending its length to the fit test, then carrying tail glyphs
    /// off at its own angle. Here the centreline is split into two disjoint pieces joined
    /// in order: a run longer than either piece but shorter than the joined total must be rejected
    /// outright (it fits nowhere), and a run that fits the longer piece must lay only there.
    #[test]
    fn joined_real_parts_lend_no_length_to_the_fit_test() {
        let centreline = real_road_centreline();
        // Two disjoint pieces, the second shifted sideways: what two parts of one feature
        // look like after production joins them in order — a phantom connector bridging a gap,
        // meeting both pieces at a right angle no real road takes. The sideways shift is
        // large (0.35 x, 0.25 y) so the joint turns ~90°, far past the 45° smooth budget,
        // and the run must split there.
        let (head, tail) = (centreline[..6].to_vec(), centreline[8..].to_vec());
        let shifted: Vec<(f32, f32)> =
            tail.iter().map(|&(x, y)| (x + 0.9, y + 0.7)).collect();
        let mut joined = head.clone();
        joined.extend_from_slice(&shifted);
        let len = |pts: &[(f32, f32)]| {
            pts.windows(2)
                .map(|w| ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt())
                .sum::<f32>()
        };
        let joined_len = len(&joined);
        let smooth_len = len(longest_smooth_run(&joined));
        assert!(
            smooth_len < joined_len - 0.1,
            "the joint must split the run: smooth {smooth_len} vs joined {joined_len}",
        );
        // One em == 0.1 tile-local units here; size the probe between the longest smooth piece
        // and the joined total, so only the phantom length could admit it.
        let ppfu = 0.1 / UP_EM as f32;
        let probe_em = ((smooth_len + joined_len) * 0.5 * 10.0) as usize;
        assert!(
            (probe_em as f32) * 0.1 > smooth_len && (probe_em as f32) * 0.1 < joined_len,
            "probe {probe_em} ems must sit between smooth {smooth_len} and joined {joined_len}",
        );
        assert!(
            layout_along_line(&block_run(probe_em), &joined, ppfu).is_empty(),
            "a run that fits no single part must not be drawn at all",
        );
        // And a run that fits the longest smooth piece lays only on it: no glyph on the connector.
        let fits_em = (smooth_len * 10.0 * 0.8) as usize;
        let placed = layout_along_line(&block_run(fits_em), &joined, ppfu);
        assert_eq!(placed.len(), fits_em, "a fitting run lays fully");
        let smooth = longest_smooth_run(&joined);
        let (sx0, sx1, sy0, sy1) = (
            smooth.iter().map(|p| p.0).fold(f32::INFINITY, f32::min),
            smooth.iter().map(|p| p.0).fold(f32::NEG_INFINITY, f32::max),
            smooth.iter().map(|p| p.1).fold(f32::INFINITY, f32::min),
            smooth.iter().map(|p| p.1).fold(f32::NEG_INFINITY, f32::max),
        );
        for cg in &placed {
            assert!(
                cg.pen.0 >= sx0 - 1e-3 && cg.pen.0 <= sx1 + 1e-3
                    && cg.pen.1 >= sy0 - 1e-3 && cg.pen.1 <= sy1 + 1e-3,
                "glyph left the smooth run at {:?}",
                cg.pen,
            );
        }
    }
