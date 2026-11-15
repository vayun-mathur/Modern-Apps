/// A luminosity soft mask with a non-default `/BC` backdrop (§11.6.5.2).
///
/// Added after `fix-interp` landed `/BC` and validated it against this harness:
/// the harness was sound for it, but only by accident of coverage — the corpus
/// had no `/BC` entry, and the module header still claimed this rasteriser
/// ignored the backdrop. It does ignore it directly; `interpret.rs` emits the
/// backdrop as a `Fill` over the group `/BBox` before the mask content, so it
/// arrives as an ordinary primitive.
///
/// This pins that arrangement from the outside. The mask group paints nothing
/// of its own over most of its area, so the mask value there is the luminosity
/// of `/BC` alone — a mid grey here, which must let roughly half the red
/// through. A renderer that ignored `/BC` and used the default black backdrop
/// would show nothing at all in that region, which is a 100%-vs-0% difference,
/// far outside any tolerance in this file.
#[test]
fn refdiff_luminosity_soft_mask_backdrop_colour() {
    let build = |bc: Option<[f64; 3]>| {
        let mut doc = Document::with_version("1.7");
        // The mask group paints a small white square in one corner only; the
        // rest of its BBox is whatever the backdrop says.
        let group = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(200.0), Object::Real(200.0)],
                "Group" => dictionary! {
                    "S" => "Transparency",
                    "CS" => Object::Name(b"DeviceRGB".to_vec()),
                },
                "Resources" => dictionary! {},
            },
            Content {
                operations: vec![
                    op("rg", vec![n(1.0), n(1.0), n(1.0)]),
                    op("re", vec![n(20.0), n(120.0), n(60.0), n(60.0)]),
                    op("f", vec![]),
                ],
            }
            .encode()
            .expect("mask content"),
        ));
        let mut smask = dictionary! {
            "S" => "Luminosity",
            "G" => Object::Reference(group),
        };
        if let Some(c) = bc {
            smask.set(
                "BC",
                vec![Object::Real(c[0] as f32), Object::Real(c[1] as f32), Object::Real(c[2] as f32)],
            );
        }
        let gs = doc.add_object(dictionary! { "Type" => "ExtGState", "SMask" => smask });
        let ops = vec![
            op("gs", vec![Object::Name(b"G".to_vec())]),
            op("rg", vec![n(0.9), n(0.0), n(0.0)]),
            op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
            op("f", vec![]),
        ];
        let res = dictionary! { "ExtGState" => dictionary! { "G" => Object::Reference(gs) } };
        let content = Content { operations: ops }.encode().expect("encode");
        let _ = assemble(&mut doc, content, res, dictionary! {});
        let mut b = Vec::new();
        doc.save_to(&mut b).expect("save");
        b
    };

    // Default backdrop: black, luminosity 0, so only the group's own white
    // square lets the red through.
    let r = compare_page("smask_bc_default", &build(None));
    let outside = interior_mean(&r.ours, r.w, r.h, r.page_h, [110.0, 20.0, 180.0, 90.0]);
    println!("  default /BC, outside the mask square: ours={outside:?}");

    // Mid-grey backdrop: luminosity ~0.5 everywhere the group did not paint.
    let r = compare_page("smask_bc_grey", &build(Some([0.5, 0.5, 0.5])));
    for (label, rect) in [
        ("inside_group_square", [30.0, 130.0, 70.0, 170.0]),
        ("backdrop_only_region", [110.0, 20.0, 180.0, 90.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  grey /BC {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!(
                (a[c] - b[c]).abs() <= 6.0,
                "{label} channel {c}: ours {} vs hayro {}",
                a[c],
                b[c]
            );
        }
    }
}

/// Bezier curve flattening (`c`, `v`, `y`; §8.5.2.2), filled and stroked, at a
/// scale where chord error is visible.
///
/// A GAP IN THIS CORPUS UNTIL NOW: not one of the other entries contains a
/// curve. Every shape was a rectangle, a straight-edged polygon or a
/// rasterised shading, so `interpret.rs`'s adaptive flattener
/// (`bezier_steps_for_flatness`, §10.6.2) had no differential coverage at all.
///
/// Relevant to the live S6 discussion about `outlines.rs`'s FIXED
/// `CURVE_STEPS = 10` for glyph outlines: this harness cannot reach that path,
/// because glyph contours are only emitted for an embedded font program and
/// there is no font file here — a standard-14 font takes the substitute path,
/// which [`rasterize`] refuses to grade. What this test CAN establish is
/// whether the OTHER flattener, the adaptive one used for content-stream
/// curves, holds up at large scale. If it does, that is a point of contrast:
/// the crate already contains a scale-aware flattener, and the glyph path is
/// the one place that does not use it.
///
/// The shapes are deliberately large — a near-circle of radius ~70pt at 2x, so
/// roughly 140 device px across — because chord error scales with radius and a
/// small curve would pass whatever the step count.
#[test]
fn refdiff_bezier_flattening_at_scale() {
    // Four cubics approximating a circle, the classic 0.5523 magic number.
    let (cx, cy, r) = (100.0f64, 100.0f64, 70.0);
    let k = 0.552_284_749_8 * r;
    let circle = vec![
        op("rg", vec![n(0.15), n(0.35), n(0.8)]),
        op("m", vec![n(cx + r), n(cy)]),
        op("c", vec![n(cx + r), n(cy + k), n(cx + k), n(cy + r), n(cx), n(cy + r)]),
        op("c", vec![n(cx - k), n(cy + r), n(cx - r), n(cy + k), n(cx - r), n(cy)]),
        op("c", vec![n(cx - r), n(cy - k), n(cx - k), n(cy - r), n(cx), n(cy - r)]),
        op("c", vec![n(cx + k), n(cy - r), n(cx + r), n(cy - k), n(cx + r), n(cy)]),
        op("h", vec![]),
        op("f", vec![]),
    ];
    // `v` (first control point = current point) and `y` (second control point =
    // endpoint) are separate operators with their own operand shuffling, and
    // both are easy to implement as `c` with the wrong pair.
    let curves_vy = vec![
        op("G", vec![n(0.0)]),
        op("w", vec![n(3.0)]),
        op("m", vec![n(15.0), n(15.0)]),
        op("v", vec![n(15.0), n(170.0), n(170.0), n(170.0)]),
        op("S", vec![]),
        op("m", vec![n(30.0), n(15.0)]),
        op("y", vec![n(185.0), n(15.0), n(185.0), n(160.0)]),
        op("S", vec![]),
    ];

    // Measured SEPARATELY so the flagged counts attribute cleanly. A filled
    // curve arrives here already flattened by `interpret.rs`, so its rim tests
    // the renderer's flattener. A stroked curve additionally goes through this
    // file's approximate stroke expansion (module header item 2), so its rim
    // cannot distinguish the two and is reported rather than graded tightly.
    let rf = compare_page("bezier_fill", &pdf_bytes(circle.clone(), dictionary! {}));
    let a = interior_mean(&rf.ours, rf.w, rf.h, rf.page_h, [80.0, 80.0, 120.0, 120.0]);
    let b = interior_mean(&rf.theirs, rf.w, rf.h, rf.page_h, [80.0, 80.0, 120.0, 120.0]);
    println!("  circle interior: ours={a:?} theirs={b:?}");
    for c in 0..3 {
        assert!((a[c] - b[c]).abs() <= 3.0, "circle interior channel {c}: {} vs {}", a[c], b[c]);
    }

    let rs = compare_page("bezier_stroke_vy", &pdf_bytes(curves_vy, dictionary! {}));
    println!(
        "  attribution: filled curve {:.4}% flagged (renderer's flattener), \
         stroked v/y {:.4}% flagged (that PLUS this file's stroke expansion)",
        rf.report.fraction * 100.0,
        rs.report.fraction * 100.0
    );
}


/// The harness's own safety net, exercised.
///
/// [`render_both`] asserts `skipped == Skipped::default()` before it grades
/// anything, so that a page containing constructs this rasteriser cannot draw
/// is REFUSED rather than silently compared as a blank against a reference that
/// drew it properly. Until this test existed that assertion had never once
/// fired — no corpus entry reaches it — which is precisely the vacuous-check
/// pattern this round kept finding elsewhere (`shows_any_glyph` with zero
/// non-test callers being the other example). An untriggered safety net is
/// indistinguishable from a broken one.
///
/// A `DCTDecode` image is the cheapest way in: `interpret.rs` passes JPEG
/// through as `Prim::Image { format: 1 }` for the platform decoder rather than
/// decoding it here, so there are no RGBA samples for [`Rast::draw_image`] to
/// read. Measured: `format=1`, `data_len=318`, `jpeg_images=1`.
///
/// KNOWN GAP THIS PINS THE EDGE OF: the JPEG path therefore has NO differential
/// coverage at all. Closing it would mean decoding `format: 1` payloads here
/// with the crate's own `decode_jpeg_rgba`, which is a stand-in for Android's
/// decoder rather than the thing that actually runs — a muddy comparison, so it
/// is deliberately left open and documented instead of half-closed. Related:
/// `hunt-missing` found that a JPEG over 16 Mpx is emitted by Rust and dropped
/// by the Kotlin consumer, which is a cross-boundary loss this harness cannot
/// see either, because it measures the prim stream and the loss happens after.
#[test]
fn refdiff_harness_refuses_a_page_it_cannot_draw() {
    // Minimal 8x8 greyscale baseline JPEG.
    let jpeg: Vec<u8> = {
        let mut v = vec![0xFFu8, 0xD8, 0xFF, 0xDB, 0x00, 0x43, 0x00];
        v.extend_from_slice(&[
            0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A,
            0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A,
            0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23,
            0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39,
            0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
        ]);
        v.extend_from_slice(&[
            0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x08, 0x00, 0x08, 0x01, 0x01, 0x11, 0x00,
        ]);
        v.extend_from_slice(&[0xFF, 0xC4, 0x00, 0x1F, 0x00]);
        v.extend_from_slice(&[
            0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        ]);
        v.extend_from_slice(&[0xFF, 0xC4, 0x00, 0xB5, 0x10]);
        v.extend_from_slice(&[
            0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00,
            0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
            0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42,
            0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16,
            0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35, 0x36, 0x37,
            0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55,
            0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73,
            0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
            0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA,
            0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6,
            0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA,
            0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA,
        ]);
        v.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00]);
        v.extend_from_slice(&[0xF9, 0xFE, 0x8A, 0x28, 0xA0, 0x0F, 0xFF, 0xD9]);
        v
    };
    let mut doc = Document::with_version("1.7");
    let img = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8,
            "ColorSpace" => Object::Name(b"DeviceGray".to_vec()),
            "BitsPerComponent" => 8,
            "Filter" => Object::Name(b"DCTDecode".to_vec()),
        },
        jpeg,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(150.0), n(0.0), n(0.0), n(150.0), n(25.0), n(25.0)]),
        op("Do", vec![Object::Name(b"Im".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "XObject" => dictionary! { "Im" => Object::Reference(img) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let d = load_document_lenient(&bytes).expect("load");
    let pid = nth_page_id(&d, 0).expect("page");
    let page = interpret_page(&d, pid).expect("interpret");
    let format = page.prims.iter().find_map(|p| match p {
        Prim::Image { format, .. } => Some(*format),
        _ => None,
    });
    assert_eq!(format, Some(1), "precondition: DCTDecode must reach us as a format-1 passthrough");

    let (_canvas, skipped) = rasterize(&page, SCALE);
    assert_eq!(
        skipped,
        Skipped { substitute_text: 0, jpeg_images: 1, blended: 0 },
        "the rasteriser must COUNT what it could not draw"
    );

    // And the counter must actually stop the comparison, not merely exist.
    let refused = std::panic::catch_unwind(|| {
        let _ = render_both("jpeg_must_be_refused", &bytes);
    });
    assert!(
        refused.is_err(),
        "render_both graded a page containing an undrawable JPEG instead of refusing it — \
         the harness's only protection against silently comparing a blank page is gone"
    );
    println!("  harness correctly refused to grade a format-1 JPEG page");
}



/// The SAME curve, once as a clip boundary and once as a fill, at the viewer's
/// real maximum zoom — independent confirmation of the asymmetry the team
/// converged on late in round 6.
///
/// `interpret.rs`'s `c`/`v`/`y` arm emits every curve TWICE: `:1035` pushes an
/// exact `PathOp::Cubic` into `clip_path_ops`, and `:1022` flattens the same
/// curve through `bezier_steps_for_flatness` into the point list that becomes
/// `Prim::Fill { contours }`. The clip therefore reaches Skia as a real cubic
/// and is re-flattened at device resolution every frame; the fill is frozen at
/// page-point resolution before zoom is known.
///
/// That asymmetry was established by reading the chain across three files and
/// two languages (`interpret.rs` → `wire.rs` → `pathOpsToPath`). This measures
/// it instead. 10.6 px/pt is the effective ceiling for a 612pt page on a 1080px
/// viewport: `maxZoomFor` clamps `4·W/V` up to `MAX_ZOOM = 6`, and 6 × the
/// 1.765 fit scale is 10.6.
///
/// A clip whose boundary is a circle should be as crisp as the reference at any
/// zoom. The same circle painted as a fill carries ~0.42 device px of chord
/// error at this scale — small, but it is the thing that does not scale away.
#[test]
fn refdiff_curved_clip_is_zoom_correct() {
    let (cx, cy, r) = (100.0f64, 100.0f64, 70.0);
    let k = 0.552_284_749_8 * r;
    let circle_path = |close_op: &str| {
        vec![
            op("m", vec![n(cx + r), n(cy)]),
            op("c", vec![n(cx + r), n(cy + k), n(cx + k), n(cy + r), n(cx), n(cy + r)]),
            op("c", vec![n(cx - k), n(cy + r), n(cx - r), n(cy + k), n(cx - r), n(cy)]),
            op("c", vec![n(cx - r), n(cy - k), n(cx - k), n(cy - r), n(cx), n(cy - r)]),
            op("c", vec![n(cx + k), n(cy - r), n(cx + r), n(cy - k), n(cx + r), n(cy)]),
            op("h", vec![]),
            op(close_op, vec![]),
        ]
    };
    // As a CLIP: the circle bounds a full-page fill, so the visible disc edge is
    // the clip boundary and nothing else.
    let mut clipped = vec![op("q", vec![])];
    clipped.extend(circle_path("W"));
    clipped.push(op("n", vec![]));
    clipped.extend(vec![
        op("rg", vec![n(0.15), n(0.35), n(0.8)]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
        op("f", vec![]),
        op("Q", vec![]),
    ]);
    // As a FILL: same disc, edge comes from the pre-flattened contour.
    let mut filled = vec![op("rg", vec![n(0.15), n(0.35), n(0.8)])];
    filled.extend(circle_path("f"));

    // 10.6 px/pt — a 612pt page at max zoom on a 1080px phone.
    const ZOOM: f32 = 10.6;
    let measure = |label: &str, ops: Vec<Operation>| -> f64 {
        let bytes = pdf_bytes(ops, dictionary! {});
        let doc = load_document_lenient(&bytes).expect("load");
        let pid = nth_page_id(&doc, 0).expect("page");
        let page = interpret_page(&doc, pid).expect("interpret");
        let (canvas, skipped) = rasterize(&page, ZOOM);
        assert_eq!(skipped, Skipped::default(), "[{label}] undrawable construct");
        let ours = canvas.to_rgb8();
        let (w, h, theirs) = hayro_rgb8(&bytes, ZOOM);
        assert_eq!((canvas.w, canvas.h), (w, h), "[{label}] dimension mismatch");
        let rep = fuzzy_diff(&ours, &theirs, w, h);
        let perim = 2.0 * std::f64::consts::PI * r * ZOOM as f64;
        let per_rim = rep.flagged as f64 / perim;
        println!(
            "  {label:22} {w}x{h}: flagged {:5} ({:.4}%)  per rim-px {per_rim:.3}",
            rep.flagged,
            rep.fraction * 100.0
        );
        per_rim
    };

    let clip_edge = measure("curve as CLIP", clipped.clone());
    let fill_edge = measure("curve as FILL", filled);

    // The pixel gap is real but small (~12% of rim pixels), because both sides
    // sit on an antialiasing floor this harness cannot remove. So the pixel
    // comparison alone is too noisy to be the regression guard. Check the
    // STRUCTURAL property directly as well: the clip must actually carry
    // beziers. If `path_ops` ever goes `None`, `clip_contours` silently falls
    // back to the pre-flattened `pts` and the clip starts faceting like the
    // fill — which the loose pixel tolerance above would not catch.
    let bytes = pdf_bytes(clipped, dictionary! {});
    let doc = load_document_lenient(&bytes).expect("load");
    let pid = nth_page_id(&doc, 0).expect("page");
    let page = interpret_page(&doc, pid).expect("interpret");
    let cubics = page
        .prims
        .iter()
        .filter_map(|p| match p {
            Prim::ClipPush { path_ops, .. } => path_ops.as_ref(),
            _ => None,
        })
        .flatten()
        .filter(|o| matches!(o, PathOp::Cubic(..)))
        .count();
    assert_eq!(
        cubics, 4,
        "the clip must reach the consumer as four real cubics (interpret.rs:1035), not as a \
         pre-flattened polyline — Skia re-flattens these at device resolution, which is the \
         only reason a clip boundary stays crisp at zoom"
    );

    assert!(
        clip_edge <= fill_edge + 0.05,
        "a clip boundary should be no worse than the same curve pre-flattened as a fill, \
         but clip {clip_edge:.3} > fill {fill_edge:.3} per rim-pixel"
    );
    println!(
        "  asymmetry at {ZOOM} px/pt: clip {clip_edge:.3} vs fill {fill_edge:.3} per rim-pixel \
         ({} exact cubics carried)",
        cubics
    );
}

