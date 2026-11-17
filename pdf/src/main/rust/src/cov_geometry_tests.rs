//! Coverage tests for geometry helpers not exercised by geometry_part1:
//! inverses, UserUnit clamping, editor-space conversion, degenerate boxes.
use crate::*;

fn rect4(r: [f64; 4]) -> Object {
    Object::Array(vec![r[0].into(), r[1].into(), r[2].into(), r[3].into()])
}

fn page_doc(media: [f64; 4], rotate: Option<i64>, user_unit: Option<Object>) -> Document {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.new_object_id();
    let mut page = dictionary! { "Type" => "Page", "Parent" => pages_id, "MediaBox" => rect4(media) };
    if let Some(r) = rotate { page.set("Rotate", Object::Integer(r)); }
    if let Some(u) = user_unit { page.set("UserUnit", u); }
    let page_id = doc.add_object(page);
    doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
        "Type" => "Pages", "Kids" => Object::Array(vec![page_id.into()]), "Count" => 1 }));
    let catalog_id = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
    doc.trailer.set("Root", catalog_id);
    doc
}

#[test]
fn mat_mul_identity_and_translation_compose() {
    let m = [2.0, 0.0, 0.0, 3.0, 10.0, 20.0];
    assert_eq!(mat_mul(&IDENTITY, &m), m);
    assert_eq!(mat_mul(&m, &IDENTITY), m);
    // translate then scale-by-m: point (1,1) -> translate(10,20) -> (11,21) -> m? m1 applied first.
    let t = translate(5.0, 7.0);
    let c = mat_mul(&t, &m);
    assert_eq!(transform(&c, 1.0, 1.0), transform(&m, 6.0, 8.0));
    assert_eq!(transform(&translate(3.0, -2.0), 10.0, 10.0), (13.0, 8.0));
}

#[test]
fn mat_inverse_checked_rejects_singular_and_roundtrips() {
    assert_eq!(mat_inverse_checked(&[1.0, 2.0, 2.0, 4.0, 5.0, 6.0]), None);
    assert_eq!(mat_inverse_checked(&[0.0; 6]), None);
    assert_eq!(mat_inverse(&[0.0; 6]), IDENTITY, "singular falls back to identity");
    let m = [2.0, 0.5, -1.0, 3.0, 40.0, -7.0];
    let inv = mat_inverse_checked(&m).expect("invertible");
    let prod = mat_mul(&m, &inv);
    for i in 0..6 { assert!((prod[i] - IDENTITY[i]).abs() < 1e-9, "elem {i}: {prod:?}"); }
    let (x, y) = transform(&inv, 100.0, 50.0);
    let (rx, ry) = transform(&m, x, y);
    assert!((rx - 100.0).abs() < 1e-9 && (ry - 50.0).abs() < 1e-9);
    // Near-singular below epsilon is also refused.
    assert_eq!(mat_inverse_checked(&[1e-13, 0.0, 0.0, 1.0, 0.0, 0.0]), None);
}

#[test]
fn user_unit_clamps_to_spec_range() {
    let d = page_doc([0.0, 0.0, 100.0, 100.0], None, Some(Object::Real(0.5)));
    let pid = nth_page_id(&d, 0).unwrap();
    assert_eq!(user_unit(&d, pid), 1.0);
    let d = page_doc([0.0, 0.0, 100.0, 100.0], None, Some(Object::Real(99999.0)));
    let pid = nth_page_id(&d, 0).unwrap();
    assert_eq!(user_unit(&d, pid), 75000.0);
    let d = page_doc([0.0, 0.0, 100.0, 100.0], None, Some(Object::Integer(3)));
    let pid = nth_page_id(&d, 0).unwrap();
    assert_eq!(user_unit(&d, pid), 3.0);
    let d = page_doc([0.0, 0.0, 100.0, 100.0], None, None);
    let pid = nth_page_id(&d, 0).unwrap();
    assert_eq!(user_unit(&d, pid), 1.0, "missing UserUnit defaults to 1");
}

#[test]
fn media_box_rejects_non_finite_and_wrong_length() {
    let mut d = page_doc([0.0, 0.0, 100.0, 100.0], None, None);
    let pid = nth_page_id(&d, 0).unwrap();
    d.get_dictionary_mut(pid).unwrap().set("MediaBox",
        Object::Array(vec![0.0f32.into(), 0.0f32.into(), f32::INFINITY.into(), 10.0f32.into()]));
    assert_eq!(media_box(&d, pid), [0.0, 0.0, 612.0, 792.0], "non-finite -> Letter");
    d.get_dictionary_mut(pid).unwrap().set("MediaBox",
        Object::Array(vec![0.into(), 0.into(), 10.into()]));
    assert_eq!(media_box(&d, pid), [0.0, 0.0, 612.0, 792.0], "3-element -> Letter");
}

#[test]
fn page_rect_and_points_convert_editor_space_back() {
    // No rotation, MediaBox origin at (100,100): editor (0,0) == page (100,100).
    let d = page_doc([100.0, 100.0, 400.0, 500.0], None, None);
    assert_eq!(page_rect(&d, 0, [0.0, 0.0, 300.0, 400.0]), [100.0, 100.0, 400.0, 500.0]);
    // Inverted editor rect is normalized.
    assert_eq!(page_rect(&d, 0, [300.0, 400.0, 0.0, 0.0]), [100.0, 100.0, 400.0, 500.0]);
    let pts = page_points(&d, 0, &[0.0, 0.0, 300.0, 400.0, 42.0]);
    assert_eq!(pts, vec![100.0f32, 100.0, 400.0, 500.0], "odd trailing value dropped");
    assert!(page_points(&d, 0, &[]).is_empty());
    // Out-of-range page: identity fallback.
    assert_eq!(page_rect(&d, 99, [1.0, 2.0, 3.0, 4.0]), [1.0, 2.0, 3.0, 4.0]);
    assert_eq!(page_base_inverse(&d, -1), IDENTITY);
}

#[test]
fn page_rect_rotated_180() {
    let d = page_doc([0.0, 0.0, 300.0, 400.0], Some(180), None);
    // Display origin corresponds to page top-right (300,400).
    assert_eq!(page_rect(&d, 0, [0.0, 0.0, 300.0, 400.0]), [0.0, 0.0, 300.0, 400.0]);
    let pts = page_points(&d, 0, &[0.0, 0.0]);
    assert_eq!(pts, vec![300.0f32, 400.0]);
}

#[test]
fn normalize_rect_orders_corners() {
    assert_eq!(normalize_rect([10.0, 20.0, 0.0, 5.0]), [0.0, 5.0, 10.0, 20.0]);
    assert_eq!(normalize_rect([0.0, 5.0, 10.0, 20.0]), [0.0, 5.0, 10.0, 20.0]);
    assert_eq!(normalize_rect([1.0, 1.0, 1.0, 1.0]), [1.0, 1.0, 1.0, 1.0]);
}
