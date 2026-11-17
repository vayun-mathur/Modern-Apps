//! Synthetic-fixture golden tests: hand-built minimal PDFs that each exercise
//! exactly one round-1 fix, asserted against the primitives `interpret_page`
//! produces.
//!
//! Why these exist: the round-1 fixes were verified by reading code against ISO
//! 32000-1, not by rendering. The real-world fixtures the `issue321_tests`
//! harness drives cannot be committed (copyright), so nothing here can depend on
//! them. A 20-line generated PDF that exercises one feature is a better
//! regression test anyway — when it fails you know exactly what broke.
//!
//! House style, inherited from `tests.rs`: build the document with `lopdf`, run
//! the real pipeline, and assert on MEANING ("no visible ink was emitted in this
//! region") rather than on incidental structure (`prims.len() == 7`). Brittle
//! count assertions are how round 1 ended up with a mode-3 test that pinned an
//! obsolete contract.

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};

// ---------------------------------------------------------------------------
// Fixture builders

/// Assemble a one-page document around `content` (raw content-stream bytes, so
/// tests can hand-write things `Content::encode` cannot express, such as an
/// inline image with binary pixel data). `page` supplies the page-dictionary
/// entries under test (`MediaBox`, `Rotate`, `CropBox`, `Annots`, ...) and
/// `catalog` any catalog entries (`OCProperties`). Returns the page's id.
fn assemble(
    doc: &mut Document,
    content: Vec<u8>,
    resources: Dictionary,
    mut page: Dictionary,
    catalog: Dictionary,
) -> ObjectId {
    let content_id = doc.add_object(Stream::new(dictionary! {}, content));
    assemble_with_contents(doc, Object::Reference(content_id), resources, &mut page, catalog)
}

/// As [`assemble`], but the caller owns the `/Contents` value — used by the tests
/// that need a filtered stream, a corrupt stream, or no `/Contents` at all.
fn assemble_with_contents(
    doc: &mut Document,
    contents: Object,
    resources: Dictionary,
    page: &mut Dictionary,
    catalog: Dictionary,
) -> ObjectId {
    let pages_id = doc.new_object_id();
    page.set("Type", Object::Name(b"Page".to_vec()));
    page.set("Parent", pages_id);
    page.set("Resources", resources);
    if !matches!(contents, Object::Null) {
        page.set("Contents", contents);
    }
    if page.get(b"MediaBox").is_err() {
        page.set("MediaBox", vec![0.into(), 0.into(), 612.into(), 792.into()]);
    }
    let page_id = doc.add_object(page.clone());
    doc.objects.insert(
        pages_id,
        Object::Dictionary(dictionary! {
            "Type" => "Pages", "Kids" => vec![page_id.into()], "Count" => 1,
        }),
    );
    let mut cat = catalog;
    cat.set("Type", Object::Name(b"Catalog".to_vec()));
    cat.set("Pages", pages_id);
    let catalog_id = doc.add_object(cat);
    doc.trailer.set("Root", catalog_id);
    page_id
}

/// Shorthand for the common case: operator list, no page or catalog extras.
fn page_from_ops(doc: &mut Document, ops: Vec<Operation>, resources: Dictionary) -> ObjectId {
    let bytes = Content { operations: ops }.encode().unwrap();
    assemble(doc, bytes, resources, dictionary! {}, dictionary! {})
}

fn rect_ops(x: i64, y: i64, w: i64, h: i64) -> Vec<Operation> {
    vec![
        Operation::new("re", vec![x.into(), y.into(), w.into(), h.into()]),
        Operation::new("f", vec![]),
    ]
}

fn flate(data: &[u8]) -> Vec<u8> {
    use flate2::write::ZlibEncoder;
    use std::io::Write;
    let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    e.write_all(data).unwrap();
    e.finish().unwrap()
}

// ---------------------------------------------------------------------------
// Meaning-level assertions about the emitted primitives

/// Whether a primitive puts ink on the page that a user could see. Mode 3 and
/// mode 7 Text records are deliberately NOT ink (§9.3.6): they exist only so the
/// glyphs reach the text index. `outline: true` Text is likewise not ink — the
/// real outline was already emitted as Fill/Stroke prims alongside it.
fn is_ink(p: &Prim) -> bool {
    match p {
        Prim::Fill { argb, contours, .. } => (argb >> 24) != 0 && contours.iter().any(|c| c.len() >= 3),
        Prim::Stroke { argb, pts, .. } => (argb >> 24) != 0 && pts.len() >= 2,
        Prim::Text { argb, render_mode, outline, text, .. } => {
            !matches!(render_mode, 3 | 7) && (argb >> 24) != 0 && !*outline && !text.is_empty()
        }
        Prim::Image { alpha, data, w, h, .. } => *alpha > 0.0 && !data.is_empty() && *w > 0 && *h > 0,
        Prim::ImageTiled { alpha, data, w, h, nx, ny, .. } => {
            *alpha > 0.0 && !data.is_empty() && *w > 0 && *h > 0 && *nx > 0 && *ny > 0
        }
        _ => false,
    }
}

/// The device-space bounding box each inking primitive covers.
///
/// Deliberately area-based rather than vertex-based. Sampling only a shape's
/// vertices misses a shape that COVERS a region without having any vertex inside
/// it — a page-sized fill, or an `ImageTiled` lattice whose corners fall outside
/// the query — and for a "no ink here" assertion that is a false negative, i.e. a
/// vacuous pass. Over-approximating with a bbox can only ever cause a false
/// FAILURE, which is the safe direction for the assertions built on this.
fn ink_boxes(prims: &[Prim]) -> Vec<[f32; 4]> {
    let quad_box = |ctm: &Mat, corners: [(f64, f64); 4]| -> [f32; 4] {
        let pts: Vec<(f32, f32)> = corners
            .iter()
            .map(|&(u, v)| {
                let (dx, dy) = transform(ctm, u, v);
                (dx as f32, dy as f32)
            })
            .collect();
        bbox_of(&pts)
    };
    let mut out = Vec::new();
    for p in prims.iter().filter(|p| is_ink(p)) {
        match p {
            Prim::Fill { contours, .. } => {
                let pts: Vec<(f32, f32)> = contours.iter().flatten().copied().collect();
                if !pts.is_empty() {
                    out.push(bbox_of(&pts));
                }
            }
            Prim::Stroke { pts, .. } => {
                if !pts.is_empty() {
                    out.push(bbox_of(pts));
                }
            }
            // A glyph's origin, widened by its advance and nominal height, so a text
            // run is treated as the area it occupies rather than a single point.
            Prim::Text { x, y, size, advance, .. } => {
                out.push([*x, *y, *x + advance.max(*size * 0.5), *y + *size])
            }
            Prim::Image { ctm, .. } => {
                out.push(quad_box(ctm, [(0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0)]))
            }
            // `ctm` maps the unit square onto ONE cell, so cell (i,j) is that square
            // offset by (i,j) and the painted area is the whole lattice extent.
            Prim::ImageTiled { ctm, i0, j0, nx, ny, .. } => {
                let (i1, j1) = ((*i0 + *nx as i32) as f64, (*j0 + *ny as i32) as f64);
                let (i0, j0) = (*i0 as f64, *j0 as f64);
                out.push(quad_box(ctm, [(i0, j0), (i1, j0), (i1, j1), (i0, j1)]));
            }
            _ => {}
        }
    }
    out
}

/// True when some primitive paints anywhere inside `[x0,x1) x [y0,y1)`.
fn ink_in_region(prims: &[Prim], x0: f32, y0: f32, x1: f32, y1: f32) -> bool {
    ink_boxes(prims)
        .iter()
        .any(|b| b[0] < x1 && b[2] > x0 && b[1] < y1 && b[3] > y0)
}

fn text_of(prims: &[Prim]) -> String {
    prims
        .iter()
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect()
}

fn count<F: Fn(&Prim) -> bool>(prims: &[Prim], f: F) -> usize {
    prims.iter().filter(|p| f(p)).count()
}

/// Signed area of a polygon. A rectangle given in the spec's `/QuadPoints`
/// order collapses to ~0 if the vertices are consumed in file order, because
/// UL,UR,LL,LR traces a self-intersecting bow-tie.
fn polygon_area(pts: &[(f32, f32)]) -> f64 {
    let p: Vec<(f64, f64)> = pts.iter().map(|&(x, y)| (x as f64, y as f64)).collect();
    shoelace_area(&p).abs()
}

/// A soft mask carrying a `/TR` must still produce a well-formed bracket. Pinned
///
/// Build a page whose content is wrapped in `BDC /OC`, with the OCG switched OFF
/// via `/OCProperties /D /OFF`. `inner` is spliced inside the hidden region and
/// `after` follows the closing `EMC`.
fn hidden_ocg_page(
    doc: &mut Document,
    inner: Vec<Operation>,
    after: Vec<Operation>,
    off: bool,
) -> ObjectId {
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("Layer") });
    let mut ops = vec![Operation::new(
        "BDC",
        vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())],
    )];
    ops.extend(inner);
    ops.push(Operation::new("EMC", vec![]));
    ops.extend(after);
    let bytes = Content { operations: ops }.encode().unwrap();
    let d = if off {
        dictionary! { "OFF" => vec![ocg_id.into()] }
    } else {
        dictionary! { "ON" => vec![ocg_id.into()] }
    };
    let catalog = dictionary! {
        "OCProperties" => dictionary! { "OCGs" => vec![ocg_id.into()], "D" => d },
    };
    let resources = dictionary! {
        "Properties" => dictionary! { "OC0" => ocg_id },
    };
    assemble(doc, bytes, resources, dictionary! {}, catalog)
}

/// §8.11.4.5: content inside a hidden OCG is not drawn, and a plain BMC/EMC pair
/// nested inside it must not pop the hidden frame early. The round-1 bug popped
/// on the inner EMC, so everything from there to the end of the page un-hid.
#[test]
fn nested_bmc_inside_hidden_ocg_does_not_unhide_the_rest_of_the_region() {
    let mut doc = Document::with_version("1.6");
    let mut inner = rect_ops(10, 10, 40, 40); // hidden, before the nested BMC
    inner.push(Operation::new("BMC", vec![Object::Name(b"Tx".to_vec())]));
    inner.extend(rect_ops(60, 10, 40, 40)); // hidden, inside the nested BMC
    inner.push(Operation::new("EMC", vec![]));
    inner.extend(rect_ops(110, 10, 40, 40)); // hidden, AFTER the nested EMC
    let after = rect_ops(300, 300, 40, 40); // visible, after the OCG region
    let page_id = hidden_ocg_page(&mut doc, inner, after, true);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 200.0, 100.0),
        "no ink may be painted anywhere in the hidden OCG region; the nested \
         BMC/EMC pair must not pop the hidden frame"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "content after the OCG region is unaffected and must still paint"
    );
}

/// Control for the test above: the same page with the OCG switched ON must paint
/// everything, so a green run cannot come from suppressing the whole page.
#[test]
fn visible_ocg_region_paints_all_of_its_content() {
    let mut doc = Document::with_version("1.6");
    let mut inner = rect_ops(10, 10, 40, 40);
    inner.push(Operation::new("BMC", vec![Object::Name(b"Tx".to_vec())]));
    inner.extend(rect_ops(60, 10, 40, 40));
    inner.push(Operation::new("EMC", vec![]));
    inner.extend(rect_ops(110, 10, 40, 40));
    let page_id = hidden_ocg_page(&mut doc, inner, rect_ops(300, 300, 40, 40), false);

    let page = interpret_page(&doc, page_id).expect("interpret");
    for (x, label) in [(10.0, "before nested BMC"), (60.0, "inside nested BMC"), (110.0, "after nested EMC")] {
        assert!(
            ink_in_region(&page.prims, x, 10.0, x + 41.0, 51.0),
            "visible OCG: the rect {label} must paint"
        );
    }
}

/// §8.11.4.3 Table 101 lists `/BaseState`, then `/ON`, then `/OFF`, and applying
/// them in that order means `/OFF` wins for a group named by both arrays — which
/// is also what mainstream viewers do, so a file authored against them hides what
/// its author expected to be hidden.
#[test]
fn a_group_in_both_on_and_off_is_hidden() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let mut ops = vec![Operation::new(
        "BDC",
        vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())],
    )];
    ops.extend(rect_ops(10, 10, 40, 40));
    ops.push(Operation::new("EMC", vec![]));
    ops.extend(rect_ops(300, 300, 40, 40));
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! {
                "ON" => vec![ocg_id.into()],
                "OFF" => vec![ocg_id.into()],
            },
        },
    };
    let resources = dictionary! { "Properties" => dictionary! { "OC0" => ocg_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 60.0, 60.0),
        "/OFF is applied after /ON, so a group in both must be hidden"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "content outside the OCG region must still paint"
    );
}

/// §14.6.2: an unbalanced `EMC` must be discarded, not allowed to pop a frame it
/// does not own — otherwise a stray EMC un-hides the hidden region that follows.
#[test]
fn unbalanced_emc_cannot_pop_a_frame_it_does_not_own() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let mut ops = vec![
        // Stray EMC with nothing open.
        Operation::new("EMC", vec![]),
        Operation::new("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())]),
    ];
    ops.extend(rect_ops(10, 10, 40, 40)); // hidden
    ops.push(Operation::new("EMC", vec![]));
    ops.push(Operation::new("EMC", vec![])); // second stray EMC
    ops.extend(rect_ops(300, 300, 40, 40)); // visible
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
        !ink_in_region(&page.prims, 0.0, 0.0, 100.0, 100.0),
        "a stray EMC before the BDC must not leave the hidden region un-hidden"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "a stray EMC after the region must not suppress later content either"
    );
}

/// §8.11.4.5 applies to *all* marked content, text included: a `Tj` inside a
/// hidden OCG must put no ink on the page. (The glyphs may still reach the text
/// index — that is what mode-3 text does — but they must not be painted.)
#[test]
fn hidden_ocg_suppresses_text_as_well_as_paths() {
    let mut doc = Document::with_version("1.6");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let ops = vec![
        Operation::new("BDC", vec![Object::Name(b"OC".to_vec()), Object::Name(b"OC0".to_vec())]),
        Operation::new("BT", vec![]),
        Operation::new("Tf", vec![Object::Name(b"F1".to_vec()), 12.into()]),
        Operation::new("Td", vec![50.into(), 50.into()]),
        Operation::new("Tj", vec![Object::string_literal("HIDDEN")]),
        Operation::new("ET", vec![]),
        Operation::new("EMC", vec![]),
    ];
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! {
        "Font" => dictionary! { "F1" => font_id },
        "Properties" => dictionary! { "OC0" => ocg_id },
    };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    let painted: Vec<&str> = page
        .prims
        .iter()
        .filter(|p| is_ink(p))
        .filter_map(|p| match p {
            Prim::Text { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect();
    assert!(
        painted.is_empty(),
        "text inside a hidden OCG must not be painted; got {painted:?}"
    );
}

/// §8.11.4.2: an XObject carrying its own `/OC` is skipped wholesale when that
/// optional content is off — the form's content never runs.
#[test]
fn form_xobject_with_hidden_oc_paints_nothing() {
    let mut doc = Document::with_version("1.6");
    let ocg_id = doc.add_object(dictionary! { "Type" => "OCG", "Name" => Object::string_literal("L") });
    let form_content = Content { operations: rect_ops(0, 0, 80, 80) };
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 80.into(), 80.into()],
            "OC" => ocg_id,
        },
        form_content.encode().unwrap(),
    ));
    let mut ops = vec![Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())])];
    ops.extend(rect_ops(300, 300, 40, 40));
    let bytes = Content { operations: ops }.encode().unwrap();
    let catalog = dictionary! {
        "OCProperties" => dictionary! {
            "OCGs" => vec![ocg_id.into()],
            "D" => dictionary! { "OFF" => vec![ocg_id.into()] },
        },
    };
    let resources = dictionary! { "XObject" => dictionary! { "Fm0" => form_id } };
    let page_id = assemble(&mut doc, bytes, resources, dictionary! {}, catalog);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        !ink_in_region(&page.prims, 0.0, 0.0, 100.0, 100.0),
        "a form XObject with /OC off must not paint"
    );
    assert!(
        ink_in_region(&page.prims, 300.0, 300.0, 341.0, 341.0),
        "the rest of the page still paints"
    );
}

// ===========================================================================
// 1. Optional content — §14.6 marked content, §8.11 optional content groups

include!("golden_tests_part1.rs");
include!("golden_tests_part2.rs");
include!("golden_tests_part3.rs");
include!("golden_tests_part4.rs");
include!("golden_tests_part5.rs");
include!("golden_tests_part6.rs");
include!("golden_tests_part7.rs");