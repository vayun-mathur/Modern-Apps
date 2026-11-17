//! Coverage tests for docedit.rs handle-level page operations.
use crate::*;

fn doc_with_pages(n: usize) -> i64 {
    let h = create_empty_document();
    for _ in 0..n {
        // 1x1 white jpeg-ish bytes; content is never decoded by these ops.
        assert_eq!(append_image_page(h, &[0xff, 0xd8, 0xff, 0xd9], 10, 20), 1);
    }
    h
}
fn page_count(h: i64) -> usize {
    let reg = registry().lock().unwrap();
    reg.get(&h).map(|d| d.get_pages().len()).unwrap_or(0)
}

#[test]
fn create_empty_document_is_valid_and_savable() {
    let h = create_empty_document();
    assert_eq!(page_count(h), 0);
    let bytes = save_document(h).expect("empty doc saves");
    assert!(bytes.starts_with(b"%PDF"));
    close_document(h);
}

#[test]
fn pages_root_of_bare_document_is_none() {
    let doc = Document::with_version("1.7");
    assert_eq!(pages_root(&doc), None);
}

#[test]
fn append_image_page_rejects_bad_args() {
    assert_eq!(append_image_page(999_999, &[1], 10, 10), 0, "unknown handle");
    let h = create_empty_document();
    assert_eq!(append_image_page(h, &[1], 0, 10), 0);
    assert_eq!(append_image_page(h, &[1], 10, 0), 0);
    assert_eq!(page_count(h), 0);
    close_document(h);
}

#[test]
fn move_remove_rotate_pages() {
    let h = doc_with_pages(3);
    assert_eq!(page_count(h), 3);
    assert!(move_page(h, 0, 2));
    assert!(!move_page(h, 0, 3), "to out of range");
    assert!(!move_page(h, 5, 0), "from out of range");
    assert!(!move_page(999_999, 0, 0));
    // Rotate by 90 twice = 180; overflow-sized delta must not panic/wrap.
    assert!(rotate_page(h, 0, 90));
    assert!(rotate_page(h, 0, i32::MAX));
    assert!(!rotate_page(h, 99, 90));
    assert!(!rotate_page(h, -1, 90));
    assert!(!rotate_page(999_999, 0, 90));
    assert!(remove_page(h, 1));
    assert_eq!(page_count(h), 2);
    assert!(!remove_page(h, 2));
    assert!(!remove_page(999_999, 0));
    close_document(h);
}

#[test]
fn extract_page_produces_standalone_pdf() {
    let h = doc_with_pages(2);
    let one = extract_page(h, 1).expect("extract page 1");
    assert!(one.starts_with(b"%PDF"));
    let doc = Document::load_mem(&one).expect("extracted parses");
    assert_eq!(doc.get_pages().len(), 1);
    assert!(extract_page(h, 2).is_none());
    assert!(extract_page(h, -1).is_none());
    assert!(extract_page(999_999, 0).is_none());
    close_document(h);
}

#[test]
fn append_pdf_merges_and_rejects_garbage() {
    let src = doc_with_pages(2);
    let bytes = save_document(src).unwrap();
    close_document(src);
    let dest = create_empty_document();
    assert_eq!(append_pdf(dest, &bytes), 2);
    assert_eq!(page_count(dest), 2);
    assert_eq!(append_pdf(dest, b"not a pdf at all"), 0);
    assert_eq!(append_pdf(dest, &[]), 0);
    assert_eq!(append_pdf(999_999, &bytes), 0);
    close_document(dest);
}

#[test]
fn render_page_and_save_compressed_guards() {
    let h = doc_with_pages(1);
    assert!(render_page(h, -1).is_none(), "negative index must not panic");
    assert!(render_page(h, 5).is_none());
    assert!(render_page(999_999, 0).is_none());
    let saved = save_compressed(h).expect("compressed save");
    assert!(saved.starts_with(b"%PDF"));
    assert!(save_compressed(999_999).is_none());
    close_document(h);
}

#[test]
fn remap_object_rewrites_nested_refs_and_streams() {
    let mut map = HashMap::new();
    map.insert((1, 0), (10, 0));
    map.insert((2, 0), (20, 0));
    let inner = dictionary! { "A" => Object::Reference((1, 0)), "N" => 42 };
    let mut st = Stream::new(inner.clone(), vec![1, 2, 3]);
    st.dict.set("S", Object::Reference((2, 0)));
    let obj = Object::Array(vec![Object::Reference((1, 0)), Object::Reference((99, 0)), Object::Stream(st)]);
    let out = remap_object(&obj, &map);
    let arr = out.as_array().unwrap();
    assert_eq!(arr[0], Object::Reference((10, 0)));
    assert_eq!(arr[1], Object::Reference((99, 0)), "unmapped ref kept");
    let s = arr[2].as_stream().unwrap();
    assert_eq!(s.dict.get(b"S").unwrap(), &Object::Reference((20, 0)));
    assert_eq!(s.content, vec![1, 2, 3], "stream body untouched");
    // Scalars clone unchanged.
    assert_eq!(remap_object(&Object::Integer(7), &map), Object::Integer(7));
    let d = remap_object(&Object::Dictionary(inner), &map);
    assert_eq!(d.as_dict().unwrap().get(b"A").unwrap(), &Object::Reference((10, 0)));
}

#[test]
fn append_kid_handles_indirect_kids_array() {
    let mut doc = Document::with_version("1.7");
    let kids_id = doc.add_object(Object::Array(vec![]));
    let pages_id = doc.add_object(dictionary! {
        "Type" => "Pages", "Kids" => Object::Reference(kids_id), "Count" => 0 });
    let page_id = doc.add_object(dictionary! { "Type" => "Page", "Parent" => pages_id });
    append_kid(&mut doc, pages_id, page_id);
    append_kid(&mut doc, pages_id, page_id);
    let kids = doc.get_object(kids_id).unwrap().as_array().unwrap();
    assert_eq!(kids.len(), 2);
    let cnt = doc.get_dictionary(pages_id).unwrap().get(b"Count").unwrap().as_i64().unwrap();
    assert_eq!(cnt, 2);
}
