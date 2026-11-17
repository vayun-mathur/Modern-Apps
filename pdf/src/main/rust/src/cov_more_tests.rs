//! Coverage tests for redaction operator rewriting and matte handling.
use crate::*;
use lopdf::content::Operation;

fn op(operator: &str, operands: Vec<Object>) -> Operation {
    Operation { operator: operator.to_string(), operands }
}
fn txt(s: &str) -> Object { Object::string_literal(s) }
fn i(n: i64) -> Object { Object::Integer(n) }

#[test]
fn approx_text_len_counts_string_bytes() {
    assert_eq!(approx_text_len(&op("Tj", vec![txt("hello")])), 5.0);
    assert_eq!(approx_text_len(&op("Tj", vec![])), 0.0);
    assert_eq!(approx_text_len(&op("Tj", vec![Object::Integer(3)])), 0.0);
    let tj = op("TJ", vec![Object::Array(vec![txt("ab"), Object::Integer(-100), txt("cde")])]);
    assert_eq!(approx_text_len(&tj), 5.0, "TJ sums string elements only");
    assert_eq!(approx_text_len(&op("TJ", vec![txt("x")])), 0.0, "TJ without array is 0");
    // Non-TJ finds the LAST string operand.
    assert_eq!(approx_text_len(&op("'", vec![txt("ab"), txt("abcd")])), 4.0);
}

#[test]
fn redact_operations_drops_text_inside_rect_keeps_outside() {
    let ops = vec![
        op("BT", vec![]),
        op("Tm", vec![i(1),i(0),i(0),i(1),i(10),i(10)]),
        op("Tj", vec![txt("secret")]),
        op("Tm", vec![i(1),i(0),i(0),i(1),i(500),i(500)]),
        op("Tj", vec![txt("public")]),
        op("ET", vec![]),
    ];
    let out = redact_operations(ops, &[[0.0, 0.0, 100.0, 100.0]]);
    let names: Vec<&str> = out.iter().map(|o| o.operator.as_str()).collect();
    assert_eq!(names, vec!["BT", "Tm", "Tm", "Tj", "ET"], "inside Tj dropped: {names:?}");
    // No rects: everything kept.
    let ops = vec![op("Tj", vec![txt("x")])];
    assert_eq!(redact_operations(ops, &[]).len(), 1);
    // Empty input.
    assert!(redact_operations(vec![], &[[0.0, 0.0, 9.0, 9.0]]).is_empty());
}

#[test]
fn redact_operations_tracks_td_and_tstar() {
    // Start at (0,0) via Td, then T* moves down by leading; second show is at y=-20.
    let ops = vec![
        op("BT", vec![]),
        op("TL", vec![i(20)]),
        op("Td", vec![i(10),i(100)]),
        op("Tj", vec![txt("first")]),
        op("T*", vec![]),
        op("Tj", vec![txt("second")]),
        op("ET", vec![]),
    ];
    // Rect covering only y in [70,90] (second line at y=80) drops second only.
    let out = redact_operations(ops, &[[0.0, 70.0, 200.0, 90.0]]);
    let shows = out.iter().filter(|o| o.operator == "Tj").count();
    assert_eq!(shows, 1);
}

#[test]
fn apply_matte_unpremultiplies_and_skips_transparent() {
    // Pixel premultiplied against white matte at alpha 0.5: stored 255 -> true 255.
    let mut px = vec![255u8, 128, 0, 128, 10, 20, 30, 0, 200, 200, 200, 255];
    apply_matte(&mut px, [1.0, 1.0, 1.0]);
    assert_eq!(px[0], 255);
    assert_eq!(&px[4..8], &[10, 20, 30, 0], "fully transparent pixel untouched");
    assert_eq!(&px[8..12], &[200, 200, 200, 255], "opaque pixel unchanged by white matte");
    // Black matte at alpha 0.5 doubles the stored colour.
    let mut px2 = vec![50u8, 25, 0, 128];
    apply_matte(&mut px2, [0.0, 0.0, 0.0]);
    assert_eq!(px2[0], 100);
    assert_eq!(px2[1], 50);
    // Trailing partial pixel (len not multiple of 4) is ignored, no panic.
    let mut px3 = vec![1u8, 2, 3];
    apply_matte(&mut px3, [0.0, 0.0, 0.0]);
    assert_eq!(px3, vec![1, 2, 3]);
}

#[test]
fn read_matte_arities() {
    let doc = Document::with_version("1.7");
    let mut img = Dictionary::new();
    assert_eq!(read_matte(&doc, &img), None, "no SMask");
    let mut sm = Dictionary::new();
    sm.set("Matte", Object::Array(vec![0.5f32.into()]));
    img.set("SMask", Object::Dictionary(sm));
    assert_eq!(read_matte(&doc, &img), Some([0.5, 0.5, 0.5]), "gray matte");
    let mut sm3 = Dictionary::new();
    sm3.set("Matte", Object::Array(vec![1.0f32.into(), 0.0f32.into(), 0.0f32.into()]));
    img.set("SMask", Object::Dictionary(sm3));
    assert_eq!(read_matte(&doc, &img), Some([1.0, 0.0, 0.0]));
    let mut sm4 = Dictionary::new();
    sm4.set("Matte", Object::Array(vec![0.0f32.into(), 0.0f32.into(), 0.0f32.into(), 0.0f32.into()]));
    img.set("SMask", Object::Dictionary(sm4));
    assert_eq!(read_matte(&doc, &img), Some([1.0, 1.0, 1.0]), "CMYK matte");
    let mut sm2 = Dictionary::new();
    sm2.set("Matte", Object::Array(vec![0.0f32.into(), 0.0f32.into()]));
    img.set("SMask", Object::Dictionary(sm2));
    assert_eq!(read_matte(&doc, &img), None, "2-component matte is invalid");
    // SMask as a Stream (the real-world shape) also resolves.
    let mut smd = Dictionary::new();
    smd.set("Matte", Object::Array(vec![0.25f32.into(), 0.5f32.into(), 0.75f32.into()]));
    img.set("SMask", Object::Stream(Stream::new(smd, vec![])));
    assert_eq!(read_matte(&doc, &img), Some([0.25, 0.5, 0.75]));
}
