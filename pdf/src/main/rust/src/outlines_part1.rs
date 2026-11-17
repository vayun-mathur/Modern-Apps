/// Map a content-stream code to an sfnt glyph id, trying the strategies that
/// apply to this font kind (CID map, glyph name, unicode cmap, symbol cmap, or
/// code-as-gid for subset fonts).
fn resolve_gid(fi: &FontInfo, face: &ttf_parser::Face, code: u32) -> Option<ttf_parser::GlyphId> {
    let num_glyphs = face.number_of_glyphs();
    let as_gid = |g: u32| -> Option<ttf_parser::GlyphId> {
        if g < num_glyphs as u32 {
            Some(ttf_parser::GlyphId(g as u16))
        } else {
            None
        }
    };

    if fi.two_byte {
        // Type0/CID: map code -> CID (via /Encoding CMap) then CID -> GID. A
        // /CIDToGIDMap stream is authoritative (PDF 32000-1 9.7.4.2): an entry of
        // 0, or a CID past the end of the stream, means .notdef — report None
        // rather than falling through to identity and drawing a wrong glyph.
        let cid = fi.to_cid(code);
        let gid = match fi.cid_to_gid.as_ref() {
            Some(m) => match m.get(&cid).copied() {
                Some(0) | None => return None,
                Some(g) => g,
            },
            None => cid as u16,
        };
        return as_gid(gid as u32);
    }

    // Simple font: prefer glyph name (CFF), then unicode via cmap, then symbol
    // cmap, then treat the code itself as a gid (common for subset fonts).
    if let Some(name) = fi.glyph_names.get(&code) {
        if let Some(gid) = face.glyph_index_by_name(name) {
            return Some(gid);
        }
        if let Some(gid) = name_as_gid(name).and_then(as_gid) {
            return Some(gid);
        }
    }
    let ch = fi.encoding.get(&code).copied().or_else(|| fi.cmap_uni.get(&code).copied());
    if let Some(c) = ch {
        if let Some(gid) = face.glyph_index(c) {
            return Some(gid);
        }
    }
    // Symbol fonts map codes into the 0xF000 private-use block.
    if let Some(c) = char::from_u32(0xF000 + code) {
        if let Some(gid) = face.glyph_index(c) {
            return Some(gid);
        }
    }
    // Built-in (non-Unicode) cmap lookup by raw code. Symbolic subset TrueType
    // fonts (e.g. macOS Quartz output) often carry only a (1,0) Macintosh or
    // (3,0) symbol subtable that maps the raw content-stream code directly to a
    // glyph id. `ttf_parser::Face::glyph_index` consults only Unicode subtables,
    // so those mappings are invisible to the lookups above and we would wrongly
    // fall through to code-as-gid. Query every subtable with the raw code (and
    // the 0xF000 symbol alias) before that last resort.
    if let Some(cmap) = face.tables().cmap {
        // PDF 9.6.6.4 fixes the precedence for a symbolic TrueType font: the
        // (3,0) Microsoft Symbol subtable is consulted FIRST, then (1,0)
        // Macintosh Roman. Scanning in the font's own record order instead let
        // whichever subtable happened to be stored first win, so a font
        // carrying both picked the wrong one and drew unrelated glyphs.
        let rank = |st: &ttf_parser::cmap::Subtable| match (st.platform_id, st.encoding_id) {
            (ttf_parser::PlatformId::Windows, 0) => 0u8, // (3,0) Symbol
            (ttf_parser::PlatformId::Macintosh, 0) => 1, // (1,0) Roman
            _ => 2,
        };
        let mut ordered: Vec<_> = cmap.subtables.into_iter().collect();
        ordered.sort_by_key(rank);
        for st in ordered {
            if let Some(gid) = st.glyph_index(code) {
                return Some(gid);
            }
            if let Some(gid) = st.glyph_index(0xF000 + code) {
                return Some(gid);
            }
        }
        // The font has a cmap and none of its subtables map this code, so the
        // code genuinely has no glyph. Report None so the caller can fall back to
        // a substitute face; treating the code as a glyph id here would draw an
        // unrelated glyph and suppress that fallback. Code-as-gid stays below for
        // subset fonts that ship no cmap at all, where it is the only convention
        // available.
        return None;
    }
    as_gid(code)
}

/// Parse a simple font's `/Encoding` `/Differences` into code -> glyph name.
pub(crate) fn encoding_differences(doc: &lopdf::Document, font: &lopdf::Dictionary) -> HashMap<u32, String> {
    let mut names = HashMap::new();
    let enc = match font.get(b"Encoding").ok().and_then(|o| crate::deref(doc, o)) {
        Some(lopdf::Object::Dictionary(d)) => d,
        _ => return names,
    };
    let diffs = match enc.get(b"Differences").ok().and_then(|o| crate::deref(doc, o)) {
        Some(lopdf::Object::Array(a)) => a,
        _ => return names,
    };
    let mut code = 0u32;
    for item in diffs {
        let obj = crate::deref(doc, item).cloned().unwrap_or_else(|| item.clone());
        match obj {
            // Real is accepted alongside Integer so this parser and the Unicode
            // one in `fonts::encoding::build` segment the array IDENTICALLY. They
            // disagreed: a `/Differences [65 /A 200.0 /B]` set code 200 there and
            // was ignored here, leaving the counter at 66 — so `glyph_names`
            // (which picks the OUTLINE) and `encoding` (which picks the substitute
            // glyph and the text) named different codes, and code 66 drew B.
            lopdf::Object::Integer(_) | lopdf::Object::Real(_) => {
                code = crate::num(&obj).unwrap_or(0.0).max(0.0) as u32;
            }
            lopdf::Object::Name(n) => {
                names.insert(code, String::from_utf8_lossy(&n).to_string());
                code += 1;
            }
            _ => {}
        }
    }
    names
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fonts::{FontInfo, FontStyle};
    use std::sync::Arc;

    fn square() -> Vec<Vec<(f64, f64)>> {
        vec![vec![(0.0, 0.0), (100.0, 0.0), (100.0, 100.0), (0.0, 100.0)]]
    }

    fn triangle() -> Vec<Vec<(f64, f64)>> {
        vec![vec![(0.0, 0.0), (50.0, 0.0), (25.0, 80.0)]]
    }

    type GlyphContours<'a> = &'a [(&'a str, Vec<Vec<(f64, f64)>> )];

    fn type1_font_info(
        glyphs: GlyphContours<'_>,
        builtin: &[(u32, &str)],
        names: &[(u32, &str)],
    ) -> FontInfo {
        let t1 = Type1Font {
            glyphs: glyphs.iter().map(|(n, c)| ((*n).to_string(), c.clone())).collect(),
            encoding: builtin.iter().map(|(c, n)| (*c, (*n).to_string())).collect(),
            font_matrix: [0.001, 0.0, 0.0, 0.001, 0.0, 0.0],
        };
        FontInfo {
            two_byte: false,
            wmode: 0,
            vertical_metrics: Arc::default(),
            default_vertical: (0.880, -1.0),
            cid_to_gid: None,
            to_unicode: None,
            encoding: Arc::default(),
            cmap_uni: Arc::default(),
            cmap: None,
            widths: Arc::default(),
            default_width: 0.5,
            t3: None,
            style: FontStyle::default(),
            family: 0,
            base_font: String::new(),
            glyph_program: Some(Arc::new(GlyphProgram::Type1(t1))),
            glyph_names: Arc::new(names.iter().map(|(c, n)| (*c, (*n).to_string())).collect()),
        }
    }

    // A glyph contour is closed by definition, and this renderer represents
    // closure as a duplicated first point (`interpret.rs`'s `h` does the same) —
    // `Prim::Stroke` has no closed flag. Without the duplicate a stroked glyph
    // (Tr 1/2) is an OPEN polyline missing its final edge, so outlined text shows
    // a notch in every letter and every counter.
    #[test]
    fn contours_are_explicitly_closed() {
        let mut cb = ContourBuilder::new();
        cb.move_to(0.0, 0.0);
        cb.line_to(100.0, 0.0);
        cb.line_to(100.0, 100.0);
        cb.close();
        let contours = cb.finish();
        assert_eq!(contours.len(), 1);
        assert_eq!(contours[0].first(), contours[0].last(), "first point repeated at the end");
        assert_eq!(contours[0].len(), 4);
    }

    // …but a contour the font already closed must not gain a zero-length segment,
    // which the stroker would render as a cap-shaped blob at the seam.
    #[test]
    fn an_already_closed_contour_is_not_double_closed() {
        let mut cb = ContourBuilder::new();
        cb.move_to(0.0, 0.0);
        cb.line_to(100.0, 0.0);
        cb.line_to(100.0, 100.0);
        cb.line_to(0.0, 0.0);
        cb.close();
        let contours = cb.finish();
        assert_eq!(contours[0].len(), 4, "no duplicate closing point added");
    }

    // PDF 32000-1 9.6.6.2: a base encoding named by /Encoding outranks the font
    // program's own built-in encoding. Code 233 is "eacute" in WinAnsi and
    // "Oslash" in StandardEncoding, so a Type 1 font carrying the usual built-in
    // StandardEncoding used with /WinAnsiEncoding drew Ø for é — the wrong glyph,
    // silently, with no missing-text symptom to notice.
    #[test]
    fn a_named_base_encoding_outranks_the_programs_built_in_one() {
        let fi = type1_font_info(
            &[("eacute", square()), ("Oslash", triangle())],
            &[(233, "Oslash")],
            &[(233, "eacute")],
        );
        let (contours, _) = glyph_outline(&fi, 233).expect("outline");
        assert_eq!(contours, square(), "must draw eacute, not the built-in Oslash");
    }

    // …but a base-encoding name the program does not define must fall THROUGH to
    // the built-in rather than to the substitute face. A subset that renamed its
    // glyphs still holds the right outline, and reporting None here would replace
    // a correct embedded glyph with a system font.
    #[test]
    fn an_undefined_base_encoding_name_falls_back_to_the_built_in() {
        let fi = type1_font_info(
            &[("uni00E9", square())],
            &[(233, "uni00E9")],
            &[(233, "eacute")],
        );
        let (contours, _) = glyph_outline(&fi, 233).expect("outline");
        assert_eq!(contours, square());
    }

    // With no /Differences and no named base encoding, `glyph_names` is empty and
    // the built-in encoding is the whole answer (9.6.6.2's next step).
    #[test]
    fn the_built_in_encoding_is_used_when_nothing_outranks_it() {
        let fi = type1_font_info(&[("A", square())], &[(65, "A")], &[]);
        assert!(glyph_outline(&fi, 65).is_some());
        assert!(glyph_outline(&fi, 66).is_none(), "unmapped code substitutes");
    }

    /// The two /Differences parsers must segment the array IDENTICALLY. This one
    /// feeds `glyph_names`, which picks the OUTLINE; `fonts::encoding::build` feeds
    /// `encoding`, which picks the substitute glyph and the extracted text. This
    /// one ignored a Real code where that one accepted it, so after a malformed
    /// `200.0` the two disagreed about every following code — and a disagreement
    /// here draws a real glyph, at the right position, for the wrong character.
    #[test]
    fn a_real_valued_differences_code_is_read_like_the_unicode_parser_reads_it() {
        let mut doc = lopdf::Document::with_version("1.7");
        let enc = doc.add_object(lopdf::Object::Dictionary(lopdf::Dictionary::from_iter([
            ("Type", lopdf::Object::Name(b"Encoding".to_vec())),
            (
                "Differences",
                lopdf::Object::Array(vec![
                    65.into(),
                    lopdf::Object::Name(b"A".to_vec()),
                    lopdf::Object::Real(200.0),
                    lopdf::Object::Name(b"B".to_vec()),
                ]),
            ),
        ])));
        let font = lopdf::Dictionary::from_iter([
            ("Type", lopdf::Object::Name(b"Font".to_vec())),
            ("Subtype", lopdf::Object::Name(b"Type1".to_vec())),
            ("Encoding", lopdf::Object::Reference(enc)),
        ]);
        let names = encoding_differences(&doc, &font);
        let (uni, _) = crate::fonts::encoding::build(&doc, &font);
        assert_eq!(names.get(&200).map(String::as_str), Some("B"));
        assert_eq!(names.get(&66), None, "the counter must jump to 200, not run on from 66");
        assert_eq!(uni.get(&200), Some(&'B'), "and the Unicode parser must agree");
    }
}
