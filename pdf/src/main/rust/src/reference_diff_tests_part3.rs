/// FINDING (recorded, not a pass/fail gate): the two renderers disagree
/// systematically on `DeviceCMYK`, by up to ~40 levels per channel.
///
/// We convert arithmetically in `color.rs:12` `cmyk_to_argb`:
/// `r = (1-c)(1-k)`, `g = (1-m)(1-k)`, `b = (1-y)(1-k)`. `hayro` instead runs
/// every DeviceCMYK sample through an embedded CGATS TR 001 (SWOP) ICC profile
/// (`hayro-interpret-0.7.0/src/color.rs:1066`, `CMYK_TRANSFORM`), which is what
/// Acrobat and pdf.js effectively do.
///
/// NEITHER IS WRONG. ISO 32000-1 §8.6.4 makes the device colour spaces
/// explicitly device-dependent, and §8.6.5.6 provides `/DefaultCMYK` precisely
/// so that a document that needs a defined colorimetry can ask for one; absent
/// that entry the mapping is the consumer's choice. This test therefore only
/// pins the divergence so that it is a known, measured quantity rather than a
/// surprise the next time someone diffs against a reference — and so that a
/// future change to `cmyk_to_argb` shows up here as a change in the printed
/// deltas.
#[test]
fn refdiff_devicecmyk_diverges_because_the_reference_is_colour_managed() {
    let patches: [[f64; 4]; 6] = [
        [0.0, 1.0, 1.0, 0.0],
        [1.0, 0.0, 1.0, 0.0],
        [1.0, 1.0, 0.0, 0.0],
        [0.0, 0.0, 0.0, 0.5],
        [0.2, 0.4, 0.6, 0.1],
        [0.0, 0.0, 0.0, 1.0],
    ];
    let mut ops = Vec::new();
    for (i, p) in patches.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        ops.push(op("k", vec![n(p[0]), n(p[1]), n(p[2]), n(p[3])]));
        ops.push(op(
            "re",
            vec![n(10.0 + col as f64 * 64.0), n(10.0 + row as f64 * 96.0), n(56.0), n(88.0)],
        ));
        ops.push(op("f", vec![]));
    }
    let r = render_both("devicecmyk", &pdf_bytes(ops, dictionary! {}));
    let mut worst = 0f64;
    for (i, p) in patches.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        let x = 15.0 + col as f64 * 64.0;
        let y = 15.0 + row as f64 * 96.0;
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, [x, y, x + 46.0, y + 78.0]);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, [x, y, x + 46.0, y + 78.0]);
        // The PostScript `setcmykcolor` additive formula, for reference: it is
        // the OTHER common reading, and is not what either renderer does.
        let ps = [
            (1.0 - (p[0] + p[3]).min(1.0)) * 255.0,
            (1.0 - (p[1] + p[3]).min(1.0)) * 255.0,
            (1.0 - (p[2] + p[3]).min(1.0)) * 255.0,
        ];
        let d = (0..3).map(|c| (a[c] - b[c]).abs()).fold(0.0, f64::max);
        worst = worst.max(d);
        println!(
            "  cmyk{p:?}: ours=[{:.0},{:.0},{:.0}] hayro=[{:.0},{:.0},{:.0}] \
             ps_additive=[{:.0},{:.0},{:.0}] max_delta={d:.0}",
            a[0], a[1], a[2], b[0], b[1], b[2], ps[0], ps[1], ps[2]
        );
    }
    println!("  worst DeviceCMYK channel delta = {worst:.0}/255");
    // Deliberately loose: this asserts the divergence stays a colour-management
    // difference (measured worst = 109/255, on saturated cyan+magenta) and has
    // not become a gross error such as a swapped channel.
    assert!(worst <= 120.0, "DeviceCMYK divergence grew past colour management: {worst}");
}

/// Nonzero vs even-odd on a self-intersecting star: the classic place to get
/// §8.5.3.3.2 backwards and never notice, because both rules look plausible.
#[test]
fn refdiff_winding_rules_on_a_self_intersecting_star() {
    let star = |close: &str| {
        let pts = [(100.0, 190.0), (145.0, 55.0), (30.0, 140.0), (170.0, 140.0), (55.0, 55.0)];
        let mut v = vec![
            op("rg", vec![n(0.1), n(0.2), n(0.8)]),
            op("m", vec![n(pts[0].0), n(pts[0].1)]),
        ];
        for p in &pts[1..] {
            v.push(op("l", vec![n(p.0), n(p.1)]));
        }
        v.push(op("h", vec![]));
        v.push(op(close, vec![]));
        v
    };
    let _ = compare_page("star_nonzero", &pdf_bytes(star("f"), dictionary! {}));
    let _ = compare_page("star_evenodd", &pdf_bytes(star("f*"), dictionary! {}));
}

/// Indexed and Separation, plus a Type 2 tint transform. Getting the Separation
/// tint→alternate direction backwards, or the Indexed lookup stride wrong, is a
/// misreading no self-referential test can catch.
#[test]
fn refdiff_indexed_and_separation_colour() {
    let mut doc = Document::with_version("1.7");
    // Indexed palette: 3 entries of DeviceRGB.
    let palette: Vec<u8> = vec![255, 0, 0, 0, 255, 0, 0, 0, 255];
    let indexed = Object::Array(vec![
        Object::Name(b"Indexed".to_vec()),
        Object::Name(b"DeviceRGB".to_vec()),
        Object::Integer(2),
        Object::String(palette, lopdf::StringFormat::Hexadecimal),
    ]);
    // Separation with a Type 2 exponential tint transform. The alternate is
    // DeviceRGB, not DeviceCMYK, so this measures the TINT TRANSFORM and not
    // the colour-management policy divergence recorded in
    // `refdiff_devicecmyk_diverges_because_the_reference_is_colour_managed`.
    let tint = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(1.0), Object::Real(1.0), Object::Real(1.0)],
            "C1" => vec![Object::Real(0.1), Object::Real(0.35), Object::Real(0.6)],
            "N" => 1,
        },
        Vec::new(),
    ));
    let sep = Object::Array(vec![
        Object::Name(b"Separation".to_vec()),
        Object::Name(b"Spot".to_vec()),
        Object::Name(b"DeviceRGB".to_vec()),
        Object::Reference(tint),
    ]);
    let cs = dictionary! { "IX" => indexed, "SP" => sep };
    let resources = dictionary! { "ColorSpace" => cs };
    let ops = vec![
        op("cs", vec![Object::Name(b"IX".to_vec())]),
        op("sc", vec![Object::Integer(2)]),
        op("re", vec![n(20.0), n(110.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("cs", vec![Object::Name(b"IX".to_vec())]),
        op("sc", vec![Object::Integer(1)]),
        op("re", vec![n(110.0), n(110.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("cs", vec![Object::Name(b"SP".to_vec())]),
        op("sc", vec![n(1.0)]),
        op("re", vec![n(20.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
        op("cs", vec![Object::Name(b"SP".to_vec())]),
        op("sc", vec![n(0.5)]),
        op("re", vec![n(110.0), n(20.0), n(70.0), n(70.0)]),
        op("f", vec![]),
    ];
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, resources, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("indexed_separation", &bytes);
    for (label, rect) in [
        ("indexed_2_blue", [30.0, 120.0, 80.0, 170.0]),
        ("indexed_1_green", [120.0, 120.0, 170.0, 170.0]),
        ("separation_1.0", [30.0, 30.0, 80.0, 80.0]),
        ("separation_0.5", [120.0, 30.0, 170.0, 80.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!(
                (a[c] - b[c]).abs() <= 4.0,
                "{label} channel {c}: ours {} vs hayro {}",
                a[c],
                b[c]
            );
        }
    }
}

/// All four function types, driven through Separation tint transforms so the
/// answer lands in a flat, antialiasing-free patch of colour. This is the single
/// highest-value entry in the corpus: `functions.rs` is 58 kB of formula, every
/// line of it verified only against our own reading of §7.10.
#[test]
fn refdiff_all_four_function_types_via_separation() {
    let mut doc = Document::with_version("1.7");

    // Type 2: exponential, N = 2 so the exponent is actually exercised.
    let f2 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.0)],
            "C1" => vec![Object::Real(1.0)],
            "N" => 2,
        },
        Vec::new(),
    ));
    // Type 3: stitching two Type 2s, with a non-trivial /Encode.
    let f2a = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.0)], "C1" => vec![Object::Real(0.3)], "N" => 1,
        },
        Vec::new(),
    ));
    let f2b = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(0.3)], "C1" => vec![Object::Real(1.0)], "N" => 1,
        },
        Vec::new(),
    ));
    let f3 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 3,
            "Domain" => vec![0.into(), 1.into()],
            "Functions" => vec![Object::Reference(f2a), Object::Reference(f2b)],
            "Bounds" => vec![Object::Real(0.4)],
            "Encode" => vec![Object::Real(0.0), Object::Real(1.0), Object::Real(0.0), Object::Real(1.0)],
        },
        Vec::new(),
    ));
    // Type 0: sampled, 5 samples, 8 bpc, linear interpolation between them.
    let f0 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 0,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
            "Size" => vec![5.into()],
            "BitsPerSample" => 8,
        },
        vec![0u8, 32, 200, 220, 255],
    ));
    // Type 4: PostScript calculator.
    let f4 = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![0.into(), 1.into()],
            "Range" => vec![0.into(), 1.into()],
        },
        b"{ dup mul 0.5 mul 0.25 add }".to_vec(),
    ));

    let mut cs = Dictionary::new();
    for (name, f) in [(&b"F2"[..], f2), (&b"F3"[..], f3), (&b"F0"[..], f0), (&b"F4"[..], f4)] {
        cs.set(
            String::from_utf8_lossy(name).to_string(),
            Object::Array(vec![
                Object::Name(b"Separation".to_vec()),
                Object::Name(b"Spot".to_vec()),
                Object::Name(b"DeviceGray".to_vec()),
                Object::Reference(f),
            ]),
        );
    }
    // Four columns x two tint values, so eight independent evaluations.
    let mut ops = Vec::new();
    let tints = [0.35f64, 0.8];
    for (col, name) in [&b"F2"[..], &b"F3"[..], &b"F0"[..], &b"F4"[..]].iter().enumerate() {
        for (row, t) in tints.iter().enumerate() {
            ops.push(op("cs", vec![Object::Name(name.to_vec())]));
            ops.push(op("sc", vec![n(*t)]));
            ops.push(op(
                "re",
                vec![n(10.0 + col as f64 * 48.0), n(10.0 + row as f64 * 96.0), n(40.0), n(88.0)],
            ));
            ops.push(op("f", vec![]));
        }
    }
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, dictionary! { "ColorSpace" => cs }, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("functions", &bytes);
    let mut worst = 0f64;
    for (col, name) in ["Type2", "Type3", "Type0", "Type4"].iter().enumerate() {
        for (row, t) in tints.iter().enumerate() {
            let x = 15.0 + col as f64 * 48.0;
            let y = 15.0 + row as f64 * 96.0;
            let rect = [x, y, x + 30.0, y + 78.0];
            let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
            let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
            let d = (a[0] - b[0]).abs().max((a[1] - b[1]).abs()).max((a[2] - b[2]).abs());
            println!("  {name} tint={t}: ours={:.1} theirs={:.1} delta={:.1}", a[0], b[0], d);
            worst = worst.max(d);
            assert!(d <= 4.0, "{name} at tint {t}: ours {:.1} vs hayro {:.1}", a[0], b[0]);
        }
    }
    println!("  worst function delta = {worst:.2}");
}

/// Axial and radial shadings with `/Extend`. §8.7.4.5.3 and §8.7.4.5.4 are dense
/// and the extend/`t` mapping is easy to read two ways.
#[test]
fn refdiff_axial_and_radial_shadings() {
    let mut doc = Document::with_version("1.7");
    let f = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 2, "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![Object::Real(1.0), Object::Real(0.0), Object::Real(0.0)],
            "C1" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(1.0)],
            "N" => 1,
        },
        Vec::new(),
    ));
    let axial = doc.add_object(dictionary! {
        "ShadingType" => 2,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Coords" => vec![Object::Real(50.0), Object::Real(0.0), Object::Real(150.0), Object::Real(0.0)],
        "Function" => Object::Reference(f),
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
    });
    let radial = doc.add_object(dictionary! {
        "ShadingType" => 3,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Coords" => vec![
            Object::Real(100.0), Object::Real(100.0), Object::Real(10.0),
            Object::Real(100.0), Object::Real(100.0), Object::Real(80.0),
        ],
        "Function" => Object::Reference(f),
        "Extend" => vec![Object::Boolean(true), Object::Boolean(true)],
    });

    let ops_ax = vec![
        op("q", vec![]),
        op("re", vec![n(0.0), n(100.0), n(200.0), n(100.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("sh", vec![Object::Name(b"AX".to_vec())]),
        op("Q", vec![]),
        op("q", vec![]),
        op("re", vec![n(0.0), n(0.0), n(200.0), n(100.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("sh", vec![Object::Name(b"RA".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! {
        "Shading" => dictionary! { "AX" => Object::Reference(axial), "RA" => Object::Reference(radial) }
    };
    let content = Content { operations: ops_ax }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("shadings", &bytes);
    // Extend regions: left of x=50 must be pure C0, right of x=150 pure C1.
    for (label, rect, want) in [
        ("axial_extend_before", [5.0, 120.0, 40.0, 180.0], [255.0, 0.0, 0.0]),
        ("axial_extend_after", [160.0, 120.0, 195.0, 180.0], [0.0, 0.0, 255.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?} spec={want:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 4.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// Image XObject placement, `/Decode` inversion, and an `/ImageMask` stencil.
/// §8.9.5.2 (unit-square mapping, row 0 = v=1) and §8.9.6.2 (stencil polarity)
/// are both easy to invert silently — a flipped image still "looks like an
/// image".
#[test]
fn refdiff_image_placement_decode_and_stencil() {
    let mut doc = Document::with_version("1.7");
    // 2x2 RGB, deliberately asymmetric so a flip in either axis is visible.
    let rgb = vec![255u8, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 0];
    let img = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 2,
            "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
            "BitsPerComponent" => 8,
        },
        rgb,
    ));
    // 8x8 stencil: left half set. 1 bit per row-padded row.
    let stencil: Vec<u8> = (0..8).map(|_| 0xF0u8).collect();
    let mask = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8,
            "ImageMask" => Object::Boolean(true),
            "BitsPerComponent" => 1,
        },
        stencil,
    ));
    let ops = vec![
        op("q", vec![]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(20.0), n(100.0)]),
        op("Do", vec![Object::Name(b"Im".to_vec())]),
        op("Q", vec![]),
        op("q", vec![]),
        op("rg", vec![n(0.0), n(0.6), n(0.2)]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(20.0), n(10.0)]),
        op("Do", vec![Object::Name(b"Ms".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! {
        "XObject" => dictionary! {
            "Im" => Object::Reference(img),
            "Ms" => Object::Reference(mask),
        }
    };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("image_placement", &bytes);
    // Quadrant colours pin the orientation: sample well inside each.
    for (label, rect) in [
        ("img_top_left", [30.0, 145.0, 55.0, 170.0]),
        ("img_top_right", [105.0, 145.0, 130.0, 170.0]),
        ("img_bottom_left", [30.0, 110.0, 55.0, 135.0]),
        ("img_bottom_right", [105.0, 110.0, 130.0, 135.0]),
        ("stencil_left_painted", [30.0, 20.0, 55.0, 80.0]),
        ("stencil_right_clear", [65.0, 20.0, 90.0, 80.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 4.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}
