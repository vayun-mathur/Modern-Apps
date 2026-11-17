fn inline_images(page: &PageData) -> Vec<(u32, u32)> {
    page.prims
        .iter()
        .filter_map(|p| match p {
            Prim::Image { w, h, .. } => Some((*w, *h)),
            _ => None,
        })
        .collect()
}

/// Control for the four tests below: the same wrapper with NO inline image in it
/// must render the marker. Without this, a failure in those tests could just mean
/// the fixture's own content stream is malformed.
#[test]
fn inline_image_wrapper_without_an_image_renders_its_marker() {
    let mut doc = Document::with_version("1.5");
    let page_id = inline_image_page(&mut doc, b"");
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        marker_survived(&page),
        "the inline-image test wrapper must itself be valid content"
    );
}

/// §8.9.7: `EI` only ends an inline image once the expected
/// `W*H*BPC*ncomp/8` bytes have been consumed — a token boundary alone is not
/// enough. Binary pixel data frequently contains the bytes "EI"; treating that as
/// the terminator truncates the image and desynchronises every operator after it.
///
/// This payload is deliberately the worst case: the fake `EI` is whitespace
/// delimited on BOTH sides *and* followed by a real operator token (`Q`), so it
/// looks like a genuine end-of-image to every scan-based heuristic, including one
/// that validates what follows. Only computing the data length and skipping
/// exactly that many bytes gets this right. Do not relax it.
#[test]
fn inline_image_binary_data_containing_ei_is_not_truncated() {
    let mut doc = Document::with_version("1.5");
    // 4x4 8-bit gray = 16 bytes, holding the sequence " EI Q " mid-row.
    let mut px: Vec<u8> = (0u8..16).map(|i| i.wrapping_mul(17).wrapping_add(1)).collect();
    px[5..11].copy_from_slice(b" EI Q ");
    let mut bi = b"BI /W 4 /H 4 /CS /G /BPC 8 ID ".to_vec();
    bi.extend_from_slice(&px);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        marker_survived(&page),
        "the operators after the inline image must still run — an embedded \
         \" EI Q \" must not desynchronise (or drop) the rest of the content stream"
    );
    assert!(
        inline_images(&page).contains(&(4, 4)),
        "the full 4x4 inline image must be decoded, not truncated at the embedded \
         \"EI\"; got {:?}", inline_images(&page)
    );
}

/// §8.9.7 Table 93: `/IM true` is an inline stencil mask, for which `/BPC` is
/// implicitly 1 and `/D` may be omitted. This is the single most common inline
/// image in the wild (every scanned-fax overlay is one) and it is exactly the
/// form that trips a decoder requiring `/BPC` — so rejecting it costs the page.
#[test]
fn inline_stencil_mask_without_bpc_renders() {
    let mut doc = Document::with_version("1.5");
    // 8x2 stencil at an implicit 1 bpc = 1 byte per row.
    let mut bi = b"BI /W 8 /H 2 /IM true ID ".to_vec();
    bi.extend_from_slice(&[0b1010_1010, 0b0101_0101]);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        marker_survived(&page),
        "an /IM true stencil with no /BPC must not cost the rest of the content stream"
    );
    assert!(
        inline_images(&page).contains(&(8, 2)),
        "/BPC is implicitly 1 for /IM true; the stencil must decode. Got {:?}",
        inline_images(&page)
    );
}

/// §8.9.7: the same embedded-`EI` hazard, but NOT whitespace-delimited. A decoder
/// that scans for a token-boundary `EI` survives this one while still failing the
/// whitespace-delimited case above, so keeping both separates "handles the easy
/// case" from "computes the data length and does not scan at all".
#[test]
fn inline_image_data_containing_a_bare_ei_is_not_truncated() {
    let mut doc = Document::with_version("1.5");
    let mut px: Vec<u8> = (0u8..16).map(|i| i.wrapping_mul(9).wrapping_add(3)).collect();
    px[9] = b'E';
    px[10] = b'I';
    let mut bi = b"BI /W 4 /H 4 /CS /G /BPC 8 ID ".to_vec();
    bi.extend_from_slice(&px);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "a bare \"EI\" in the pixel data must not end the image");
    assert!(
        inline_images(&page).contains(&(4, 4)),
        "the full 4x4 image must decode; got {:?}", inline_images(&page)
    );
}
/// §8.9.7 + §8.10.1: an inline image inside a FORM XOBJECT must not cost the
/// form's other content. Nested content streams are decoded with a bare
/// `Content::decode` whose failure drops the whole stream, so one `BI` blanks the
/// entire form — the same all-or-nothing failure already fixed at page level.
#[test]
fn inline_image_inside_a_form_xobject_keeps_the_rest_of_the_form() {
    let mut doc = Document::with_version("1.5");
    // No /BPC, which is one of the forms a strict inline-image parser rejects.
    let mut form: Vec<u8> = b"q 20 0 0 20 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    form.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    form.extend_from_slice(b" EI\nQ\n0 1 0 rg\n5 5 30 30 re f\n");
    let form_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        },
        form,
    ));
    let ops = vec![Operation::new("Do", vec![Object::Name(b"Fm0".to_vec())])];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "XObject" => dictionary! { "Fm0" => form_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        ink_in_region(&page.prims, 5.0, 5.0, 36.0, 36.0),
        "the form's own rectangle, drawn AFTER the inline image, must still paint — \
         one BI must not blank the whole form XObject"
    );
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the inline image inside the form must decode; got {:?}",
        inline_images(&page)
    );
}

/// §8.9.7 + §8.7.3.1: the same hazard inside a TILING PATTERN cell. A pattern
/// cell is its own content stream, so an inline image in one blanks every tile.
#[test]
fn inline_image_inside_a_tiling_pattern_cell_keeps_the_rest_of_the_cell() {
    let mut doc = Document::with_version("1.5");
    let mut cell: Vec<u8> = b"q 10 0 0 10 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    cell.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    cell.extend_from_slice(b" EI\nQ\n1 0 0 rg\n0 0 10 10 re f\n");
    let pid = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "Pattern", "PatternType" => 1, "PaintType" => 1, "TilingType" => 1,
            "BBox" => vec![0.into(), 0.into(), 20.into(), 20.into()],
            "XStep" => 20, "YStep" => 20,
            "Resources" => dictionary! {},
        },
        cell,
    ));
    let region = fill_region(0.0, 0.0, 100.0, 100.0);
    let mut prims = Vec::new();
    paint_pattern_fill(PatternFill { doc: &doc, pattern_id: pid, polys: &region, even_odd: false, pattern_base_ctm: &IDENTITY, base_argb: 0xFF00_0000, alpha_fill: 1.0, blend: BlendMode::Normal, cs_resources: &HashMap::new(), prims: &mut prims, depth: 0, clip_depth: 0 });
    let inked = prims.iter().filter(|p| is_ink(p)).count();
    assert!(
        inked > 4,
        "each tile's own content must still paint after the inline image; got \
         {inked} inking prims across a 5x5 lattice"
    );
}

/// §8.9.7 + §11.6.5.2: the same hazard inside a SOFT-MASK GROUP. If the group's
/// stream is dropped the mask has no shape, which for a luminosity mask means
/// alpha 0 everywhere and the masked content vanishes entirely.
#[test]
fn inline_image_inside_a_soft_mask_group_keeps_the_rest_of_the_group() {
    let mut doc = Document::with_version("1.7");
    let mut mask: Vec<u8> = b"q 50 0 0 50 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    mask.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    mask.extend_from_slice(b" EI\nQ\n1 1 1 rg\n0 0 200 200 re f\n");
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 200.into(), 200.into()],
            "Group" => dictionary! { "S" => "Transparency" },
        },
        mask,
    ));
    let gs_id = doc.add_object(dictionary! {
        "SMask" => dictionary! { "S" => "Luminosity", "G" => Object::Reference(mask_id) },
    });
    let ops = vec![
        Operation::new("gs", vec![Object::Name(b"GS1".to_vec())]),
        Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()]),
        Operation::new("re", vec![10.into(), 10.into(), 50.into(), 50.into()]),
        Operation::new("f", vec![]),
    ];
    let page_id = page_from_ops(
        &mut doc,
        ops,
        dictionary! { "ExtGState" => dictionary! { "GS1" => gs_id } },
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let sep = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskContent))
        .expect("SoftMaskContent");
    let pop = page
        .prims
        .iter()
        .position(|p| matches!(p, Prim::SoftMaskPop))
        .expect("SoftMaskPop");
    // The mask group's white rectangle is what gives the mask any luminance at all.
    let mask_has_shape = page.prims[sep..pop]
        .iter()
        .any(|p| matches!(p, Prim::Fill { .. } | Prim::Image { .. }));
    assert!(
        mask_has_shape,
        "the mask group's own content, drawn after the inline image, must survive — \
         a luminosity mask with no shape means alpha 0 everywhere and the masked \
         content disappears"
    );
}

/// §8.9.7 + §12.5.5: the same hazard inside an ANNOTATION APPEARANCE stream. An
/// `/AP /N` form is its own content stream, so one `BI` blanks the whole
/// appearance — and an annotation with no appearance is invisible, which for a
/// stamp or a signature is a missing seal rather than a missing decoration.
#[test]
fn inline_image_inside_an_annotation_appearance_keeps_the_rest_of_it() {
    let mut doc = Document::with_version("1.7");
    let mut ap: Vec<u8> = b"q 20 0 0 20 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    ap.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    ap.extend_from_slice(b" EI\nQ\n0 1 0 rg\n10 10 60 60 re f\n");
    let ap_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Form",
            "BBox" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        },
        ap,
    ));
    let annot = doc.add_object(dictionary! {
        "Type" => "Annot", "Subtype" => "Stamp",
        "Rect" => vec![0.into(), 0.into(), 100.into(), 100.into()],
        "AP" => dictionary! { "N" => ap_id },
    });
    let mut page = dictionary! { "Annots" => vec![annot.into()] };
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Null,
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        ink_in_region(&page.prims, 10.0, 10.0, 71.0, 71.0),
        "the appearance's own rectangle, drawn AFTER the inline image, must still \
         paint — one BI must not blank the whole /AP /N stream"
    );
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the inline image inside the appearance must decode; got {:?}",
        inline_images(&page)
    );
}

/// The SEARCH INDEX must survive the same failure as rendering. A page's text is
/// extracted by re-interpreting its content stream, so if one inline image loses
/// the operator list the page becomes unsearchable — silently, because nothing
/// visibly breaks. Guards the index against the all-or-nothing tokenizer failure
/// that was fixed for the render path.
#[test]
fn search_index_survives_an_inline_image_in_the_page_content() {
    let mut doc = Document::with_version("1.5");
    let font_id = doc.add_object(dictionary! {
        "Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Helvetica",
    });
    // An inline image with no /BPC, then the text that must remain findable.
    let mut raw: Vec<u8> = b"q 20 0 0 20 0 0 cm\nBI /W 2 /H 2 /CS /G ID ".to_vec();
    raw.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    raw.extend_from_slice(
        b" EI\nQ\nBT /F1 12 Tf 72 700 Td (Findable) Tj ET\n",
    );
    let page_id = assemble(
        &mut doc,
        raw,
        dictionary! { "Font" => dictionary! { "F1" => font_id } },
        dictionary! {},
        dictionary! {},
    );
    // Sanity: the page must render its inline image, so the index and the render
    // path are being fed the same recovered operator list.
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "precondition: the inline image should decode on the render path too"
    );

    let index = build_index(&doc);
    let joined: String = index.iter().map(|p| p.text_orig.as_str()).collect();
    assert!(
        joined.contains("Findable"),
        "text after an inline image must still reach the search index; got {joined:?}"
    );
    let hits = search_document_inner(&index, "Findable", false);
    assert!(
        !hits.is_empty(),
        "and it must be locatable, so a tap can highlight it"
    );
}
#[test]
fn inline_image_with_abbreviated_gray_colorspace_renders() {
    let mut doc = Document::with_version("1.5");
    let mut bi = b"BI /W 2 /H 2 /CS /G /BPC 8 ID ".to_vec();
    bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "/CS /G must not cost the rest of the content stream");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the /CS /G inline image must decode; got {:?}", inline_images(&page)
    );
}

/// §8.9.7 Table 93: `/F` (`/Filter`) is legal on an inline image, with the same
/// abbreviations. `/AHx` here so the payload stays ASCII.
#[test]
fn inline_image_with_a_filter_renders() {
    let mut doc = Document::with_version("1.5");
    let bi = b"BI /W 2 /H 2 /CS /G /BPC 8 /F /AHx ID 004080FF> EI".to_vec();
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "an inline image with /F must not cost the rest of the stream");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "the filtered inline image must decode; got {:?}", inline_images(&page)
    );
}

/// §8.9.7: `/BPC` is required on a non-stencil inline image, but real files omit
/// it. Defaulting to 8 keeps the page; rejecting it loses everything after `BI`.
#[test]
fn inline_image_without_bpc_defaults_to_eight_bits() {
    let mut doc = Document::with_version("1.5");
    let mut bi = b"BI /W 2 /H 2 /CS /G ID ".to_vec();
    bi.extend_from_slice(&[0x00, 0x40, 0x80, 0xFF]);
    bi.extend_from_slice(b" EI");
    let page_id = inline_image_page(&mut doc, &bi);

    let page = interpret_page(&doc, page_id).expect("interpret");
    assert!(marker_survived(&page), "a missing /BPC must not cost the rest of the stream");
    assert!(
        inline_images(&page).contains(&(2, 2)),
        "a missing /BPC must default to 8 rather than dropping the image; got {:?}",
        inline_images(&page)
    );
}
