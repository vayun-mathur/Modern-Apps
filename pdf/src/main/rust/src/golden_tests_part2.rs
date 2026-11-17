/// §7.7.3.3 + §14.11.2: `/Rotate` is baked into the emitted coordinates, and the
/// reported size is the DISPLAY size (swapped for quarter turns). For every
/// rotation the same content must land on the canvas.
#[test]
fn content_lands_on_canvas_for_every_rotation() {
    for rot in [0i64, 90, 180, 270] {
        let mut doc = Document::with_version("1.5");
        let bytes = Content { operations: rect_ops(50, 50, 100, 100) }.encode().unwrap();
        let page_id = assemble(
            &mut doc,
            bytes,
            dictionary! {},
            dictionary! {
                "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()],
                "Rotate" => rot,
            },
            dictionary! {},
        );
        let page = interpret_page(&doc, page_id).expect("interpret");
        let (ew, eh) = if rot == 90 || rot == 270 { (500.0, 400.0) } else { (400.0, 500.0) };
        assert_eq!(
            (page.width, page.height), (ew, eh),
            "/Rotate {rot}: reported size must be the display size"
        );
        let pts = all_points(&page.prims);
        assert!(!pts.is_empty(), "/Rotate {rot}: the rectangle must be emitted");
        for &(x, y) in &pts {
            assert!(
                x >= -0.5 && x <= page.width + 0.5 && y >= -0.5 && y <= page.height + 0.5,
                "/Rotate {rot}: point ({x},{y}) fell off the {}x{} canvas",
                page.width, page.height
            );
        }
        // A 100x100 square stays a 100x100 square under any quarter turn.
        let b = bbox_of(&pts);
        assert!(
            (b[2] - b[0] - 100.0).abs() < 0.5 && (b[3] - b[1] - 100.0).abs() < 0.5,
            "/Rotate {rot}: the square was distorted to {}x{}",
            b[2] - b[0], b[3] - b[1]
        );
    }
}

/// §14.11.2: `/CropBox` sets the visible page, and its origin is baked into the
/// emitted coordinates — Kotlin must not re-apply it. Content at the CropBox
/// origin therefore lands at (0,0).
#[test]
fn crop_box_smaller_than_media_box_moves_its_origin_to_zero() {
    let mut doc = Document::with_version("1.5");
    let bytes = Content { operations: rect_ops(100, 100, 200, 300) }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! {},
        dictionary! {
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            "CropBox" => vec![100.into(), 100.into(), 300.into(), 400.into()],
        },
        dictionary! {},
    );
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!((page.width, page.height), (200.0, 300.0), "size is the CropBox, not the MediaBox");
    let b = bbox_of(&all_points(&page.prims));
    assert!(
        b[0].abs() < 0.5 && b[1].abs() < 0.5,
        "the CropBox origin must be baked in: rect started at ({}, {}), expected (0, 0)",
        b[0], b[1]
    );
    assert!(
        b[2] <= page.width + 0.5 && b[3] <= page.height + 0.5,
        "and the content must still fit the canvas"
    );
}

/// §7.9.5: a rectangle may be given with its corners in any order. A `/CropBox`
/// written as [x1 y1 x0 y0] must be normalized, not treated as empty or negative.
#[test]
fn crop_box_with_inverted_corner_order_is_normalized() {
    let mut doc = Document::with_version("1.5");
    let bytes = Content { operations: rect_ops(100, 100, 200, 300) }.encode().unwrap();
    let page_id = assemble(
        &mut doc,
        bytes,
        dictionary! {},
        dictionary! {
            "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
            // Upper-right corner first — the same box as the test above.
            "CropBox" => vec![300.into(), 400.into(), 100.into(), 100.into()],
        },
        dictionary! {},
    );
    let page = interpret_page(&doc, page_id).expect("interpret");
    assert_eq!(
        (page.width, page.height), (200.0, 300.0),
        "an inverted /CropBox must normalize to the same 200x300 box"
    );
    let b = bbox_of(&all_points(&page.prims));
    assert!(b[0].abs() < 0.5 && b[1].abs() < 0.5, "and place content at the origin");
}

/// §14.11.2: `/UserUnit` scales the *physical* interpretation of a unit; it must
/// not scale the geometry we emit, or every page with it renders at the wrong
/// size and content walks off the canvas.
#[test]
fn user_unit_does_not_move_content_or_resize_the_page() {
    let mut sizes = Vec::new();
    let mut origins = Vec::new();
    for uu in [1i64, 5] {
        let mut doc = Document::with_version("1.6");
        let bytes = Content { operations: rect_ops(50, 50, 100, 100) }.encode().unwrap();
        let page_id = assemble(
            &mut doc,
            bytes,
            dictionary! {},
            dictionary! {
                "MediaBox" => vec![0.into(), 0.into(), 400.into(), 500.into()],
                "UserUnit" => uu,
            },
            dictionary! {},
        );
        let page = interpret_page(&doc, page_id).expect("interpret");
        sizes.push((page.width, page.height));
        origins.push(bbox_of(&all_points(&page.prims)));
    }
    assert_eq!(sizes[0], sizes[1], "/UserUnit must not change the reported page size");
    assert_eq!(sizes[0], (400.0, 500.0));
    for i in 0..4 {
        assert!(
            (origins[0][i] - origins[1][i]).abs() < 0.5,
            "/UserUnit must not move content: {:?} vs {:?}", origins[0], origins[1]
        );
    }
}

/// `page_base_inverse` must undo `page_base_matrix` exactly, for every rotation
/// AND with a CropBox in play — hit-testing a tap back into PDF space depends on
/// it, so an approximate inverse puts every tap on the wrong annotation.
#[test]
fn page_base_inverse_round_trips_with_a_crop_box_and_rotation() {
    for rot in [0i64, 90, 180, 270] {
        let mut doc = Document::with_version("1.5");
        let bytes = Content { operations: rect_ops(0, 0, 1, 1) }.encode().unwrap();
        let page_id = assemble(
            &mut doc,
            bytes,
            dictionary! {},
            dictionary! {
                "MediaBox" => vec![0.into(), 0.into(), 612.into(), 792.into()],
                "CropBox" => vec![37.into(), 61.into(), 337.into(), 461.into()],
                "Rotate" => rot,
            },
            dictionary! {},
        );
        let fwd = page_base_matrix(&doc, page_id);
        let inv = page_base_inverse(&doc, 0);
        for (x, y) in [(37.0, 61.0), (337.0, 461.0), (200.0, 123.5)] {
            let (dx, dy) = transform(&fwd, x, y);
            let (rx, ry) = transform(&inv, dx, dy);
            assert!(
                (rx - x).abs() < 1e-3 && (ry - y).abs() < 1e-3,
                "/Rotate {rot}: ({x},{y}) -> ({dx},{dy}) -> ({rx},{ry}) is not a round trip"
            );
        }
    }
}

// ===========================================================================
// 7. Images and masks — §8.9.6

fn no_cs() -> HashMap<Vec<u8>, ObjectId> {
    HashMap::new()
}

fn alphas(img: &ImageData) -> Vec<u8> {
    assert_eq!(img.format, 0, "expected decoded RGBA, not a passthrough format");
    img.data.chunks_exact(4).map(|px| px[3]).collect()
}

/// §8.9.6.2: in a stencil mask with the default `/Decode [0 1]`, sample value 0
/// marks the places that ARE painted with the current fill colour; 1 leaves the
/// page untouched.
#[test]
fn stencil_image_mask_paints_where_the_sample_bit_is_zero() {
    let doc = Document::with_version("1.5");
    // 8x1, bits 1010 1010: even x painted? no — bit 1 means "leave alone".
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 1, "ImageMask" => true, "BitsPerComponent" => 1,
        },
        vec![0b1010_1010u8],
    );
    let img = extract_image(&doc, &stream, 0xFF00_FF00, &no_cs()).expect("stencil must decode");
    assert_eq!((img.w, img.h), (8, 1));
    let a = alphas(&img);
    for (x, alpha) in a.iter().enumerate().take(8) {
        let bit = (0b1010_1010u8 >> (7 - x)) & 1;
        let want = if bit == 0 { 255 } else { 0 };
        assert_eq!(*alpha, want, "x={x}: sample bit {bit} must give alpha {want}");
    }
    // The painted pixels carry the fill colour, not black.
    let px = &img.data[4..8];
    assert_eq!((px[0], px[1], px[2]), (0, 255, 0), "painted stencil pixels take the fill colour");
}

/// §8.9.6.2 + §8.9.5.2: `/Decode [1 0]` inverts a stencil, so exactly the
/// complementary set of pixels is painted. Inverting it twice (or not at all) is
/// the classic "the mask came out negative" bug.
#[test]
fn stencil_image_mask_decode_one_zero_inverts_exactly_once() {
    let doc = Document::with_version("1.5");
    let mk = |inverted: bool| {
        let mut d = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 1, "ImageMask" => true, "BitsPerComponent" => 1,
        };
        if inverted {
            d.set("Decode", vec![1.into(), 0.into()]);
        }
        Stream::new(d, vec![0b1010_1010u8])
    };
    let plain = alphas(&extract_image(&doc, &mk(false), 0xFF00_0000, &no_cs()).unwrap());
    let inv = alphas(&extract_image(&doc, &mk(true), 0xFF00_0000, &no_cs()).unwrap());
    for x in 0..8 {
        assert_ne!(
            plain[x], inv[x],
            "x={x}: /Decode [1 0] must flip coverage, got {} both ways", plain[x]
        );
    }
    assert!(plain.contains(&255) && inv.contains(&255), "both must paint something");
}

/// §8.9.5.4: an `/SMask` sample IS the alpha, so sample 0 is transparent.
#[test]
fn smask_sample_value_becomes_the_alpha_channel() {
    let mut doc = Document::with_version("1.5");
    let smask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
        },
        vec![0x00, 0xFF],
    ));
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            "SMask" => smask_id,
        },
        vec![0xFF, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    assert_eq!(alphas(&img), vec![0, 255], "/SMask sample 0 is transparent, 255 is opaque");
}

/// §8.9.6.4 vs §8.9.6.5: an explicit `/Mask` stencil has the OPPOSITE polarity
/// to an `/SMask`. For the same low/high sample pair, `/SMask` gives alpha
/// (0, 255) while `/Mask` gives (255, 0) — sample 1 means "masked OUT". Getting
/// this backwards makes masked images render as their own negative.
#[test]
fn explicit_mask_polarity_is_the_opposite_of_smask() {
    let mut doc = Document::with_version("1.5");
    // Stencil bits 0,1 for x=0,1 -> masked out where the bit is 1.
    let mask_id = doc.add_object(Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1, "ImageMask" => true, "BitsPerComponent" => 1,
        },
        vec![0b0100_0000u8],
    ));
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 2, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            "Mask" => mask_id,
        },
        vec![0xFF, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    assert_eq!(
        alphas(&img), vec![255, 0],
        "/Mask stencil bit 0 keeps the pixel and bit 1 removes it — the mirror of /SMask"
    );
}

/// §8.9.6.4: a colour-key `/Mask` array makes every pixel whose components all
/// fall in the given ranges transparent, and leaves the others alone.
#[test]
fn color_key_mask_makes_only_in_range_pixels_transparent() {
    let doc = Document::with_version("1.5");
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 3, "Height" => 1,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 8,
            // Mask out samples in [0, 8] only.
            "Mask" => vec![0.into(), 8.into()],
        },
        vec![0x00, 0x80, 0xFF],
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("image must decode");
    assert_eq!(
        alphas(&img), vec![0, 255, 255],
        "only the sample inside the colour-key range becomes transparent"
    );
}

// ===========================================================================
// 8. Filters — §7.4

/// §7.4: a Flate stream truncated mid-way must yield the PARTIAL content it did
/// manage to inflate. Returning nothing turns a slightly-damaged page into a
/// blank one, which is the difference between "mostly readable" and "broken".
#[test]
fn truncated_flate_stream_yields_partial_content_not_nothing() {
    let plain: Vec<u8> = (0u8..250).cycle().take(4000).collect();
    let full = flate(&plain);
    assert!(full.len() > 40, "need a stream long enough to truncate meaningfully");
    let cut = &full[..full.len() * 2 / 3];
    let doc = Document::with_version("1.5");
    let got = crate::filters::decode_flate(cut);
    let got = got.expect("a truncated Flate stream must still return what inflated");
    assert!(
        !got.is_empty() && got.len() < plain.len(),
        "expected a partial inflate, got {} of {} bytes", got.len(), plain.len()
    );
    assert_eq!(&got[..64], &plain[..64], "the recovered prefix must be byte-correct");
    // And the same through the dictionary-driven chain the image/content layer uses.
    let dict = dictionary! { "Filter" => "FlateDecode" };
    let via_chain = crate::filters::decode_stream_chain(
        cut.to_vec(),
        &crate::filters::filter_specs_from_dict(&doc, &dict),
        &doc,
    )
    .expect("the filter chain must also surface partial output");
    assert_eq!(via_chain, got);
}

/// §7.4.4.4: the PNG predictor's row length is
/// `ceil(Columns * Colors * BitsPerComponent / 8)`. The round-1 bug computed it
/// as `bytes-per-pixel * Columns`, which is only correct at BPC >= 8 — every
/// sub-byte image decoded as garbage. Drive a real 1-bpc image end to end.
#[test]
fn png_predictor_row_length_is_correct_at_one_bit_per_component() {
    let doc = Document::with_version("1.5");
    // 16 columns at 1 bpc, 1 colour => 2 bytes per row.
    // Row 0: filter 0 (None)  data FF 00  -> 8 white then 8 black
    // Row 1: filter 2 (Up)    data 00 FF  -> FF, FF => all white
    let raw = vec![0u8, 0xFF, 0x00, 2u8, 0x00, 0xFF];
    let stream = Stream::new(
        dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 16, "Height" => 2,
            "ColorSpace" => "DeviceGray", "BitsPerComponent" => 1,
            "Filter" => "FlateDecode",
            "DecodeParms" => dictionary! {
                "Predictor" => 15, "Colors" => 1, "BitsPerComponent" => 1, "Columns" => 16,
            },
        },
        flate(&raw),
    );
    let img = extract_image(&doc, &stream, 0xFF00_0000, &no_cs()).expect("1-bpc predictor image must decode");
    assert_eq!((img.w, img.h), (16, 2));
    let lum = |x: u32, y: u32| img.data[((y * img.w + x) * 4) as usize];
    assert_eq!(lum(0, 0), 255, "row 0 left half is white");
    assert_eq!(lum(8, 0), 0, "row 0 right half is black — the row is 2 bytes, not 16");
    assert_eq!(lum(0, 1), 255, "row 1 came from the Up filter over a correct 2-byte row");
    assert_eq!(lum(8, 1), 255);
}

/// §7.4.4.2: an LZW stream that just stops, with no EOD (257) marker, must keep
/// everything decoded so far. Encoded here with literal codes only, so the
/// expected output is exact.
#[test]
fn lzw_stream_without_an_eod_marker_keeps_what_it_decoded() {
    /// 9-bit LZW: ClearTable then one literal code per byte. No EOD emitted.
    fn lzw_literals(bytes: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut acc: u32 = 0;
        let mut nbits: u32 = 0;
        for code in std::iter::once(256u32).chain(bytes.iter().map(|&b| b as u32)) {
            acc = (acc << 9) | code;
            nbits += 9;
            while nbits >= 8 {
                nbits -= 8;
                out.push((acc >> nbits) as u8);
            }
        }
        if nbits > 0 {
            out.push((acc << (8 - nbits)) as u8);
        }
        out
    }
    let plain = b"1 0 0 rg 10 10 50 50 re f";
    let got = crate::filters::decode_lzw(&lzw_literals(plain), true)
        .expect("an LZW stream with no EOD must still decode");
    assert_eq!(&got[..], &plain[..], "every literal code before the missing EOD must survive");
}

/// §7.4 end to end: a page whose content stream is a truncated Flate must still
/// render the operators that did survive, rather than becoming a blank page.
#[test]
fn truncated_flate_content_stream_still_renders_its_leading_operators() {
    let mut doc = Document::with_version("1.5");
    let mut ops = vec![Operation::new("rg", vec![1.0.into(), 0.0.into(), 0.0.into()])];
    for i in 0..300 {
        ops.extend(rect_ops(10 + (i % 50) * 2, 10, 5, 5));
    }
    let plain = Content { operations: ops }.encode().unwrap();
    let full = flate(&plain);
    let cut = full[..full.len() * 3 / 4].to_vec();
    let cid = doc.add_object(Stream::new(dictionary! { "Filter" => "FlateDecode" }, cut));
    let mut page = dictionary! {};
    let page_id = assemble_with_contents(
        &mut doc,
        Object::Reference(cid),
        dictionary! {},
        &mut page,
        dictionary! {},
    );

    let page = interpret_page(&doc, page_id).expect("interpret");
    let fills = count(&page.prims, |p| matches!(p, Prim::Fill { .. }));
    assert!(
        fills > 0,
        "a content stream truncated at 75% must still render its leading operators, \
         not collapse to a blank page (got 0 fills of ~300)"
    );
}

// ===========================================================================
// 9. Text metrics — §9.4.4, §9.6.2, §9.3.3
