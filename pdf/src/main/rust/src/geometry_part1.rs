#[cfg(test)]
mod geometry_tests {
    use crate::*;

    fn rect4(r: [f64; 4]) -> Object {
        Object::Array(vec![r[0].into(), r[1].into(), r[2].into(), r[3].into()])
    }

    /// Single-page document with the given boxes, `/Rotate` and `/UserUnit`.
    fn page_doc(
        media: [f64; 4],
        crop: Option<[f64; 4]>,
        rotate: Option<i64>,
        user_unit: Option<f64>,
    ) -> Document {
        let mut doc = Document::with_version("1.7");
        let pages_id = doc.new_object_id();
        let mut page = dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => rect4(media),
        };
        if let Some(c) = crop {
            page.set("CropBox", rect4(c));
        }
        if let Some(r) = rotate {
            page.set("Rotate", Object::Integer(r));
        }
        if let Some(u) = user_unit {
            page.set("UserUnit", Object::Real(u as f32));
        }
        let page_id = doc.add_object(page);
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => Object::Array(vec![page_id.into()]),
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);
        doc
    }

    /// The producer must not describe a page the consumer will not accept.
    /// `SafePdfParser.kt` clamps width and height with `coerceAtMost(20000f)`,
    /// so an unbounded box here laid every coordinate out against one page size
    /// while the canvas was sized to another. Only the LOWER bound was checked.
    #[test]
    fn an_oversized_page_is_clamped_to_the_bound_the_consumer_uses() {
        let doc = page_doc([0.0, 0.0, 50_000.0, 90_000.0], None, None, None);
        let page_id = nth_page_id(&doc, 0).expect("page 0");
        let vb = page_visible_box(&doc, page_id);
        assert_eq!(vb, [0.0, 0.0, 20_000.0, 20_000.0]);

        // Clamped from the ORIGIN, so a non-zero origin keeps its own coordinates
        // rather than being relocated.
        let doc = page_doc([100.0, 200.0, 50_000.0, 90_000.0], None, None, None);
        let page_id = nth_page_id(&doc, 0).expect("page 0");
        assert_eq!(page_visible_box(&doc, page_id), [100.0, 200.0, 20_100.0, 20_200.0]);
    }

    /// The clamp must not touch a page that is merely large but in range, nor
    /// re-open the degenerate case the lower bound already handles.
    #[test]
    fn the_page_clamp_leaves_in_range_and_degenerate_boxes_alone() {
        let doc = page_doc([0.0, 0.0, 20_000.0, 612.0], None, None, None);
        let page_id = nth_page_id(&doc, 0).expect("page 0");
        assert_eq!(page_visible_box(&doc, page_id), [0.0, 0.0, 20_000.0, 612.0]);

        let doc = page_doc([0.0, 0.0, 0.0, 0.0], None, None, None);
        let page_id = nth_page_id(&doc, 0).expect("page 0");
        assert_eq!(page_visible_box(&doc, page_id), [0.0, 0.0, 612.0, 792.0]);
    }

    /// [`MAX_PAGE_DIMENSION`] is a CONTRACT with `SafePdfParser.kt`, not a local
    /// policy: the whole point of FINDING F was that the two sides disagreed, so
    /// a comment saying "matches the consumer" is exactly the assurance that
    /// rots. Nothing else catches a drift — both numbers are valid literals,
    /// Kotlin still compiles, and every Rust test here asserts our own value
    /// against itself. So assert it across the language boundary against the
    /// real file, the way `wire::wire_version_is_not_ahead_of_the_kotlin_parser`
    /// already does for the wire version.
    ///
    /// Suggested by `hunt-missing`, who pointed out that hoisting a duplicated
    /// literal into a named constant on each side makes each side internally
    /// consistent and leaves the PAIR just as free to drift.
    #[test]
    fn the_page_bound_matches_the_kotlin_consumers_clamp() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../java/com/vayunmathur/pdf/util/SafePdfParser.kt"
        );
        let src = std::fs::read_to_string(path)
            .unwrap_or_else(|e| panic!("cannot read the page box's only consumer {path}: {e}"));
        // `rawWidth.coerceAtMost(MAX_PAGE_DIM)` and the same on height: the
        // consumer hoisted the literal into a named constant, so resolve the
        // constant's value from the same file rather than matching a literal.
        // `private const val MAX_PAGE_DIM = 20000f`, with an optional comment.
        let max_page_dim: f64 = src
            .lines()
            .find_map(|l| {
                let v = l.trim().strip_prefix("private const val MAX_PAGE_DIM = ")?;
                v.split_whitespace()
                    .next()?
                    .trim_end_matches('f')
                    .parse()
                    .ok()
            })
            .unwrap_or_else(|| panic!("{path} must declare `private const val MAX_PAGE_DIM = <n>f`"));
        // Both dimension clamps must go through the constant: a clamp arm that
        // stops mentioning it has drifted off the contract silently.
        let pinned: Vec<&str> = src
            .lines()
            .filter(|l| (l.contains("rawWidth") || l.contains("rawHeight")) && l.contains("coerceAtMost("))
            .collect();
        assert_eq!(
            pinned.len(),
            2,
            "expected a `coerceAtMost(...)` on each of rawWidth and rawHeight in {path}; \
             found {pinned:?}. If the consumer's clamp moved or was renamed, this contract \
             needs re-pinning rather than deleting."
        );
        for l in &pinned {
            assert!(
                l.contains("coerceAtMost(MAX_PAGE_DIM)"),
                "{path} clamps a page dimension without MAX_PAGE_DIM: {l:?}."
            );
        }
        let bounds = [max_page_dim, max_page_dim];
        for b in bounds {
            assert_eq!(
                b, super::MAX_PAGE_DIMENSION,
                "geometry.rs clamps the page box at {} but SafePdfParser.kt clamps at {b}. \
                 The two then describe DIFFERENT pages: wire coordinates are laid out against \
                 one box and the canvas is sized from the other. Change both together.",
                super::MAX_PAGE_DIMENSION
            );
        }
    }

    fn assert_mat_eq(a: &Mat, b: &Mat, what: &str) {
        for i in 0..6 {
            assert!(
                (a[i] - b[i]).abs() < 1e-9,
                "{what}: element {i} was {} expected {}",
                a[i],
                b[i]
            );
        }
    }

    /// The corner test above proves the box FILLS the canvas, which 90 and 270
    /// both do — it cannot tell them apart, and neither can a determinant or a
    /// round-trip through the inverse. This pins the DIRECTION by naming which
    /// crop-box corner each rotation puts at the display origin, re-derived from
    /// §7.7.3.3 (`/Rotate` is CLOCKWISE) in a y-up display space (`model.rs`:
    /// Kotlin applies only the y-flip and the fit-to-width scale).
    ///
    /// Crop box [50,100]..[350,500], so w=300, h=400, and in page-local
    /// coordinates (u,v) = (x-50, y-100) the spec maps are `(u,v)`, `(v, w-u)`,
    /// `(w-u, h-v)`, `(h-v, u)`. Physically turning the sheet clockwise sends its
    /// bottom-right corner to the bottom-left, which is why 90 puts the page's
    /// BOTTOM-RIGHT corner on the display origin and 270 (a quarter turn the
    /// other way) puts its TOP-LEFT corner there. Swapping the two arms swaps
    /// exactly those two rows.
    #[test]
    fn quarter_turns_rotate_clockwise_not_anticlockwise() {
        let (bl, br, tr, tl) = (
            (50.0, 100.0),
            (350.0, 100.0),
            (350.0, 500.0),
            (50.0, 500.0),
        );
        // (rotation, the page corner that must land on the display origin,
        //  and the corner that must land at the far end of the display x axis).
        for (rot, at_origin, at_x_max) in [
            (0i64, bl, br),
            (90, br, tr),
            (180, tr, tl),
            (270, tl, bl),
        ] {
            let doc = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([50.0, 100.0, 350.0, 500.0]),
                Some(rot),
                None,
            );
            let pid = nth_page_id(&doc, 0).expect("page 0");
            let base = page_base_matrix(&doc, pid);
            let (w, _h) = page_display_size(&doc, pid);
            let (ox, oy) = transform(&base, at_origin.0, at_origin.1);
            assert!(
                ox.abs() < 1e-9 && oy.abs() < 1e-9,
                "rot={rot}: {at_origin:?} must map to the display origin, got ({ox},{oy}) \
                 — the 90 and 270 arms are swapped (anticlockwise)"
            );
            let (xx, xy) = transform(&base, at_x_max.0, at_x_max.1);
            assert!(
                (xx - w as f64).abs() < 1e-6 && xy.abs() < 1e-9,
                "rot={rot}: {at_x_max:?} must map to ({w},0), got ({xx},{xy})"
            );
        }
    }

    /// The matrices must agree with the maps documented above `page_base_matrix`,
    /// element by element, so a future edit to one without the other is caught.
    #[test]
    fn base_matrix_matches_the_documented_maps() {
        let (w, h) = (300.0_f64, 400.0_f64);
        for (rot, want) in [
            (0i64, [1.0, 0.0, 0.0, 1.0, -50.0, -100.0]),
            // (x,y) -> (y, w-x), composed with the translate(-50,-100).
            (90, [0.0, -1.0, 1.0, 0.0, -100.0, w + 50.0]),
            (180, [-1.0, 0.0, 0.0, -1.0, w + 50.0, h + 100.0]),
            (270, [0.0, 1.0, -1.0, 0.0, h + 100.0, -50.0]),
        ] {
            let doc = page_doc([50.0, 100.0, 350.0, 500.0], None, Some(rot), None);
            let pid = nth_page_id(&doc, 0).expect("page 0");
            assert_mat_eq(&page_base_matrix(&doc, pid), &want, &format!("rot={rot}"));
        }
    }

    /// `page_base_inverse` must be the exact inverse of `page_base_matrix` for
    /// every rotation — it converts editor coordinates back into raw page space
    /// when storing annotations, so any drift writes them to the wrong place.
    #[test]
    fn base_inverse_is_exact_inverse_for_all_rotations() {
        for uu in [None, Some(2.0)] {
            for rot in [0i64, 90, 180, 270] {
                let doc = page_doc([50.0, 100.0, 350.0, 500.0], None, Some(rot), uu);
                let pid = nth_page_id(&doc, 0).expect("page 0");
                let base = page_base_matrix(&doc, pid);
                let inv = page_base_inverse(&doc, 0);
                let tag = format!("rot={rot} uu={uu:?}");
                assert_mat_eq(&mat_mul(&base, &inv), &IDENTITY, &tag);
                assert_mat_eq(&mat_mul(&inv, &base), &IDENTITY, &format!("{tag} reversed"));
                let (dx, dy) = transform(&base, 350.0, 500.0);
                let (rx, ry) = transform(&inv, dx, dy);
                assert!(
                    (rx - 350.0).abs() < 1e-6 && (ry - 500.0).abs() < 1e-6,
                    "{tag}: round-trip gave ({rx},{ry})"
                );
            }
        }
    }

    /// Every corner of the crop box must land exactly on the canvas for all four
    /// rotations: nothing off-canvas, and the box must cover the full display
    /// size (which catches a rotation that rotates but forgets to translate).
    #[test]
    fn crop_box_corners_fill_the_canvas() {
        for rot in [0i64, 90, 180, 270] {
            let doc = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([50.0, 100.0, 350.0, 500.0]),
                Some(rot),
                None,
            );
            let pid = nth_page_id(&doc, 0).expect("page 0");
            let base = page_base_matrix(&doc, pid);
            let (w, h) = page_display_size(&doc, pid);
            let (w, h) = (w as f64, h as f64);
            let mut xs: Vec<f64> = Vec::new();
            let mut ys: Vec<f64> = Vec::new();
            for (x, y) in [(50.0, 100.0), (350.0, 100.0), (350.0, 500.0), (50.0, 500.0)] {
                let (dx, dy) = transform(&base, x, y);
                assert!(dx >= -1e-6 && dx <= w + 1e-6, "rot={rot}: x={dx} outside 0..{w}");
                assert!(dy >= -1e-6 && dy <= h + 1e-6, "rot={rot}: y={dy} outside 0..{h}");
                xs.push(dx);
                ys.push(dy);
            }
            let minx = xs.iter().cloned().fold(f64::INFINITY, f64::min);
            let maxx = xs.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            let miny = ys.iter().cloned().fold(f64::INFINITY, f64::min);
            let maxy = ys.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            assert!(
                minx.abs() < 1e-6 && miny.abs() < 1e-6,
                "rot={rot}: origin not translated to 0, got ({minx},{miny})"
            );
            assert!((maxx - w).abs() < 1e-6, "rot={rot}: extent {maxx} != width {w}");
            assert!((maxy - h).abs() < 1e-6, "rot={rot}: extent {maxy} != height {h}");
        }
    }

    /// `/Rotate` 90 and 270 swap the display dimensions; 0 and 180 do not.
    #[test]
    fn display_size_swaps_for_quarter_turns() {
        for (rot, want) in [
            (0i64, (300.0f32, 400.0f32)),
            (90, (400.0, 300.0)),
            (180, (300.0, 400.0)),
            (270, (400.0, 300.0)),
        ] {
            let doc = page_doc([50.0, 100.0, 350.0, 500.0], None, Some(rot), None);
            let pid = nth_page_id(&doc, 0).expect("page 0");
            assert_eq!(page_display_size(&doc, pid), want, "rot={rot}");
        }
    }

    /// Rendering is invariant under `/UserUnit`, so it must affect NEITHER the
    /// page matrix nor the display size. Applying it to only one of them (the
    /// original bug) mis-scaled content against the canvas, offset every
    /// hit-target by a UserUnit-scaled crop origin, and wrote newly authored
    /// annotations at the wrong coordinates.
    #[test]
    fn user_unit_does_not_affect_geometry() {
        for rot in [0i64, 90, 180, 270] {
            let plain = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([10.0, 20.0, 500.0, 700.0]),
                Some(rot),
                None,
            );
            let scaled = page_doc(
                [0.0, 0.0, 612.0, 792.0],
                Some([10.0, 20.0, 500.0, 700.0]),
                Some(rot),
                Some(2.0),
            );
            let pa = nth_page_id(&plain, 0).expect("page 0");
            let pb = nth_page_id(&scaled, 0).expect("page 0");
            assert_mat_eq(
                &page_base_matrix(&plain, pa),
                &page_base_matrix(&scaled, pb),
                &format!("rot={rot} matrix"),
            );
            assert_eq!(
                page_display_size(&plain, pa),
                page_display_size(&scaled, pb),
                "rot={rot} display size"
            );
        }
    }

    /// §7.7.3.3: `/Rotate` is normalized to a multiple of 90 in [0,360),
    /// including negatives and values above 360.
    #[test]
    fn rotation_is_normalized() {
        for (given, want) in [
            (-90i64, 270i64),
            (450, 90),
            (360, 0),
            (-450, 270),
            (720, 0),
            (45, 0),
            (100, 90),
        ] {
            let doc = page_doc([0.0, 0.0, 612.0, 792.0], None, Some(given), None);
            let pid = nth_page_id(&doc, 0).expect("page 0");
            assert_eq!(page_rotation(&doc, pid), want, "/Rotate {given}");
        }
    }

    /// §14.11.2: the visible box is CropBox intersected with MediaBox, falling
    /// back to MediaBox when the intersection is empty, always normalized, and
    /// never degenerate.
    #[test]
    fn visible_box_intersects_and_degrades() {
        let vb = |d: &Document| page_visible_box(d, nth_page_id(d, 0).expect("page 0"));

        // CropBox larger than MediaBox clamps to MediaBox.
        let d = page_doc([0.0, 0.0, 612.0, 792.0], Some([-100.0, -100.0, 1000.0, 1000.0]), None, None);
        assert_eq!(vb(&d), [0.0, 0.0, 612.0, 792.0]);

        // Partial overlap intersects.
        let d = page_doc([0.0, 0.0, 612.0, 792.0], Some([100.0, 100.0, 1000.0, 500.0]), None, None);
        assert_eq!(vb(&d), [100.0, 100.0, 612.0, 500.0]);

        // A disjoint CropBox falls back to MediaBox, not a zero-size page.
        let d = page_doc([0.0, 0.0, 612.0, 792.0], Some([700.0, 800.0, 900.0, 1000.0]), None, None);
        assert_eq!(vb(&d), [0.0, 0.0, 612.0, 792.0]);

        // §7.9.5: rect corners may be given in any order and must be normalized.
        let d = page_doc([612.0, 792.0, 0.0, 0.0], None, None, None);
        assert_eq!(vb(&d), [0.0, 0.0, 612.0, 792.0]);

        // A degenerate MediaBox must not ship a zero dimension, which would
        // divide by zero in the rasterizer's fit-to-width scale.
        let d = page_doc([0.0, 0.0, 0.0, 0.0], None, None, None);
        let (w, h) = page_display_size(&d, nth_page_id(&d, 0).expect("page 0"));
        assert!(w >= 1.0 && h >= 1.0, "degenerate MediaBox produced {w}x{h}");
    }

    /// §7.7.3.4: inheritable attributes walk /Parent, and a cyclic /Parent in a
    /// malformed file must terminate rather than hang.
    #[test]
    fn inherited_walks_parent_and_survives_a_cycle() {
        let mut doc = Document::with_version("1.7");
        let a = doc.new_object_id();
        let b = doc.new_object_id();
        // Two nodes that are each other's parent, neither carrying /MediaBox.
        doc.objects.insert(a, Object::Dictionary(dictionary! { "Type" => "Pages", "Parent" => b }));
        doc.objects.insert(b, Object::Dictionary(dictionary! { "Type" => "Pages", "Parent" => a }));
        assert!(inherited(&doc, a, b"MediaBox").is_none(), "cycle must terminate");
        assert_eq!(media_box(&doc, a), [0.0, 0.0, 612.0, 792.0], "falls back to Letter");

        // A page with no /MediaBox inherits its grandparent's.
        let root = doc.new_object_id();
        let mid = doc.new_object_id();
        let page = doc.add_object(dictionary! { "Type" => "Page", "Parent" => mid });
        doc.objects.insert(
            mid,
            Object::Dictionary(dictionary! { "Type" => "Pages", "Parent" => root, "Kids" => Object::Array(vec![page.into()]), "Count" => 1 }),
        );
        doc.objects.insert(
            root,
            Object::Dictionary(dictionary! { "Type" => "Pages", "Kids" => Object::Array(vec![mid.into()]), "Count" => 1, "MediaBox" => rect4([0.0, 0.0, 200.0, 400.0]) }),
        );
        assert_eq!(media_box(&doc, page), [0.0, 0.0, 200.0, 400.0]);
    }
}
