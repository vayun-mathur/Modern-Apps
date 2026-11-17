#[cfg(test)]
mod mask_tests3 {
    use super::*;
    use super::mask_tests::half_black_g4;

    // §8.9.5.2 Table 90: an explicit /Decode on an Indexed image remaps the sample onto
    // the index range, and the Indexed branch ignored it. The default [0 2^bpc-1] must
    // stay the identity, so pin both directions.
    #[test]
    fn indexed_decode_array_remaps_the_palette_index() {
        let mut doc = Document::with_version("1.7");
        // 4-entry RGB palette: black, red, green, blue.
        let pal: Vec<u8> = vec![0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255];
        let cs = Object::Array(vec![
            Object::Name(b"Indexed".to_vec()),
            Object::Name(b"DeviceRGB".to_vec()),
            Object::Integer(3),
            Object::String(pal, lopdf::StringFormat::Literal),
        ]);
        let cs_id = doc.add_object(cs);
        let mut res = HashMap::new();
        res.insert(b"Cs".to_vec(), cs_id);

        // One 8-bpc sample of 255. With the default decode that is index 255, clamped to
        // hival 3 -> blue.
        let plain = dictionary! { "ColorSpace" => "Cs", "BitsPerComponent" => 8 };
        let out = image_samples_to_rgba(&doc, &plain, &res, &[255u8], (1, 1), 1, 8);
        assert_eq!(&out[0..3], &[0, 0, 255], "default decode clamps to hival -> blue");

        // /Decode [0 1] maps sample 0..255 onto index 0..1, so 255 -> index 1 -> RED.
        let decoded = dictionary! {
            "ColorSpace" => "Cs",
            "BitsPerComponent" => 8,
            "Decode" => vec![0.into(), 1.into()],
        };
        let out = image_samples_to_rgba(&doc, &decoded, &res, &[255u8], (1, 1), 1, 8);
        assert_eq!(&out[0..3], &[255, 0, 0], "/Decode [0 1] remaps 255 to index 1 -> red");
        // And the low end still maps to index 0.
        let out = image_samples_to_rgba(&doc, &decoded, &res, &[0u8], (1, 1), 1, 8);
        assert_eq!(&out[0..3], &[0, 0, 0], "sample 0 stays index 0 -> black");
    }

    /// A mask stream carrying an image codec whose decode FAILS must leave the base
    /// image unmasked. `stream_data_with_doc` hands image codecs back still ENCODED
    /// (objects.rs decides that, because only the image layer knows /Width), so the
    /// raw-bit fallback below the codec branches was unpacking the codestream itself
    /// as mask samples. For an /SMask the sample IS the alpha (§11.6.5.3), and the
    /// bytes past the short codestream read as 0, so the base image went almost
    /// entirely transparent: the "graphic just isn't there" report, from an image
    /// that decoded perfectly well.
    #[test]
    fn a_mask_whose_codec_fails_leaves_the_image_unmasked() {
        let mut doc = Document::with_version("1.7");
        let codec_mask = |filter: &str, doc: &mut Document| {
            let id = doc.add_object(Stream::new(
                dictionary! {
                    "Width" => 4,
                    "Height" => 4,
                    "BitsPerComponent" => 8,
                    "ColorSpace" => "DeviceGray",
                    "Filter" => filter,
                },
                // A plausible SOI followed by nothing decodable.
                vec![0xFFu8, 0xD8, 0x00, 0x01, 0x02, 0x03],
            ));
            dictionary! { "SMask" => Object::Reference(id) }
        };
        // CCITTFaxDecode is deliberately absent: `filters::decode_ccitt` is tolerant and
        // returns a (mostly blank) raster rather than failing, so it never reaches the
        // fall-through. The three that DO fail are the ones that mattered.
        for filter in ["DCTDecode", "JPXDecode", "JBIG2Decode"] {
            let dict = codec_mask(filter, &mut doc);
            assert!(
                read_smask(&doc, &dict, 4, 4).is_none(),
                "{filter}: an undecodable mask must not become a mask of codestream bytes"
            );
        }
    }

    /// `jp2::decode_with_opts` sniffed the JP2 signature box with `bytes.len() > 4`
    /// and then sliced `bytes[4..8]`, so a 5-to-7-byte JPX stream panicked with
    /// "range end index 8 out of range". A panic crosses the JNI boundary and takes
    /// the whole document down, not just the one image.
    #[test]
    fn a_short_jpx_codestream_does_not_panic() {
        for len in 0..12usize {
            let bytes: Vec<u8> = (0..len).map(|i| i as u8).collect();
            assert!(jp2::decode(&bytes).is_none(), "{len} bytes must fail, not panic");
        }
        // And the byte pattern that actually reaches the signature comparison.
        let mut sig = b"\x00\x00\x00\x0CjP  ".to_vec();
        sig.truncate(6);
        assert!(jp2::decode(&sig).is_none());
    }

    /// §8.9.6.4 puts no restriction on the base image's filter: a stencil `/Mask`
    /// applies to a CCITT/JBIG2/JPX base just as it does to raw samples. Only the
    /// raw-sample path read it, so a codec-compressed base rendered as an uncut
    /// opaque rectangle with the cut-out areas still showing.
    #[test]
    fn explicit_stencil_mask_applies_to_a_codec_base_image() {
        let mut doc = Document::with_version("1.7");
        let (data, w) = half_black_g4();
        // 8x2 stencil, left half set. Mask sample 1 = masked out (§8.9.6.2/§8.9.6.4).
        let mask = doc.add_object(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Image",
                "Width" => 8, "Height" => 2, "BitsPerComponent" => 1,
                "ImageMask" => true,
            },
            vec![0b1111_0000u8, 0b1111_0000u8],
        ));
        let d = dictionary! {
            "Width" => w as i64, "Height" => 2, "BitsPerComponent" => 1,
            "ColorSpace" => "DeviceGray",
            "Filter" => "CCITTFaxDecode",
            "DecodeParms" => dictionary! { "K" => -1, "Columns" => w as i64, "Rows" => 2 },
            "Mask" => Object::Reference(mask),
        };
        let img = extract_image_inner(&doc, &Stream::new(d, data), 0xFF00_0000, &HashMap::new())
            .expect("a G4 image must decode");
        assert_eq!((img.w, img.h), (8, 2));
        assert_eq!(img.data[3], 0, "x=0 is under a set mask bit - masked out");
        assert_eq!(img.data[4 * 3 + 3], 0, "x=3 still masked out");
        assert_eq!(img.data[4 * 4 + 3], 255, "x=4 is under a clear mask bit - kept");
        assert_eq!(img.data[4 * 8 + 3], 0, "row 1 masks the same half");
    }

    /// A stencil is one bit per pixel, so `/Width 8192 /Height 4096` is 4 MB on disk
    /// and 134 MB of RGBA. Every other branch bounds its own raster; this one had no
    /// pixel budget at all, so a tiny file could commit gigabytes. Decimating matches
    /// what the contone path already does and is the identity for any sane stencil.
    #[test]
    fn an_oversized_stencil_is_decimated_not_allocated_whole() {
        let doc = Document::with_version("1.7");
        let d = dictionary! { "Width" => 8192, "Height" => 4096, "ImageMask" => true };
        // One row of zero samples (which PAINT under the default /Decode), no more.
        let img = extract_image_inner(
            &doc,
            &Stream::new(d, vec![0u8; 8192 / 8]),
            0xFFFF_0000,
            &HashMap::new(),
        )
        .expect("an oversized stencil must still render");
        assert!(
            (img.w as usize) * (img.h as usize) <= MAX_IMAGE_PIXELS,
            "{}x{} must fit the pixel budget",
            img.w,
            img.h
        );
        assert_eq!((img.w, img.h), (4096, 2048), "decimated by 2 on each axis");
        assert_eq!(img.data.len(), 4096 * 2048 * 4);
        assert_eq!(img.data[3], 255, "row 0 sample 0 paints the fill colour");
        assert_eq!(
            img.data[4096 * 4 + 3],
            0,
            "a row past the supplied bytes must stay unpainted, not become a solid block"
        );
    }

    /// The 20000-per-side cap still admits a 400 Mpx mask, which `decode_mask_stream_gray`
    /// would allocate one byte per pixel of, twice.
    #[test]
    fn an_oversized_mask_is_refused_rather_than_allocated() {
        let mut doc = Document::with_version("1.7");
        let big = dictionary! {
            "Width" => 20000, "Height" => 20000, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceGray",
        };
        let sm = doc.add_object(Stream::new(big.clone(), vec![0u8; 16]));
        assert!(read_smask(&doc, &dictionary! { "SMask" => Object::Reference(sm) }, 8, 8).is_none());
        let mut stencil = big;
        stencil.set("ImageMask", true);
        stencil.set("BitsPerComponent", 1);
        let mk = doc.add_object(Stream::new(stencil, vec![0u8; 16]));
        assert!(read_explicit_mask(&doc, &dictionary! { "Mask" => Object::Reference(mk) }, 8, 8).is_none());
    }

    /// `decode_stream_chain` returns `None` for a CORRUPT Flate/LZW/ASCII85 wrapper as
    /// well as for an unknown filter name, and `unwrap_or(raw)` then unpacked the
    /// still-compressed bytes as image samples. That is noise, and for an inline
    /// stencil (§8.9.6.2) noise is a solid block of fill colour over the page.
    #[test]
    fn an_inline_image_whose_filter_chain_fails_is_dropped_not_rendered_as_noise() {
        let doc = Document::with_version("1.7");
        // Not valid zlib, so `decode_flate` fails and the chain returns None.
        let junk = vec![0x00u8, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07];
        let d = dictionary! { "W" => 4, "H" => 2, "BPC" => 8, "CS" => "G", "F" => "Fl" };
        assert!(
            extract_inline_image(&doc, &Stream::new(d, junk.clone()), 0xFF00_0000, &HashMap::new())
                .is_none(),
            "an undecodable inline image must be dropped, not unpacked from its own zlib bytes"
        );
        // And the stencil form, where the failure mode is a solid block rather than noise.
        let d = dictionary! { "W" => 8, "H" => 8, "IM" => true, "F" => "Fl" };
        assert!(
            extract_inline_image(&doc, &Stream::new(d, junk), 0xFF00_0000, &HashMap::new())
                .is_none()
        );
    }

    /// The inline path never called `downscale_rgba`, so it could hand the wire a full
    /// `MAX_IMAGE_PIXELS` raster (64 MB of RGBA) that the consumer refuses to
    /// materialise. A few KB of inline Flate expands to exactly that.
    #[test]
    fn an_inline_image_is_downscaled_like_an_xobject() {
        let doc = Document::with_version("1.7");
        let (w, h) = (4096usize, 8usize);
        let d = dictionary! { "W" => w as i64, "H" => h as i64, "BPC" => 8, "CS" => "G" };
        let img = extract_inline_image(
            &doc,
            &Stream::new(d, vec![0x80u8; w * h]),
            0xFF00_0000,
            &HashMap::new(),
        )
        .expect("decodes");
        assert!(
            img.w <= IMAGE_DOWNSCALE_MAX_DIM && img.h <= IMAGE_DOWNSCALE_MAX_DIM,
            "{}x{} must be bounded by the downscale cap",
            img.w,
            img.h
        );
        assert_eq!(img.data.len(), (img.w as usize) * (img.h as usize) * 4);
    }

    /// §8.7.4.3 Table 78: `/Background` "shall be ignored by the `sh` operator" and
    /// applies only when the shading is painted as a shading PATTERN. Painting it under
    /// `sh` floods the whole clip region with the background colour where the shading's
    /// own extent should have left it clear.
    #[test]
    fn background_is_ignored_by_sh_but_honoured_by_a_pattern() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![1.into(), 1.into(), 1.into()],
            "N" => 1,
        });
        // An axial shading spanning only the middle of its /BBox, with /Extend false, so
        // the pixels outside the axis are "outside the shading's extent".
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.4.into(), 0.into(), 0.6.into(), 0.into()],
            "Function" => Object::Reference(func),
            "Extend" => vec![Object::Boolean(false), Object::Boolean(false)],
            "BBox" => vec![0.into(), 0.into(), 1.into(), 1.into()],
            "Background" => vec![1.into(), 0.into(), 0.into()],
        });
        let alpha_at_left_edge = |data: &[u8]| data[3];

        let (_, _, _, sh_data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 32, None).expect("sh");
        assert_eq!(
            alpha_at_left_edge(&sh_data), 0,
            "`sh` must leave the area outside the shading CLEAR, not flood it with /Background"
        );

        let (_, _, _, pat_data) =
            rasterize_shading_as_pattern(&doc, &sh, &IDENTITY, &HashMap::new(), 32, None)
                .expect("pattern");
        assert_eq!(alpha_at_left_edge(&pat_data), 255, "a pattern DOES paint /Background");
        assert_eq!(&pat_data[0..3], &[255, 0, 0], "and it is the declared red");
    }

    /// §8.7.4.3 does not restrict /Background by shading type, but types 1 and 4-7
    /// return a finished raster from a different function and neither reads it, so a
    /// function-based or MESH pattern dropped the entry while types 2 and 3 honoured
    /// it. `fill_background_outside` is the post-pass that closes that gap; the mesh
    /// and type 1 call sites are one line each.
    #[test]
    fn background_post_pass_fills_only_the_uncovered_pixels() {
        let doc = Document::with_version("1.7");
        let dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "Background" => vec![0.into(), 0.into(), 1.into()],
        };
        // Pixel 0 was covered by the shading (opaque green), pixel 1 was not.
        let mut rgba = vec![0, 255, 0, 255, 0, 0, 0, 0];
        fill_background_outside(&doc, &dict, &HashMap::new(), &mut rgba);
        assert_eq!(&rgba[0..4], &[0, 255, 0, 255], "a covered pixel is untouched");
        assert_eq!(&rgba[4..8], &[0, 0, 255, 255], "an uncovered pixel takes /Background");

        // Evaluated in the shading's OWN colour space, not assumed to be RGB.
        let cmyk = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceCMYK",
            "Background" => vec![0.into(), 1.into(), 1.into(), 0.into()],
        };
        let mut rgba = vec![0, 0, 0, 0];
        fill_background_outside(&doc, &cmyk, &HashMap::new(), &mut rgba);
        assert_eq!(&rgba[0..4], &[255, 0, 0, 255], "CMYK 0,1,1,0 is red");

        // No /Background is a no-op: an uncovered pixel stays CLEAR, it does not
        // become opaque black.
        let bare = dictionary! { "ShadingType" => 4, "ColorSpace" => "DeviceRGB" };
        let mut rgba = vec![0, 0, 0, 0];
        fill_background_outside(&doc, &bare, &HashMap::new(), &mut rgba);
        assert_eq!(&rgba[0..4], &[0, 0, 0, 0], "no /Background must not paint anything");
    }

    /// A RADIAL shading reports "outside the extent" as `None` from
    /// `radial_shading_param`, which becomes NaN — and the NaN guard used to `continue`
    /// above the two arms that paint `/Background`, so those arms were structurally
    /// unreachable for ShadingType 3 and a radial pattern painted none of its
    /// background. The solver is correct; the ordering was not. (a-shading's diagnosis
    /// on a-interp's repro.)
    #[test]
    fn a_radial_pattern_paints_background_outside_its_extent() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            // All black, so any red pixel can only have come from /Background.
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 0.into(), 0.into()],
            "N" => 1,
        });
        // Concentric circles r0=0 -> r1=0.1 in the middle of the bbox, /Extend false
        // both ends, so every pixel outside the small disc is outside the extent.
        let sh = Object::Dictionary(dictionary! {
            "ShadingType" => 3,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.5.into(), 0.5.into(), 0.into(), 0.5.into(), 0.5.into(), 0.1.into()],
            "Function" => Object::Reference(func),
            "Extend" => vec![Object::Boolean(false), Object::Boolean(false)],
            "BBox" => vec![0.into(), 0.into(), 1.into(), 1.into()],
            "Background" => vec![1.into(), 0.into(), 0.into()],
        });

        let (_, _, _, sh_data) =
            rasterize_shading(&doc, &sh, &IDENTITY, &HashMap::new(), 32, None).expect("sh");
        assert_eq!(sh_data[3], 0, "`sh` must leave the corner CLEAR (/Background is ignored)");

        let (_, _, _, pat) =
            rasterize_shading_as_pattern(&doc, &sh, &IDENTITY, &HashMap::new(), 32, None)
                .expect("pattern");
        assert_eq!(
            &pat[0..4], &[255, 0, 0, 255],
            "a radial PATTERN must paint /Background outside the ending circle"
        );
        let red = pat.chunks_exact(4).filter(|px| px[0] == 255 && px[3] == 255).count();
        assert!(red > 100, "most of the raster is outside the r=0.1 disc, got {red} red pixels");
        // The disc itself is still the shading's own colour, not background.
        let mid = ((16 * 32) + 16) * 4;
        assert_eq!(&pat[mid..mid + 4], &[0, 0, 0, 255], "inside the disc stays the gradient's black");
    }

    /// The NaN branch that paints /Background must be reachable by RADIAL only.
    /// `len2 < 1e-12` is false for inf, so non-finite /Coords (a real like 1e40
    /// overflows an f32 on parse) used to reach the division and make `t` NaN for
    /// every pixel of an AXIAL shading. That was harmless while the guard just
    /// skipped them; once it paints the background, a degenerate axial pattern would
    /// flood its whole raster — reintroducing the exact failure the /Background split
    /// was made to prevent.
    #[test]
    fn a_degenerate_axial_pattern_does_not_flood_with_background() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![0.into(), 0.into(), 0.into()],
            "N" => 1,
        });
        for coords in [
            // Non-finite: a real like 1e40 overflows an f32 on parse, so /Coords can
            // legitimately hold inf and len2 becomes inf.
            vec![
                Object::Real(0.0),
                Object::Real(0.0),
                Object::Real(f32::INFINITY),
                Object::Real(0.0),
            ],
            // Zero-length axis, the case the len2 ~ 0 arm already handled.
            vec![Object::Real(0.5), Object::Real(0.5), Object::Real(0.5), Object::Real(0.5)],
        ] {
            let sh = Object::Dictionary(dictionary! {
                "ShadingType" => 2,
                "ColorSpace" => "DeviceRGB",
                "Coords" => coords,
                "Function" => Object::Reference(func),
                "Extend" => vec![Object::Boolean(false), Object::Boolean(false)],
                "BBox" => vec![0.into(), 0.into(), 1.into(), 1.into()],
                "Background" => vec![1.into(), 0.into(), 0.into()],
            });
            let (_, _, _, pat) =
                rasterize_shading_as_pattern(&doc, &sh, &IDENTITY, &HashMap::new(), 16, None)
                    .expect("degenerate axial still rasterizes");
            let red = pat.chunks_exact(4).filter(|px| px[0] == 255 && px[3] == 255).count();
            assert_eq!(
                red, 0,
                "degenerate axial geometry must resolve to the shading's own colour, \
                 not flood the raster with /Background"
            );
        }
    }

    /// A shading's placement matrix is built from file-controlled /BBox, /Matrix and
    /// /Domain, so an overflowed real reaches `Canvas.drawBitmap` as a non-finite
    /// transform and the bitmap silently does not draw. Nothing downstream guards it —
    /// interpret.rs drops non-finite PATH operands, but a shading matrix takes a
    /// different route to the wire. Dropping it here at least names the reason.
    #[test]
    fn a_non_finite_shading_matrix_is_refused() {
        let mut doc = Document::with_version("1.7");
        let func = doc.add_object(dictionary! {
            "FunctionType" => 2,
            "Domain" => vec![0.into(), 1.into()],
            "C0" => vec![0.into(), 0.into(), 0.into()],
            "C1" => vec![1.into(), 1.into(), 1.into()],
            "N" => 1,
        });
        // Axial, via a /BBox whose width overflowed an f32 on parse.
        let axial = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 1.into(), 0.into()],
            "Function" => Object::Reference(func),
            "BBox" => vec![
                Object::Real(0.0),
                Object::Real(0.0),
                Object::Real(f32::INFINITY),
                Object::Real(1.0),
            ],
        });
        assert!(
            rasterize_shading(&doc, &axial, &IDENTITY, &HashMap::new(), 16, None).is_none(),
            "a non-finite /BBox must not yield a shading with a broken transform"
        );
        // And via a non-finite inherited CTM, which neither this file nor the caller owns.
        let ok = Object::Dictionary(dictionary! {
            "ShadingType" => 2,
            "ColorSpace" => "DeviceRGB",
            "Coords" => vec![0.into(), 0.into(), 1.into(), 0.into()],
            "Function" => Object::Reference(func),
            "BBox" => vec![0.into(), 0.into(), 1.into(), 1.into()],
        });
        let bad_ctm: Mat = [f64::INFINITY, 0.0, 0.0, 1.0, 0.0, 0.0];
        assert!(
            rasterize_shading(&doc, &ok, &bad_ctm, &HashMap::new(), 16, None).is_none(),
            "a non-finite inherited CTM must not reach the wire either"
        );
        // The same shading with a sane CTM still renders, so the guard is not blanket.
        assert!(
            rasterize_shading(&doc, &ok, &IDENTITY, &HashMap::new(), 16, None).is_some(),
            "precondition: the guard must not reject well-formed shadings"
        );
    }

}