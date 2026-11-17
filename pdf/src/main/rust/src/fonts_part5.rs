#[cfg(test)]
mod encoding_priority_tests {
    use super::*;

    fn helvetica(encoding: Option<&str>) -> (Document, Dictionary) {
        let doc = Document::with_version("1.7");
        let mut f = dictionary! {
            "Type" => "Font",
            "Subtype" => "Type1",
            "BaseFont" => "Helvetica",
        };
        if let Some(e) = encoding {
            f.set("Encoding", Object::Name(e.as_bytes().to_vec()));
        }
        (doc, f)
    }

    // Builds a minimal but REAL Type 1 font program, so `font_info` takes the
    // `GlyphProgram::Type1` arm for real rather than being handed a pre-built
    // `glyph_names`. Mutation testing found that no test covered the wiring: with
    // the Type1/Cff arm reverted to an empty map, all 11 of the original tests
    // still passed, because each one either built `FontInfo` by hand or exercised
    // `named_base_encoding_names` directly. The production path that carries a
    // named base encoding into glyph selection was the one thing untested.
    fn type1_font_program(glyph: &str, charstring: &[u8]) -> Vec<u8> {
        fn crypt(seed: u16, lead: usize, plain: &[u8]) -> Vec<u8> {
            let (c1, c2) = (52845u16, 22719u16);
            let mut r = seed;
            let mut out = Vec::new();
            for &p in std::iter::repeat_n(&0u8, lead).chain(plain) {
                let c = p ^ (r >> 8) as u8;
                r = (c as u16).wrapping_add(r).wrapping_mul(c1).wrapping_add(c2);
                out.push(c);
            }
            out
        }
        let mut private = Vec::new();
        private.extend_from_slice(b"dup /Private 8 dict dup begin\n/lenIV 0 def\n");
        private.extend_from_slice(b"/CharStrings 1 dict dup begin\n");
        private.extend_from_slice(format!("/{glyph} {} RD ", charstring.len()).as_bytes());
        // `lenIV 0`, so the charstring is eexec-charstring encrypted with no skip.
        private.extend_from_slice(&crypt(4330, 0, charstring));
        private.extend_from_slice(b" ND\nend\nend\n");

        let mut font = Vec::new();
        font.extend_from_slice(b"%!PS-AdobeFont-1.0\n");
        font.extend_from_slice(b"/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n");
        font.extend_from_slice(b"/Encoding StandardEncoding def\n");
        font.extend_from_slice(b"currentfile eexec\n");
        // Four lead plaintext bytes that `extract_eexec` discards. Zero bytes put
        // 0xD9 first in the ciphertext, which is not an ASCII hex digit, so the
        // section is correctly detected as binary rather than hex.
        font.extend_from_slice(&crypt(55665, 4, &private));
        font
    }

    /// A square: `0 500 hsbw  0 0 rmoveto  100 0 rlineto  0 100 rlineto  -100 0 rlineto  closepath endchar`
    fn square_charstring() -> Vec<u8> {
        vec![
            139, 248, 136, 13, // 0 500 hsbw
            139, 139, 21, // 0 0 rmoveto
            239, 139, 5, // 100 0 rlineto
            139, 239, 5, // 0 100 rlineto
            39, 139, 5, // -100 0 rlineto
            9,  // closepath
            14, // endchar
        ]
    }

    // §9.6.6.2 end to end, through `font_info` rather than a hand-built FontInfo.
    // The program's built-in encoding is StandardEncoding, which names code 233
    // "Oslash"; the PDF declares /WinAnsiEncoding, which names it "eacute". The
    // named base encoding must win, or an accented character silently draws a
    // different letter.
    #[test]
    fn font_info_carries_a_named_base_encoding_into_glyph_names() {
        let mut doc = Document::new();
        let ff = doc.add_object(Stream::new(
            dictionary! {},
            type1_font_program("eacute", &square_charstring()),
        ));
        let fd = doc.add_object(dictionary! {
            "Type" => "FontDescriptor", "FontName" => "Test", "FontFile" => ff,
        });
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Test",
            "Encoding" => "WinAnsiEncoding",
            "FontDescriptor" => fd,
        };
        let fi = font_info(&doc, &font);
        assert!(fi.glyph_program.is_some(), "precondition: the Type 1 program must parse");
        assert_eq!(
            fi.glyph_names.get(&233).map(String::as_str),
            Some("eacute"),
            "WinAnsi must name code 233 eacute, not the program's built-in Oslash"
        );
        // And the whole path resolves to the real outline for that name.
        let (contours, upm) = crate::outlines::glyph_outline(&fi, 233).expect("outline");
        assert_eq!(upm, 1000.0);
        assert_eq!(contours.len(), 1);
        assert_eq!(contours[0].first(), contours[0].last(), "contour is closed");
    }

    // PDF 32000-1 9.6.6.2. The AFM metrics are keyed by GLYPH NAME, so resolving
    // the code through StandardEncoding regardless of the declared base encoding
    // charges every accented character another glyph's advance: Standard calls
    // code 233 "Oslash" (778/1000 in Helvetica) where WinAnsi calls it "eacute"
    // (556/1000). A whole line of accented text drifts right by ~40% of an em per
    // accent, so words overlap the following ones.
    #[test]
    fn standard14_widths_follow_the_declared_base_encoding() {
        let (doc, font) = helvetica(Some("WinAnsiEncoding"));
        let fi = font_info(&doc, &font);
        assert_eq!(fi.widths.get(&233).copied(), Some(0.556), "233 is eacute in WinAnsi");
        assert_eq!(fi.widths.get(&65).copied(), Some(0.667), "ASCII is unaffected");

        // With no /Encoding at all, StandardEncoding remains the right fallback.
        let (doc, font) = helvetica(None);
        let fi = font_info(&doc, &font);
        assert_eq!(fi.widths.get(&233).copied(), Some(0.778), "233 is Oslash in Standard");
    }

    // Symbol has its own built-in encoding, whose AFM names (alpha, Beta, …) are
    // in neither StandardEncoding nor WinAnsi. Resolving them through Unicode is
    // what keeps Greek and math text from falling back to a flat default advance.
    #[test]
    fn symbol_metrics_resolve_through_its_own_encoding() {
        let doc = Document::with_version("1.7");
        let font = dictionary! { "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Symbol" };
        let fi = font_info(&doc, &font);
        assert_eq!(fi.widths.get(&0x61).copied(), Some(0.631), "0x61 is alpha (631)");
        assert_eq!(fi.widths.get(&0x41).copied(), Some(0.722), "0x41 is Alpha (722)");
        assert_eq!(fi.widths.get(&0x2B).copied(), Some(0.549), "0x2B is plus (549)");
    }

    // An absent base-encoding NAME is the case where 9.6.6.2 gives the font
    // program's built-in encoding priority, so the table must stay empty and let
    // `outlines.rs` fall through to it rather than asserting Standard names.
    #[test]
    fn no_named_base_encoding_yields_no_names() {
        let (doc, font) = helvetica(None);
        assert!(named_base_encoding_names(&doc, &font).is_empty());

        let (doc, font) = helvetica(Some("WinAnsiEncoding"));
        let m = named_base_encoding_names(&doc, &font);
        assert_eq!(m.get(&233).map(String::as_str), Some("eacute"));
        assert_eq!(m.get(&39).map(String::as_str), Some("quotesingle"));
        assert_eq!(m.get(&96).map(String::as_str), Some("grave"));

        // /BaseEncoding inside an /Encoding dictionary is the same base encoding.
        let doc2 = Document::with_version("1.7");
        let font2 = dictionary! {
            "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
            "Encoding" => dictionary! { "BaseEncoding" => "WinAnsiEncoding" },
        };
        assert_eq!(
            named_base_encoding_names(&doc2, &font2).get(&233).map(String::as_str),
            Some("eacute")
        );
    }

    // 9.6.2.1 Table 111: /MissingWidth covers codes /Widths does not, which is all
    // of them when /Widths is absent. Discarding it there charged an invented
    // 0.5 em to every glyph of a font that had said what its default advance is.
    #[test]
    fn missing_width_applies_even_with_no_widths_array() {
        let mut doc = Document::new();
        let fd = doc.add_object(dictionary! { "Type" => "FontDescriptor", "MissingWidth" => 600 });
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "TrueType", "BaseFont" => "NotAStandardFace",
            "FontDescriptor" => fd,
        };
        let (widths, default) = simple_widths(&doc, &font);
        assert!(widths.is_empty());
        assert_eq!(default, 0.6);

        // No descriptor at all still gets the non-degenerate invented default.
        let bare = dictionary! { "Type" => "Font", "Subtype" => "TrueType" };
        assert_eq!(simple_widths(&doc, &bare).1, 0.5);
    }

    // The array form of /W was the one width path with no bound on the start CID.
    // `num` saturates at u32::MAX, so `c + j` panics in debug and wraps in release.
    #[test]
    fn w_array_form_cannot_overflow_the_cid() {
        let mut doc = Document::new();
        let desc = doc.add_object(dictionary! {
            "Type" => "Font",
            "Subtype" => "CIDFontType2",
            "W" => vec![4_294_967_295u32.into(), Object::Array(vec![500.into(), 600.into()])],
        });
        let font = dictionary! {
            "Type" => "Font",
            "Subtype" => "Type0",
            "DescendantFonts" => vec![desc.into()],
        };
        let (widths, _) = cid_widths(&doc, &font);
        assert!(widths.keys().all(|c| *c <= MAX_CID));
    }

    // 9.10.3: a bfrange's array holds one destination per code in lo..=hi. A
    // longer array used to run past `hi` and, at the top of the code space, wrap.
    #[test]
    fn bfrange_array_form_stops_at_hi() {
        let map = cmap::parse(b"1 beginbfrange\n<0041> <0042> [<0061> <0062> <0063>]\nendbfrange");
        assert_eq!(map.get(&0x41).map(String::as_str), Some("a"));
        assert_eq!(map.get(&0x42).map(String::as_str), Some("b"));
        assert_eq!(map.get(&0x43), None, "the surplus entry is outside the range");
    }
}

#[cfg(test)]
mod blind_reaudit_r5_width_tests {
    use super::*;

    /// `/FirstChar` is file-controlled and `num`'s `as u32` SATURATES at u32::MAX,
    /// so `first_char + i` panicked in debug and wrapped in release — silently
    /// dropping the whole /Widths table onto codes 0.. and charging every glyph of
    /// the string another glyph's advance. `/W`'s array form was hardened against
    /// exactly this; `/Widths` and Type 3's `/Widths` were not.
    #[test]
    fn a_hostile_first_char_cannot_overflow_the_simple_width_table() {
        let doc = Document::new();
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "TrueType", "BaseFont" => "NotAStandardFace",
            "FirstChar" => 4_294_967_295u32,
            "Widths" => vec![500.into(), 600.into(), 700.into()],
        };
        let (widths, _) = simple_widths(&doc, &font);
        // No wrap: nothing may land on a low code that a real glyph would use.
        // The saturated entries collide on u32::MAX, which is harmless — a simple
        // font's codes are single bytes, so no showing operator can ever ask for it.
        assert_eq!(widths.get(&0), None, "an overflowed key would collide with code 0");
        assert_eq!(widths.get(&1), None);
        assert_eq!(widths.keys().copied().collect::<Vec<_>>(), vec![u32::MAX]);
    }

    #[test]
    fn a_hostile_first_char_cannot_overflow_the_type3_width_table() {
        let doc = Document::new();
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type3",
            "FirstChar" => 4_294_967_295u32,
            "Widths" => vec![500.into(), 600.into()],
        };
        let (widths, _) = type3_widths(&doc, &font, 0.001);
        assert_eq!(widths.get(&0), None);
        assert_eq!(widths.keys().copied().collect::<Vec<_>>(), vec![u32::MAX]);
    }

    /// Any dictionary value may be an indirect reference (7.3.10). An unresolved
    /// `/BaseFont` is not a missing name, it is the WRONG one: a non-embedded
    /// Helvetica loses its Core-14 metrics and every glyph falls back to a flat
    /// 0.5 em, so the line drifts against the rules and boxes drawn around it.
    #[test]
    fn an_indirect_base_font_still_resolves_its_standard_14_metrics() {
        let mut doc = Document::new();
        let name = doc.add_object(Object::Name(b"Helvetica".to_vec()));
        let font = dictionary! {
            "Type" => "Font", "Subtype" => "Type1",
            "BaseFont" => Object::Reference(name),
            "Encoding" => "WinAnsiEncoding",
        };
        let fi = font_info(&doc, &font);
        assert_eq!(fi.widths.get(&65).copied(), Some(0.667), "Helvetica's real /A advance");
        assert_eq!(fi.widths.get(&105).copied(), Some(0.222), "…and its narrow /i");
    }

    /// The standard-14 code->name chain ends in a StandardEncoding guess that runs
    /// for EVERY face, including Symbol, whose built-in encoding has nothing to do
    /// with StandardEncoding. It is safe only because no Symbol AFM name is also a
    /// StandardEncoding name at a DIFFERENT code — a property of afm.rs's table
    /// contents, not of this file, so a row added there could break it silently and
    /// at a distance. This asserts the invariant instead of documenting it: wherever
    /// the Standard guess and the font's own encoding both yield a width, they must
    /// agree. r5-fontprog enumerated the collision set by hand in R5 and found it
    /// empty; this keeps it empty.
    #[test]
    fn the_standard_encoding_guess_never_contradicts_symbols_own_encoding() {
        let afm = crate::afm::standard_14_widths("Symbol").expect("Symbol is a Core-14 face");
        let enc = crate::glyphlist::symbol();
        // `by_char` exactly as `font_info` builds it: sorted names, first wins.
        let by_char: HashMap<char, f64> = {
            let mut names: Vec<&String> = afm.keys().collect();
            names.sort();
            let mut m = HashMap::new();
            for n in names {
                if let Some(c) = encoding::glyph_to_char(n) {
                    m.entry(c).or_insert(afm[n]);
                }
            }
            m
        };
        let mut checked = 0;
        for (code, name) in crate::type1::STANDARD_ENCODING {
            let Some(&guessed) = afm.get(*name) else { continue };
            let Some(&derived) = enc.get(&(*code as u32)).and_then(|c| by_char.get(c)) else {
                continue;
            };
            assert_eq!(
                guessed, derived,
                "code {code:#04x}: StandardEncoding names it {name:?} ({guessed}), but Symbol's \
                 own encoding puts a {derived}-wide glyph there. The name guess runs FIRST, so \
                 this code would be charged the wrong advance."
            );
            checked += 1;
        }
        assert!(checked > 20, "only {checked} codes compared — the fixture stopped working");
    }

    /// Likewise `/Subtype`: unresolved, a Type0 font parses as a simple one, so its
    /// 2-byte codes are read one byte at a time and the entire string decodes to
    /// unrelated glyphs at unrelated advances.
    #[test]
    fn an_indirect_subtype_is_resolved_before_choosing_the_code_width() {
        let mut doc = Document::new();
        let sub = doc.add_object(Object::Name(b"Type0".to_vec()));
        let desc = doc.add_object(dictionary! {
            "Type" => "Font", "Subtype" => "CIDFontType2", "DW" => 1000,
        });
        let font = dictionary! {
            "Type" => "Font",
            "Subtype" => Object::Reference(sub),
            "BaseFont" => "Test",
            "DescendantFonts" => vec![desc.into()],
        };
        let fi = font_info(&doc, &font);
        assert!(fi.two_byte, "an indirect /Type0 must still be a composite font");
        let mut codes = Vec::new();
        fi.for_each_code(&[0x00, 0x41], |c, _| codes.push(c));
        assert_eq!(codes, vec![0x0041], "one 2-byte code, not two 1-byte codes");
    }
}
