/// A `/Decode` array that inverts a grayscale image (§8.9.5.2, Table 89).
#[test]
fn refdiff_image_decode_array_inversion() {
    let mut doc = Document::with_version("1.7");
    let gray = vec![0u8, 64, 128, 255];
    let img = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 4, "Height" => 1,
            "ColorSpace" => Object::Name(b"DeviceGray".to_vec()),
            "BitsPerComponent" => 8,
            "Decode" => vec![Object::Real(1.0), Object::Real(0.0)],
        },
        gray,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(160.0), n(0.0), n(0.0), n(120.0), n(20.0), n(40.0)]),
        op("Do", vec![Object::Name(b"Im".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "XObject" => dictionary! { "Im" => Object::Reference(img) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("image_decode", &bytes);
    for (i, want) in [255.0f64, 191.0, 127.0, 0.0].iter().enumerate() {
        let x = 25.0 + i as f64 * 40.0;
        let rect = [x, 50.0, x + 30.0, 150.0];
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  sample {i}: ours={:.1} theirs={:.1} spec≈{want:.0}", a[0], b[0]);
        assert!((a[0] - b[0]).abs() <= 4.0, "sample {i}: ours {:.1} vs hayro {:.1}", a[0], b[0]);
    }
}

/// Form XObject with a non-identity `/Matrix` and a `/BBox` that must clip
/// (§8.10.2, Table 95). Getting the Matrix/BBox composition order wrong is the
/// canonical self-consistent mistake here: the content still appears, just in
/// the wrong place or unclipped.
#[test]
fn refdiff_form_xobject_matrix_and_bbox_clip() {
    let mut doc = Document::with_version("1.7");
    let inner = Content {
        operations: vec![
            op("rg", vec![n(0.8), n(0.2), n(0.6)]),
            op("re", vec![n(-50.0), n(-50.0), n(200.0), n(200.0)]),
            op("f", vec![]),
        ],
    }
    .encode()
    .expect("encode inner");
    let form = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            // BBox must clip the oversized rect to 0..100 in form space.
            "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(100.0), Object::Real(100.0)],
            // Scale by 1.5 and translate: applied BEFORE the CTM.
            "Matrix" => vec![
                Object::Real(1.5), Object::Real(0.0), Object::Real(0.0),
                Object::Real(1.5), Object::Real(10.0), Object::Real(20.0),
            ],
            "Resources" => dictionary! {},
        },
        inner,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(1.0), n(0.0), n(0.0), n(1.0), n(15.0), n(10.0)]),
        op("Do", vec![Object::Name(b"Fm".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "XObject" => dictionary! { "Fm" => Object::Reference(form) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("form_matrix_bbox", &bytes);
}

/// Nested clipping with both winding rules, and the `W n` deferral rule
/// (§8.5.4: the clip takes effect only after the path-painting operator).
#[test]
fn refdiff_nested_and_evenodd_clipping() {
    let ops = vec![
        op("q", vec![]),
        op("re", vec![n(20.0), n(20.0), n(120.0), n(120.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("re", vec![n(60.0), n(60.0), n(120.0), n(120.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("rg", vec![n(0.1), n(0.5), n(0.9)]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
        op("f", vec![]),
        op("Q", vec![]),
        // Even-odd clip: an annulus made of two nested rects.
        op("q", vec![]),
        op("re", vec![n(150.0), n(10.0), n(45.0), n(45.0)]),
        op("re", vec![n(160.0), n(20.0), n(25.0), n(25.0)]),
        op("W*", vec![]),
        op("n", vec![]),
        op("g", vec![n(0.0)]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(200.0)]),
        op("f", vec![]),
        op("Q", vec![]),
    ];
    let _ = compare_page("clipping", &pdf_bytes(ops, dictionary! {}));
}

/// Type 3 font text: the only way to compare TEXT POSITIONING pixel-for-pixel
/// between two renderers without shipping a font file, because the glyphs are
/// content streams both must draw identically. Exercises `/FontMatrix`,
/// `/Widths`, `Tf`, `Td`, `TL`/`T*`, `Tc`, `Tz` and `TJ` kerning (§9.4.4).
///
/// **FIXED.** See [`refdiff_root_cause_d0_mangles_the_next_operator`] for the
/// isolated repro. In short: every Type 3 glyph description must begin
/// with `d0` or `d1` (§9.6.5), lopdf mis-tokenises those, and the damage lands
/// on the glyph's first painting operator. The square glyph in this fixture
/// disappeared entirely; the triangle glyph was drawn from the wrong start point.
#[test]
fn refdiff_type3_font_text_positioning() {
    let mut doc = Document::with_version("1.7");
    // Glyph "a": a filled square in the left 700/1000 of the em.
    let ga = doc.add_object(Stream::new(
        dictionary! {},
        Content {
            operations: vec![
                op("d0", vec![n(700.0), n(0.0)]),
                op("re", vec![n(50.0), n(0.0), n(600.0), n(600.0)]),
                op("f", vec![]),
            ],
        }
        .encode()
        .expect("glyph a"),
    ));
    // Glyph "b": a filled triangle, narrower.
    let gb = doc.add_object(Stream::new(
        dictionary! {},
        Content {
            operations: vec![
                op("d0", vec![n(400.0), n(0.0)]),
                op("m", vec![n(30.0), n(0.0)]),
                op("l", vec![n(370.0), n(0.0)]),
                op("l", vec![n(200.0), n(700.0)]),
                op("h", vec![]),
                op("f", vec![]),
            ],
        }
        .encode()
        .expect("glyph b"),
    ));
    let font = doc.add_object(dictionary! {
        "Type" => "Font",
        "Subtype" => "Type3",
        "FontBBox" => vec![0.into(), 0.into(), 750.into(), 750.into()],
        "FontMatrix" => vec![
            Object::Real(0.001), Object::Real(0.0), Object::Real(0.0),
            Object::Real(0.001), Object::Real(0.0), Object::Real(0.0),
        ],
        "CharProcs" => dictionary! { "ga" => Object::Reference(ga), "gb" => Object::Reference(gb) },
        "Encoding" => dictionary! {
            "Type" => "Encoding",
            "Differences" => vec![
                Object::Integer(97),
                Object::Name(b"ga".to_vec()),
                Object::Name(b"gb".to_vec()),
            ],
        },
        "FirstChar" => 97,
        "LastChar" => 98,
        "Widths" => vec![Object::Integer(700), Object::Integer(400)],
        "Resources" => dictionary! {},
    });
    let ops = vec![
        op("BT", vec![]),
        op("rg", vec![n(0.0), n(0.0), n(0.0)]),
        op("Tf", vec![Object::Name(b"T3".to_vec()), n(24.0)]),
        op("TL", vec![n(34.0)]),
        op("Td", vec![n(15.0), n(160.0)]),
        op("Tj", vec![Object::string_literal("abab")]),
        op("T*", vec![]),
        op("Tc", vec![n(6.0)]),
        op("Tj", vec![Object::string_literal("abab")]),
        op("T*", vec![]),
        op("Tc", vec![n(0.0)]),
        op("Tz", vec![n(160.0)]),
        op("Tj", vec![Object::string_literal("aab")]),
        op("T*", vec![]),
        op("Tz", vec![n(100.0)]),
        op(
            "TJ",
            vec![Object::Array(vec![
                Object::string_literal("ab"),
                Object::Integer(-500),
                Object::string_literal("ab"),
            ])],
        ),
        op("ET", vec![]),
    ];
    let res = dictionary! { "Font" => dictionary! { "T3" => Object::Reference(font) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("type3_text", &bytes);
}

/// Constant alpha (`/ca`) with overlapping fills — §11.6.4.4. The compositing
/// arithmetic is a place where "close enough" hides a systematic error.
#[test]
fn refdiff_constant_alpha_compositing() {
    let mut doc = Document::with_version("1.7");
    let gs = doc.add_object(dictionary! { "Type" => "ExtGState", "ca" => Object::Real(0.5) });
    let ops = vec![
        op("rg", vec![n(1.0), n(0.0), n(0.0)]),
        op("re", vec![n(20.0), n(60.0), n(100.0), n(80.0)]),
        op("f", vec![]),
        op("gs", vec![Object::Name(b"G".to_vec())]),
        op("rg", vec![n(0.0), n(0.0), n(1.0)]),
        op("re", vec![n(80.0), n(60.0), n(100.0), n(80.0)]),
        op("f", vec![]),
    ];
    let res = dictionary! { "ExtGState" => dictionary! { "G" => Object::Reference(gs) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("constant_alpha", &bytes);
    for (label, rect) in [
        ("red_only", [30.0, 70.0, 70.0, 130.0]),
        ("blue_over_red", [90.0, 70.0, 110.0, 130.0]),
        ("blue_over_white", [140.0, 70.0, 170.0, 130.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 5.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// `/Rotate` and a `/CropBox` offset from the `/MediaBox` origin (§7.7.3.3,
/// §14.11.2). Both renderers must independently agree on where the ink lands.
#[test]
fn refdiff_rotate_and_cropbox() {
    for rotate in [0i64, 90, 180, 270] {
        let ops = vec![
            op("rg", vec![n(0.9), n(0.3), n(0.0)]),
            // An L shape, so every rotation is distinguishable.
            op("re", vec![n(60.0), n(60.0), n(80.0), n(20.0)]),
            op("f", vec![]),
            op("re", vec![n(60.0), n(60.0), n(20.0), n(80.0)]),
            op("f", vec![]),
        ];
        let page = dictionary! {
            "MediaBox" => vec![0.into(), 0.into(), 240.into(), 240.into()],
            "CropBox" => vec![20.into(), 20.into(), 220.into(), 200.into()],
            "Rotate" => Object::Integer(rotate),
        };
        let _ = compare_page(
            &format!("rotate_{rotate}"),
            &pdf_bytes_page(ops, dictionary! {}, page),
        );
    }
}

/// Dash patterns (§8.4.3.6): phase handling and the odd-length-array rule are
/// both easy to read two ways.
#[test]
fn refdiff_dash_patterns() {
    let ops = vec![
        op("w", vec![n(6.0)]),
        op("J", vec![Object::Integer(0)]),
        op("g", vec![n(0.0)]),
        op("d", vec![Object::Array(vec![n(12.0), n(8.0)]), n(0.0)]),
        op("m", vec![n(10.0), n(170.0)]),
        op("l", vec![n(190.0), n(170.0)]),
        op("S", vec![]),
        op("d", vec![Object::Array(vec![n(12.0), n(8.0)]), n(6.0)]),
        op("m", vec![n(10.0), n(130.0)]),
        op("l", vec![n(190.0), n(130.0)]),
        op("S", vec![]),
        // Odd-length array: §8.4.3.6 says it is used cyclically, so the phases
        // alternate on/off across repetitions.
        op("d", vec![Object::Array(vec![n(15.0)]), n(0.0)]),
        op("m", vec![n(10.0), n(90.0)]),
        op("l", vec![n(190.0), n(90.0)]),
        op("S", vec![]),
        // Empty array = solid.
        op("d", vec![Object::Array(vec![]), n(0.0)]),
        op("m", vec![n(10.0), n(50.0)]),
        op("l", vec![n(190.0), n(50.0)]),
        op("S", vec![]),
    ];
    let _ = compare_page("dashes", &pdf_bytes(ops, dictionary! {}));
}

/// Luminosity soft mask (§11.6.5.2) driven by an axial shading.
#[test]
fn refdiff_luminosity_soft_mask() {
    let mut doc = Document::with_version("1.7");
    let f = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.0)], "C1" => vec![Object::Real(1.0)], "N" => 1,
        },
        Vec::new(),
    ));
    let sh = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => Object::Name(b"DeviceGray".to_vec()),
        "Coords" => vec![Object::Real(20.0), Object::Real(0.0), Object::Real(180.0), Object::Real(0.0)],
        "Function" => Object::Reference(f),
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
    });
    let group = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(200.0), Object::Real(200.0)],
            "Group" => dictionary! { "S" => "Transparency", "CS" => Object::Name(b"DeviceGray".to_vec()) },
            "Resources" => dictionary! { "Shading" => dictionary! { "S0" => Object::Reference(sh) } },
        },
        Content { operations: vec![op("sh", vec![Object::Name(b"S0".to_vec())])] }
            .encode()
            .expect("mask content"),
    ));
    let gs = doc.add_object(dictionary! {
        "Type" => "ExtGState",
        "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(group) },
    });
    let ops = vec![
        op("gs", vec![Object::Name(b"G".to_vec())]),
        op("rg", vec![n(0.8), n(0.0), n(0.0)]),
        op("re", vec![n(10.0), n(50.0), n(180.0), n(100.0)]),
        op("f", vec![]),
    ];
    let res = dictionary! { "ExtGState" => dictionary! { "G" => Object::Reference(gs) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("soft_mask", &bytes);
}

