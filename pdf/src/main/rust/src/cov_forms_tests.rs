//! Coverage tests for forms destination resolution and color component packing.
use crate::*;

fn doc_with_dests() -> (Document, ObjectId) {
    let mut doc = Document::with_version("1.7");
    let pages_id = doc.new_object_id();
    let page_id = doc.add_object(dictionary! { "Type" => "Page", "Parent" => pages_id });
    doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
        "Type" => "Pages", "Kids" => Object::Array(vec![page_id.into()]), "Count" => 1 }));
    let mut dests = Dictionary::new();
    dests.set("chap1", Object::Array(vec![Object::Reference(page_id), name_obj("XYZ")]));
    let catalog_id = doc.add_object(dictionary! {
        "Type" => "Catalog", "Pages" => pages_id, "Dests" => Object::Dictionary(dests) });
    doc.trailer.set("Root", catalog_id);
    (doc, page_id)
}

#[test]
fn resolve_dest_direct_named_and_unknown() {
    let (doc, page_id) = doc_with_dests();
    let mut pi = HashMap::new();
    pi.insert(page_id, 3i32);
    let direct = Object::Array(vec![Object::Reference(page_id), name_obj("Fit")]);
    assert_eq!(resolve_dest(&doc, &direct, &pi), 3);
    assert_eq!(resolve_dest(&doc, &name_obj("chap1"), &pi), 3, "named dest via /Dests");
    assert_eq!(resolve_dest(&doc, &Object::string_literal("chap1"), &pi), 3);
    assert_eq!(resolve_dest(&doc, &name_obj("missing"), &pi), -1);
    assert_eq!(resolve_dest(&doc, &Object::Integer(1), &pi), -1);
    assert_eq!(resolve_dest(&doc, &Object::Array(vec![]), &pi), -1);
    // Page not in index -> -1, not a panic.
    assert_eq!(resolve_dest(&doc, &direct, &HashMap::new()), -1);
}

#[test]
fn dest_array_unwraps_dict_d() {
    let (doc, page_id) = doc_with_dests();
    let inner = vec![Object::Reference(page_id)];
    let arr = Object::Array(inner.clone());
    assert_eq!(dest_array(&doc, &arr), Some(inner.clone()));
    let wrapped = Object::Dictionary(dictionary! { "D" => arr.clone() });
    assert_eq!(dest_array(&doc, &wrapped), Some(inner));
    assert_eq!(dest_array(&doc, &Object::Integer(5)), None);
    assert_eq!(named_dest(&doc, b"nope"), None);
}

#[test]
fn comps_to_rgb_all_arities_and_short_input() {
    assert_eq!(comps_to_rgb(&[10, 20, 30], 3), (10, 20, 30));
    assert_eq!(comps_to_rgb(&[0, 0, 0, 0], 4), (255, 255, 255));
    assert_eq!(comps_to_rgb(&[0, 0, 0, 255], 4), (0, 0, 0));
    assert_eq!(comps_to_rgb(&[255, 0, 0, 0], 4), (0, 255, 255));
    assert_eq!(comps_to_rgb(&[128], 1), (128, 128, 128));
    assert_eq!(comps_to_rgb(&[], 1), (0, 0, 0));
    assert_eq!(comps_to_rgb(&[77, 99], 9), (77, 77, 77), "DeviceN fallback uses first comp");
    // Truncated component slices must never panic (decoder robustness).
    assert_eq!(comps_to_rgb(&[10], 3), (10, 0, 0));
    assert_eq!(comps_to_rgb(&[10, 20], 4), (245, 235, 255));
    assert_eq!(comps_to_rgb(&[], 3), (0, 0, 0));
    assert_eq!(comps_to_rgb(&[], 4), (255, 255, 255));
}
