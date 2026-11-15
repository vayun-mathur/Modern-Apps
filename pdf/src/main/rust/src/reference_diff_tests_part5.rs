/// ROOT CAUSE of `refdiff_type3_font_text_positioning`, isolated to the parser
/// so phase 2 has an exact, sub-millisecond target. **FIXED** — see
/// `content.rs::repair_d0_d1`.
///
/// ISO 32000-1 §9.6.5 "Type 3 Fonts": "The glyph description shall begin with
/// either the `d0` or the `d1` operator." Table 113 gives their signatures as
/// `wx wy d0` and `wx wy llx lly urx ury d1`.
///
/// lopdf 0.36's content tokeniser ends an operator token at the first digit, so
/// `700 0 d0` decodes as the DASH operator `d` with operands `[700, 0]`, and the
/// orphaned `0` becomes the FIRST OPERAND OF THE NEXT OPERATION. Measured on
/// this host with `lopdf::content::Content::decode`:
///
/// ```text
///   "700 0 d0\n50 0 600 600 re\nf"
///     -> op="d"  operands=[700, 0]
///        op="re" operands=[0, 50, 0, 600, 600]     <-- five operands
///        op="f"  operands=[]
///
///   "400 0 d0\n30 0 m\n..."
///     -> op="d"  operands=[400, 0]
///        op="m"  operands=[0, 30, 0]               <-- three operands
/// ```
///
/// Consequences in this crate, all silent:
///
/// * `interpret.rs:952` requires `nums.len() == 4` for `re`, so a rectangle that
///   opens a glyph is DROPPED. The glyph vanishes; the advance still happens, so
///   the line looks like it has spaces in it.
/// * `interpret.rs` `"m"` takes the first two operands, so the subpath starts at
///   `(0, 30)` instead of `(30, 0)` — the glyph is drawn from the wrong point.
/// * `interpret.rs:1546`'s `"d0" | "d1"` arm is DEAD CODE: it can never match.
/// * `interpret.rs:588`'s `"d"` arm runs instead, setting `gs.dash = []` and
///   `gs.dash_phase = 0`, so opening a glyph also silently clears an inherited
///   dash pattern (§9.6.5 says the glyph inherits the graphics state).
///
/// The lenient recovery tokeniser is NOT affected — `content.rs:83`'s
/// `OPERATORS` table lists `d0`/`d1` and `is_known_operator` matches the whole
/// token — so the bug is confined to the strict path, which is the path healthy
/// files take.
///
/// PROPOSED PATCH, in `content.rs`, applied inside `strict_operations` so both
/// `page_operations` and `stream_operations` get it:
///
/// ```ignore
/// fn strict_operations(bytes: &[u8]) -> Option<Vec<Operation>> {
///     if nesting_is_too_deep(bytes, MAX_DEPTH) {
///         return None;
///     }
///     match Content::decode(bytes) {
///         Ok(content) if !content.operations.is_empty() => {
///             Some(repair_d0_d1(content.operations))
///         }
///         _ => None,
///     }
/// }
///
/// /// Undo lopdf 0.36's mis-tokenisation of `d0`/`d1` (§9.6.5, Table 113).
/// ///
/// /// A conforming `d` takes an ARRAY then a number (§8.4.3.6, Table 52), so a
/// /// `d` whose operands are all numeric is unambiguously the mangled form:
/// /// two operands mean `d0`, six mean `d1`. The digit lopdf split off is the
/// /// next operation's first operand and is removed there.
/// fn repair_d0_d1(mut ops: Vec<Operation>) -> Vec<Operation> {
///     for i in 0..ops.len() {
///         let mangled = ops[i].operator == "d"
///             && matches!(ops[i].operands.len(), 2 | 6)
///             && ops[i]
///                 .operands
///                 .iter()
///                 .all(|o| matches!(o, Object::Integer(_) | Object::Real(_)));
///         if !mangled {
///             continue;
///         }
///         let is_d1 = ops[i].operands.len() == 6;
///         if let Some(next) = ops.get_mut(i + 1) {
///             let leaked = match next.operands.first() {
///                 Some(Object::Integer(v)) => *v == i64::from(is_d1),
///                 Some(Object::Real(v)) => *v == f32::from(u8::from(is_d1)),
///                 _ => false,
///             };
///             if !leaked {
///                 continue; // not the mangling after all; leave it alone
///             }
///             let _ = next.operands.remove(0);
///         }
///         ops[i].operator = if is_d1 { "d1" } else { "d0" }.to_string();
///     }
///     ops
/// }
/// ```
///
/// Note the ordering: the repair only renames `d` once it has confirmed the
/// leaked digit is present, so a genuinely malformed `d` is left untouched.
/// `d0` as the final operator of a stream leaves no next operation and no
/// leaked digit, which is why the `None` arm of `ops.get_mut(i + 1)` still
/// renames.
#[test]
fn refdiff_root_cause_d0_mangles_the_next_operator() {
    let cases: [(&str, &str, usize); 2] = [
        ("700 0 d0\n50 0 600 600 re\nf", "re", 4),
        ("0 0 0 0 750 750 d1\n30 0 m\n370 0 l\nh\nf", "m", 2),
    ];
    for (src, want_op, want_operands) in cases {
        // Our STRICT path — the one healthy files take — not `Content::decode`
        // directly: the repair lives in `content.rs`, and lopdf's raw decoder is
        // what is being compensated for, so asserting on it could never go green.
        let ops = crate::content::strict_operations(src.as_bytes()).expect("strict parse");
        for o in &ops {
            println!("  {src:?} -> op={:?} operands={:?}", o.operator, o.operands);
        }
        let first = &ops[0];
        assert!(
            first.operator == "d0" || first.operator == "d1",
            "§9.6.5: a glyph description begins with d0/d1, but the parser produced {:?} \
             with operands {:?}. See this test's doc comment for the patch.",
            first.operator,
            first.operands
        );
        let painted = ops.iter().find(|o| o.operator == want_op).expect("painting operator");
        assert_eq!(
            painted.operands.len(),
            want_operands,
            "`{want_op}` must receive exactly {want_operands} operands; it received {:?}",
            painted.operands
        );
    }
}

/// The §8.6.5.4 Lab decoding plus the IEC 61966-2-1 sRGB encoding, written here
/// FROM THE SPEC TEXT rather than from `color.rs`, so this function is a third
/// independent implementation and can arbitrate between the other two.
///
/// §8.6.5.4: `M = (L*+16)/116 + a*/500`, `L = (L*+16)/116`,
/// `N = (L*+16)/116 − b*/200`; `X = Xw·g(M)`, `Y = Yw·g(L)`, `Z = Zw·g(N)`;
/// `g(x) = x³` for `x ≥ 6/29`, else `g(x) = (108/841)(x − 4/29)`.
fn spec_lab_to_srgb(l: f64, a: f64, b: f64, wp: [f64; 3]) -> [f64; 3] {
    let g = |x: f64| {
        if x >= 6.0 / 29.0 {
            x * x * x
        } else {
            (108.0 / 841.0) * (x - 4.0 / 29.0)
        }
    };
    let fy = (l + 16.0) / 116.0;
    let (x, y, z) = (wp[0] * g(fy + a / 500.0), wp[1] * g(fy), wp[2] * g(fy - b / 200.0));
    let lin = [
        3.2406 * x - 1.5372 * y - 0.4986 * z,
        -0.9689 * x + 1.8758 * y + 0.0415 * z,
        0.0557 * x - 0.2040 * y + 1.0570 * z,
    ];
    let enc = |v: f64| {
        let v = v.clamp(0.0, 1.0);
        let s = if v <= 0.0031308 { 12.92 * v } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 };
        (s * 255.0).round()
    };
    [enc(lin[0]), enc(lin[1]), enc(lin[2])]
}

/// CIE-based colour: `Lab` (§8.6.5.4) and `CalRGB` (§8.6.5.2).
///
/// FINDING — **OURS IS RIGHT.** `CalRGB` agrees exactly. `Lab` diverges by
/// 1..61 levels, growing with chroma, and [`spec_lab_to_srgb`] — a third
/// implementation written straight from the clause — arbitrates in our favour
/// at every patch. `hayro`'s numbers are consistent with routing Lab through a
/// D50-referenced ICC PCS and discarding the `/WhitePoint` the file declares,
/// which is defensible as a colour-management policy but is not the XYZ that
/// §8.6.5.4 defines. NO PATCH PROPOSED.
#[test]
fn refdiff_lab_and_calrgb_colour() {
    const WP: [f64; 3] = [0.9505, 1.0, 1.089];
    let lab = Object::Array(vec![
        Object::Name(b"Lab".to_vec()),
        Object::Dictionary(dictionary! {
            "WhitePoint" => vec![Object::Real(WP[0] as f32), Object::Real(WP[1] as f32), Object::Real(WP[2] as f32)],
            "Range" => vec![Object::Real(-100.0), Object::Real(100.0), Object::Real(-100.0), Object::Real(100.0)],
        }),
    ]);
    let calrgb = Object::Array(vec![
        Object::Name(b"CalRGB".to_vec()),
        Object::Dictionary(dictionary! {
            "WhitePoint" => vec![Object::Real(WP[0] as f32), Object::Real(WP[1] as f32), Object::Real(WP[2] as f32)],
            "Gamma" => vec![Object::Real(2.2), Object::Real(2.2), Object::Real(2.2)],
        }),
    ]);
    let res = dictionary! { "ColorSpace" => dictionary! { "LB" => lab, "CR" => calrgb } };
    let labs: [[f64; 3]; 6] = [
        [60.0, 0.0, 0.0],
        [70.0, 20.0, -30.0],
        [40.0, -25.0, 15.0],
        [54.0, 81.0, 70.0],
        [88.0, -79.0, 81.0],
        [30.0, 50.0, -60.0],
    ];
    let mut ops = Vec::new();
    for (i, p) in labs.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        ops.push(op("cs", vec![Object::Name(b"LB".to_vec())]));
        ops.push(op("sc", vec![n(p[0]), n(p[1]), n(p[2])]));
        ops.push(op(
            "re",
            vec![n(10.0 + col as f64 * 64.0), n(105.0 - row as f64 * 95.0), n(56.0), n(85.0)],
        ));
        ops.push(op("f", vec![]));
    }
    let r = render_both("lab", &pdf_bytes(ops, res.clone()));
    let mut ours_worst = 0f64;
    let mut theirs_worst = 0f64;
    for (i, p) in labs.iter().enumerate() {
        let (col, row) = (i % 3, i / 3);
        let x = 15.0 + col as f64 * 64.0;
        let y = 110.0 - row as f64 * 95.0;
        let rect = [x, y, x + 46.0, y + 75.0];
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        let spec = spec_lab_to_srgb(p[0], p[1], p[2], WP);
        let da = (0..3).map(|c| (a[c] - spec[c]).abs()).fold(0.0, f64::max);
        let db = (0..3).map(|c| (b[c] - spec[c]).abs()).fold(0.0, f64::max);
        ours_worst = ours_worst.max(da);
        theirs_worst = theirs_worst.max(db);
        println!(
            "  Lab{p:?}: spec=[{:.0},{:.0},{:.0}] ours=[{:.0},{:.0},{:.0}] (Δ{da:.0}) \
             hayro=[{:.0},{:.0},{:.0}] (Δ{db:.0})",
            spec[0], spec[1], spec[2], a[0], a[1], a[2], b[0], b[1], b[2]
        );
    }
    println!("  worst |ours-spec| = {ours_worst:.0}/255, worst |hayro-spec| = {theirs_worst:.0}/255");
    assert!(
        ours_worst <= 2.0,
        "our Lab conversion deviates from the §8.6.5.4 formula by {ours_worst:.0}/255"
    );

    // CalRGB, where the two renderers do agree exactly.
    let cals: [[f64; 3]; 3] = [[0.8, 0.2, 0.2], [0.2, 0.8, 0.2], [0.5, 0.5, 0.5]];
    let mut ops = Vec::new();
    for (i, p) in cals.iter().enumerate() {
        ops.push(op("cs", vec![Object::Name(b"CR".to_vec())]));
        ops.push(op("sc", vec![n(p[0]), n(p[1]), n(p[2])]));
        ops.push(op("re", vec![n(10.0 + i as f64 * 64.0), n(55.0), n(56.0), n(90.0)]));
        ops.push(op("f", vec![]));
    }
    let r = compare_page("calrgb", &pdf_bytes(ops, res));
    for (i, p) in cals.iter().enumerate() {
        let x = 15.0 + i as f64 * 64.0;
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, [x, 60.0, x + 46.0, 140.0]);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, [x, 60.0, x + 46.0, 140.0]);
        let d = (0..3).map(|c| (a[c] - b[c]).abs()).fold(0.0, f64::max);
        println!(
            "  CalRGB{p:?}: ours=[{:.0},{:.0},{:.0}] hayro=[{:.0},{:.0},{:.0}] delta={d:.0}",
            a[0], a[1], a[2], b[0], b[1], b[2]
        );
        assert!(d <= 3.0, "CalRGB {p:?} disagrees by {d:.0}/255");
    }
}

/// Function-based (Type 1) shading with a non-identity `/Matrix` and a `/Domain`
/// that is not the unit square (§8.7.4.5.2). The `/Matrix` maps the domain into
/// the shading's target space, and applying it in the wrong direction still
/// produces a plausible-looking gradient.
#[test]
fn refdiff_function_based_shading_matrix_and_domain() {
    let mut doc = Document::with_version("1.7");
    let f = doc.add_object(Stream::new(
        dictionary! {
            "FunctionType" => 4,
            "Domain" => vec![Object::Real(-1.0), Object::Real(1.0), Object::Real(-1.0), Object::Real(1.0)],
            "Range" => vec![0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
        },
        // (x, y) -> (|x|, |y|, 0.25)
        b"{ 0.25 3 1 roll abs exch abs exch 3 -1 roll }".to_vec(),
    ));
    let sh = doc.add_object(dictionary! {
        "ShadingType" => 1,
        "ColorSpace" => Object::Name(b"DeviceRGB".to_vec()),
        "Domain" => vec![Object::Real(-1.0), Object::Real(1.0), Object::Real(-1.0), Object::Real(1.0)],
        "Matrix" => vec![
            Object::Real(80.0), Object::Real(0.0), Object::Real(0.0),
            Object::Real(80.0), Object::Real(100.0), Object::Real(100.0),
        ],
        "Function" => Object::Reference(f),
    });
    let ops = vec![
        op("q", vec![]),
        op("re", vec![n(10.0), n(10.0), n(180.0), n(180.0)]),
        op("W", vec![]),
        op("n", vec![]),
        op("sh", vec![Object::Name(b"S0".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! { "Shading" => dictionary! { "S0" => Object::Reference(sh) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("function_shading", &bytes);
}

/// `/ImageMask` with `/Decode [1 0]`: §8.9.6.2 says sample value 0 marks the
/// page with `/Decode [0 1]`, and `/Decode [1 0]` reverses that. Inverting a
/// stencil is invisible to any test that only asserts "some ink appeared".
#[test]
fn refdiff_image_mask_decode_polarity() {
    let mut doc = Document::with_version("1.7");
    let stencil: Vec<u8> = (0..8).map(|_| 0xF0u8).collect();
    let mk = |doc: &mut Document, decode: Option<[f64; 2]>| {
        let mut d = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8,
            "ImageMask" => Object::Boolean(true),
            "BitsPerComponent" => 1,
        };
        if let Some(dec) = decode {
            d.set("Decode", vec![Object::Real(dec[0] as f32), Object::Real(dec[1] as f32)]);
        }
        doc.add_object(Stream::new(d, stencil.clone()))
    };
    let a = mk(&mut doc, None);
    let b = mk(&mut doc, Some([1.0, 0.0]));
    let ops = vec![
        op("rg", vec![n(0.85), n(0.1), n(0.4)]),
        op("q", vec![]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(15.0), n(105.0)]),
        op("Do", vec![Object::Name(b"A".to_vec())]),
        op("Q", vec![]),
        op("q", vec![]),
        op("cm", vec![n(80.0), n(0.0), n(0.0), n(80.0), n(105.0), n(105.0)]),
        op("Do", vec![Object::Name(b"B".to_vec())]),
        op("Q", vec![]),
    ];
    let res = dictionary! {
        "XObject" => dictionary! { "A" => Object::Reference(a), "B" => Object::Reference(b) }
    };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");

    let r = compare_page("imagemask_decode", &bytes);
    for (label, rect) in [
        ("default_left_painted", [20.0, 115.0, 50.0, 175.0]),
        ("default_right_clear", [60.0, 115.0, 90.0, 175.0]),
        ("inverted_left_clear", [110.0, 115.0, 140.0, 175.0]),
        ("inverted_right_painted", [150.0, 115.0, 180.0, 175.0]),
    ] {
        let a = interior_mean(&r.ours, r.w, r.h, r.page_h, rect);
        let b = interior_mean(&r.theirs, r.w, r.h, r.page_h, rect);
        println!("  {label}: ours={a:?} theirs={b:?}");
        for c in 0..3 {
            assert!((a[c] - b[c]).abs() <= 4.0, "{label} channel {c}: {} vs {}", a[c], b[c]);
        }
    }
}

/// Coloured tiling pattern (§8.7.3.3): `/XStep`/`/YStep` independent of
/// `/BBox`, plus a pattern `/Matrix`. Our renderer collapses this to one cell
/// bitmap plus a lattice (`Prim::ImageTiled`), which is a representation choice
/// nothing outside this crate has ever checked.
#[test]
fn refdiff_tiling_pattern_step_and_matrix() {
    let mut doc = Document::with_version("1.7");
    let cell = Content {
        operations: vec![
            op("rg", vec![n(0.1), n(0.2), n(0.9)]),
            op("re", vec![n(0.0), n(0.0), n(10.0), n(10.0)]),
            op("f", vec![]),
        ],
    }
    .encode()
    .expect("cell");
    let pat = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern",
            "PatternType" => 1,
            "PaintType" => 1,
            "TilingType" => 1,
            "BBox" => vec![Object::Real(0.0), Object::Real(0.0), Object::Real(10.0), Object::Real(10.0)],
            "XStep" => Object::Real(20.0),
            "YStep" => Object::Real(20.0),
            "Matrix" => vec![
                Object::Real(1.0), Object::Real(0.0), Object::Real(0.0),
                Object::Real(1.0), Object::Real(5.0), Object::Real(5.0),
            ],
            "Resources" => dictionary! {},
        },
        cell,
    ));
    let ops = vec![
        op("cs", vec![Object::Name(b"Pattern".to_vec())]),
        op("scn", vec![Object::Name(b"P0".to_vec())]),
        op("re", vec![n(25.0), n(25.0), n(150.0), n(150.0)]),
        op("f", vec![]),
    ];
    let res = dictionary! { "Pattern" => dictionary! { "P0" => Object::Reference(pat) } };
    let content = Content { operations: ops }.encode().expect("encode");
    let _ = assemble(&mut doc, content, res, dictionary! {});
    let mut bytes = Vec::new();
    doc.save_to(&mut bytes).expect("save");
    let _ = compare_page("tiling_pattern", &bytes);
}








