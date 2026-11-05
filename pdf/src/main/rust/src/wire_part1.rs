#[cfg(test)]
mod tests {
    use super::*;

    /// Minimal Kotlin-equivalent decoder used to round-trip the wire format.
    struct Reader<'a> {
        buf: &'a [u8],
        pos: usize,
    }
    impl<'a> Reader<'a> {
        fn u8(&mut self) -> u8 {
            let v = self.buf[self.pos];
            self.pos += 1;
            v
        }
        fn u16(&mut self) -> u16 {
            let v = u16::from_le_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
            self.pos += 2;
            v
        }
        fn u32(&mut self) -> u32 {
            let v = u32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
            self.pos += 4;
            v
        }
        fn f32(&mut self) -> f32 {
            let v = f32::from_le_bytes(self.buf[self.pos..self.pos + 4].try_into().unwrap());
            self.pos += 4;
            v
        }
    }

    #[test]
    fn round_trips_all_primitives() {
        let page = PageData {
            width: 612.0,
            height: 792.0,
            prims: vec![
                Prim::Text {
                    x: 10.0,
                    y: 20.0,
                    size: 12.0,
                    argb: 0xFF112233,
                    text: "Hé".to_string(),
                    stroke_argb: Some(0xFF445566),
                    stroke_width: Some(0.5),
                    advance: 12.0,
                    render_mode: 0,
                    blend: BlendMode::Multiply,
                    is_bold: true,
                    is_italic: false,
                    font_family: 1,
                    outline: false,
                    h_scale: 1.0,
                },
                Prim::Fill {
                    argb: 0xFFAABBCC,
                    even_odd: true,
                    contours: vec![
                        vec![(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)],
                        vec![(0.25, 0.25), (0.5, 0.25), (0.5, 0.5)],
                    ],
                    blend: BlendMode::Screen,
                },
                Prim::Stroke {
                    argb: 0xFF010203,
                    width: 2.5,
                    dash: vec![3.0, 2.0],
                    dash_phase: 1.0,
                    cap: 1,
                    join: 1,
                    miter: 10.0,
                    pts: vec![(3.0, 4.0), (5.0, 6.0)],
                    blend: BlendMode::Normal,
                },
                Prim::ClipPush {
                    even_odd: false,
                    pts: vec![(0.0,0.0),(10.0,0.0),(10.0,10.0),(0.0,10.0)],
                    path_ops: Some(vec![
                        PathOp::Move(0.0, 0.0),
                        PathOp::Cubic(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                        PathOp::Close,
                    ]),
                },
                Prim::ClipPop,
                Prim::TextClipApply,
                Prim::SoftMaskPush { mask_type: 1 },
                Prim::SoftMaskContent,
                Prim::SoftMaskPop,
            ],
        };
        let buf = serialize(&page);
        let mut r = Reader { buf: &buf, pos: 0 };
        assert_eq!(r.u32(), WIRE_MAGIC);
        assert_eq!(r.u32(), WIRE_VERSION);
        assert_eq!(r.f32(), 612.0);
        assert_eq!(r.f32(), 792.0);
        assert_eq!(r.u32(), 9);

        assert_eq!(r.u8(), TAG_TEXT);
        assert_eq!(r.f32(), 10.0);
        assert_eq!(r.f32(), 20.0);
        assert_eq!(r.f32(), 12.0);
        assert_eq!(r.u32(), 0xFF112233);
        let len = r.u16() as usize;
        let s = std::str::from_utf8(&buf[r.pos..r.pos + len]).unwrap();
        assert_eq!(s, "Hé");
        r.pos += len;
        assert_eq!(r.u8(), 1); // hasStroke
        assert_eq!(r.u32(), 0xFF445566);
        assert!((r.f32() - 0.5).abs() < 1e-6);
        assert_eq!(r.u8(), 0); // render_mode (v4)
        assert_eq!(r.u8(), BlendMode::Multiply as u8); // blend (v5)
        assert!((r.f32() - 12.0).abs() < 1e-6); // advance (v7)
        assert_eq!(r.u8(), 1 | (1 << 2)); // fontFlags: bold + serif family (v8)
        assert!((r.f32() - 1.0).abs() < 1e-6); // h_scale (v8)

        assert_eq!(r.u8(), TAG_FILL);
        assert_eq!(r.u32(), 0xFFAABBCC);
        assert_eq!(r.u8(), 1); // even-odd
        assert_eq!(r.u16(), 2); // nContours (v6)
        assert_eq!(r.u16(), 3); // contour 0 nPts
        r.pos += 3 * 8;
        assert_eq!(r.u16(), 3); // contour 1 nPts
        r.pos += 3 * 8;
        assert_eq!(r.u8(), BlendMode::Screen as u8); // blend (v5)

        assert_eq!(r.u8(), TAG_STROKE);
        assert_eq!(r.u32(), 0xFF010203);
        assert_eq!(r.f32(), 2.5);
        assert_eq!(r.u8(), 2); // dash count
        assert_eq!(r.f32(), 3.0);
        assert_eq!(r.f32(), 2.0);
        assert_eq!(r.f32(), 1.0); // phase
        assert_eq!(r.u8(), 1); // cap
        assert_eq!(r.u8(), 1); // join
        assert!((r.f32() - 10.0).abs() < 1e-4);
        assert_eq!(r.u16(), 2);
        r.pos += 2*8;
        assert_eq!(r.u8(), BlendMode::Normal as u8); // blend (v5)

        assert_eq!(r.u8(), TAG_CLIP_PUSH);
        assert_eq!(r.u8(), 0); // evenOdd false
        let n = r.u16() as usize;
        assert_eq!(n, 4);
        r.pos += n*8;
        // v4 path-ops section: Move, Cubic, Close.
        assert_eq!(r.u16(), 3);
        assert_eq!(r.u8(), PATHOP_MOVE);
        r.pos += 2*4;
        assert_eq!(r.u8(), PATHOP_CUBIC);
        r.pos += 6*4;
        assert_eq!(r.u8(), PATHOP_CLOSE);

        assert_eq!(r.u8(), TAG_CLIP_POP);
        assert_eq!(r.u8(), TAG_TEXT_CLIP_APPLY);
        assert_eq!(r.u8(), TAG_SMASK_PUSH);
        assert_eq!(r.u8(), 1); // mask_type luminosity
        assert_eq!(r.u8(), TAG_SMASK_CONTENT);
        assert_eq!(r.u8(), TAG_SMASK_POP);
    }

    #[test]
    fn truncates_text_at_char_boundary() {
        // 70000 'é' chars = 140000 bytes > u16::MAX, must truncate at char boundary
        let long = "é".repeat(40000); // 80000 bytes
        assert!(long.len() > MAX_TEXT_BYTES);
        let truncated = truncate_str_safe(&long, MAX_TEXT_BYTES);
        assert!(truncated.len() <= MAX_TEXT_BYTES);
        // Must still be valid UTF-8 and end at char boundary (é is 2 bytes, so len even)
        assert!(truncated.is_char_boundary(truncated.len()));
        assert!(std::str::from_utf8(truncated.as_bytes()).is_ok());
        assert_eq!(truncated.len() % 2, 0);
    }

    /// `SafePdfParser.kt` is the only consumer of this format, and its own constant is the
    /// highest version it understands. Ours may lag it (the parser gates the newer fields
    /// off and the stream simply lacks them) but must never lead it: a version above what
    /// the parser knows is NOT rejected — it logs and attempts a forward-compat parse with
    /// every unknown field's gate closed, so it reads too few bytes per primitive, desyncs
    /// and truncates the page at the first byte it mistakes for a tag.
    ///
    /// Nothing else catches that: both constants are valid integers, Kotlin still compiles,
    /// and the Rust round-trip tests only ever read back what Rust wrote. So assert it
    /// across the language boundary against the real file.
    #[test]
    fn wire_version_is_not_ahead_of_the_kotlin_parser() {
        let src = kotlin_parser_src();
        let decl = |prefix: &str| kotlin_decl(&src, prefix);
        let kotlin_version: u32 = decl("const val WIRE_VERSION: Int = ")
            .split_whitespace()
            .next()
            .and_then(|v| v.parse().ok())
            .expect("SafePdfParser.WIRE_VERSION must be a bare integer literal");
        assert!(
            WIRE_VERSION <= kotlin_version,
            "wire.rs emits v{WIRE_VERSION} but SafePdfParser.kt understands only up to \
             v{kotlin_version}, so it would parse every page with the newer fields' gates \
             closed and desync. Bump the Kotlin constant and teach the parser the new \
             fields in the same change."
        );
        let magic = decl("const val WIRE_MAGIC: Int = ");
        let magic = magic.split_whitespace().next().unwrap_or_default();
        let kotlin_magic = u32::from_str_radix(magic.trim_start_matches("0x"), 16)
            .expect("SafePdfParser.WIRE_MAGIC must be a hex literal");
        assert_eq!(
            WIRE_MAGIC, kotlin_magic,
            "the magic disagrees, so the parser takes every buffer for a headerless v1 one"
        );
    }

    /// The wire format's only consumer, read from source so an assertion can be made
    /// across the language boundary against the real file rather than a transcription.
    fn kotlin_parser_src() -> String {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../java/com/vayunmathur/pdf/util/SafePdfParser.kt"
        );
        std::fs::read_to_string(path)
            .unwrap_or_else(|e| panic!("cannot read the wire format's only consumer {path}: {e}"))
    }

    /// The image budgets moved out of the parser into `SafePdfImages` in the
    /// file split, so the budget assertion below reads them there rather than
    /// from the parser.
    fn kotlin_images_src() -> String {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../java/com/vayunmathur/pdf/util/SafePdfImages.kt"
        );
        std::fs::read_to_string(path)
            .unwrap_or_else(|e| panic!("cannot read the image-budget owner {path}: {e}"))
    }

    /// The text following `prefix` on the first line that starts with it.
    fn kotlin_decl(src: &str, prefix: &str) -> String {
        src.lines()
            .find_map(|l| l.trim().strip_prefix(prefix).map(|v| v.to_string()))
            .unwrap_or_else(|| panic!("SafePdfParser.kt must declare `{prefix}<value>`"))
    }

    /// The image budgets are declared TWICE, once per language, and nothing tied them
    /// together. Rust decimates an oversized image down to its own bound; Kotlin drops
    /// anything above its own. So the invariant is directional — the consumer's ceiling
    /// must be at or above the producer's — and it is asymmetric in consequence:
    ///
    ///   kotlin >= rust  the image Rust decimated fits, and is drawn. Correct.
    ///   kotlin <  rust  Rust decimates to ITS bound, hands over an image that clears
    ///                   every Rust guard, and the parser silently drops the primitive.
    ///                   No bitmap, no warning on either side, nothing in logcat.
    ///
    /// That second row is exactly the defect that made every JPEG over 16 Mpx — an
    /// ordinary phone photo — render as nothing, and the whole-image loss the CCITT
    /// budget fix in `filters.rs` and the codec decimation in `images.rs` were landed to
    /// stop. All three are undone by one side moving.
    ///
    /// Asserted as an INEQUALITY, deliberately, not equality: a Kotlin ceiling above
    /// Rust's is strictly safe, and failing the build on a strictly-safer configuration
    /// would train the next person to widen the assertion rather than think about it.
    #[test]
    fn the_kotlin_image_budgets_are_not_below_the_rust_ones() {
        let src = kotlin_images_src();
        let int_after = |prefix: &str| -> u64 {
            kotlin_decl(&src, prefix)
                .split_whitespace()
                .next()
                .and_then(|v| v.parse().ok())
                .unwrap_or_else(|| panic!("`{prefix}` must be followed by a bare integer literal"))
        };
        let kotlin_pixels = int_after("private const val MAX_IMAGE_PIXELS: Long = ");
        let kotlin_dim = int_after("private const val MAX_IMAGE_DIM: Int = ");
        assert!(
            kotlin_pixels >= crate::MAX_IMAGE_PIXELS as u64,
            "SafePdfImages.kt caps images at {kotlin_pixels} pixels but images.rs decimates \
             only to {}, so every image between the two is produced, passes every Rust \
             guard, and is then dropped by the parser with no diagnostic on either side. \
             Raise the Kotlin constant, or lower MAX_IMAGE_PIXELS in graphics_state.rs.",
            crate::MAX_IMAGE_PIXELS
        );
        assert!(
            kotlin_dim >= crate::MAX_IMAGE_DIM as u64,
            "SafePdfImages.kt refuses images wider or taller than {kotlin_dim} but images.rs \
             admits up to {}, so an image between the two is emitted and silently dropped.",
            crate::MAX_IMAGE_DIM
        );
    }

    /// Header size: magic + version + width + height + count.
    const HEADER_LEN: usize = 4 + 4 + 4 + 4 + 4;

    /// Bytes `serialize` writes for a page holding exactly `prim`, header excluded.
    fn arm_len(prim: Prim) -> usize {
        let page = PageData { width: 10.0, height: 10.0, prims: vec![prim] };
        serialize(&page).len() - HEADER_LEN
    }

    /// Every arm's on-wire width, pinned term by term.
    ///
    /// `wire_version_matches_the_image_payload_layout` does this for the Image arm only, and
    /// `SafePdfParserTest.theImageArmMatchesTheByteCountRustSerializes` names the residual
    /// explicitly: the other thirteen tags are transcription-against-transcription, because
    /// Rust's round-trip test only reads back what Rust wrote and Kotlin's `WireWriter` is a
    /// second hand transcription of this file. A field that changed width on ONE side only
    /// would leave both suites green while every real page desynced from that byte on.
    ///
    /// This is the Rust half of the pairing. `SafePdfParserTest.everyArmMatchesTheByteCount\
    /// RustSerializes` asserts the same arithmetic against `WireWriter`, so a width or
    /// ordering change in either serializer fails one of the two.
    ///
    /// The sums are written out field by field rather than as totals: a bare total still
    /// matches when two fields change by offsetting amounts.
    #[test]
    fn every_arm_has_the_byte_length_the_kotlin_parser_reads() {
        // 1 Text, with an N-byte string: tag + x + y + size + argb + len + N + hasStroke +
        // strokeArgb + strokeWidth + renderMode + blend + advance + fontFlags + hScale.
        let text_fixed = 1 + 4 + 4 + 4 + 4 + 2 + 1 + 4 + 4 + 1 + 1 + 4 + 1 + 4;
        assert_eq!(
            arm_len(Prim::Text {
                x: 0.0, y: 0.0, size: 1.0, argb: 0, text: "ab".to_string(),
                stroke_argb: None, stroke_width: None, advance: 1.0, render_mode: 0,
                blend: BlendMode::Normal, is_bold: false, is_italic: false,
                font_family: 0, outline: false, h_scale: 1.0,
            }),
            text_fixed + 2,
            "Text arm width changed",
        );

        // 2 Fill: tag + argb + evenOdd + nContours + per contour (nPts + 8 per point) + blend.
        assert_eq!(
            arm_len(Prim::Fill {
                argb: 0, even_odd: false,
                contours: vec![vec![(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)]],
                blend: BlendMode::Normal,
            }),
            1 + 4 + 1 + 2 + (2 + 3 * 8) + 1,
            "Fill arm width changed",
        );

        // 3 Stroke: tag + argb + width + nDash + 4 per dash + phase + cap + join + miter +
        // nPts + 8 per point + blend.
        assert_eq!(
            arm_len(Prim::Stroke {
                argb: 0, width: 1.0, dash: vec![3.0, 2.0], dash_phase: 0.0,
                cap: 0, join: 0, miter: 10.0, pts: vec![(0.0, 0.0), (1.0, 1.0)],
                blend: BlendMode::Normal,
            }),
            1 + 4 + 4 + 1 + 2 * 4 + 4 + 1 + 1 + 4 + 2 + 2 * 8 + 1,
            "Stroke arm width changed",
        );

        // 4 Image: tag + 6 ctm + w + h + format + alpha + blend + len + payload. No v11
        // interpolate byte while WIRE_VERSION is 10 — see the note on that constant.
        assert_eq!(
            arm_len(Prim::Image {
                ctm: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], w: 1, h: 1, format: 0,
                data: vec![1, 2, 3, 4], alpha: 1.0, blend: BlendMode::Normal,
            }),
            1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4,
            "Image arm width changed",
        );

        // 14 ImageTiled: tag + 6 ctm + w + h + xstep + ystep + i0 + j0 + nx + ny + alpha +
        // blend + len + payload. No format byte: the cell is always RGBA8888.
        assert_eq!(
            arm_len(Prim::ImageTiled {
                ctm: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], w: 1, h: 1, data: vec![1, 2, 3, 4],
                xstep: 1.0, ystep: 1.0, i0: 0, j0: 0, nx: 1, ny: 1,
                alpha: 1.0, blend: BlendMode::Normal,
            }),
            1 + 24 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 1 + 4 + 4,
            "ImageTiled arm width changed",
        );

        // 5 ClipPush: tag + evenOdd + nPts + 8 per point + nPathOps, then the tagged ops:
        // Move/Line 1 + 8, Cubic 1 + 24, Close 1.
        assert_eq!(
            arm_len(Prim::ClipPush { even_odd: false, pts: vec![(0.0, 0.0)], path_ops: None }),
            1 + 1 + 2 + 8 + 2,
            "ClipPush arm width changed (no path ops)",
        );
        assert_eq!(
            arm_len(Prim::ClipPush {
                even_odd: false,
                pts: vec![(0.0, 0.0)],
                path_ops: Some(vec![
                    PathOp::Move(0.0, 0.0),
                    PathOp::Line(1.0, 1.0),
                    PathOp::Cubic(1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
                    PathOp::Close,
                ]),
            }),
            1 + 1 + 2 + 8 + 2 + (1 + 8) + (1 + 8) + (1 + 24) + 1,
            "ClipPush path-ops section width changed",
        );

        // The empty-payload markers are one tag byte each.
        assert_eq!(arm_len(Prim::ClipPop), 1, "ClipPop arm width changed");
        assert_eq!(arm_len(Prim::TextClipApply), 1, "TextClipApply arm width changed");
        assert_eq!(arm_len(Prim::GroupPop), 1, "GroupPop arm width changed");
        assert_eq!(arm_len(Prim::SoftMaskContent), 1, "SoftMaskContent arm width changed");
        assert_eq!(arm_len(Prim::SoftMaskPop), 1, "SoftMaskPop arm width changed");

        // 7 GroupPush: tag + isolated + knockout + alpha + blend.
        assert_eq!(
            arm_len(Prim::GroupPush {
                isolated: true, knockout: false, alpha: 1.0, blend: BlendMode::Normal,
            }),
            1 + 1 + 1 + 4 + 1,
            "GroupPush arm width changed",
        );

        // 10 SoftMaskPush: tag + maskType.
        assert_eq!(
            arm_len(Prim::SoftMaskPush { mask_type: 1 }), 1 + 1,
            "SoftMaskPush arm width changed",
        );

        // 13 SoftMaskTransfer: tag + the LUT, raw and unprefixed.
        assert_eq!(
            arm_len(Prim::SoftMaskTransfer(Box::new([0u8; TRANSFER_LUT_SIZE]))),
            1 + TRANSFER_LUT_SIZE,
            "SoftMaskTransfer arm width changed",
        );
    }

    /// A version bump only means something if the payload changes with it, and the reverse
    /// is just as fatal. Pin the emitted Image payload to the exact v10 field list so that
    /// bumping WIRE_VERSION without writing the v11 interpolate byte — or writing the byte
    /// without bumping — fails here instead of silently desyncing the decoder.
    #[test]
    fn wire_version_matches_the_image_payload_layout() {
        let page = PageData {
            width: 10.0,
            height: 10.0,
            prims: vec![Prim::Image {
                ctm: [1.0, 0.0, 0.0, 1.0, 0.0, 0.0],
                w: 1,
                h: 1,
                format: 0,
                data: vec![1, 2, 3, 4],
                alpha: 1.0,
                blend: BlendMode::Normal,
            }],
        };
        // header: magic + version + width + height + count. Payload: tag + 6xf32 ctm +
        // u32 w + u32 h + u8 format + f32 alpha (v9) + u8 blend (v10) + u32 len + 4 bytes.
        let v10_len = (4 + 4 + 4 + 4 + 4) + 1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4;
        assert_eq!(
            (serialize(&page).len(), WIRE_VERSION),
            (v10_len, 10),
            "the Image payload and WIRE_VERSION must move together: SafePdfParser.kt reads \
             a u8 interpolate between the blend byte and the u32 length once the declared \
             version reaches 11"
        );
    }
}
