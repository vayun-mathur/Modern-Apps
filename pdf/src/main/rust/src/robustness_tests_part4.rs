/// HAZARD: an ASCII85 5-tuple whose base-85 value exceeds u32::MAX overflows the
/// accumulator. In debug that is a panic; in release it wraps silently.
#[test]
fn ascii85_tuple_overflowing_u32_is_rejected() {
    // 'u' = 84, the largest digit; "uuuuu" = 84*(85^4+85^3+85^2+85+1) >> u32::MAX.
    let bodies: [&[u8]; 6] = [
        b"uuuuu~>",
        b"uuuuuuuuuu~>",
        b"s8W-\"~>",  // one past the largest legal tuple "s8W-!"
        b"zzzz~>",    // z = all-zero group, must not be mixed into a tuple
        b"!!!!!uuuuu~>",
        b"uuuu~>",    // short final tuple at the maximum digit
    ];
    for (i, body) in bodies.iter().enumerate() {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (
                    4,
                    raw_stream(
                        &format!(
                            "<< /Length {} /Filter /ASCII85Decode >>",
                            body.len()
                        ),
                        body,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("ASCII85 overflowing 5-tuple #{i}"), pdf);
    }
}

/// HAZARD: /Columns, /Colors and /BitsPerComponent on a PNG/TIFF predictor feed
/// a row-length multiplication that must be bounded by the actual data, not by
/// the declared values.
///
/// STATUS: CLOSED. `png-1GiB-row` below used to hang and allocate ~3 GiB from a
/// 512-byte stream. `apply_png_predictor` (and `apply_tiff_predictor2`) now bound
/// the row length by the decoded stream, which is the only honest bound available.
#[test]
fn predictor_columns_and_colors_cannot_overflow_a_row_size() {
    let payload = flate(&[0u8; 512]);
    let cases: [(&str, i64, i64, i64, i64); 8] = [
        ("png-huge-columns", 15, 0x0FFF_FFFF, 3, 8),
        ("png-huge-colors", 15, 64, 0x0FFF_FFFF, 8),
        ("png-huge-both", 15, 0x7FFF_FFFF, 0x7FFF_FFFF, 16),
        // The minimal reproduction: exactly the clamp ceilings in filters.rs.
        // filters.rs clamped the FACTORS (cols to 1<<24, colors to 32) but never
        // bounded the PRODUCT, so
        //   row_bytes = (1<<24 * 32 * 16).div_ceil(8) = 1 GiB
        // and apply_png_predictor then did:
        //   vec![0u8; 1 GiB]  x2      -> 2 GiB allocated
        //   row[got..].fill(0)        -> ~1 GiB memset per row
        //   out.extend_from_slice     -> a third 1 GiB, reallocated from cap 512
        // All from two dictionary numbers and 512 bytes of stream. Fixed by
        // clamping row_bytes to the decoded length (7.4.4.4).
        ("png-1GiB-row", 15, 1 << 24, 32, 16),
        ("png-negative", 15, -1, -1, 8),
        ("tiff-huge", 2, 0x7FFF_FFFF, 0x7FFF_FFFF, 16),
        ("png-zero", 15, 0, 0, 0),
        ("png-just-under", 15, 1 << 20, 32, 16),
    ];
    for (name, pred, columns, colors, bpc) in cases {
        let pdf = raw_pdf(
            &[
                (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
                (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
                (
                    3,
                    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"
                        .to_vec(),
                ),
                (
                    4,
                    raw_stream(
                        &format!(
                            "<< /Length {} /Filter /FlateDecode /DecodeParms \
                             << /Predictor {pred} /Columns {columns} /Colors {colors} \
                             /BitsPerComponent {bpc} >> >>",
                            payload.len()
                        ),
                        &payload,
                    ),
                ),
            ],
            "/Root 1 0 R",
        );
        assert_construct_survives(&format!("PNG/TIFF predictor {name}"), pdf);
    }
}

/// HAZARD: an inline image whose declared /L exceeds the bytes actually present
/// must not slice past the end of the content stream.
#[test]
fn inline_image_declared_length_beyond_the_stream_is_clamped() {
    let cases: [&[u8]; 5] = [
        b"BI /W 4 /H 4 /BPC 8 /CS /RGB /L 999999999 ID \x00\x01\x02 EI",
        b"BI /W 4 /H 4 /BPC 8 /CS /RGB /L 2147483647 ID \x00 EI",
        // No EI at all.
        b"BI /W 4 /H 4 /BPC 8 /CS /RGB ID \x00\x01\x02\x03",
        // Dimensions that claim far more data than is present.
        b"BI /W 65535 /H 65535 /BPC 8 /CS /RGB ID \x00\x01 EI",
        // Negative length.
        b"BI /W 4 /H 4 /BPC 8 /CS /G /L -1 ID \x00\x01\x02 EI",
    ];
    for (i, content) in cases.iter().enumerate() {
        let pdf = raw_one_page("", content, vec![]);
        assert_construct_survives(&format!("inline image with lying /L #{i}"), pdf);
    }
}

/// HAZARD: a cyclic /Parent chain and a /Kids cycle in the page tree are
/// infinite walks unless every traversal is depth-bounded.
#[test]
fn cyclic_page_tree_parent_and_kids_chains_terminate() {
    // /Parent cycle: the page's parent is the Pages node, whose own /Parent
    // points back at the page.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (
                2,
                b"<< /Type /Pages /Kids [3 0 R] /Count 1 /Parent 3 0 R >>".to_vec(),
            ),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("page tree with a cyclic /Parent", pdf);

    // /Kids cycle: the Pages node lists itself.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (
                2,
                b"<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 2 >>".to_vec(),
            ),
            (
                3,
                b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 10 10] /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("page tree whose /Kids lists its own node", pdf);

    // Two Pages nodes that list each other.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [5 0 R] /Count 1 >>".to_vec()),
            (
                3,
                b"<< /Type /Page /Parent 5 0 R /MediaBox [0 0 10 10] /Contents 4 0 R >>".to_vec(),
            ),
            (4, raw_stream("<< /Length 16 >>", b"0 0 10 10 re f  ")),
            (
                5,
                b"<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 2 /Parent 2 0 R >>".to_vec(),
            ),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("mutually recursive /Pages nodes", pdf);

    // The catalog's /Pages points at the catalog.
    let pdf = raw_pdf(
        &[
            (1, b"<< /Type /Catalog /Pages 1 0 R >>".to_vec()),
            (2, b"<< /Type /Pages /Kids [] /Count 0 >>".to_vec()),
        ],
        "/Root 1 0 R",
    );
    assert_construct_survives("catalog whose /Pages is the catalog", pdf);
}

/// HAZARD: deeply nested arrays and dictionaries must hit a depth bound rather
/// than recursing to a stack overflow.
///
/// STATUS: CLOSED. Both routes are now bounded before lopdf recurses, and the two
/// `deep_nesting_*` reproductions below run live at depth 500 rather than being
/// `#[ignore]`d:
///   * content streams - `content::strict_operations` pre-checks nesting and skips
///     `Content::decode` above `MAX_DEPTH`, leaving the depth-bounded lenient
///     tokenizer to handle the stream;
///   * the object graph - `registry::load_document_lenient` pre-scans the raw bytes
///     and refuses the document above `MAX_RAW_NESTING`, with the parse itself run
///     on a 32 MiB worker stack as defence in depth.
///
/// The depths exercised here span 8..100_000, crossing both bounds. They were
/// pinned to 8..128 while the crash was live, because a guard-page fault aborts
/// the whole test binary; now that both routes are bounded, the full range is
/// safe to assert and is much stronger evidence. Verified with
/// `bisect_nesting_depth` at a 1 MiB stack (the Android-realistic condition, and
/// the one the original thresholds made fatal): `load` and `page` both Completed
/// at depths 48 / 500 / 5000 / 100_000.
///
/// Thresholds measured BEFORE the fix, debug build, for the record:
///   nested `[` via strict content parse:  8 MiB survives 320, dies at 360;
///                                         1 MiB survives  32, dies at  48
///   nested `<< >>` via Document::load_mem: 8 MiB survives 256, dies at 280;
///                                          1 MiB survives  16, dies at  32
#[test]
fn deeply_nested_arrays_and_dictionaries_hit_a_depth_bound() {
    for depth in [8usize, 32, 64, 128, 500, 5_000, 100_000] {
        // Nesting in a content stream operand.
        let mut content = Vec::new();
        content.extend_from_slice(b"BT /F1 12 Tf ");
        content.extend(std::iter::repeat_n(b'[', depth));
        content.extend_from_slice(b"(x)");
        content.extend(std::iter::repeat_n(b']', depth));
        content.extend_from_slice(b" TJ ET");
        let pdf = raw_one_page("", &content, vec![]);
        assert_construct_survives(&format!("content stream with {depth} nested arrays"), pdf);

        // Nesting in a dictionary in the object graph.
        let mut dict = Vec::new();
        for _ in 0..depth {
            dict.extend_from_slice(b"<< /K ");
        }
        dict.extend_from_slice(b"0");
        for _ in 0..depth {
            dict.extend_from_slice(b" >>");
        }
        let pdf = raw_one_page("/DeepNest 5 0 R", b"0 0 10 10 re f", vec![(5, dict)]);
        assert_construct_survives(&format!("object graph with {depth} nested dictionaries"), pdf);

        // Unbalanced: openers only, so the parser never sees a terminator.
        let mut content = vec![b'['; depth];
        content.extend_from_slice(b" 1 2 3 ");
        let pdf = raw_one_page("", &content, vec![]);
        assert_construct_survives(&format!("content stream with {depth} unclosed arrays"), pdf);

        let mut content = Vec::new();
        for _ in 0..depth {
            content.extend_from_slice(b"<< /A ");
        }
        let pdf = raw_one_page("", &content, vec![]);
        assert_construct_survives(&format!("content stream with {depth} unclosed dictionaries"), pdf);
    }
}

/// REGRESSION TEST (was a crash reproduction) — content-stream nesting.
///
/// Originally: `content::page_operations` handed the content stream to lopdf's
/// `Content::decode` BEFORE falling back to `parse_operations_lenient`. The
/// lenient tokenizer has `MAX_DEPTH = 32` and survives 500 levels; the strict
/// parser had no bound, so the guard never ran and the process died with
/// STATUS_STACK_OVERFLOW at ~48 levels on a 1 MiB stack.
///
/// FIXED: `content::strict_operations` now runs `nesting_is_too_deep` over the raw
/// content bytes first and skips `Content::decode` entirely when nesting exceeds
/// `MAX_DEPTH`, so the depth-bounded lenient tokenizer handles the stream instead.
/// The pre-check respects literal-string, hex-string and comment context, so a `[`
/// or `>` inside `(...)` cannot move the count.
#[test]
fn deep_content_stream_nesting_degrades_instead_of_overflowing_the_stack() {
    let depth = 500;
    let mut content = Vec::from(&b"BT /F1 12 Tf "[..]);
    content.extend(std::iter::repeat_n(b'[', depth));
    content.extend_from_slice(b"(x)");
    content.extend(std::iter::repeat_n(b']', depth));
    content.extend_from_slice(b" TJ ET");
    let pdf = raw_one_page("", &content, vec![]);
    assert_construct_survives(&format!("content stream with {depth} nested arrays"), pdf);
}

/// REGRESSION TEST (was a crash reproduction) — object-graph nesting, at OPEN.
///
/// Originally: `load_document_lenient` -> `lopdf::Document::load_mem` recursed per
/// nesting level with no bound. ~280 levels overflowed an 8 MiB stack and ~32 an
/// ordinary 1 MiB worker-thread stack, killing the process. This was both the
/// "doesntopenexample" and the "crashexample" class at once.
///
/// FIXED: `load_document_lenient` pre-scans the raw bytes with `nesting_exceeds`
/// and refuses the document above `MAX_RAW_NESTING` (200) levels before lopdf's
/// recursive object parser is entered, so the process kill becomes a clean "cannot
/// open" per 7.5.1. The parse itself additionally runs on a worker thread with a
/// 32 MiB stack, for nesting the pre-scan cannot see because it only exists after
/// an object stream is decompressed.
///
/// A clean refusal is the CORRECT outcome here, so this asserts "no panic, no
/// hang", not "loads successfully".
#[test]
fn deep_object_graph_nesting_is_refused_cleanly_at_open() {
    let depth = 500;
    let mut dict = Vec::new();
    for _ in 0..depth {
        dict.extend_from_slice(b"<< /K ");
    }
    dict.extend_from_slice(b"0");
    for _ in 0..depth {
        dict.extend_from_slice(b" >>");
    }
    let pdf = raw_one_page("/DeepNest 5 0 R", b"0 0 10 10 re f", vec![(5, dict)]);
    let v = guarded("deep object graph", construct_budget(), move || {
        let loaded = load_document_lenient(&pdf).is_some();
        eprintln!("loaded={loaded}");
    });
    assert!(!v.is_failure(), "{v:?}");
}

/// HAZARD: /Width * /Height * components overflows a usize/u32 product. Release
/// builds have no overflow checks, so this only panics in debug — which is
/// exactly the build these tests run in.
#[test]
fn image_dimension_products_that_overflow_are_refused() {
    let cases: [(&str, &str, &str, &str, &str); 8] = [
        ("u32-square", "65536", "65536", "8", "/DeviceRGB"),
        ("i64-max", "9223372036854775807", "9223372036854775807", "8", "/DeviceRGB"),
        ("u32-max", "4294967295", "4294967295", "16", "/DeviceCMYK"),
        ("negative", "-1", "-1", "8", "/DeviceGray"),
        ("zero", "0", "0", "8", "/DeviceRGB"),
        ("bpc-absurd", "16", "16", "4294967295", "/DeviceRGB"),
        ("bpc-zero", "16", "16", "0", "/DeviceRGB"),
        ("tall-thin", "1", "2147483647", "8", "/DeviceRGB"),
    ];
    for (name, w, h, bpc, cs) in cases {
        let pdf = raw_one_page(
            "/Resources << /XObject << /Im0 5 0 R >> >>",
            b"q 600 0 0 700 0 0 cm /Im0 Do Q",
            vec![(
                5,
                raw_stream(
                    &format!(
                        "<< /Type /XObject /Subtype /Image /Width {w} /Height {h} \
                         /BitsPerComponent {bpc} /ColorSpace {cs} /Length 16 >>"
                    ),
                    &[0x7Fu8; 16],
                ),
            )],
        );
        assert_construct_survives(&format!("image dimensions {name}: {w}x{h}x{bpc} {cs}"), pdf);
    }
}

/// HAZARD: an /Indexed colour space with an absurd /HiVal, and Separation /
/// DeviceN with an absurd component count, both size lookup tables from
/// attacker-controlled numbers.
#[test]
fn absurd_colorspace_declarations_do_not_size_a_lookup_table() {
    let cases: [(&str, String); 5] = [
        (
            "indexed-hival-u32max",
            "[/Indexed /DeviceRGB 4294967295 <00FF00>]".to_string(),
        ),
        (
            "indexed-hival-negative",
            "[/Indexed /DeviceRGB -1 <00FF00>]".to_string(),
        ),
        (
            "indexed-base-is-itself",
            "[/Indexed 5 0 R 255 <00FF00>]".to_string(),
        ),
        (
            "devicen-many-components",
            format!("[/DeviceN [{}] /DeviceRGB 6 0 R]", "/C ".repeat(4096)),
        ),
        (
            "lab-absurd-range",
            "[/Lab << /WhitePoint [1 1 1] /Range [-2147483648 2147483647 -2147483648 2147483647] >>]"
                .to_string(),
        ),
    ];
    for (name, cs) in cases {
        let pdf = raw_one_page(
            "/Resources << /ColorSpace << /CS0 5 0 R >> >>",
            b"/CS0 cs 0 0 0 sc 0 0 400 400 re f",
            vec![
                (5, cs.into_bytes()),
                (
                    6,
                    b"<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0] /C1 [1 1 1] /N 1 >>".to_vec(),
                ),
            ],
        );
        assert_construct_survives(&format!("colour space {name}"), pdf);
    }
}

/// HAZARD: a mesh shading declaring an absurd patch/vertex count, or bit-packed
/// coordinate widths that do not divide, must be bounded by the actual stream
/// length rather than the declaration.
#[test]
fn mesh_shading_absurd_counts_are_bounded_by_the_stream() {
    for shading_type in [4i64, 5, 6, 7] {
        let dict = format!(
            "<< /ShadingType {shading_type} /ColorSpace /DeviceRGB \
             /BitsPerCoordinate 32 /BitsPerComponent 16 /BitsPerFlag 8 \
             /VerticesPerRow 2147483647 \
             /Decode [0 1 0 1 0 1 0 1 0 1] /Length 4 >>"
        );
        let pdf = raw_one_page(
            "/Resources << /Shading << /Sh0 5 0 R >> >>",
            b"q 0 0 600 700 re W n /Sh0 sh Q",
            vec![(5, raw_stream(&dict, &[0xFFu8; 4]))],
        );
        assert_construct_survives(
            &format!("mesh shading type {shading_type} with absurd /VerticesPerRow"),
            pdf,
        );
    }
}
