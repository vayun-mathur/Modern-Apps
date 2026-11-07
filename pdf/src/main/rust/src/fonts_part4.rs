// ---------------------------------------------------------------------------
// Simple-font encodings (base encoding + /Differences)
// ---------------------------------------------------------------------------

pub(crate) mod encoding {
    use super::{deref, num, Object};
    use lopdf::Document;
    use std::collections::HashMap;

    /// Build a `code -> unicode char` map for a simple font: start from the base
    /// encoding (WinAnsi / MacRoman / Standard, or Symbol / ZapfDingbats for
    /// those base fonts), then apply any `/Encoding /Differences`.
    ///
    /// Returns `(map, builtin_first)`. `builtin_first` is true when the font is
    /// symbolic AND declares neither `/Encoding` nor `/BaseEncoding`: PDF 9.6.6.1
    /// gives the font program's built-in encoding priority there, so the base is
    /// left empty and the caller layers the built-in map underneath /Differences.
    pub fn build(doc: &Document, font: &lopdf::Dictionary) -> (HashMap<u32, char>, bool) {
        let base_font = font
            .get(b"BaseFont")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .unwrap_or_default();

        let enc_obj = font.get(b"Encoding").ok().and_then(|o| deref(doc, o));
        let mut builtin_first = false;
        let base_name = match &enc_obj {
            Some(Object::Name(n)) => Some(String::from_utf8_lossy(n).into_owned()),
            Some(Object::Dictionary(d)) => d
                .get(b"BaseEncoding")
                .ok()
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_name().ok())
                .map(|n| String::from_utf8_lossy(n).into_owned()),
            _ => None,
        };

        let mut map = if base_font.contains("Symbol") {
            symbol_table()
        } else if base_font.contains("ZapfDingbats") || base_font.contains("Dingbats") {
            zapf_table()
        } else if base_name.is_none() && is_symbolic(doc, font) {
            // Built-in encoding takes priority (PDF 9.6.6.1); the caller layers it
            // in. Only /Differences belongs in this map.
            builtin_first = true;
            HashMap::new()
        } else {
            match base_name.as_deref() {
                Some("WinAnsiEncoding") => win_ansi(),
                Some("MacRomanEncoding") => crate::glyphlist::mac_roman(),
                Some("StandardEncoding") => standard(),
                Some("Symbol") => symbol_table(),
                Some("ZapfDingbats") => zapf_table(),
                // Default base encoding for most simple fonts is Standard, but
                // WinAnsi is the safest superset for modern PDFs.
                _ => win_ansi(),
            }
        };

        // Apply /Differences: [ code /name /name code /name ... ].
        if let Some(Object::Dictionary(d)) = &enc_obj {
            if let Some(Object::Array(diffs)) = d.get(b"Differences").ok().and_then(|o| deref(doc, o))
            {
                let mut code = 0u32;
                for item in diffs {
                    match item {
                        Object::Integer(_) | Object::Real(_) => {
                            // Clamp negatives to 0 to match outlines.rs's
                            // /Differences parser, which does `n.max(0)`.
                            code = num(item).unwrap_or(0.0).max(0.0) as u32;
                        }
                        Object::Name(name) => {
                            if let Some(c) = glyph_to_char(&String::from_utf8_lossy(name)) {
                                map.insert(code, c);
                            }
                            code += 1;
                        }
                        _ => {}
                    }
                }
            }
        }
        (map, builtin_first)
    }

    /// FontDescriptor `/Flags` bit 3 (value 4) = Symbolic (PDF 9.8.2, Table 121).
    fn is_symbolic(doc: &Document, font: &lopdf::Dictionary) -> bool {
        font.get(b"FontDescriptor")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
            .and_then(|d| d.get(b"Flags").ok())
            .and_then(|o| deref(doc, o))
            .and_then(num)
            .map(|f| (f as i64) & 4 != 0)
            .unwrap_or(false)
    }

    /// Resolve an Adobe glyph name to a Unicode scalar. Handles `uniXXXX`,
    /// `uXXXXXX`, the Adobe Glyph List (standard Latin/Greek/symbol names),
    /// single-character names, and named digits/letters.
    pub fn glyph_to_char(name: &str) -> Option<char> {
        // Strip a font-specific suffix like "name.sc" / "name.alt".
        let base = name.split('.').next().unwrap_or(name);
        if let Some(hex) = base.strip_prefix("uni") {
            if hex.len() >= 4 {
                if let Ok(cp) = u32::from_str_radix(&hex[..4], 16) {
                    return char::from_u32(cp);
                }
            }
        }
        if base.starts_with('u') && base.len() >= 5 && base.len() <= 7 {
            if let Ok(cp) = u32::from_str_radix(&base[1..], 16) {
                if let Some(c) = char::from_u32(cp) {
                    return Some(c);
                }
            }
        }
        // Adobe Glyph List (standard names).
        if let Some(c) = crate::glyphlist::agl(base) {
            return Some(c);
        }
        if let Some(c) = curated(base) {
            return Some(c);
        }
        // Single-character glyph name (e.g. "A", "a", "1").
        let mut chars = base.chars();
        if let (Some(c), None) = (chars.next(), chars.clone().next()) {
            return Some(c);
        }
        None
    }

    fn curated(name: &str) -> Option<char> {
        let c = match name {
            "space" | "nbspace" => ' ',
            "bullet" => '\u{2022}',
            "periodcentered" => '\u{00B7}',
            "endash" => '\u{2013}',
            "emdash" => '\u{2014}',
            "hyphen" | "sfthyphen" => '-',
            "quoteleft" => '\u{2018}',
            "quoteright" => '\u{2019}',
            "quotedblleft" => '\u{201C}',
            "quotedblright" => '\u{201D}',
            "quotesingle" => '\'',
            "quotedbl" => '"',
            "comma" => ',',
            "period" => '.',
            "colon" => ':',
            "semicolon" => ';',
            "slash" => '/',
            "backslash" => '\\',
            "asterisk" => '*',
            "ampersand" => '&',
            "at" => '@',
            "numbersign" => '#',
            "percent" => '%',
            "dollar" => '$',
            "cent" => '\u{00A2}',
            "sterling" => '\u{00A3}',
            "euro" => '\u{20AC}',
            "yen" => '\u{00A5}',
            "trademark" => '\u{2122}',
            "registered" => '\u{00AE}',
            "copyright" => '\u{00A9}',
            "degree" => '\u{00B0}',
            "plusminus" => '\u{00B1}',
            "multiply" => '\u{00D7}',
            "divide" => '\u{00F7}',
            "ellipsis" => '\u{2026}',
            "dagger" => '\u{2020}',
            "daggerdbl" => '\u{2021}',
            "paragraph" => '\u{00B6}',
            "section" => '\u{00A7}',
            "fi" => '\u{FB01}',
            "fl" => '\u{FB02}',
            "exclam" => '!',
            "question" => '?',
            "parenleft" => '(',
            "parenright" => ')',
            "bracketleft" => '[',
            "bracketright" => ']',
            "braceleft" => '{',
            "braceright" => '}',
            "less" => '<',
            "greater" => '>',
            "equal" => '=',
            "plus" => '+',
            "minus" => '\u{2212}',
            "underscore" => '_',
            "hyphenminus" => '-',
            "arrowright" => '\u{2192}',
            "arrowleft" => '\u{2190}',
            "arrowup" => '\u{2191}',
            "arrowdown" => '\u{2193}',
            "zero" => '0',
            "one" => '1',
            "two" => '2',
            "three" => '3',
            "four" => '4',
            "five" => '5',
            "six" => '6',
            "seven" => '7',
            "eight" => '8',
            "nine" => '9',
            _ => return None,
        };
        Some(c)
    }

    /// WinAnsiEncoding (CP1252): Latin-1 with the 0x80–0x9F range remapped.
    pub fn win_ansi() -> HashMap<u32, char> {
        let mut m = latin1();
        let overrides: [(u32, u32); 27] = [
            (0x80, 0x20AC),
            (0x82, 0x201A),
            (0x83, 0x0192),
            (0x84, 0x201E),
            (0x85, 0x2026),
            (0x86, 0x2020),
            (0x87, 0x2021),
            (0x88, 0x02C6),
            (0x89, 0x2030),
            (0x8A, 0x0160),
            (0x8B, 0x2039),
            (0x8C, 0x0152),
            (0x8E, 0x017D),
            (0x91, 0x2018),
            (0x92, 0x2019),
            (0x93, 0x201C),
            (0x94, 0x201D),
            (0x95, 0x2022),
            (0x96, 0x2013),
            (0x97, 0x2014),
            (0x98, 0x02DC),
            (0x99, 0x2122),
            (0x9A, 0x0161),
            (0x9B, 0x203A),
            (0x9C, 0x0153),
            (0x9E, 0x017E),
            (0x9F, 0x0178),
        ];
        for (code, cp) in overrides {
            if let Some(c) = char::from_u32(cp) {
                m.insert(code, c);
            }
        }
        m
    }

    /// WinAnsiEncoding as `code -> glyph NAME` (PDF 32000-1 Annex D.2).
    ///
    /// Distinct from [`win_ansi`], which yields Unicode. Selecting an outline in a
    /// Type 1 or bare-CFF program is done by NAME, and 9.6.6.2 makes a named base
    /// encoding outrank the program's own built-in encoding — so a Unicode map
    /// cannot serve that lookup and StandardEncoding is the wrong table for it
    /// (Standard puts Oslash where WinAnsi puts eacute, ae where it puts ntilde,
    /// and so on across the whole 0xA0-0xFF range).
    pub static WIN_ANSI_NAMES: &[(u8, &str)] = &[
        (32, "space"), (33, "exclam"), (34, "quotedbl"), (35, "numbersign"),
        (36, "dollar"), (37, "percent"), (38, "ampersand"), (39, "quotesingle"),
        (40, "parenleft"), (41, "parenright"), (42, "asterisk"), (43, "plus"),
        (44, "comma"), (45, "hyphen"), (46, "period"), (47, "slash"),
        (48, "zero"), (49, "one"), (50, "two"), (51, "three"), (52, "four"),
        (53, "five"), (54, "six"), (55, "seven"), (56, "eight"), (57, "nine"),
        (58, "colon"), (59, "semicolon"), (60, "less"), (61, "equal"),
        (62, "greater"), (63, "question"), (64, "at"),
        (65, "A"), (66, "B"), (67, "C"), (68, "D"), (69, "E"), (70, "F"),
        (71, "G"), (72, "H"), (73, "I"), (74, "J"), (75, "K"), (76, "L"),
        (77, "M"), (78, "N"), (79, "O"), (80, "P"), (81, "Q"), (82, "R"),
        (83, "S"), (84, "T"), (85, "U"), (86, "V"), (87, "W"), (88, "X"),
        (89, "Y"), (90, "Z"),
        (91, "bracketleft"), (92, "backslash"), (93, "bracketright"),
        (94, "asciicircum"), (95, "underscore"), (96, "grave"),
        (97, "a"), (98, "b"), (99, "c"), (100, "d"), (101, "e"), (102, "f"),
        (103, "g"), (104, "h"), (105, "i"), (106, "j"), (107, "k"), (108, "l"),
        (109, "m"), (110, "n"), (111, "o"), (112, "p"), (113, "q"), (114, "r"),
        (115, "s"), (116, "t"), (117, "u"), (118, "v"), (119, "w"), (120, "x"),
        (121, "y"), (122, "z"),
        (123, "braceleft"), (124, "bar"), (125, "braceright"), (126, "asciitilde"),
        (128, "Euro"), (130, "quotesinglbase"), (131, "florin"),
        (132, "quotedblbase"), (133, "ellipsis"), (134, "dagger"),
        (135, "daggerdbl"), (136, "circumflex"), (137, "perthousand"),
        (138, "Scaron"), (139, "guilsinglleft"), (140, "OE"), (142, "Zcaron"),
        (145, "quoteleft"), (146, "quoteright"), (147, "quotedblleft"),
        (148, "quotedblright"), (149, "bullet"), (150, "endash"), (151, "emdash"),
        (152, "tilde"), (153, "trademark"), (154, "scaron"), (155, "guilsinglright"),
        (156, "oe"), (158, "zcaron"), (159, "Ydieresis"),
        (160, "space"), (161, "exclamdown"), (162, "cent"), (163, "sterling"),
        (164, "currency"), (165, "yen"), (166, "brokenbar"), (167, "section"),
        (168, "dieresis"), (169, "copyright"), (170, "ordfeminine"),
        (171, "guillemotleft"), (172, "logicalnot"), (173, "hyphen"),
        (174, "registered"), (175, "macron"), (176, "degree"), (177, "plusminus"),
        (178, "twosuperior"), (179, "threesuperior"), (180, "acute"), (181, "mu"),
        (182, "paragraph"), (183, "periodcentered"), (184, "cedilla"),
        (185, "onesuperior"), (186, "ordmasculine"), (187, "guillemotright"),
        (188, "onequarter"), (189, "onehalf"), (190, "threequarters"),
        (191, "questiondown"),
        (192, "Agrave"), (193, "Aacute"), (194, "Acircumflex"), (195, "Atilde"),
        (196, "Adieresis"), (197, "Aring"), (198, "AE"), (199, "Ccedilla"),
        (200, "Egrave"), (201, "Eacute"), (202, "Ecircumflex"), (203, "Edieresis"),
        (204, "Igrave"), (205, "Iacute"), (206, "Icircumflex"), (207, "Idieresis"),
        (208, "Eth"), (209, "Ntilde"), (210, "Ograve"), (211, "Oacute"),
        (212, "Ocircumflex"), (213, "Otilde"), (214, "Odieresis"), (215, "multiply"),
        (216, "Oslash"), (217, "Ugrave"), (218, "Uacute"), (219, "Ucircumflex"),
        (220, "Udieresis"), (221, "Yacute"), (222, "Thorn"), (223, "germandbls"),
        (224, "agrave"), (225, "aacute"), (226, "acircumflex"), (227, "atilde"),
        (228, "adieresis"), (229, "aring"), (230, "ae"), (231, "ccedilla"),
        (232, "egrave"), (233, "eacute"), (234, "ecircumflex"), (235, "edieresis"),
        (236, "igrave"), (237, "iacute"), (238, "icircumflex"), (239, "idieresis"),
        (240, "eth"), (241, "ntilde"), (242, "ograve"), (243, "oacute"),
        (244, "ocircumflex"), (245, "otilde"), (246, "odieresis"), (247, "divide"),
        (248, "oslash"), (249, "ugrave"), (250, "uacute"), (251, "ucircumflex"),
        (252, "udieresis"), (253, "yacute"), (254, "thorn"), (255, "ydieresis"),
    ];

    /// Adobe StandardEncoding: matches Latin-1 for the core ASCII letters/digits
    /// but differs across punctuation (0x27 quoteright, 0x60 quoteleft) and the
    /// whole 0x80–0xFF range, so it is built from the real name table rather than
    /// aliased to Latin-1.
    fn standard() -> HashMap<u32, char> {
        let mut m = HashMap::new();
        for (code, name) in crate::type1::STANDARD_ENCODING {
            if let Some(c) = glyph_to_char(name) {
                m.insert(*code as u32, c);
            }
        }
        m
    }

    /// Codes 0x20–0xFF mapped as Latin-1 (identity to Unicode).
    fn latin1() -> HashMap<u32, char> {
        let mut m = HashMap::new();
        for code in 0x20u32..=0xFF {
            if let Some(c) = char::from_u32(code) {
                m.insert(code, c);
            }
        }
        m
    }

    /// The full Adobe Symbol-font encoding (Greek + math operators).
    fn symbol_table() -> HashMap<u32, char> {
        crate::glyphlist::symbol()
    }

    /// The ZapfDingbats encoding (dingbats/ornaments).
    fn zapf_table() -> HashMap<u32, char> {
        crate::glyphlist::zapf()
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn winansi_maps_bullet_and_dashes() {
            let m = win_ansi();
            assert_eq!(m.get(&0x95), Some(&'\u{2022}'));
            assert_eq!(m.get(&0x96), Some(&'\u{2013}'));
            assert_eq!(m.get(&0x97), Some(&'\u{2014}'));
            assert_eq!(m.get(&0x41), Some(&'A'));
        }

        #[test]
        fn glyph_names_resolve() {
            assert_eq!(glyph_to_char("bullet"), Some('\u{2022}'));
            assert_eq!(glyph_to_char("uni20AC"), Some('\u{20AC}'));
            assert_eq!(glyph_to_char("A"), Some('A'));
            assert_eq!(glyph_to_char("emdash"), Some('\u{2014}'));
            assert_eq!(glyph_to_char("Aacute"), Some('\u{00C1}'));
        }
    }
}

#[cfg(test)]
mod encrypt_tests {
    use super::*;

    fn build_doc_bytes(title: &[u8]) -> Vec<u8> {
        let mut doc = Document::with_version("1.7");
        let info = doc.add_object(dictionary! {
            "Title" => Object::String(title.to_vec(), lopdf::StringFormat::Literal),
        });
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "MediaBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        });
        doc.objects.insert(pages_id, Object::Dictionary(dictionary! {
            "Type" => "Pages",
            "Kids" => vec![page_id.into()],
            "Count" => 1,
        }));
        let catalog = doc.add_object(dictionary! { "Type" => "Catalog", "Pages" => pages_id });
        doc.trailer.set("Root", catalog);
        doc.trailer.set("Info", info);
        let mut out = Vec::new();
        doc.save_to(&mut out).unwrap();
        out
    }

    fn roundtrip(algo: crate::EncryptAlgo) {
        let title = b"SecretTitle123";
        let plain = build_doc_bytes(title);
        let pw = b"hunter2";
        let enc = crate::encrypt_doc_bytes(&plain, pw, pw, algo).expect("encrypt");
        // Wrong/empty password should not authenticate.
        let mut doc0 = Document::load_mem(&enc).unwrap();
        assert!(doc0.trailer.get(b"Encrypt").is_ok(), "should be encrypted");
        assert_ne!(crate::decrypt_in_place(&mut doc0, b""), crate::DecryptStatus::Ok);
        // Correct password decrypts and recovers the /Title string. Goes
        // through the production open path: plain load_mem cannot carry a
        // user password (lopdf drops every object when its empty-password
        // authentication fails), so decrypt_in_place alone sees a husk.
        let doc = crate::registry::open_decrypted(&enc, pw).expect("open with correct password");
        let info_ref = doc.trailer.get(b"Info").unwrap().as_reference().unwrap();
        let info = doc.get_dictionary(info_ref).unwrap();
        let got = info.get(b"Title").unwrap().as_str().unwrap();
        assert_eq!(got, &title[..], "title should round-trip through {:?}", algo as u8);
    }

    #[test]
    fn rc4_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Rc4_128);
    }

    #[test]
    fn aes128_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Aes128);
    }

    #[test]
    fn aes256_save_roundtrip() {
        roundtrip(crate::EncryptAlgo::Aes256);
    }
}


