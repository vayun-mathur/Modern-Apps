//! Coverage tests for annotation helpers: matrices, colours, ids, DA parsing.
use crate::*;

#[test]
fn appearance_fit_matrix_identity_and_scale() {
    // BBox exactly equals Rect with identity matrix -> identity fit.
    let m = appearance_fit_matrix([0.0, 0.0, 100.0, 50.0], [0.0, 0.0, 100.0, 50.0], IDENTITY);
    assert_eq!(m, IDENTITY);
    // BBox half the Rect -> scale by 2.
    let m = appearance_fit_matrix([0.0, 0.0, 100.0, 50.0], [0.0, 0.0, 50.0, 25.0], IDENTITY);
    assert_eq!(m, [2.0, 0.0, 0.0, 2.0, 0.0, 0.0]);
    // Offset BBox: origin maps onto Rect origin.
    let m = appearance_fit_matrix([10.0, 20.0, 110.0, 70.0], [5.0, 5.0, 105.0, 55.0], IDENTITY);
    let (x, y) = transform(&m, 5.0, 5.0);
    assert!((x - 10.0).abs() < 1e-9 && (y - 20.0).abs() < 1e-9);
    // Inverted Rect corners are normalized.
    let m2 = appearance_fit_matrix([110.0, 70.0, 10.0, 20.0], [5.0, 5.0, 105.0, 55.0], IDENTITY);
    assert_eq!(m, m2);
}

#[test]
fn appearance_fit_matrix_degenerate_bbox_does_not_divide_by_zero() {
    let m = appearance_fit_matrix([0.0, 0.0, 10.0, 10.0], [5.0, 5.0, 5.0, 5.0], IDENTITY);
    assert!(m.iter().all(|v| v.is_finite()), "got {m:?}");
    assert_eq!((m[0], m[3]), (1.0, 1.0), "zero-size dimension falls back to scale 1");
}

#[test]
fn appearance_matrix_composes_form_matrix_once() {
    let mat = translate(7.0, 9.0);
    let aa = appearance_matrix([0.0, 0.0, 100.0, 50.0], [0.0, 0.0, 100.0, 50.0], mat);
    // Form origin (0,0) -> translate -> (7,9) -> fit onto Rect maps BBox(7,9)..(107,59)->(0,0)..(100,50)
    let (x, y) = transform(&aa, 0.0, 0.0);
    assert!((x - 0.0).abs() < 1e-9 && (y - 0.0).abs() < 1e-9, "got ({x},{y})");
}

#[test]
fn annot_color_rgb_gray_cmyk_and_absent() {
    let doc = Document::with_version("1.7");
    let mut d = Dictionary::new();
    assert_eq!(annot_color(&doc, &d), 0xFF000000, "absent /C defaults to opaque black");
    d.set("C", Object::Array(vec![1.0f32.into(), 0.0f32.into(), 0.0f32.into()]));
    assert_eq!(annot_color(&doc, &d), 0xFFFF0000);
    // §12.5.2 Table 166: /C may hold 1 (gray), 3 (RGB) or 4 (CMYK) numbers.
    d.set("C", Object::Array(vec![1.0f32.into()]));
    assert_eq!(annot_color(&doc, &d), 0xFFFFFFFF, "gray 1.0 is white, not black");
    d.set("C", Object::Array(vec![0.0f32.into()]));
    assert_eq!(annot_color(&doc, &d), 0xFF000000, "gray 0.0 is black");
    d.set("C", Object::Array(vec![0.0f32.into(), 0.0f32.into(), 0.0f32.into(), 0.0f32.into()]));
    assert_eq!(annot_color(&doc, &d), 0xFFFFFFFF, "CMYK 0,0,0,0 is white");
    d.set("C", Object::Array(vec![1.0f32.into(), 0.0f32.into(), 0.0f32.into(), 0.0f32.into()]));
    assert_eq!(annot_color(&doc, &d), 0xFF00FFFF, "CMYK cyan");
    // Empty array = transparent (no colour): alpha must be zero, not opaque black.
    d.set("C", Object::Array(vec![]));
    assert_eq!(annot_color(&doc, &d) >> 24, 0, "empty /C is transparent");
}

#[test]
fn subtype_codes_cover_table() {
    assert_eq!(subtype_code(b"FreeText"), 1);
    assert_eq!(subtype_code(b"Highlight"), 2);
    assert_eq!(subtype_code(b"Link"), 15);
    assert_eq!(subtype_code(b"3D"), 26);
    assert_eq!(subtype_code(b"Nope"), 0);
    assert_eq!(subtype_code(b""), 0);
}

#[test]
fn parse_da_size_extracts_tf_operand() {
    assert_eq!(parse_da_size(b"/Helv 12 Tf 0 g"), Some(12.0));
    assert_eq!(parse_da_size(b"/Helv 10.5 Tf"), Some(10.5));
    assert_eq!(parse_da_size(b"0 g"), None);
    assert_eq!(parse_da_size(b"Tf 12"), None, "Tf first has no size operand");
    assert_eq!(parse_da_size(b"/Helv abc Tf"), None);
    assert_eq!(parse_da_size(b""), None);
}

#[test]
fn id_encode_decode_roundtrip() {
    for id in [(1u32, 0u16), (42, 3), (65535, 65535), (70000, 1)] {
        assert_eq!(decode_id(encode_id(id)), id);
    }
    assert_eq!(encode_id((1, 0)), 65536);
}

#[test]
fn escape_and_decode_pdf_text() {
    assert_eq!(escape_pdf_literal("a(b)c\\d\ne\tf"), "a\\(b\\)c\\\\d\\ne\\tf");
    assert_eq!(escape_pdf_literal("plain"), "plain");
    assert_eq!(decode_pdf_text(&[0xFE, 0xFF, 0x00, b'A', 0x00, b'B']), "AB");
    assert_eq!(decode_pdf_text(&[0xFF, 0xFE, b'A', 0x00]), "A");
    assert_eq!(decode_pdf_text(b"abc"), "abc");
    assert_eq!(decode_pdf_text(&[]), "");
    // Odd trailing byte in UTF-16BE must not panic.
    assert_eq!(decode_pdf_text(&[0xFE, 0xFF, 0x00]), "\u{0}");
}

#[test]
fn offset_flat_shifts_alternating_axes() {
    let mut arr = ints(&[0, 0, 10, 20]);
    offset_flat(&mut arr, 5.0, -5.0);
    let v: Vec<f64> = arr.iter().map(|o| num(o).unwrap()).collect();
    assert_eq!(v, vec![5.0, -5.0, 15.0, 15.0]);
    let mut empty: Vec<Object> = vec![];
    offset_flat(&mut empty, 1.0, 1.0); // no panic
}
fn ints(v: &[i64]) -> Vec<Object> { v.iter().map(|n| Object::Integer(*n)).collect() }

#[test]
fn set_alpha_and_shape_border() {
    let mut d = Dictionary::new();
    set_alpha(&mut d, 0xFF000000);
    assert!(d.get(b"CA").is_err(), "opaque alpha writes nothing");
    set_alpha(&mut d, 0x80000000);
    match d.get(b"CA").unwrap() { Object::Real(r) => assert!((*r - 0.502).abs() < 0.01), _ => panic!() }
    let mut s = Dictionary::new();
    set_shape_border(&mut s, 0xFF00FF00, 2.0, false);
    assert!(s.get(b"IC").is_err(), "stroked shape has no interior colour");
    let mut f = Dictionary::new();
    set_shape_border(&mut f, 0xFF00FF00, 2.0, true);
    assert!(f.get(b"IC").is_ok(), "filled shape carries /IC");
}

#[test]
fn annotation_add_and_delete_roundtrip() {
    let h = create_empty_document();
    assert_eq!(append_image_page(h, &[0xff, 0xd8], 100, 100), 1);
    let id = add_highlight(h, 0, [10.0, 10.0, 100.0, 50.0], 0x80FF0000).expect("highlight");
    assert!(id != 0);
    assert!(add_highlight(h, 99, [0.0, 0.0, 1.0, 1.0], 0xFFFF0000).is_none());
    assert!(add_highlight(999_999, 0, [0.0, 0.0, 1.0, 1.0], 0xFFFF0000).is_none());
    let dup = duplicate_annotation(h, 0, id, 5.0, 5.0);
    assert!(dup != 0 && dup != id);
    assert!(delete_annotation(h, 0, dup));
    assert!(delete_annotation(h, 0, id));
    assert!(!delete_annotation(h, 0, id), "second delete finds no ref");
    assert_eq!(duplicate_annotation(h, 0, id, 0.0, 0.0), 0, "deleted annot cannot duplicate");
    close_document(h);
}
