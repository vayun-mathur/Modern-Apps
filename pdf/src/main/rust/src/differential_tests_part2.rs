/// The font cache key requires the STORED font dictionary to still equal the one
/// the object id resolves to now (`fonts.rs`, `cached_font`). That kills the
/// address-reuse collision above, but `Dictionary` equality is SHALLOW: a font
/// dictionary carries indirect references (`/Widths`, `/FontDescriptor`,
/// `/ToUnicode`, `/DescendantFonts`), and comparing them compares object
/// NUMBERS, not the objects they resolve to.
///
/// So two documents can hold a byte-identical font dictionary at the same object
/// id whose referenced metrics differ completely. This fixture is that case,
/// reduced to its simplest form: both documents allocate objects in the same
/// order, so both have the font at the same id with a dictionary naming
/// `/Widths 1 0 R`; only the CONTENTS of object 1 differ (every glyph 500/1000
/// vs 900/1000). Nothing about the dictionaries distinguishes them.
///
/// Same precondition as the address-reuse case — it needs one scope spanning two
/// documents, which no current caller does — so this is latent too. It is here
/// because "a colliding object id from another document cannot be served" is
/// slightly stronger than what shallow dictionary equality actually buys, and
/// that gap should be written down rather than rediscovered.
///
/// This originally FAILED exactly as its predecessor did before the fix.
/// Verified 2026-08-29 against the `ObjectId` + stored-dictionary key: the
/// 900/1000 document was served the 500/1000 document's metrics, giving an
/// advance of 10.0 (= 500/1000 x 20pt) where 18.0 (= 900/1000 x 20pt) is correct,
/// and glyph origins at 10/20/30 instead of 10/28/46. It was then ignored
/// because no caller could reach it, NOT because the finding was doubtful.
///
/// The cache now keys on `font_identity`, which resolves through the
/// dictionary's indirect references, so the two documents no longer collide
/// and this test passes. It runs in the default suite as the regression
/// guard for that identity: a key that goes back to shallow dictionary
/// equality turns it red again.
#[test]
fn font_cache_distinguishes_documents_whose_font_dicts_are_equal_but_indirect_targets_differ() {
    let build = |width: i64| {
        let mut doc = Document::with_version("1.5");
        // Object 1: the widths array. Same id in both documents, different values.
        let widths: Vec<Object> = (32..=122).map(|_| width.into()).collect();
        let widths_id = doc.add_object(Object::Array(widths));
        // Object 2: the font dictionary. Byte-identical in both documents.
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => "Helvetica", "Encoding" => "WinAnsiEncoding",
            "FirstChar" => 32, "LastChar" => 122,
            "Widths" => widths_id,
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 20.into()]),
            Operation::new("Td", vec![10.into(), 40.into()]),
            Operation::new("Tj", vec![Object::string_literal("iii")]),
            Operation::new("ET", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {
            "Font" => dictionary! { "F1" => font_id }
        });
        (doc, page_id, font_id)
    };

    let (narrow, np, nf) = build(500);
    let (wide, wp, wf) = build(900);
    assert_eq!(nf, wf, "fixture must place the font at the same object id in both documents");
    assert_eq!(
        narrow.get_object(nf).unwrap().as_dict().unwrap(),
        wide.get_object(wf).unwrap().as_dict().unwrap(),
        "fixture must give both documents an EQUAL font dictionary, else it proves nothing"
    );

    let want_narrow = fingerprint(&interpret_page(&narrow, np).expect("narrow"));
    let want_wide = fingerprint(&interpret_page(&wide, wp).expect("wide"));
    assert_ne!(
        want_narrow, want_wide,
        "/Widths is not reaching the advances, so this test cannot detect a stale hit"
    );

    // One scope spanning both documents: the arrangement a cache hit needs.
    let (got_narrow, got_wide) = {
        let _outer = crate::FontCacheScope::new();
        let a = fingerprint(&interpret_page(&narrow, np).expect("narrow scoped"));
        let b = fingerprint(&interpret_page(&wide, wp).expect("wide scoped"));
        (a, b)
    };

    assert_eq!(got_narrow, want_narrow, "first document changed under a shared cache scope");
    assert_eq!(
        got_wide, want_wide,
        "STALE FONT CACHE via shallow dictionary equality: the second document's font \
         resolves to different /Widths through an identical dictionary, but was served \
         the first document's parsed metrics. {}",
        first_difference(&want_wide, &got_wide)
    );
}

/// The live counterpart of the ignored test above: the SAME address-reuse
/// arrangement, but without an outer `FontCacheScope` — which is exactly how
/// production calls in. `interpret_page` opens and drops its own scope per call
/// (`interpret.rs:113`), so the cache must be empty on entry and document B must
/// render with its own font despite sitting at document A's address.
///
/// This is the property that currently protects us, and it was previously
/// implicit. Pinning it means the protection cannot be removed silently: if
/// someone hoists the scope out of `interpret_page` for a performance win, this
/// test goes red rather than the bug shipping.
#[test]
fn font_cache_is_scoped_per_render_so_address_reuse_is_safe() {
    let build = |face: &str| {
        let mut doc = Document::with_version("1.5");
        let font_id = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => face, "Encoding" => "WinAnsiEncoding",
        });
        let ops = vec![
            Operation::new("BT", vec![]),
            Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 24.into()]),
            Operation::new("Td", vec![10.into(), 40.into()]),
            Operation::new("Tj", vec![Object::string_literal("MMM iii")]),
            Operation::new("ET", vec![]),
        ];
        let page_id = page_from_ops(&mut doc, ops, dictionary! {
            "Font" => dictionary! { "F1" => font_id }
        });
        (doc, page_id)
    };

    let (ctl_a, ca) = build("Helvetica");
    let (ctl_b, cb) = build("Courier");
    let want_a = fingerprint(&interpret_page(&ctl_a, ca).expect("ctl a"));
    let want_b = fingerprint(&interpret_page(&ctl_b, cb).expect("ctl b"));
    assert_ne!(want_a, want_b, "fixture fonts must be distinguishable");
    drop(ctl_a);
    drop(ctl_b);

    // No outer scope here: this is the production arrangement.
    let (doc_a, pa) = build("Helvetica");
    let mut slot = Box::new(doc_a);
    let addr_a = &*slot as *const Document as usize;
    let got_a = fingerprint(&interpret_page(&slot, pa).expect("a"));

    let (doc_b, pb) = build("Courier");
    *slot = doc_b;
    assert_eq!(
        addr_a,
        &*slot as *const Document as usize,
        "in-place overwrite failed to reuse the address; test cannot conclude"
    );
    let got_b = fingerprint(&interpret_page(&slot, pb).expect("b"));

    assert_eq!(got_a, want_a, "document A rendered differently after boxing");
    assert_eq!(
        got_b, want_b,
        "per-render font cache scoping is broken: document B at A's address was \
         served A's font even with no outer scope. {}",
        first_difference(&want_b, &got_b)
    );
}

/// One quarter turn, as documented at `geometry.rs:144`: `(x,y) -> (y, w-x)`,
/// and the page's width/height swap. Returns the mapped point and the new
/// dimensions so the map can be composed.
fn quarter_turn(p: (f64, f64), w: f64, h: f64) -> ((f64, f64), (f64, f64)) {
    ((p.1, w - p.0), (h, w))
}

/// Four quarter turns are the identity on both the point and the dimensions.
///
/// Pure algebra on the documented map, kept as a test because it is the
/// premise every rotation assertion below rests on: if the composition were
/// not the identity, the documented maps could not all describe the same
/// rotation and one of the arms in `page_base_matrix` would have to be wrong.
#[test]
fn four_quarter_turns_compose_to_the_identity() {
    let (w0, h0) = (300.0_f64, 200.0_f64);
    let p0 = (37.0_f64, 91.0_f64);

    let (mut p, mut d) = (p0, (w0, h0));
    let mut seen = Vec::new();
    for _ in 0..4 {
        let (np, nd) = quarter_turn(p, d.0, d.1);
        p = np;
        d = nd;
        seen.push((p, d));
    }

    // Intermediate turns must match the other two documented maps.
    assert_eq!(seen[1].0, (w0 - p0.0, h0 - p0.1), "two turns must equal the 180 map");
    assert_eq!(seen[2].0, (h0 - p0.1, p0.0), "three turns must equal the 270 map");
    assert_eq!(seen[3].0, p0, "four turns must restore the point");
    assert_eq!(seen[3].1, (w0, h0), "four turns must restore the dimensions");
}

/// Marker rectangle and page used by the rotation tests. Asymmetric in both the
/// page (300x200) and the mark's placement, so a swapped 90/270 arm — the
/// regression behind issue #321 — cannot coincidentally still line up.
fn rotated_marker_doc(rotate: Option<i64>) -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.5");
    let mut page = dictionary! { "MediaBox" => vec![0.into(), 0.into(), 300.into(), 200.into()] };
    if let Some(r) = rotate {
        page.set("Rotate", r);
    }
    let bytes = Content {
        operations: vec![
            Operation::new("rg", vec![1.into(), 0.into(), 0.into()]),
            Operation::new("re", vec![20.into(), 30.into(), 40.into(), 25.into()]),
            Operation::new("f", vec![]),
        ],
    }
    .encode()
    .unwrap();
    let page_id = assemble(&mut doc, bytes, dictionary! {}, page);
    (doc, page_id)
}

/// The bounding box of the single filled marker.
fn marker_box(page: &PageData) -> [f64; 4] {
    let mut b = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
    let mut found = false;
    for p in &page.prims {
        if let Prim::Fill { contours, .. } = p {
            for pt in contours.iter().flatten() {
                b[0] = b[0].min(pt.0 as f64);
                b[1] = b[1].min(pt.1 as f64);
                b[2] = b[2].max(pt.0 as f64);
                b[3] = b[3].max(pt.1 as f64);
                found = true;
            }
        }
    }
    assert!(found, "fixture emitted no Fill primitive to measure");
    b
}

fn boxes_close(a: [f64; 4], b: [f64; 4]) -> bool {
    // Generous relative to page size but far tighter than any real rotation
    // error, which displaces the mark by tens of points.
    a.iter().zip(b.iter()).all(|(x, y)| (x - y).abs() < 0.01)
}

/// Rotating the page through the four quarter turns moves the mark exactly as
/// the documented maps predict, and the fourth turn brings it home.
///
/// This is the check that would have caught issue #321 without knowing which
/// way round PDF rotation goes: it never asserts where the mark "should" be,
/// only that each successive turn is one application of the same map as the
/// previous, and that four of them are the identity.
#[test]
fn rotating_a_page_four_quarter_turns_returns_to_the_original() {
    let (d0, p0) = rotated_marker_doc(None);
    let base = interpret_page(&d0, p0).expect("rotate 0");
    let (w0, h0) = (base.width as f64, base.height as f64);
    let mut expect_box = marker_box(&base);
    let mut dims = (w0, h0);

    for (turns, rotate) in [(1, 90_i64), (2, 180), (3, 270), (4, 360)] {
        // Advance the prediction by one quarter turn.
        let corners = [
            (expect_box[0], expect_box[1]),
            (expect_box[2], expect_box[1]),
            (expect_box[2], expect_box[3]),
            (expect_box[0], expect_box[3]),
        ];
        let mut next = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
        let mut nd = dims;
        for c in corners {
            let (m, d) = quarter_turn(c, dims.0, dims.1);
            nd = d;
            next[0] = next[0].min(m.0);
            next[1] = next[1].min(m.1);
            next[2] = next[2].max(m.0);
            next[3] = next[3].max(m.1);
        }
        expect_box = next;
        dims = nd;

        let (doc, pid) = rotated_marker_doc(Some(rotate));
        let got = interpret_page(&doc, pid).expect("rotated render");
        assert_eq!(
            (got.width as f64, got.height as f64),
            dims,
            "/Rotate {rotate} ({turns} quarter turns) gave the wrong display size"
        );
        let got_box = marker_box(&got);
        assert!(
            boxes_close(got_box, expect_box),
            "/Rotate {rotate} ({turns} quarter turns) put the mark at {got_box:?}, \
             but composing the documented quarter-turn map {turns} times predicts {expect_box:?}"
        );
    }

    // The fourth turn is /Rotate 360, which must be indistinguishable from 0 —
    // not merely close, but the same primitives.
    let (d360, p360) = rotated_marker_doc(Some(360));
    let f0 = fingerprint(&base);
    let f360 = fingerprint(&interpret_page(&d360, p360).expect("rotate 360"));
    assert_eq!(
        f0, f360,
        "/Rotate 360 must render exactly as /Rotate 0: {}",
        first_difference(&f0, &f360)
    );
}

/// `/Rotate` is reduced modulo 360 (§7.7.3.3 requires a multiple of 90; the
/// implementation normalises rather than rejecting). Every value congruent to
/// the same quarter turn must render identically — including negatives, which
/// are the common real-world spelling of a counter-clockwise turn.
#[test]
fn congruent_rotations_render_identically() {
    let canonical: Vec<String> = [0_i64, 90, 180, 270]
        .iter()
        .map(|r| {
            let (d, p) = rotated_marker_doc(Some(*r));
            fingerprint(&interpret_page(&d, p).expect("canonical"))
        })
        .collect();

    for (value, quarter) in [
        (360_i64, 0_usize),
        (720, 0),
        (-360, 0),
        (450, 1),
        (-270, 1),
        (-180, 2),
        (540, 2),
        (-90, 3),
        (630, 3),
    ] {
        let (d, p) = rotated_marker_doc(Some(value));
        let got = fingerprint(&interpret_page(&d, p).expect("congruent"));
        assert_eq!(
            got,
            canonical[quarter],
            "/Rotate {value} must match /Rotate {}: {}",
            quarter * 90,
            first_difference(&canonical[quarter], &got)
        );
    }
}

// ---------------------------------------------------------------------------
// Metamorphic: translation and Form XObject equivalence

/// Prepending a `cm` translation must translate every emitted coordinate by
/// exactly that vector and change nothing else — same primitive kinds, same
/// order, same colours.
#[test]
fn translating_content_translates_the_primitives() {
    const DX: f64 = 37.0;
    const DY: f64 = -21.0;

    let draw = vec![
        Operation::new("rg", vec![0.2.into(), 0.7.into(), 0.3.into()]),
        Operation::new("re", vec![20.into(), 60.into(), 40.into(), 25.into()]),
        Operation::new("f", vec![]),
        Operation::new("w", vec![2.into()]),
        Operation::new("m", vec![15.into(), 100.into()]),
        Operation::new("l", vec![90.into(), 130.into()]),
        Operation::new("S", vec![]),
    ];

    let mut d0 = Document::with_version("1.5");
    let p0 = page_from_ops(&mut d0, draw.clone(), dictionary! {});
    let plain = interpret_page(&d0, p0).expect("plain");

    let mut shifted_ops = vec![Operation::new(
        "cm",
        vec![1.into(), 0.into(), 0.into(), 1.into(), DX.into(), DY.into()],
    )];
    shifted_ops.extend(draw);
    let mut d1 = Document::with_version("1.5");
    let p1 = page_from_ops(&mut d1, shifted_ops, dictionary! {});
    let shifted = interpret_page(&d1, p1).expect("shifted");

    assert_eq!(
        plain.prims.len(),
        shifted.prims.len(),
        "translation changed the primitive count"
    );
    assert!(!plain.prims.is_empty(), "fixture emitted nothing");

    for (i, (a, b)) in plain.prims.iter().zip(shifted.prims.iter()).enumerate() {
        match (a, b) {
            (
                Prim::Fill { argb: c0, contours: k0, .. },
                Prim::Fill { argb: c1, contours: k1, .. },
            ) => {
                assert_eq!(c0, c1, "prim {i}: translation changed the fill colour");
                assert_eq!(k0.len(), k1.len(), "prim {i}: contour count changed");
                for (ca, cb) in k0.iter().zip(k1.iter()) {
                    assert_eq!(ca.len(), cb.len(), "prim {i}: vertex count changed");
                    for (pa, pb) in ca.iter().zip(cb.iter()) {
                        assert!(
                            ((pb.0 - pa.0) as f64 - DX).abs() < 1e-3
                                && ((pb.1 - pa.1) as f64 - DY).abs() < 1e-3,
                            "prim {i}: vertex moved by ({}, {}), expected ({DX}, {DY})",
                            pb.0 - pa.0,
                            pb.1 - pa.1
                        );
                    }
                }
            }
            (Prim::Stroke { pts: s0, width: w0, .. }, Prim::Stroke { pts: s1, width: w1, .. }) => {
                assert_eq!(w0, w1, "prim {i}: a pure translation must not change stroke width");
                assert_eq!(s0.len(), s1.len(), "prim {i}: point count changed");
                for (pa, pb) in s0.iter().zip(s1.iter()) {
                    assert!(
                        ((pb.0 - pa.0) as f64 - DX).abs() < 1e-3
                            && ((pb.1 - pa.1) as f64 - DY).abs() < 1e-3,
                        "prim {i}: point moved by ({}, {}), expected ({DX}, {DY})",
                        pb.0 - pa.0,
                        pb.1 - pa.1
                    );
                }
            }
            _ => panic!("prim {i}: translation changed the primitive kind"),
        }
    }
}
