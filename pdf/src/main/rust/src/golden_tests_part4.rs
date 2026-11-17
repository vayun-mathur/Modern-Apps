/// The same per-glyph cap, but with a TRANSPARENCY GROUP open at the cut, not just
/// a clip. Closing the open brackets means synthesising a `GroupPop` and a
/// `ClipPop` in the right order; my sibling test above only exercises the clip
/// branch, so a fix that handled `ClipPush` and forgot `GroupPush` would pass it
/// and leave a `saveLayer` unbalanced for the rest of the page — which is worse
/// than an unbalanced clip, because it leaks a whole compositing layer.
#[test]
fn type3_per_glyph_cap_closes_group_brackets_too() {
    let mut doc = Document::with_version("1.7");
    let wanted = MAX_TYPE3_PRIMS_PER_GLYPH * 3;
    let mut form_src = String::from("1 0 0 rg\n");
    for i in 0..wanted {
        let y = (i % 600) as i64;
        form_src.push_str(&format!("0 {y} 10 10 re f\n"));
    }
    // A transparency group, so `Do` emits GroupPush inside the form's BBox clip.
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 700.into(), 700.into()],
            "Group" => dictionary! { "Type" => "Group", "S" => "Transparency", "I" => true },
        },
        form_src.into_bytes(),
    ));
    let proc_id = doc.add_object(Stream::new(
        dictionary! {},
        b"q /Fm0 Do Q\n".to_vec(),
    ));
    let char_procs = doc.add_object(dictionary! { "a" => proc_id });
    let encoding = doc.add_object(dictionary! {
        "Type" => "Encoding",
        "Differences" => vec![65.into(), "a".into()],
    });
    let font = dictionary! {
        "Type" => "Font", "Subtype" => "Type3",
        "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "CharProcs" => char_procs, "Encoding" => encoding,
        "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
        "Resources" => dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
    };
    let mut fonts = HashMap::new();
    fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
    let gs = GraphicsState {
        font_key: b"F1".to_vec(),
        font_size: 100.0,
        ..Default::default()
    };
    let mut prims = Vec::new();
    show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"AA", 0);

    let groups = count(&prims, |p| matches!(p, Prim::GroupPush { .. }));
    println!(
        "type3 group cap: {} prims, {} fills, {groups} GroupPush for 2 glyphs",
        prims.len(),
        count(&prims, |p| matches!(p, Prim::Fill { .. }))
    );
    assert!(
        groups > 0,
        "precondition: the CharProc's form must open a transparency group, else this \
         test exercises the same branch as its sibling"
    );
    assert!(
        prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "the capped glyph must still draw something"
    );
    let mut clip = 0i32;
    let mut group = 0i32;
    for (i, p) in prims.iter().enumerate() {
        match p {
            Prim::ClipPush { .. } => clip += 1,
            Prim::ClipPop => {
                clip -= 1;
                assert!(clip >= 0, "prim {i}: unmatched ClipPop");
            }
            Prim::GroupPush { .. } => group += 1,
            Prim::GroupPop => {
                group -= 1;
                assert!(group >= 0, "prim {i}: unmatched GroupPop would over-restore a saveLayer");
            }
            _ => {}
        }
    }
    assert_eq!(
        group, 0,
        "{group} transparency group(s) left open by the per-glyph cut — a leaked \
         saveLayer composites the whole rest of the page through this glyph's group"
    );
    assert_eq!(clip, 0, "{clip} clip level(s) left open by the per-glyph cut");
}

/// The per-glyph cap with a SOFT MASK in play. `interp2` reports the
/// `SoftMaskPop` arm of the closer synthesis is deliberately not implemented,
/// because a soft-mask bracket cannot be closed after the fact — so the question
/// this test settles is not "does the arm work" but "can a cut orphan a soft-mask
/// bracket at all". Consecutive fills under one `gs` coalesce into a single
/// bracket, so a cut landing inside one is at least plausible.
///
/// Either outcome is informative: passing documents that soft-mask brackets are
/// emitted retroactively around a completed range and therefore cannot be open at
/// the cut (making the missing arm unreachable rather than a hole); failing means a
/// capped glyph leaks a soft mask over the rest of the page.
#[test]
fn type3_per_glyph_cap_cannot_orphan_a_soft_mask_bracket() {
    // Build a Type 3 font whose CharProc paints `fills` rectangles under an
    // ExtGState soft mask, and return the prims one glyph emits.
    let run = |fills: usize| -> Vec<Prim> {
        let mut doc = Document::with_version("1.7");
        let mask_content = Content {
            operations: vec![
                Operation::new("rg", vec![1.0.into(), 1.0.into(), 1.0.into()]),
                Operation::new("re", vec![0.into(), 0.into(), 700.into(), 700.into()]),
                Operation::new("f", vec![]),
            ],
        };
        let mask_id = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Form",
                "BBox" => vec![0.into(), 0.into(), 700.into(), 700.into()],
                "Group" => dictionary! { "S" => "Transparency" },
            },
            mask_content.encode().unwrap(),
        ));
        let gs_id = doc.add_object(dictionary! {
            "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(mask_id) },
        });
        let mut proc_src = String::from("q\n/GS1 gs\n1 0 0 rg\n");
        for i in 0..fills {
            let y = (i % 600) as i64;
            proc_src.push_str(&format!("0 {y} 10 10 re f\n"));
        }
        proc_src.push_str("Q\n");
        let proc_id = doc.add_object(Stream::new(dictionary! {}, proc_src.into_bytes()));
        let char_procs = doc.add_object(dictionary! { "a" => proc_id });
        let encoding = doc.add_object(dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![65.into(), "a".into()],
        });
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FontMatrix" => vec![0.001.into(), 0.into(), 0.into(), 0.001.into(), 0.into(), 0.into()],
            "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
            "CharProcs" => char_procs, "Encoding" => encoding,
            "FirstChar" => 65, "LastChar" => 65, "Widths" => vec![700.into()],
            "Resources" => dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
        };
        let mut fonts = HashMap::new();
        fonts.insert(b"F1".to_vec(), font_info(&doc, &font));
        let gs = GraphicsState {
            font_key: b"F1".to_vec(),
            font_size: 100.0,
            ..Default::default()
        };
        let mut prims = Vec::new();
        show_string(&doc, &mut prims, &gs, &fonts, &IDENTITY, b"A", 0);
        prims
    };

    // Precondition: WELL under the cap, the fixture must genuinely emit a soft-mask
    // bracket. Without this, a zero-prim result below could just mean the mask was
    // never set up and the test would be asserting nothing.
    let small = run(4);
    assert!(
        count(&small, |p| matches!(p, Prim::SoftMaskPush { .. })) > 0,
        "precondition: an uncapped glyph must emit a SoftMaskPush, else this test \
         cannot say anything about orphaning one"
    );
    assert!(
        small.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "precondition: an uncapped glyph must draw"
    );

    let prims = run(MAX_TYPE3_PRIMS_PER_GLYPH * 3);
    let pushes = count(&prims, |p| matches!(p, Prim::SoftMaskPush { .. }));
    let pops = count(&prims, |p| matches!(p, Prim::SoftMaskPop));
    println!(
        "type3 soft-mask cap: {} prims, {} fills, {pushes} SoftMaskPush / {pops} SoftMaskPop",
        prims.len(),
        count(&prims, |p| matches!(p, Prim::Fill { .. }))
    );
    assert_eq!(
        pushes, pops,
        "a capped glyph left {pushes} SoftMaskPush against {pops} SoftMaskPop — an \
         orphaned soft mask composites the rest of the page through this glyph's mask"
    );
    assert!(
        prims.iter().any(|p| matches!(p, Prim::Fill { .. })),
        "the capped glyph vanished entirely. Avoiding an orphaned soft mask by cutting \
         BEFORE the bracket degenerates when the mask opens at the start of the glyph, \
         which is the normal shape for `q /GS1 gs <draw> Q` — there is nothing before \
         it to keep. A glyph missing from the page is a worse regression than a glyph \
         drawn without its mask."
    );
}

// ===========================================================================
// 10. Shading and patterns — §8.7

fn tiling_pattern(doc: &mut Document, xstep: f64, ystep: f64) -> ObjectId {
    let tile = Content {
        operations: vec![
            Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 8.into(), 8.into()]),
            Operation::new("f", vec![]),
        ],
    };
    doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern", "PatternType" => 1, "PaintType" => 1, "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "XStep" => xstep, "YStep" => ystep,
            "Resources" => dictionary! {},
        },
        tile.encode().unwrap(),
    ))
}

fn fill_region(x0: f64, y0: f64, x1: f64, y1: f64) -> Vec<Vec<(f64, f64)>> {
    vec![vec![(x0, y0), (x1, y0), (x1, y1), (x0, y1)]]
}

/// §8.7.3.1: a tiling pattern replicates across the WHOLE fill region on the
/// `/XStep` x `/YStep` lattice. A small step over a large area must tile it, not
/// paint a single cell in one corner.
///
/// Asserted on coverage rather than on prim counts, because the representation is
/// an implementation choice: a non-overlapping periodic pattern may collapse to a
/// single `ImageTiled` cell raster instead of one Fill per tile. What must hold
/// either way is that ink reaches every corner of the region.
#[test]
fn tiling_pattern_with_a_small_step_covers_the_whole_region() {
    let mut doc = Document::with_version("1.5");
    let pid = tiling_pattern(&mut doc, 10.0, 10.0);
    let region = fill_region(0.0, 0.0, 200.0, 200.0);
    let mut prims = Vec::new();
    paint_pattern_fill(PatternFill { doc: &doc, pattern_id: pid, polys: &region, even_odd: false, pattern_base_ctm: &IDENTITY, base_argb: 0xFF00_0000, alpha_fill: 1.0, blend: BlendMode::Normal, cs_resources: &HashMap::new(), prims: &mut prims, depth: 0, clip_depth: 0 });
    assert!(
        prims.iter().any(is_ink),
        "the pattern must paint something at all"
    );
    assert!(
        ink_in_region(&prims, 0.0, 0.0, 20.0, 20.0),
        "the origin corner must be tiled"
    );
    assert!(
        ink_in_region(&prims, 180.0, 180.0, 201.0, 201.0),
        "the far corner must be tiled too — this is the 'pattern painted one corner' bug"
    );
}

/// §8.7.3.1: `/XStep` and `/YStep` are lattice *spacings*; a negative value is a
/// magnitude, not a direction. The round-1 bug let a negative step invert the
/// loop bounds so the pattern painted nothing at all.
#[test]
fn tiling_pattern_with_a_negative_step_still_paints() {
    let mut doc = Document::with_version("1.5");
    let pid = tiling_pattern(&mut doc, -10.0, -10.0);
    let region = fill_region(0.0, 0.0, 100.0, 100.0);
    let mut prims = Vec::new();
    paint_pattern_fill(PatternFill { doc: &doc, pattern_id: pid, polys: &region, even_odd: false, pattern_base_ctm: &IDENTITY, base_argb: 0xFF00_0000, alpha_fill: 1.0, blend: BlendMode::Normal, cs_resources: &HashMap::new(), prims: &mut prims, depth: 0, clip_depth: 0 });
    assert!(
        prims.iter().any(is_ink),
        "a negative /XStep must tile by its magnitude, not paint nothing"
    );
    assert!(
        ink_in_region(&prims, 0.0, 0.0, 20.0, 20.0)
            && ink_in_region(&prims, 80.0, 80.0, 101.0, 101.0),
        "and it must cover the region, not just one corner"
    );
}

/// §8.9.5.2: a raster is placed by mapping the unit square through `ctm`, and
/// sample row 0 is the TOP of the image — it pairs with `v = 1`, not `v = 0`.
/// That convention is forced by real image XObjects, whose decoded JPEG/PNG
/// scanlines arrive top-first, so every producer of a `Prim::Image` must match it.
///
/// A SYNTHETIC raster (axial/radial shading, mesh shading, pattern cell) is
/// generated rather than decoded, so nothing forces its row order — if it writes
/// row 0 at the LOW y of its bbox while still handing back a positive-`d`
/// placement matrix, it renders vertically flipped while real images stay correct.
///
/// This is asserted on the shading's own geometry rather than against another
/// raster, which is what makes it decisive: an axial shading running black at
/// `y = 0` to white at `y = 100` has a known correct answer independent of any
/// convention I might have misread. It also catches the cancelling double-fix —
/// producer flipped AND consumer flipped looks right end-to-end but fails here.
#[test]
fn synthetic_shading_raster_pairs_row_zero_with_the_same_edge_as_a_real_image() {
    let mut doc = Document::with_version("1.5");
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![0.0.into(), 0.0.into(), 0.0.into()], // black at t=0
        "C1" => vec![1.0.into(), 1.0.into(), 1.0.into()], // white at t=1
        "N" => 1,
    });
    // Vertical axis: t=0 at y=0 (black), t=1 at y=100 (white).
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![0.into(), 0.into(), 0.into(), 100.into()],
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
        "Function" => func_id,
    });
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()] },
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let (ctm, w, h, data) = page
        .prims
        .iter()
        .find_map(|p| match p {
            Prim::Image { ctm, w, h, data, format: 0, .. } => Some((*ctm, *w, *h, data.clone())),
            _ => None,
        })
        .expect("the shading must emit a decoded raster");
    assert!(w > 0 && h > 1, "need at least two rows to talk about orientation");

    // Mean luminance of a raster row.
    let row_luma = |row: u32| -> f64 {
        let mut sum = 0f64;
        let mut n = 0f64;
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 2 < data.len() {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { 0.0 } else { sum / n }
    };
    let (top_row, bottom_row) = (row_luma(0), row_luma(h - 1));
    assert!(
        (top_row - bottom_row).abs() > 16.0,
        "the ramp must actually vary between the first and last row, else this test \
         cannot detect an inversion (row0={top_row:.0}, row{}={bottom_row:.0})",
        h - 1
    );

    // Where does each edge of the unit square land in page space?
    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    // Row 0 pairs with v = 1 (§8.9.5.2), row h-1 with v = 0.
    let (luma_at_v1, luma_at_v0) = (top_row, bottom_row);
    let (low_y_luma, high_y_luma) = if y_at_v0 < y_at_v1 {
        (luma_at_v0, luma_at_v1)
    } else {
        (luma_at_v1, luma_at_v0)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the shading is vertically FLIPPED. Its function is black at t=0 (page y=0) \
         and white at t=1 (page y=100), so once placed, the low-y edge must be dark \
         and the high-y edge light. Got low-y luma {low_y_luma:.0} and high-y luma \
         {high_y_luma:.0} (raster row0={top_row:.0}, row{}={bottom_row:.0}; v=0 lands \
         at y={y_at_v0:.0}, v=1 at y={y_at_v1:.0}). Row 0 must pair with v=1, the same \
         way a decoded image's first scanline does — a synthetic raster that writes \
         row 0 at its bbox's low y while returning a positive-d matrix inverts. NOTE: \
         if this fails after BOTH the raster producer and the Kotlin decoder were \
         changed, the two flips cancelled and each looked correct in isolation.",
        h - 1
    );
}
