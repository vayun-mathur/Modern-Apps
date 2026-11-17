//! Coverage tests for decrypt.rs object transforms and password probing.
use crate::*;

#[test]
fn crypt_object_transforms_strings_streams_and_nesting() {
    let rev = |b: &[u8]| -> Vec<u8> { b.iter().rev().copied().collect() };
    let mut st = Stream::new(dictionary! { "K" => Object::string_literal("ab") }, vec![1, 2, 3]);
    let mut obj = Object::Dictionary({
        let mut d = Dictionary::new();
        d.set("S", Object::string_literal("hello"));
        d.set("N", Object::Integer(42));
        d.set("Arr", Object::Array(vec![Object::string_literal("xy"), Object::Stream(st.clone())]));
        d
    });
    crypt_object(&mut obj, &rev);
    let d = obj.as_dict().unwrap();
    // Transformed strings become hex format (binary safety).
    match d.get(b"S").unwrap() { Object::String(s, fmt) => {
        assert_eq!(s, b"olleh"); assert_eq!(*fmt, lopdf::StringFormat::Hexadecimal);
    } _ => panic!("expected string") }
    assert_eq!(d.get(b"N").unwrap(), &Object::Integer(42), "scalars untouched");
    let arr = d.get(b"Arr").unwrap().as_array().unwrap();
    match &arr[1] { Object::Stream(s) => {
        assert_eq!(s.content, vec![3, 2, 1]);
        match s.dict.get(b"K").unwrap() { Object::String(s, _) => assert_eq!(s, b"ba"), _ => panic!() }
    } _ => panic!("expected stream") }
    let _ = &mut st;
}

#[test]
fn trailer_id_lifecycle() {
    let mut doc = Document::with_version("1.7");
    assert!(trailer_id0(&doc).is_empty(), "missing ID yields empty, not synthesized");
    let id1 = ensure_trailer_id(&mut doc, b"seed");
    assert_eq!(id1.len(), 16);
    assert_eq!(trailer_id0(&doc), id1);
    let id2 = ensure_trailer_id(&mut doc, b"other");
    assert_eq!(id1, id2, "existing ID is preserved");
    // Pre-seeded ID is honoured verbatim.
    let mut doc2 = Document::with_version("1.7");
    doc2.trailer.set("ID", Object::Array(vec![
        Object::String(b"0123456789abcdef".to_vec(), lopdf::StringFormat::Hexadecimal),
        Object::String(b"0123456789abcdef".to_vec(), lopdf::StringFormat::Hexadecimal)]));
    assert_eq!(ensure_trailer_id(&mut doc2, b"x"), b"0123456789abcdef".to_vec());
}

#[test]
fn password_state_for_plain_and_garbage() {
    assert_eq!(pdf_password_state(b""), 0);
    assert_eq!(pdf_password_state(b"garbage bytes, no pdf here"), 0);
    assert_eq!(pdf_password_state(&[0xff; 64]), 0);
    let h = create_empty_document();
    assert_eq!(append_image_page(h, &[0xff, 0xd8], 4, 4), 1);
    let bytes = save_document(h).unwrap();
    close_document(h);
    assert_eq!(pdf_password_state(&bytes), 0, "unencrypted doc needs no password");
    // Truncations of a valid file must not panic the probe.
    for n in [0, 1, 10, 100, bytes.len() / 2] {
        assert_eq!(pdf_password_state(&bytes[..n]), 0);
    }
}

#[test]
fn apply_alpha_to_argb_scales_only_alpha() {
    assert_eq!(apply_alpha_to_argb(0xFF112233, 1.0), 0xFF112233);
    assert_eq!(apply_alpha_to_argb(0xFF112233, 0.5), 0x80112233);
    assert_eq!(apply_alpha_to_argb(0xFF112233, 0.0), 0x00112233);
    assert_eq!(apply_alpha_to_argb(0xFF112233, 2.0), 0xFF112233, "clamped to 255");
    assert_eq!(apply_alpha_to_argb(0x80112233, 0.5), 0x40112233);
    // rgb/gray/cmyk conversion clamps out-of-range components.
    assert_eq!(rgb_to_argb(2.0, -1.0, 0.5), 0xFF00_0080 | 0xFF0000);
    assert_eq!(gray_to_argb(1.0), 0xFFFFFFFF);
    assert_eq!(gray_to_argb(0.0), 0xFF000000);
    assert_eq!(cmyk_to_argb(0.0, 0.0, 0.0, 0.0), 0xFFFFFFFF);
    assert_eq!(cmyk_to_argb(0.0, 0.0, 0.0, 1.0), 0xFF000000);
    assert_eq!(cmyk_to_argb(1.0, 0.0, 0.0, 0.0), 0xFF00FFFF);
}
