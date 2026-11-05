#[cfg(test)]
mod tests {
    use super::*;

    pub(super) fn operators(ops: &[Operation]) -> Vec<String> {
        ops.iter().map(|o| o.operator.clone()).collect()
    }

    pub(super) fn num_operands(op: &Operation) -> Vec<f64> {
        op.operands
            .iter()
            .filter_map(|o| match o {
                Object::Integer(i) => Some(*i as f64),
                Object::Real(r) => Some(*r as f64),
                _ => None,
            })
            .collect()
    }

    /// Raw data of every recovered inline image, in order.
    fn inline_data(ops: &[Operation]) -> Vec<Vec<u8>> {
        ops.iter()
            .filter(|o| o.operator == "BI")
            .filter_map(|o| match o.operands.first() {
                Some(Object::Stream(s)) => Some(s.content.clone()),
                _ => None,
            })
            .collect()
    }

    fn operands_of(ops: &[Operation], operator: &str) -> Vec<Object> {
        ops.iter()
            .find(|o| o.operator == operator)
            .map(|o| o.operands.clone())
            .unwrap_or_default()
    }

    #[test]
    fn empty_input_yields_nothing() {
        assert!(parse_operations_lenient(b"").is_empty());
        assert!(parse_operations_lenient(b"   \r\n\t ").is_empty());
    }

    #[test]
    fn tokenizes_an_ordinary_stream() {
        let ops = parse_operations_lenient(b"q 1 0 0 1 10 20 cm BT /F1 12 Tf (Hi) Tj ET Q");
        assert_eq!(
            operators(&ops),
            vec!["q", "cm", "BT", "Tf", "Tj", "ET", "Q"]
        );
        assert_eq!(
            operands_of(&ops, "Tj"),
            vec![Object::String(b"Hi".to_vec(), StringFormat::Literal)]
        );
    }

    #[test]
    fn malformed_number_forms() {
        // `.5`, `4.`, `+3` and `--5` all occur in real files; lopdf's `real`/`integer`
        // parsers reject some of them outright.
        let ops = parse_operations_lenient(b".5 4. +3 --5 -0.25 12abc m");
        let o = operands_of(&ops, "m");
        assert_eq!(o.len(), 6, "every numeric token must survive: {o:?}");
        assert_eq!(o[0], Object::Real(0.5));
        assert_eq!(o[1], Object::Real(4.0));
        assert_eq!(o[2], Object::Integer(3));
        assert_eq!(o[3], Object::Integer(-5), "`--5` reads as -5, as Acrobat does");
        assert_eq!(o[4], Object::Real(-0.25));
        assert_eq!(o[5], Object::Integer(12), "trailing junk is dropped");
    }

    #[test]
    fn literal_string_nesting_and_escapes() {
        let ops = parse_operations_lenient(b"(a(b)c) Tj");
        assert_eq!(
            operands_of(&ops, "Tj"),
            vec![Object::String(b"a(b)c".to_vec(), StringFormat::Literal)]
        );

        // \101 == 'A'; \) is a literal paren; a backslash before EOL is a continuation.
        let ops = parse_operations_lenient(b"(\\101\\)\\n\\\n end) Tj");
        let Object::String(s, _) = &operands_of(&ops, "Tj")[0] else {
            panic!("expected a string");
        };
        assert_eq!(s.as_slice(), b"A)\n end");

        // A bare CR inside a literal string means LF (7.3.4.2).
        let ops = parse_operations_lenient(b"(a\rb) Tj");
        let Object::String(s, _) = &operands_of(&ops, "Tj")[0] else {
            panic!("expected a string");
        };
        assert_eq!(s.as_slice(), b"a\nb");
    }

    #[test]
    fn hex_string_pads_odd_digit() {
        let ops = parse_operations_lenient(b"<48656C6C6F> Tj <4> Tj");
        let data: Vec<Vec<u8>> = ops
            .iter()
            .filter(|o| o.operator == "Tj")
            .filter_map(|o| match o.operands.first() {
                Some(Object::String(s, _)) => Some(s.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(data[0], b"Hello".to_vec());
        assert_eq!(data[1], vec![0x40], "a trailing nibble is padded with 0");
    }

    #[test]
    fn name_hex_escapes() {
        let ops = parse_operations_lenient(b"/A#20B gs");
        assert_eq!(operands_of(&ops, "gs"), vec![Object::Name(b"A B".to_vec())]);
    }

    #[test]
    fn arrays_dicts_booleans_null_and_comments() {
        let ops = parse_operations_lenient(
            b"% a comment\n[3 1] 0 d\n<</Type/Foo/N 2>> BDC true false null gs",
        );
        assert_eq!(operators(&ops), vec!["d", "BDC", "gs"]);
        assert_eq!(
            operands_of(&ops, "d"),
            vec![
                Object::Array(vec![Object::Integer(3), Object::Integer(1)]),
                Object::Integer(0)
            ]
        );
        assert_eq!(
            operands_of(&ops, "gs"),
            vec![Object::Boolean(true), Object::Boolean(false), Object::Null]
        );
        let bdc = operands_of(&ops, "BDC");
        let Object::Dictionary(d) = &bdc[0] else {
            panic!("expected a dictionary, got {bdc:?}");
        };
        assert_eq!(d.get(b"N").unwrap(), &Object::Integer(2));
    }

    // -- The inline-image cases lopdf hard-fails on -------------------------

    #[test]
    fn stencil_without_bpc_or_cs() {
        // `/IM true` implies one bit per sample and no colour space, so lopdf's
        // mandatory /BPC and /CS lookups fail and the whole page is lost.
        // ceil(8*1*1/8) = 1 byte per row, 2 rows.
        let ops = parse_operations_lenient(b"q BI /W 8 /H 2 /IM true ID \xAA\xBB EI Q");
        assert_eq!(operators(&ops), vec!["q", "BI", "Q"]);
        assert_eq!(inline_data(&ops), vec![vec![0xAA, 0xBB]]);
    }

    #[test]
    fn cs_g_abbreviation() {
        // 8.9.7 Table 93 lists /G for DeviceGray, but it is absent from lopdf's list.
        let ops = parse_operations_lenient(b"BI /W 2 /H 2 /BPC 8 /CS /G ID \x01\x02\x03\x04 EI S");
        assert_eq!(operators(&ops), vec!["BI", "S"]);
        assert_eq!(inline_data(&ops), vec![vec![1, 2, 3, 4]]);
    }

    #[test]
    fn filtered_inline_image_with_length() {
        // Any /F is an outright Unimplemented error in lopdf. With /L the data length
        // is exact even though the geometry says nothing about the encoded size.
        let ops =
            parse_operations_lenient(b"BI /W 4 /H 4 /BPC 8 /CS /G /F /AHx /L 9 ID 41424344> EI Q");
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
        assert_eq!(inline_data(&ops), vec![b"41424344>".to_vec()]);
    }

    #[test]
    fn filtered_inline_image_without_length_falls_back_to_scanning() {
        // No /L and a filter, so the length genuinely cannot be computed. This is the
        // only case that may scan, and the candidate must still validate.
        let ops = parse_operations_lenient(b"BI /W 4 /H 4 /BPC 8 /CS /G /F /AHx ID 41424344> EI Q");
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
        assert_eq!(inline_data(&ops), vec![b"41424344>".to_vec()]);
    }

    // -- The false-EI hazard ----------------------------------------------

    #[test]
    fn binary_data_containing_the_bytes_ei() {
        // The data holds "EI" twice, the second occurrence whitespace-delimited on both
        // sides (0x20 before, NUL after — NUL is PDF white space). A scanning parser
        // resynchronizes into the middle of the image here.
        let mut s = Vec::new();
        s.extend_from_slice(b"q BI /W 4 /H 2 /BPC 8 /CS /G ID ");
        let data = vec![0x45, 0x49, 0x20, 0x45, 0x49, 0x00, 0xFF, 0x41];
        s.extend_from_slice(&data);
        s.extend_from_slice(b" EI 10 20 m S Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(inline_data(&ops), vec![data], "must skip the computed length");
        assert_eq!(operators(&ops), vec!["q", "BI", "m", "S", "Q"]);
    }

    #[test]
    fn binary_data_containing_a_whitespace_delimited_ei_followed_by_an_operator() {
        // The worst case: the pixel data contains " EI Q ", so the false match is
        // whitespace-delimited AND followed by a real operator. Every scan-based
        // heuristic accepts it. Only the computed length survives this.
        // 8 columns x 1 row x 8bpc x 1 comp = exactly 8 bytes.
        let data = b"x EI Q y";
        let mut s = Vec::new();
        s.extend_from_slice(b"q BI /W 8 /H 1 /BPC 8 /CS /G ID ");
        s.extend_from_slice(data);
        s.extend_from_slice(b" EI 1 0 0 1 5 5 cm Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(
            inline_data(&ops),
            vec![data.to_vec()],
            "the false ` EI Q ` inside the pixel data must not terminate the image"
        );
        assert_eq!(operators(&ops), vec!["q", "BI", "cm", "Q"]);
    }

    #[test]
    fn page_content_after_an_inline_image_survives() {
        // The actual user-visible bug: today the inline image aborts the whole stream,
        // so the text and path AFTER it are lost along with everything else.
        let mut s = Vec::new();
        s.extend_from_slice(b"BT /F1 9 Tf (before) Tj ET\n");
        s.extend_from_slice(b"BI /W 4 /H 1 /IM true ID \x0F EI\n");
        s.extend_from_slice(b"BT /F1 9 Tf (after) Tj ET 1 2 m 3 4 l S");
        let ops = parse_operations_lenient(&s);
        assert_eq!(
            operators(&ops),
            vec![
                "BT", "Tf", "Tj", "ET", "BI", "BT", "Tf", "Tj", "ET", "m", "l", "S"
            ]
        );
        let strings: Vec<Vec<u8>> = ops
            .iter()
            .filter(|o| o.operator == "Tj")
            .filter_map(|o| match o.operands.first() {
                Some(Object::String(v, _)) => Some(v.clone()),
                _ => None,
            })
            .collect();
        assert_eq!(strings, vec![b"before".to_vec(), b"after".to_vec()]);
    }

    #[test]
    fn crlf_after_id_is_not_taken_as_pixel_data() {
        // 8.9.7 allows exactly one white-space byte after ID, but producers write CRLF.
        // The LF must be treated as a separator, not as the first sample.
        let mut s = Vec::new();
        s.extend_from_slice(b"BI /W 2 /H 1 /BPC 8 /CS /G ID\r\n");
        s.extend_from_slice(&[0xAA, 0xBB]);
        s.extend_from_slice(b" EI Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(inline_data(&ops), vec![vec![0xAA, 0xBB]]);
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
    }

    #[test]
    fn ambiguous_colorspace_is_resolved_by_verifying_ei() {
        // /CS names a page colour-space resource we cannot resolve, so the component
        // count is unknown. Candidates are tried (1, 3, 4 components => 8, 24, 32
        // bytes) and the one whose EI verifies is kept.
        let data = vec![0x41u8; 24]; // 4 x 2 x 8bpc x 3 components
        let mut s = Vec::new();
        s.extend_from_slice(b"BI /W 4 /H 2 /BPC 8 /CS /CS0 ID ");
        s.extend_from_slice(&data);
        s.extend_from_slice(b" EI Q");
        let ops = parse_operations_lenient(&s);
        assert_eq!(inline_data(&ops), vec![data]);
        assert_eq!(operators(&ops), vec!["BI", "Q"]);
    }

    #[test]
    fn truncated_inline_image_keeps_preceding_content() {
        // The stream ends inside the image data. Everything before it must survive.
        let ops = parse_operations_lenient(b"q 1 0 0 1 2 2 cm BI /W 900 /H 900 /IM true ID \x01\x02");
        assert_eq!(operators(&ops), vec!["q", "cm", "BI"]);
    }

    // -- Recovery and robustness -------------------------------------------

    #[test]
    fn recovers_after_unparseable_bytes() {
        let ops = parse_operations_lenient(b"q \xFF\xFE\x01 10 20 m 30 40 l S Q");
        let names = operators(&ops);
        for expected in ["q", "m", "l", "S", "Q"] {
            assert!(names.contains(&expected.to_string()), "lost {expected} in {names:?}");
        }
    }

    #[test]
    fn unterminated_constructs_do_not_hang() {
        // Each of these ends mid-token; the tokenizer must terminate regardless.
        for bad in [
            &b"(unterminated"[..],
            &b"<48656"[..],
            &b"<</Key"[..],
            &b"[1 2 3"[..],
            &b"/Name"[..],
            &b"BI /W 2"[..],
            &b"BI /W 2 /H 2 /BPC 8 /CS /G ID"[..],
        ] {
            let _ = parse_operations_lenient(bad);
        }
    }

    #[test]
    fn matches_the_end_to_end_fixture_payloads() {
        // The exact byte sequences golden_tests.rs builds, verified at the tokenizer
        // layer so a failure there can be attributed to the wiring rather than here.
        // Each asserts the recovered data length, since that is what decides whether
        // the image decodes at its true dimensions.
        let mut px: Vec<u8> = (0u8..16).map(|i| i.wrapping_mul(17)).collect();
        px[4] = b' ';
        px[5] = b'E';
        px[6] = b'I';
        px[7] = b' ';
        let mut bi = b"BI /W 4 /H 4 /CS /G /BPC 8 ID ".to_vec();
        bi.extend_from_slice(&px);
        bi.extend_from_slice(b" EI");
        let ops = parse_operations_lenient(&bi);
        assert_eq!(inline_data(&ops), vec![px], "4x4x8bpc gray is exactly 16 bytes");

        // /CS /G.
        let mut bi = b"BI /W 2 /H 2 /CS /G /BPC 8 ID ".to_vec();
        bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        bi.extend_from_slice(b" EI");
        assert_eq!(
            inline_data(&parse_operations_lenient(&bi)),
            vec![vec![0x00, 0x40, 0x80, 0xFF]]
        );

        // A missing /BPC defaults to 8 rather than being an error.
        let mut bi = b"BI /W 2 /H 2 /CS /G ID ".to_vec();
        bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        bi.extend_from_slice(b" EI");
        assert_eq!(
            inline_data(&parse_operations_lenient(&bi)),
            vec![vec![0x00, 0x40, 0x80, 0xFF]]
        );

        // /F /AHx with no /L: the length is not derivable, so this is the scan path.
        let ops = parse_operations_lenient(b"BI /W 2 /H 2 /CS /G /BPC 8 /F /AHx ID 004080FF> EI");
        assert_eq!(inline_data(&ops), vec![b"004080FF>".to_vec()]);
    }

    // -- Document-level recovery -------------------------------------------

    /// Minimal single-page document whose `/Contents` is `content`, uncompressed.
    fn one_page_doc(content: &[u8]) -> (Document, ObjectId) {
        let mut doc = Document::with_version("1.5");
        let content_id = doc.add_object(Stream::new(Dictionary::new(), content.to_vec()));
        let pages_id = doc.new_object_id();
        let page_id = doc.add_object(dictionary! {
            "Type" => "Page",
            "Parent" => pages_id,
            "Contents" => content_id,
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
        });
        doc.objects.insert(
            pages_id,
            Object::Dictionary(dictionary! {
                "Type" => "Pages",
                "Kids" => vec![page_id.into()],
                "Count" => 1,
            }),
        );
        let catalog_id = doc.add_object(dictionary! {
            "Type" => "Catalog",
            "Pages" => pages_id,
        });
        doc.trailer.set("Root", catalog_id);
        (doc, page_id)
    }

    #[test]
    fn page_operations_recovers_a_page_lopdf_cannot_parse() {
        // No /BPC, so lopdf's inline-image parser errors inside `cut(...)` and the
        // whole content stream fails to decode.
        let mut content = b"q 1 0 0 1 10 20 cm\n".to_vec();
        content.extend_from_slice(b"BI /W 2 /H 2 /CS /G ID ");
        content.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
        content.extend_from_slice(b" EI\nQ\n0 1 0 rg\n300 300 40 40 re f\n");
        let (doc, page_id) = one_page_doc(&content);

        // Establish that this really is the P0 rather than a test that would pass
        // anyway: the strict content parse must genuinely fail on this page (and,
        // since the BI fix, fail on the dropped image rather than succeed
        // without it). `get_and_decode_page_content` cannot establish this: it
        // only decodes the stream's own filters and never parses operators.
        assert!(
            crate::content::strict_operations(&content).is_none(),
            "precondition: the strict content parse is expected to reject this page"
        );

        let (ops, recovered) = page_operations(&doc, page_id);
        assert!(recovered, "the lenient fallback must have produced the result");
        let names = operators(&ops);
        assert!(names.contains(&"BI".to_string()), "got {names:?}");
        // The operators AFTER the inline image are exactly what is lost today.
        for op in ["q", "cm", "Q", "rg", "re", "f"] {
            assert!(names.contains(&op.to_string()), "lost `{op}` after BI: {names:?}");
        }
        assert_eq!(inline_data(&ops), vec![vec![0x00, 0x40, 0x80, 0xFF]]);
    }

    #[test]
    fn page_operations_leaves_a_healthy_page_to_lopdf() {
        // A stream lopdf parses fine must come back from lopdf untouched, so wiring
        // the fallback in cannot regress any file that renders today.
        let content = b"q 1 0 0 1 10 20 cm BT /F1 12 Tf (hi) Tj ET Q 1 2 m 3 4 l S";
        let (doc, page_id) = one_page_doc(content);
        let strict = doc
            .get_and_decode_page_content(page_id)
            .expect("precondition: lopdf parses this")
            .operations;
        let (ops, recovered) = page_operations(&doc, page_id);
        assert!(!recovered, "the strict parser's result must be preferred");
        assert_eq!(operators(&ops), operators(&strict));
    }

    /// §9.6.5: every Type 3 glyph description begins with `d0`/`d1`, and lopdf
    /// 0.36 ends an operator token at the first digit — so without
    /// [`repair_d0_d1`] the glyph's first painting operator is corrupted.
    #[test]
    fn strict_operations_repairs_lopdf_mistokenised_d0_and_d1() {
        // Precondition: lopdf really does mangle these, so this is not a test
        // that would pass anyway.
        let raw = Content::decode(b"700 0 d0\n50 0 600 600 re\nf").expect("decode").operations;
        assert_eq!(raw[0].operator, "d", "precondition: lopdf produces the dash operator");
        assert_eq!(raw[1].operands.len(), 5, "precondition: the digit leaks into `re`");

        let ops = strict_operations(b"700 0 d0\n50 0 600 600 re\nf").expect("strict");
        assert_eq!(operators(&ops), vec!["d0", "re", "f"]);
        assert_eq!(ops[0].operands.len(), 2);
        assert_eq!(ops[1].operands.len(), 4, "`re` must get exactly its four operands");

        let ops = strict_operations(b"0 0 0 0 750 750 d1\n30 0 m\n370 0 l\nh\nf").expect("strict");
        assert_eq!(operators(&ops), vec!["d1", "m", "l", "h", "f"]);
        assert_eq!(ops[0].operands.len(), 6);
        assert_eq!(ops[1].operands.len(), 2, "`m` must start the subpath at (30, 0)");
        assert_eq!(num_operands(&ops[1]), vec![30.0, 0.0]);
    }

    /// The blank-glyph shape — `wx 0 d0` and nothing after it — leaves no next
    /// operation to carry the leaked digit.
    #[test]
    fn strict_operations_repairs_d0_as_the_final_operator() {
        let ops = strict_operations(b"600 0 d0\n").expect("strict");
        assert_eq!(operators(&ops), vec!["d0"]);
        assert_eq!(num_operands(&ops[0]), vec![600.0, 0.0]);
    }

}