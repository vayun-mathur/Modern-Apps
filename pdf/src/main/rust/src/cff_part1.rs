/// charset (format 0/1/2) -> GID -> SID with bounds checking. For a CID-keyed
/// font (`cid_keyed`) the entries are CIDs rather than SIDs (CFF spec, Adobe
/// TN #5176 "Charsets"), so they are bounded by the CID range, not the string
/// index — bounding them by `max_sid` would discard nearly the whole mapping.
fn parse_charset(d: &[u8], off: usize, nglyphs: usize, custom_string_count: usize, cid_keyed: bool) -> Option<HashMap<u16, usize>> {
    if off == 0 {
        return None; // ISOAdobe predefined
    }
    if off >= d.len() {
        return None;
    }
    let fmt = u8a(d, off)?;
    // Largest legal charset entry: a CID for CID-keyed fonts, else a SID.
    let max_sid = if cid_keyed {
        0xFFFF
    } else if custom_string_count == 0 {
        N_STD_STRINGS - 1
    } else {
        N_STD_STRINGS + custom_string_count - 1
    };
    let mut map = HashMap::new();
    map.insert(0u16, 0usize);
    let mut pos = off + 1;
    let mut gid = 1u16;
    if fmt == 0 {
        while (gid as usize) < nglyphs {
            if pos + 1 >= d.len() { break; }
            let sid = u16a(d, pos)? as usize;
            pos += 2;
            if sid > max_sid {
                // charset entry exceeds SID range - skip as invalid
                // continue but don't insert? Audit says validate, so we skip invalid
                // For robustness, still insert only if <=max
            } else {
                map.insert(gid, sid);
            }
            gid = gid.wrapping_add(1);
            if gid == 0 { break; } // overflow
        }
    } else if fmt == 1 || fmt == 2 {
        while (gid as usize) < nglyphs {
            if pos + 1 >= d.len() { break; }
            let first = u16a(d, pos)? as usize;
            pos += 2;
            if first > max_sid {
                // invalid first, skip its range but still need to advance pos
                let nleft = if fmt == 1 {
                    if pos >= d.len() { break; }
                    let v = u8a(d, pos)? as usize;
                    pos += 1;
                    v
                } else {
                    if pos +1 >= d.len() { break; }
                    let v = u16a(d, pos)? as usize;
                    pos += 2;
                    v
                };
                // skip inserting, but need to advance gid by nleft+1
                for _ in 0..=nleft {
                    if (gid as usize) >= nglyphs { break; }
                    gid = gid.wrapping_add(1);
                }
                continue;
            }
            let nleft = if fmt == 1 {
                if pos >= d.len() { break; }
                let v = u8a(d, pos)? as usize;
                pos += 1;
                v
            } else {
                if pos +1 >= d.len() { break; }
                let v = u16a(d, pos)? as usize;
                pos += 2;
                v
            };
            for k in 0..=nleft {
                if (gid as usize) >= nglyphs { break; }
                let sid = first + k;
                if sid <= max_sid {
                    map.insert(gid, sid);
                }
                gid = gid.wrapping_add(1);
                if gid == 0 { break; }
            }
        }
    } else {
        return None;
    }
    Some(map)
}

fn parse(d: &[u8]) -> Option<HashMap<u32, char>> {
    if d.len() < 4 {
        return None;
    }
    // CFF2 detection: major version byte ==2
    let major = d[0];
    if major == 2 {
        // CFF2 - OpenType CFF2 table, not supported in this minimal parser
        // Bail gracefully with empty map.
        return None;
    }
    if major != 1 {
        // Unknown version
        return None;
    }
    let hdr_size = u8a(d, 2)? as usize;
    if hdr_size < 4 || hdr_size > d.len() {
        return None;
    }
    // Name INDEX, Top DICT INDEX, String INDEX, Global Subr INDEX.
    let name_idx = read_index(d, hdr_size)?;
    let top_idx = read_index(d, name_idx.end)?;
    let string_idx = read_index(d, top_idx.end)?;
    let (ts, te) = *top_idx.entries.first()?;
    let top = parse_dict(d.get(ts..te)?);

    // CIDFont (has ROS, op 1230): built-in encoding is not code-based; bail.
    if top.contains_key(&1230) {
        return None;
    }

    let nglyphs = {
        let cs_off = *top.get(&17)?.first()? as usize;
        read_index(d, cs_off)?.entries.len()
    };
    if nglyphs == 0 || nglyphs > 65535 {
        return None;
    }

    // Encoding handling
    let enc_val = top.get(&16).and_then(|v| v.first()).copied().unwrap_or(0.0);
    let charset_off = top.get(&15).and_then(|v| v.first()).copied().unwrap_or(0.0) as usize;
    let custom_string_count = string_idx.entries.len();
    let gid_to_sid = parse_charset(d, charset_off, nglyphs, custom_string_count, false);

    let sid_name = |sid: usize| -> Option<String> {
        if sid < STD_STRINGS.len() {
            Some(STD_STRINGS[sid].to_string())
        } else {
            let idx = sid - N_STD_STRINGS;
            string_idx.entries.get(idx).and_then(|&(s, e)| {
                d.get(s..e).map(|b| String::from_utf8_lossy(b).into_owned())
            })
        }
    };

    let mut out = HashMap::new();

    if enc_val == 0.0 {
        // StandardEncoding predefined
        let std_map = standard_encoding_map();
        for (code, sid) in std_map {
            if let Some(name) = sid_name(sid) {
                if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                    out.insert(code as u32, c);
                }
            }
        }
        return Some(out);
    } else if enc_val == 1.0 {
        let exp_map = expert_encoding_map();
        for (code, sid) in exp_map {
            if let Some(name) = sid_name(sid) {
                if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                    out.insert(code as u32, c);
                }
            }
        }
        return Some(out);
    }

    // Custom encoding
    let enc_off = enc_val as usize;
    if enc_off >= d.len() {
        return None;
    }
    let (code_to_gid, supplements) = parse_custom_encoding(d, enc_off)?;

    // First, resolve base encoding via GID->SID
    for (code, gid) in &code_to_gid {
        // Check supplemental override first
        if let Some(&sup_sid) = supplements.get(code) {
            if let Some(name) = sid_name(sup_sid) {
                if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                    out.insert(*code as u32, c);
                }
            }
            continue;
        }
        let sid = match &gid_to_sid {
            Some(map) => match map.get(gid) {
                Some(s) => *s,
                None => continue,
            },
            None => *gid as usize, // identity fallback (ISOAdobe-ish)
        };
        if let Some(name) = sid_name(sid) {
            if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                out.insert(*code as u32, c);
            }
        }
    }
    // Also handle supplemental codes not present in base
    for (code, sid) in supplements {
        if code_to_gid.contains_key(&code) { continue; } // already handled
        if let Some(name) = sid_name(sid) {
            if let Some(c) = crate::fonts::encoding::glyph_to_char(&name) {
                out.insert(code as u32, c);
            }
        }
    }

    Some(out)
}

/// The 391 CFF standard strings (SID 0..390) — Adobe TN #5176 Appendix A.
// SID 390 is "Semibold"; SIDs 379..390 are the version/weight strings
// ("001.000" .. "Semibold"), which is the distinguishing tail of this table.
const STD_STRINGS: &[&str] = &[
    ".notdef", "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand",
    "quoteright", "parenleft", "parenright", "asterisk", "plus", "comma", "hyphen", "period",
    "slash", "zero", "one", "two", "three", "four", "five", "six",
    "seven", "eight", "nine", "colon", "semicolon", "less", "equal", "greater",
    "question", "at", "A", "B", "C", "D", "E", "F",
    "G", "H", "I", "J", "K", "L", "M", "N",
    "O", "P", "Q", "R", "S", "T", "U", "V",
    "W", "X", "Y", "Z", "bracketleft", "backslash", "bracketright", "asciicircum",
    "underscore", "quoteleft", "a", "b", "c", "d", "e", "f",
    "g", "h", "i", "j", "k", "l", "m", "n",
    "o", "p", "q", "r", "s", "t", "u", "v",
    "w", "x", "y", "z", "braceleft", "bar", "braceright", "asciitilde",
    "exclamdown", "cent", "sterling", "fraction", "yen", "florin", "section", "currency",
    "quotesingle", "quotedblleft", "guillemotleft", "guilsinglleft", "guilsinglright", "fi", "fl", "endash",
    "dagger", "daggerdbl", "periodcentered", "paragraph", "bullet", "quotesinglbase", "quotedblbase", "quotedblright",
    "guillemotright", "ellipsis", "perthousand", "questiondown", "grave", "acute", "circumflex", "tilde",
    "macron", "breve", "dotaccent", "dieresis", "ring", "cedilla", "hungarumlaut", "ogonek",
    "caron", "emdash", "AE", "ordfeminine", "Lslash", "Oslash", "OE", "ordmasculine",
    "ae", "dotlessi", "lslash", "oslash", "oe", "germandbls", "onesuperior", "logicalnot",
    "mu", "trademark", "Eth", "onehalf", "plusminus", "Thorn", "onequarter", "divide",
    "brokenbar", "degree", "thorn", "threequarters", "twosuperior", "registered", "minus", "eth",
    "multiply", "threesuperior", "copyright", "Aacute", "Acircumflex", "Adieresis", "Agrave", "Aring",
    "Atilde", "Ccedilla", "Eacute", "Ecircumflex", "Edieresis", "Egrave", "Iacute", "Icircumflex",
    "Idieresis", "Igrave", "Ntilde", "Oacute", "Ocircumflex", "Odieresis", "Ograve", "Otilde",
    "Scaron", "Uacute", "Ucircumflex", "Udieresis", "Ugrave", "Yacute", "Ydieresis", "Zcaron",
    "aacute", "acircumflex", "adieresis", "agrave", "aring", "atilde", "ccedilla", "eacute",
    "ecircumflex", "edieresis", "egrave", "iacute", "icircumflex", "idieresis", "igrave", "ntilde",
    "oacute", "ocircumflex", "odieresis", "ograve", "otilde", "scaron", "uacute", "ucircumflex",
    "udieresis", "ugrave", "yacute", "ydieresis", "zcaron", "exclamsmall", "Hungarumlautsmall", "dollaroldstyle",
    "dollarsuperior", "ampersandsmall", "Acutesmall", "parenleftsuperior", "parenrightsuperior", "twodotenleader", "onedotenleader", "zerooldstyle",
    "oneoldstyle", "twooldstyle", "threeoldstyle", "fouroldstyle", "fiveoldstyle", "sixoldstyle", "sevenoldstyle", "eightoldstyle",
    "nineoldstyle", "commasuperior", "threequartersemdash", "periodsuperior", "questionsmall", "asuperior", "bsuperior", "centsuperior",
    "dsuperior", "esuperior", "isuperior", "lsuperior", "msuperior", "nsuperior", "osuperior", "rsuperior",
    "ssuperior", "tsuperior", "ff", "ffi", "ffl", "parenleftinferior", "parenrightinferior", "Circumflexsmall",
    "hyphensuperior", "Gravesmall", "Asmall", "Bsmall", "Csmall", "Dsmall", "Esmall", "Fsmall",
    "Gsmall", "Hsmall", "Ismall", "Jsmall", "Ksmall", "Lsmall", "Msmall", "Nsmall",
    "Osmall", "Psmall", "Qsmall", "Rsmall", "Ssmall", "Tsmall", "Usmall", "Vsmall",
    "Wsmall", "Xsmall", "Ysmall", "Zsmall", "colonmonetary", "onefitted", "rupiah", "Tildesmall",
    "exclamdownsmall", "centoldstyle", "Lslashsmall", "Scaronsmall", "Zcaronsmall", "Dieresissmall", "Brevesmall", "Caronsmall",
    "Dotaccentsmall", "Macronsmall", "figuredash", "hypheninferior", "Ogoneksmall", "Ringsmall", "Cedillasmall", "questiondownsmall",
    "oneeighth", "threeeighths", "fiveeighths", "seveneighths", "onethird", "twothirds", "zerosuperior", "foursuperior",
    "fivesuperior", "sixsuperior", "sevensuperior", "eightsuperior", "ninesuperior", "zeroinferior", "oneinferior", "twoinferior",
    "threeinferior", "fourinferior", "fiveinferior", "sixinferior", "seveninferior", "eightinferior", "nineinferior", "centinferior",
    "dollarinferior", "periodinferior", "commainferior", "Agravesmall", "Aacutesmall", "Acircumflexsmall", "Atildesmall", "Adieresissmall",
    "Aringsmall", "AEsmall", "Ccedillasmall", "Egravesmall", "Eacutesmall", "Ecircumflexsmall", "Edieresissmall", "Igravesmall",
    "Iacutesmall", "Icircumflexsmall", "Idieresissmall", "Ethsmall", "Ntildesmall", "Ogravesmall", "Oacutesmall", "Ocircumflexsmall",
    "Otildesmall", "Odieresissmall", "OEsmall", "Oslashsmall", "Ugravesmall", "Uacutesmall", "Ucircumflexsmall", "Udieresissmall",
    "Yacutesmall", "Thornsmall", "Ydieresissmall", "001.000", "001.001", "001.002", "001.003", "Black",
    "Bold", "Book", "Light", "Medium", "Regular", "Roman", "Semibold",
];



/// Total number of CFF standard strings (SID 0..390); custom SIDs start at 391.
const N_STD_STRINGS: usize = 391;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dict_parses_integers_and_operators() {
        let d = [139u8, 17u8];
        let dict = parse_dict(&d);
        assert_eq!(dict.get(&17), Some(&vec![0.0]));
    }

    #[test]
    fn dict_parses_real() {
        // Real number encoding: 30, nibbles for "1.5" -> 0x1A 0x5F
        // 0x1 =1, 0xA='.', 0x5=5, 0xF terminator
        let d = [30u8, 0x1A, 0x5F, 17u8];
        let dict = parse_dict(&d);
        let v = dict.get(&17).unwrap()[0];
        assert!((v - 1.5).abs() < 0.001);
    }

    #[test]
    fn cff2_detected() {
        // Header major=2 should bail
        let data = [2u8, 0, 4, 0, 0, 0];
        assert!(builtin_encoding(&data).is_empty());
    }

    #[test]
    fn empty_or_garbage_is_safe() {
        assert!(builtin_encoding(&[]).is_empty());
        assert!(builtin_encoding(&[0, 1, 2, 3, 4, 5, 6]).is_empty());
    }

    #[test]
    fn std_strings_len() {
        assert_eq!(STD_STRINGS.len(), N_STD_STRINGS, "STD_STRINGS must have 391 entries");
    }

    #[test]
    fn standard_encoding_not_empty() {
        let m = standard_encoding_map();
        assert!(!m.is_empty());
        // Should contain 'A' at code 65
        assert!(m.contains_key(&65));
    }

    #[test]
    fn cid_keyed_charset_keeps_cids_above_sid_range() {
        // Format 0 charset for 3 glyphs: GID1 -> 1000, GID2 -> 2000. Offset 1,
        // because offset 0 means the predefined ISOAdobe charset.
        let d = [0xFFu8, 0x00, 0x03, 0xE8, 0x07, 0xD0];
        // Read as CIDs (CID-keyed font): both are in range and must survive.
        let cids = parse_charset(&d, 1, 3, 0, true).unwrap();
        assert_eq!(cids.get(&1), Some(&1000));
        assert_eq!(cids.get(&2), Some(&2000));
        // Read as SIDs: both exceed the 391-string range, so they are dropped.
        // Applying this SID bound to a CID-keyed font is the bug being guarded.
        let sids = parse_charset(&d, 1, 3, 0, false).unwrap();
        assert_eq!(sids.get(&1), None);
        assert_eq!(sids.get(&2), None);
    }

    #[test]
    fn std_strings_tail_is_the_version_and_weight_block() {
        // Adobe TN #5176 Appendix A: SIDs 363..390 run Ethsmall .. Semibold and
        // end with the version/weight strings. The tail previously held
        // MacExpert/Symbol names (radicalex, arrowvertex, parenlefttp, …), which
        // are not CFF standard strings at all, so every charset entry with a SID
        // above 362 resolved to the wrong glyph name — and therefore, for a
        // name-keyed CFF, to the wrong Unicode in the built-in encoding.
        assert_eq!(STD_STRINGS[363], "Ethsmall");
        assert_eq!(STD_STRINGS[378], "Ydieresissmall");
        assert_eq!(STD_STRINGS[379], "001.000");
        assert_eq!(STD_STRINGS[390], "Semibold");
        for bogus in ["radicalex", "arrowvertex", "parenlefttp", "bracketleftbt", "Emacronsmall"] {
            assert!(!STD_STRINGS.contains(&bogus), "{bogus} is not a CFF standard string");
        }
    }

    /// An INDEX whose `count` entries are all empty, with 1-byte offsets.
    fn empty_index(count: usize) -> Vec<u8> {
        let mut d = vec![(count >> 8) as u8, count as u8, 1u8];
        d.extend(std::iter::repeat_n(1u8, count + 1));
        d
    }

    #[test]
    fn index_count_is_bounded_by_the_format_not_by_a_lower_cap() {
        // A CFF INDEX count is a u16, so 32768..=65535 entries are legal, and
        // CID-keyed CJK fonts really do carry that many glyphs. Rejecting them
        // made `cid_to_gid_map` return None, and the caller then falls back to
        // CID==GID identity — silently drawing a different glyph per character.
        let d = empty_index(40000);
        let idx = read_index(&d, 0).expect("a 40000-entry INDEX is legal CFF");
        assert_eq!(idx.entries.len(), 40000);
        assert_eq!(idx.end, d.len());
    }

    #[test]
    fn index_offset_array_must_fit_in_the_data() {
        // `count` is attacker-controlled and sizes two allocations. A header
        // claiming 65535 four-byte offsets over 8 bytes of file must be rejected
        // before anything is reserved.
        let d = [0xFFu8, 0xFF, 4, 0, 0, 0, 1, 0];
        assert!(read_index(&d, 0).is_none());
        let mut short = empty_index(500);
        short.truncate(100);
        assert!(read_index(&short, 0).is_none());
    }

    #[test]
    fn charset_bounds_follow_the_string_index_size() {
        // Format 0 at offset 1 (offset 0 means the predefined ISOAdobe charset),
        // 3 glyphs, SIDs 391 and 392: legal only when the String INDEX supplies at
        // least two custom strings.
        let d = [0xFFu8, 0x00, 0x01, 0x87, 0x01, 0x88];
        let with_strings = parse_charset(&d, 1, 3, 2, false).unwrap();
        assert_eq!(with_strings.get(&1), Some(&391));
        assert_eq!(with_strings.get(&2), Some(&392));
        let without = parse_charset(&d, 1, 3, 0, false).unwrap();
        assert_eq!(without.get(&1), None, "a SID past the standard strings is dropped");
    }
}
