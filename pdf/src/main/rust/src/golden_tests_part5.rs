/// The same orientation invariant for the TYPE 4 MESH producer
/// (`shading::rasterize_shading_mesh`), which is a different code path from the
/// axial one and was flipped independently. Mesh vertex colours are black along
/// `y = 0` and white along `y = 100`, so the row placed at the lower page y must
/// be the darker one — the same document-level fact, no convention assumed.
#[test]
fn type4_mesh_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    // flag(1) x(1) y(1) r(1) g(1) b(1) per vertex; two triangles covering a square.
    // Decode maps x,y from 0..255 onto 0..100, so y=0 is black and y=100 is white.
    let v = |x: u8, y: u8, l: u8| -> [u8; 6] { [0, x, y, l, l, l] };
    let mut mesh: Vec<u8> = Vec::new();
    for b in [v(0, 0, 0), v(255, 0, 0), v(0, 255, 255)] {
        mesh.extend_from_slice(&b);
    }
    for b in [v(255, 0, 0), v(0, 255, 255), v(255, 255, 255)] {
        mesh.extend_from_slice(&b);
    }
    let sh_id = doc.add_object(Stream::new(
        dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            "Decode" => vec![
                0.into(), 100.into(), 0.into(), 100.into(),
                0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into(),
            ],
        },
        mesh,
    ));
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
        .expect("a type 4 mesh must emit a decoded raster");
    assert!(w > 0 && h > 1, "need at least two rows to talk about orientation");

    // Mean luminance over OPAQUE pixels only: a mesh does not cover its whole bbox,
    // and averaging in the transparent margin would wash the gradient out.
    let row_luma = |row: u32| -> Option<f64> {
        let (mut sum, mut n) = (0f64, 0f64);
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 3 < data.len() && data[i + 3] > 0 {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some(sum / n) }
    };
    // First and last rows that actually contain mesh.
    let first = (0..h).find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let last = (0..h).rev().find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    assert!(last > first, "the mesh must cover more than one row");
    let (luma_first, luma_last) = (row_luma(first).unwrap(), row_luma(last).unwrap());
    assert!
    (
        (luma_first - luma_last).abs() > 16.0,
        "the mesh gradient must vary between its first and last covered row, else \
         this test cannot detect an inversion (row{first}={luma_first:.0}, \
         row{last}={luma_last:.0})"
    );

    // Lower row index is nearer v=1 (§8.9.5.2), so it lands wherever v=1 lands.
    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    let (low_y_luma, high_y_luma) = if y_at_v1 > y_at_v0 {
        (luma_last, luma_first)
    } else {
        (luma_first, luma_last)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the type 4 mesh raster is vertically FLIPPED. Vertex colours are black along \
         page y=0 and white along y=100, so the row placed at the lower page y must be \
         darker. Got low-y luma {low_y_luma:.0}, high-y luma {high_y_luma:.0} \
         (row{first}={luma_first:.0}, row{last}={luma_last:.0}; v=0 at y={y_at_v0:.0}, \
         v=1 at y={y_at_v1:.0}). This producer is separate from the axial one and was \
         flipped independently, so fixing one does not fix the other."
    );
}

/// The same orientation invariant for the TYPE 1 FUNCTION-BASED producer
/// (`images::rasterize_shading_function_based`), the fourth and last of the
/// synthetic rasters that were mirrored. Its `/Function` returns the gray level
/// directly from the y coordinate, so black sits at page y=0 and white at y=100
/// by construction — the same document-level fact as the other three.
#[test]
fn type1_function_based_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    // PostScript calculator over (x, y): drop x, scale y into 0..1 as the gray level.
    let func_id = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ exch pop 100 div }".to_vec(),
    ));
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 1,
        "ColorSpace" => "DeviceGray",
        "Domain" => vec![0.into(), 100.into(), 0.into(), 100.into()],
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
        .expect("a type 1 function-based shading must emit a decoded raster");
    assert!(w > 0 && h > 1, "need at least two rows to talk about orientation");

    let row_luma = |row: u32| -> Option<f64> {
        let (mut sum, mut n) = (0f64, 0f64);
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 3 < data.len() && data[i + 3] > 0 {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some(sum / n) }
    };
    let first = (0..h).find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let last = (0..h).rev().find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    assert!(last > first, "the shading must cover more than one row");
    let (luma_first, luma_last) = (row_luma(first).unwrap(), row_luma(last).unwrap());
    assert!(
        (luma_first - luma_last).abs() > 16.0,
        "the ramp must vary between the first and last covered row, else this test \
         cannot detect an inversion (row{first}={luma_first:.0}, row{last}={luma_last:.0})"
    );

    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    let (low_y_luma, high_y_luma) = if y_at_v1 > y_at_v0 {
        (luma_last, luma_first)
    } else {
        (luma_first, luma_last)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the type 1 function-based raster is vertically FLIPPED. Its /Function returns \
         gray = y/100, so the row placed at the lower page y must be darker. Got low-y \
         luma {low_y_luma:.0}, high-y luma {high_y_luma:.0} (row{first}={luma_first:.0}, \
         row{last}={luma_last:.0}; v=0 at y={y_at_v0:.0}, v=1 at y={y_at_v1:.0})."
    );
}

/// The same orientation invariant for a RADIAL (type 3) shading. Radial shares the
/// axial pixel loop, so this is a cheap guard against a future divergence rather
/// than a separate producer — the concentric case has no vertical asymmetry, so the
/// circles are offset along y to give the raster a known top and bottom.
#[test]
fn radial_shading_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    let func_id = doc.add_object(dictionary! {
        "FunctionType" => 2,
        "Domain" => vec![0.into(), 1.into()],
        "C0" => vec![0.0.into(), 0.0.into(), 0.0.into()], // black at the small circle
        "C1" => vec![1.0.into(), 1.0.into(), 1.0.into()], // white at the large one
        "N" => 1,
    });
    // Small circle low on the page, large circle high: dark at the bottom.
    let sh_id = doc.add_object(dictionary! {
        "ShadingType" => 3,
        "ColorSpace" => "DeviceRGB",
        "Coords" => vec![50.into(), 5.into(), 1.into(), 50.into(), 95.into(), 90.into()],
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
        .expect("a radial shading must emit a decoded raster");
    let row_luma = |row: u32| -> Option<f64> {
        let (mut sum, mut n) = (0f64, 0f64);
        for x in 0..w {
            let i = ((row * w + x) * 4) as usize;
            if i + 3 < data.len() && data[i + 3] > 0 {
                sum += (data[i] as f64 + data[i + 1] as f64 + data[i + 2] as f64) / 3.0;
                n += 1.0;
            }
        }
        if n == 0.0 { None } else { Some(sum / n) }
    };
    let first = (0..h).find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let last = (0..h).rev().find(|&r| row_luma(r).is_some()).expect("some row must be covered");
    let (luma_first, luma_last) = (row_luma(first).unwrap(), row_luma(last).unwrap());
    assert!(
        (luma_first - luma_last).abs() > 16.0,
        "the radial ramp must vary vertically for this test to mean anything \
         (row{first}={luma_first:.0}, row{last}={luma_last:.0})"
    );
    let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
    let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
    let (low_y_luma, high_y_luma) = if y_at_v1 > y_at_v0 {
        (luma_last, luma_first)
    } else {
        (luma_first, luma_last)
    };
    assert!(
        low_y_luma < high_y_luma,
        "the radial raster is vertically FLIPPED. Its small (black) circle sits low on \
         the page and its large (white) circle high, so the row at the lower page y \
         must be darker. Got low-y {low_y_luma:.0}, high-y {high_y_luma:.0}."
    );
}

/// only in its LOWER half must come back as a raster whose opaque rows are the
/// ones that land at the lower page y — not the upper.
#[test]
fn tiling_pattern_cell_raster_is_not_vertically_flipped() {
    let mut doc = Document::with_version("1.5");
    // Cell is 10x10; paint only y in [0,5), i.e. the bottom half.
    let cell = Content {
        operations: vec![
            Operation::new("rg", vec![0.0.into(), 0.0.into(), 0.0.into()]),
            Operation::new("re", vec![0.into(), 0.into(), 10.into(), 5.into()]),
            Operation::new("f", vec![]),
        ],
    };
    let pid = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern", "PatternType" => 1, "PaintType" => 1, "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 10.into(), 10.into()],
            "XStep" => 10, "YStep" => 10,
            "Resources" => dictionary! {},
        },
        cell.encode().unwrap(),
    ));
    let region = fill_region(0.0, 0.0, 100.0, 100.0);
    let mut prims = Vec::new();
    paint_pattern_fill(PatternFill { doc: &doc, pattern_id: pid, polys: &region, even_odd: false, pattern_base_ctm: &IDENTITY, base_argb: 0xFF00_0000, alpha_fill: 1.0, blend: BlendMode::Normal, cs_resources: &HashMap::new(), prims: &mut prims, depth: 0, clip_depth: 0 });

    // The periodic path emits one ImageTiled cell raster; the per-tile path emits
    // Fills. Both are valid representations, so assert whichever appeared.
    let tiled = prims.iter().find_map(|p| match p {
        Prim::ImageTiled { ctm, w, h, data, .. } => Some((*ctm, *w, *h, data.clone())),
        _ => None,
    });
    if let Some((ctm, w, h, data)) = tiled {
        println!("pattern cell: RASTER path, {w}x{h} cell");
        assert!(h > 1, "need at least two rows to talk about orientation");
        let row_opaque = |row: u32| -> f64 {
            let (mut op, mut n) = (0f64, 0f64);
            for x in 0..w {
                let i = ((row * w + x) * 4) as usize;
                if i + 3 < data.len() {
                    if data[i + 3] > 0 { op += 1.0; }
                    n += 1.0;
                }
            }
            if n == 0.0 { 0.0 } else { op / n }
        };
        let (first, last) = (row_opaque(0), row_opaque(h - 1));
        assert!(
            (first - last).abs() > 0.5,
            "the cell must be opaque in one half and clear in the other, else this \
             test cannot detect an inversion (row0={first:.2}, row{}={last:.2})",
            h - 1
        );
        let y_at_v0 = transform(&ctm, 0.5, 0.0).1;
        let y_at_v1 = transform(&ctm, 0.5, 1.0).1;
        let (low_y_opaque, high_y_opaque) = if y_at_v1 > y_at_v0 { (last, first) } else { (first, last) };
        assert!(
            low_y_opaque > high_y_opaque,
            "the pattern cell raster is vertically FLIPPED. The cell paints only its \
             lower half, so the raster row landing at the lower page y must be the \
             opaque one. Got low-y opacity {low_y_opaque:.2}, high-y {high_y_opaque:.2} \
             (row0={first:.2}, row{}={last:.2}; v=0 at y={y_at_v0:.0}, v=1 at \
             y={y_at_v1:.0}). A flipped cell still tiles seamlessly, so this is \
             invisible to a spot check.",
            h - 1
        );
    } else {
        // Per-tile path: the painted rects must sit in the lower half of each cell.
        println!("pattern cell: PER-TILE path (no ImageTiled emitted)");
        let ys: Vec<f32> = prims
            .iter()
            .filter_map(|p| match p {
                Prim::Fill { contours, .. } => contours.first().map(|c| bbox_of(c)[1]),
                _ => None,
            })
            .collect();
        assert!(!ys.is_empty(), "the pattern must paint something");
        assert!(
            ys.iter().any(|&y| (y % 10.0).abs() < 1.0),
            "each tile's painted rect must start at the cell's low y, got {ys:?}"
        );
    }
}

/// `tests::unclipped_sh_covers_page` by checking the emitted raster genuinely
/// varies across the page rather than being one flat colour in a corner.
#[test]
fn unclipped_sh_raster_spans_the_page_and_varies_across_it() {
    let mut doc = Document::with_version("1.5");
    let sh_id = axial_shading(&mut doc);
    let bytes = Content {
        operations: vec![Operation::new("sh", vec![Object::Name(b"Sh0".to_vec())])],
    }
    .encode()
    .unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! { "Shading" => dictionary! { "Sh0" => sh_id } },
        dictionary! { "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()] },
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
        .expect("an unclipped sh must emit a raster");
    let dev_w = ctm[0].abs() + ctm[2].abs();
    let dev_h = ctm[1].abs() + ctm[3].abs();
    assert!(dev_w >= 399.0 && dev_h >= 499.0, "raster must span the page, got {dev_w:.0}x{dev_h:.0}");
    // The axial ramp runs left to right, so the left and right edges must differ.
    let px = |x: u32, y: u32| {
        let i = ((y * w + x) * 4) as usize;
        (data[i], data[i + 1], data[i + 2])
    };
    let (l, r) = (px(0, h / 2), px(w - 1, h / 2));
    assert_ne!(l, r, "the axial ramp must vary across the page, not paint one flat colour");
}

/// §14.6.2 + the MAX_OC_STACK cap: BMC/BDC pushes beyond the nesting cap are
/// dropped, so the matching EMCs must be dropped too. If an EMC past the cap
/// pops a frame it does not own, the hidden region un-hides from there on.
#[test]
fn marked_content_nested_past_the_stack_cap_still_stays_hidden() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let mut ops = vec![Operation::new(
        "BDC",
        vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())],
    )];
    // Nest far past the cap, paint, then unwind by exactly as many EMCs.
    const DEEP: usize = 80;
    for _ in 0..DEEP {
        ops.push(Operation::new("BMC", vec![Object::Name(b"Tx".to_vec())]));
    }
    ops.extend(rect_ops(10, 10, 40, 40)); // deep inside, hidden
    for _ in 0..DEEP {
        ops.push(Operation::new("EMC", vec![]));
    }
    ops.extend(rect_ops(60, 10, 40, 40)); // back at OCG level, still hidden
    ops.push(Operation::new("EMC", vec![]));
    ops.extend(rect_ops(300, 300, 40, 40)); // outside the OCG, visible
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! { "Properties" => dictionary! { "OC0" => ocg_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 150.0, 100.0),
        "nesting past the marked-content cap must not un-hide the region on the way out"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "and content genuinely outside the OCG must still paint"
    );
}
