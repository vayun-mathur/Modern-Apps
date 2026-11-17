#[cfg(test)]
mod dct_decode_tests {
    use super::*;

    fn dict_with_decode(entries: Vec<Object>) -> Dictionary {
        dictionary! {
            "Width" => 1, "Height" => 1, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceCMYK",
            "Decode" => entries,
        }
    }

    #[test]
    fn inverted_decode_array_detected_only_in_its_exact_form() {
        let doc = Document::with_version("1.7");
        let none = HashMap::new();
        let one = Object::Integer;

        let cmyk = dict_with_decode(vec![one(1), one(0), one(1), one(0), one(1), one(0), one(1), one(0)]);
        assert!(decode_array_is_inverted(&doc, &cmyk, 4, &none), "[1 0]x4 on a CMYK image is the inverted form");

        // The identity is the Table 90 default and must stay a no-op.
        let identity = dict_with_decode(vec![one(0), one(1), one(0), one(1), one(0), one(1), one(0), one(1)]);
        assert!(!decode_array_is_inverted(&doc, &identity, 4, &none));

        // Too short for the component count: not every component is inverted.
        let short = dict_with_decode(vec![one(1), one(0)]);
        assert!(!decode_array_is_inverted(&doc, &short, 4, &none));

        // Mixed: only some components inverted. Not the complement.
        let mixed = dict_with_decode(vec![one(1), one(0), one(0), one(1)]);
        assert!(!decode_array_is_inverted(&doc, &mixed, 2, &none));

        // A general affine remap is NOT the complement and must keep the passthrough.
        let partial = dict_with_decode(vec![Object::Real(0.2), Object::Real(0.8)]);
        assert!(!decode_array_is_inverted(&doc, &partial, 1, &none));

        // Absent /Decode, and an unknown component count.
        let bare = dictionary! { "Width" => 1, "Height" => 1, "ColorSpace" => "DeviceGray" };
        assert!(!decode_array_is_inverted(&doc, &bare, 1, &none));
        assert!(!decode_array_is_inverted(&doc, &cmyk, 0, &none));
    }

    // Inline images abbreviate /Decode to /D (Table 93) and carry the same bug.
    #[test]
    fn inline_abbreviation_d_is_read() {
        let doc = Document::with_version("1.7");
        let d = dictionary! {
            "W" => 1, "H" => 1, "BPC" => 8, "CS" => "DeviceGray",
            "D" => vec![1.into(), 0.into()],
        };
        assert!(decode_array_is_inverted(&doc, &d, 1, &HashMap::new()));
    }

    // An Indexed image's /Decode maps onto the PALETTE INDEX range (Table 90), not a
    // 0..1 colour range, so `1 - v` is not its complement and the component-inversion
    // path must not claim it. Lab's ranges are the same argument.
    #[test]
    fn indexed_and_lab_are_excluded() {
        let mut doc = Document::with_version("1.7");
        let lookup = doc.add_object(Object::Stream(Stream::new(dictionary! {}, vec![0u8; 6])));
        let idx = Object::Array(vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(1),
            Object::Reference(lookup),
        ]);
        let d = dictionary! {
            "Width" => 1, "Height" => 1, "BitsPerComponent" => 8,
            "ColorSpace" => idx,
            "Decode" => vec![1.into(), 0.into()],
        };
        assert!(!decode_array_is_inverted(&doc, &d, 1, &HashMap::new()));

        let lab = Object::Array(vec![
            Object::Name(b"Lab".to_vec()),
            Object::Dictionary(dictionary! {
                "WhitePoint" => vec![Object::Real(0.9505), Object::Real(1.0), Object::Real(1.089)],
            }),
        ]);
        let dl = dictionary! {
            "Width" => 1, "Height" => 1, "BitsPerComponent" => 8,
            "ColorSpace" => lab,
            "Decode" => vec![1.into(), 0.into(), 1.into(), 0.into(), 1.into(), 0.into()],
        };
        assert!(!decode_array_is_inverted(&doc, &dl, 3, &HashMap::new()));
    }

    /// A hand-assembled 1x1 baseline CMYK JPEG whose four DC coefficients decode to
    /// raw samples C=0, M=Y=K=255. `jpeg-decoder` un-inverts the Adobe convention, so
    /// this reaches our CMYK arm as components (255, 0, 0, 0) — pure cyan.
    ///
    /// The quant table is all 8s, so a DC diff of +127 dequantizes to 1016, the DC-only
    /// IDCT gives 127 and the level shift makes it 255; a diff of -128 gives 0. The DC
    /// Huffman table codes category 7 as "0" and category 8 as "10"; the AC table codes
    /// EOB as "0". The five entropy bytes are
    ///   C: "10"+"01111111"+"0", then M,Y,K: "0"+"1111111"+"0", padded with 1s.
    pub(super) fn cyan_cmyk_jpeg() -> Vec<u8> {
        let mut d: Vec<u8> = vec![0xFF, 0xD8];
        // APP14 Adobe, transform 0 (CMYK, not YCCK).
        d.extend_from_slice(&[0xFF, 0xEE, 0x00, 0x0E]);
        d.extend_from_slice(b"Adobe");
        d.extend_from_slice(&[0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x00]);
        // DQT, 8-bit, table 0, every entry 8.
        d.extend_from_slice(&[0xFF, 0xDB, 0x00, 0x43, 0x00]);
        d.extend_from_slice(&[0x08; 64]);
        // SOF0: 8-bit, 1x1, four 1x1-sampled components sharing quant table 0.
        d.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x14, 0x08, 0x00, 0x01, 0x00, 0x01, 0x04]);
        d.extend_from_slice(&[0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00, 0x04, 0x11, 0x00]);
        // DHT: DC table 0 (a 1-bit code for cat 7, a 2-bit code for cat 8) and
        //      AC table 0 (a 1-bit code for EOB).
        d.extend_from_slice(&[0xFF, 0xC4, 0x00, 0x27]);
        d.push(0x00);
        d.extend_from_slice(&[0x01, 0x01]);
        d.extend_from_slice(&[0x00; 14]);
        d.extend_from_slice(&[0x07, 0x08]);
        d.push(0x10);
        d.push(0x01);
        d.extend_from_slice(&[0x00; 15]);
        d.push(0x00);
        // SOS over all four components, then the entropy-coded MCU.
        d.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x0E, 0x04]);
        d.extend_from_slice(&[0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04, 0x00]);
        d.extend_from_slice(&[0x00, 0x3F, 0x00]);
        d.extend_from_slice(&[0x9F, 0xCF, 0xE7, 0xF3, 0xFB]);
        d.extend_from_slice(&[0xFF, 0xD9]);
        d
    }

    /// The headline of finding 1, and the part that is easy to get backwards.
    ///
    /// `/Decode [1 0 1 0 1 0 1 0]` on a CMYK JPEG must complement the four COMPONENTS
    /// before `cmyk_to_argb`, not the RGB it produces: r = (1-c)(1-k), and
    /// (1-(1-c))(1-(1-k)) = ck is not 1 - (1-c)(1-k). Pure cyan separates the three
    /// possible answers:
    ///   - no /Decode at all       -> (0, 255, 255)  cyan
    ///   - components complemented -> (0, 0, 0)      black  [correct]
    ///   - RGB complemented        -> (255, 0, 0)    red    [the plausible wrong fix]
    #[test]
    fn cmyk_decode_inverts_components_not_the_converted_rgb() {
        let jpeg = cyan_cmyk_jpeg();
        assert_eq!(jpeg_num_components(&jpeg), Some(4), "fixture must declare four components");
        assert_eq!(jpeg_adobe_transform(&jpeg), Some(0), "fixture must be Adobe transform 0");

        let (w, h, plain) = decode_jpeg_rgba_decoded(&jpeg, false).expect("fixture must decode");
        assert_eq!((w, h), (1, 1));
        assert_eq!(&plain[0..3], &[0, 255, 255], "baseline must be cyan");

        let (_, _, inverted) = decode_jpeg_rgba_decoded(&jpeg, true).expect("fixture must decode");
        assert_eq!(
            &inverted[0..3], &[0, 0, 0],
            "complementing C,M,Y,K gives (0,255,255,255), which is black; \
             (255,0,0) would mean the inversion was applied to the converted RGB"
        );
        assert_eq!(inverted[3], 255, "alpha is untouched by /Decode");
    }

    /// End to end: the same JPEG in an image XObject with `/Decode [1 0 1 0 1 0 1 0]`
    /// must come back as decoded RGBA, NOT as `format: 1` passthrough bytes — Android's
    /// decoder has no way to apply /Decode, so the passthrough was the negative.
    #[test]
    fn a_cmyk_jpeg_with_inverted_decode_is_not_passed_through() {
        let doc = Document::with_version("1.7");
        let base = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 1, "Height" => 1, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceCMYK",
            "Filter" => "DCTDecode",
        };
        let mut inverted = base.clone();
        inverted.set("Decode", vec![1.into(), 0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into(), 0.into()]);
        let out = extract_image(&doc, &Stream::new(inverted, cyan_cmyk_jpeg()), 0xFF00_0000, &HashMap::new())
            .expect("image must not be dropped");
        assert_eq!(out.format, 0, "an inverted /Decode must take the Rust decode path");
        assert_eq!(&out.data[0..3], &[0, 0, 0], "cyan complemented through CMYK is black");

        // Without /Decode the same bytes stay cyan: the fix is scoped to the array.
        let plain = extract_image(&doc, &Stream::new(base, cyan_cmyk_jpeg()), 0xFF00_0000, &HashMap::new())
            .expect("image must not be dropped");
        assert_eq!(&plain.data[0..3], &[0, 255, 255]);
    }

    /// Finding 3: the short-buffer fallback used to write opaque black, so a
    /// regression in the `samples.len()` guard would have put a solid black rectangle
    /// over the page. Uncovered pixels must be fully transparent instead.
    #[test]
    fn samples_shorter_than_the_raster_leave_transparent_pixels_not_black_ones() {
        let doc = Document::with_version("1.7");
        let dict = dictionary! {
            "Width" => 2, "Height" => 2, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceGray",
        };
        // Four pixels' worth of raster, one pixel's worth of samples.
        let rgba = image_samples_to_rgba(&doc, &dict, &HashMap::new(), &[0x40u8], (2, 2), 1, 8);
        assert_eq!(rgba.len(), 16);
        assert_eq!(rgba[3], 255, "the covered pixel is opaque");
        assert_eq!(&rgba[0..3], &[0x40, 0x40, 0x40]);
        for i in 1..4 {
            assert_eq!(
                &rgba[i * 4..i * 4 + 4], &[0, 0, 0, 0],
                "pixel {i} has no samples and must be transparent, not opaque black"
            );
        }
    }

    /// And pin the guard that keeps the case above unreachable in the first place: a
    /// stream that cannot supply even one scanline is a failed decode, not a short
    /// image, and must be dropped rather than unpacked into noise.
    #[test]
    fn a_stream_shorter_than_one_row_is_dropped() {
        let doc = Document::with_version("1.7");
        let dict = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceRGB",
        };
        // One row needs 8 * 3 = 24 bytes.
        assert!(
            extract_image(&doc, &Stream::new(dict.clone(), vec![0u8; 23]), 0xFF00_0000, &HashMap::new()).is_none(),
            "23 bytes cannot fill a 24-byte row"
        );
        let full = extract_image(&doc, &Stream::new(dict, vec![0u8; 24]), 0xFF00_0000, &HashMap::new())
            .expect("exactly one row is a short image, not a failed decode");
        assert_eq!((full.w, full.h), (8, 8));
    }
}

#[cfg(test)]
mod pixel_budget_tests {
    use super::*;

    /// The step is the identity for every image that fits, which is what makes it safe
    /// to put in the sampling loops. It only grows once `w * h` passes the budget, and
    /// it is capped so a hostile /Width x /Height cannot spin.
    #[test]
    fn decimation_step_is_identity_below_the_budget_and_bounded_above_it() {
        assert_eq!(decimation_step(0, 0), 1);
        assert_eq!(decimation_step(1, 1), 1);
        // 4096 * 4096 = 16 Mpx exactly, which is the budget, not over it.
        assert_eq!(decimation_step(4096, 4096), 1);
        assert_eq!(decimation_step(4097, 4096), 2);
        // A 300 dpi A0 engineering scan: 9933 x 14043 = 139 Mpx.
        let s = decimation_step(9933, 14043);
        assert_eq!(s, 3, "139 Mpx needs 3x3 to reach 15.5 Mpx");
        let (dw, dh) = (9933usize.div_ceil(s), 14043usize.div_ceil(s));
        assert!(
            dw * dh <= MAX_IMAGE_PIXELS,
            "decimated {}x{} = {} must fit the budget",
            dw, dh, dw * dh
        );
        // Never unbounded, and never zero, for the largest raster the dimension cap allows.
        let worst = decimation_step(MAX_IMAGE_DIM, MAX_IMAGE_DIM);
        assert!((1..=64).contains(&worst), "step {worst} out of range");
    }

    /// Decimation must not disturb the overwhelmingly common case. A small CCITT fax
    /// has step 1, so it has to come out byte-identical to what the pre-decimation code
    /// produced — same dimensions, same ink in the same places.
    #[test]
    fn a_small_ccitt_fax_is_untouched_by_the_decimation_path() {
        let doc = Document::with_version("1.7");
        let (data, w) = super::mask_tests::half_black_g4();
        let d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ColorSpace" => "DeviceGray",
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => dictionary! { "K" => -1, "Columns" => w as i64, "Rows" => 2 },
        };
        let img = extract_image(&doc, &Stream::new(d, data), 0xFF00_0000, &HashMap::new())
            .expect("a small G4 fax must still decode");
        assert_eq!((img.w, img.h), (8, 2), "step 1 must not resize");
        // Left half black, right half white, on both rows.
        for y in 0..2usize {
            for x in 0..8usize {
                let px = &img.data[(y * 8 + x) * 4..(y * 8 + x) * 4 + 3];
                let expected: &[u8] = if x < 4 { &[0, 0, 0] } else { &[255, 255, 255] };
                assert_eq!(px, expected, "pixel ({x},{y})");
            }
        }
    }

    /// A JPEG that fits the budget must decode at its full size and be unaffected by
    /// the decimating sampler — same dimensions, same pixel.
    #[test]
    fn a_small_jpeg_is_untouched_by_the_decimation_path() {
        let jpeg = super::dct_decode_tests::cyan_cmyk_jpeg();
        let (w, h, rgba) = decode_jpeg_rgba_decoded(&jpeg, false).expect("must decode");
        assert_eq!((w, h), (1, 1), "step 1 must not resize");
        assert_eq!(&rgba[0..3], &[0, 255, 255]);
        let (gw, gh, gray) = decode_jpeg_gray(&jpeg).expect("must decode");
        assert_eq!((gw, gh), (1, 1));
        assert_eq!(gray.len(), 1);
    }

    /// END TO END for the paired CCITT fix, which needed BOTH halves to land: the byte
    /// budget in `filters.rs::decode_ccitt` and the decimation in the CCITT branch here.
    ///
    /// The raster is 20000 x 839 = 16.8 Mpx, one pixel-row past `MAX_IMAGE_PIXELS`,
    /// which is the smallest shape that crosses the old cap without needing an A0's
    /// worth of memory in a unit test. Before the pair, `decode_ccitt` refused it on a
    /// pixel budget sized for RGBA and the page rendered NOTHING.
    #[test]
    fn a_ccitt_raster_over_the_pixel_budget_renders_decimated_instead_of_vanishing() {
        const COLS: usize = 20000;
        const ROWS: usize = 839;
        const { assert!(COLS * ROWS > MAX_IMAGE_PIXELS, "fixture must cross the budget") };
        // Packed at one bit per pixel this is only ~2.1 MB, which is the whole point.
        assert!(COLS.div_ceil(8) * ROWS < MAX_UNPACKED_SAMPLE_BYTES);

        // Left 1/4 black so there is unambiguous ink to find after decimation.
        let row: Vec<bool> = (0..COLS).map(|x| x < COLS / 4).collect();
        let rows: Vec<Vec<bool>> = vec![row; ROWS];
        let data = super::mask_tests::g4_encode(&rows, COLS as u32);

        let doc = Document::with_version("1.7");
        let dict = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => COLS as i64, "Height" => ROWS as i64,
            "BitsPerComponent" => 1,
            "ColorSpace" => "DeviceGray",
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => dictionary! { "K" => -1, "Columns" => COLS as i64, "Rows" => ROWS as i64 },
        };
        let img = extract_image(&doc, &Stream::new(dict, data), 0xFF00_0000, &HashMap::new())
            .expect("a large fax must render decimated, not vanish");

        // Decimated by `decimation_step`, then area-downscaled to IMAGE_DOWNSCALE_MAX_DIM
        // by `extract_image` like every other raster.
        assert!(img.w > 0 && img.h > 0);
        assert_eq!(img.format, 0);
        assert_eq!(img.data.len(), img.w as usize * img.h as usize * 4);
        assert!(
            (img.w as usize) * (img.h as usize) <= MAX_IMAGE_PIXELS,
            "output {}x{} must be inside the budget",
            img.w, img.h
        );
        // Ink on the left quarter, white on the right. Sampled well inside each region so
        // the downscale's edge blending cannot make this flaky.
        let px = |x: u32, y: u32| -> &[u8] {
            let i = (y as usize * img.w as usize + x as usize) * 4;
            &img.data[i..i + 3]
        };
        let mid_y = img.h / 2;
        assert_eq!(px(img.w / 8, mid_y), &[0, 0, 0], "left quarter is fax ink");
        assert_eq!(px(img.w * 3 / 4, mid_y), &[255, 255, 255], "right side is page white");
    }
}
