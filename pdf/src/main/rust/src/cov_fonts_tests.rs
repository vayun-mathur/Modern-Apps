//! Coverage tests for glyph_outline (Type1 program) and TTF code_to_unicode.
use crate::*;
use crate::outlines::{build_glyph_program, glyph_outline, GlyphProgram};
use std::sync::Arc;

fn font_info(program: GlyphProgram) -> FontInfo {
    let mut t1_names = HashMap::new();
    t1_names.insert(65u32, "A".to_string());
    FontInfo {
        two_byte: false, wmode: 0,
        vertical_metrics: Arc::new(HashMap::new()), default_vertical: (880.0, -1000.0),
        cid_to_gid: None, to_unicode: None,
        encoding: Arc::new(HashMap::new()), cmap_uni: Arc::new(HashMap::new()),
        cmap: None, widths: Arc::new(HashMap::new()), default_width: 0.0,
        t3: None, style: FontStyle { bold: false, italic: false }, family: 0,
        base_font: String::new(),
        glyph_program: Some(Arc::new(program)),
        glyph_names: Arc::new(t1_names),
    }
}

#[test]
fn glyph_outline_type1_by_name_and_builtin_encoding() {
    let mut glyphs: HashMap<String, Vec<Vec<(f64, f64)>>> = HashMap::new();
    glyphs.insert("A".to_string(), vec![vec![(0.0, 0.0), (10.0, 0.0), (10.0, 10.0)]]);
    let t1 = crate::type1::Type1Font { glyphs, encoding: HashMap::new(), font_matrix: [0.001, 0.0, 0.0, 0.001, 0.0, 0.0] };
    let fi = font_info(GlyphProgram::Type1(t1));
    let (contours, upm) = glyph_outline(&fi, 65).expect("glyph A");
    assert_eq!(upm, 1000.0);
    assert_eq!(contours.len(), 1);
    assert_eq!(glyph_outline(&fi, 66), None, "unknown code has no outline");
    // No program -> None.
    let mut fi2 = font_info(GlyphProgram::Sfnt { data: vec![], upm: 1000.0 });
    fi2.glyph_program = None;
    assert_eq!(glyph_outline(&fi2, 65), None);
    // Garbage sfnt data -> None, no panic.
    let fi3 = font_info(GlyphProgram::Sfnt { data: vec![0u8; 32], upm: 1000.0 });
    assert_eq!(glyph_outline(&fi3, 65), None);
}

#[test]
fn build_glyph_program_none_without_font_file() {
    let doc = Document::with_version("1.7");
    assert!(build_glyph_program(&doc, None).is_none());
    let fd = dictionary! { "Type" => "FontDescriptor", "FontName" => name_obj("X") };
    assert!(build_glyph_program(&doc, Some(&fd)).is_none());
    // Garbage FontFile2 stream -> sfnt program with default UPM (parse fails gracefully).
    let mut fd2 = dictionary! { "Type" => "FontDescriptor" };
    fd2.set("FontFile2", Object::Stream(Stream::new(Dictionary::new(), vec![1, 2, 3])));
    match build_glyph_program(&doc, Some(&fd2)) {
        Some(GlyphProgram::Sfnt { upm, .. }) => assert_eq!(upm, 1000.0),
        other => panic!("expected Sfnt fallback, got {:?}", other.is_some()),
    }
}

fn minimal_ttf() -> Vec<u8> {
    let mut sub = Vec::new();
    sub.extend(0u16.to_be_bytes()); // format 0
    sub.extend(262u16.to_be_bytes());
    sub.extend(0u16.to_be_bytes());
    let mut g = [0u8; 256]; g[65] = 1; g[66] = 2;
    sub.extend(g);
    let mut cmap = Vec::new();
    cmap.extend(0u16.to_be_bytes());
    cmap.extend(2u16.to_be_bytes()); // two records, both -> same subtable
    cmap.extend(3u16.to_be_bytes()); cmap.extend(1u16.to_be_bytes()); cmap.extend(20u32.to_be_bytes());
    cmap.extend(3u16.to_be_bytes()); cmap.extend(0u16.to_be_bytes()); cmap.extend(20u32.to_be_bytes());
    cmap.extend(&sub);
    let mut ttf = Vec::new();
    ttf.extend(0x00010000u32.to_be_bytes());
    ttf.extend(1u16.to_be_bytes()); ttf.extend(16u16.to_be_bytes());
    ttf.extend(0u16.to_be_bytes()); ttf.extend(0u16.to_be_bytes());
    ttf.extend(b"cmap"); ttf.extend(0u32.to_be_bytes());
    ttf.extend(28u32.to_be_bytes()); ttf.extend((cmap.len() as u32).to_be_bytes());
    ttf.extend(&cmap);
    ttf
}

#[test]
fn code_to_unicode_from_synthetic_ttf() {
    use crate::fonts::ttf::code_to_unicode;
    let m = code_to_unicode(&minimal_ttf());
    assert_eq!(m.get(&65), Some(&'A'));
    assert_eq!(m.get(&66), Some(&'B'));
    assert!(code_to_unicode(&[]).is_empty());
    assert!(code_to_unicode(&[0u8; 64]).is_empty());
    assert!(code_to_unicode(b"not a font at all........").is_empty());
    // Truncations never panic.
    let t = minimal_ttf();
    for n in 0..t.len() { let _ = code_to_unicode(&t[..n]); }
}
