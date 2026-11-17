fn extract_image_inner(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let dict = &stream.dict;
    // A missing or non-numeric /Width or /Height dropped the image with no diagnostic —
    // one of the paths that renders as an invisible hole (§8.9.5.1 makes both required).
    let (Some(w), Some(h)) = (
        // §7.3.10: any dictionary value may be an indirect reference, and these are
        // routinely written as one by producers that share a size object across a
        // scanned page's images. Without the deref `num` sees `Object::Reference`,
        // returns None, and the `let ... else` below deletes the image.
        dict.get(b"Width").ok().and_then(|o| deref(doc, o)).and_then(num),
        dict.get(b"Height").ok().and_then(|o| deref(doc, o)).and_then(num),
    ) else {
        image_warn!("image XObject has no usable /Width or /Height - dropped");
        return None;
    };
    let (w, h) = (w as u32, h as u32);
    if w == 0 || h == 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM {
        image_warn!("image {}x{} outside 1..{} - dropped", w, h, MAX_IMAGE_DIM);
        return None;
    }
    // NOTE: no MAX_IMAGE_PIXELS guard here. It used to drop the image outright,
    // which deleted every high-res scan and photo, and it ran BEFORE the JPEG
    // passthrough below — which allocates no RGBA buffer at all and is subsampled
    // by the platform decoder. The budget is now enforced where memory is actually
    // committed: the raw-sample path decimates via `unpack_samples_decimated`, and
    // the codec paths cap their own decoded dimensions.
    if stream.content.len() > MAX_IMAGE_BYTES * 4 {
        // raw compressed size guard, still attempt but cap later
    }

    // Normalize filter chain case-insensitive + DecodeParms pairing
    let specs = filters::filter_specs_from_dict(doc, dict);
    let has_kind = |k: filters::FilterKind| specs.iter().any(|(kind,_)| *kind == k);

    // Legacy names fallback for callers without new parser (inline images)
    let legacy_filters = filter_names(doc, dict);
    let legacy_is_dct = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("DCTDecode") || f.eq_ignore_ascii_case("DCT"));
    let legacy_is_jpx = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("JPXDecode"));
    let is_dct = has_kind(filters::FilterKind::Dct) || legacy_is_dct;
    let is_jpx = has_kind(filters::FilterKind::Jpx) || legacy_is_jpx;
    let is_ccitt = has_kind(filters::FilterKind::Ccitt);
    let is_jbig2 = has_kind(filters::FilterKind::Jbig2);

    // A CCITT/JBIG2 stencil (`/ImageMask true`) must paint the current fill color
    // where the sample selects "paint" and be transparent elsewhere — not render
    // an opaque black/white raster. Detect it up front so the codec branches can
    // stencil their output.
    // `/Decode [1 0]` on a one-bit image swaps black and white. It applies to a stencil's
    // paint/skip sense and to a plain bilevel image's colours alike.
    let decode_inverts_1bit = matches!(
        dict.get(b"Decode").ok().and_then(|o| deref(doc, o)),
        Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
    );
    let mask_stencil = dict_true(doc, dict, b"ImageMask");
    let mask_invert = mask_stencil && decode_inverts_1bit;

    // JBIG2: attempt with Globals
    if is_jbig2 {
        let globals_bytes = jbig2_globals(doc, dict, &specs);

        // Attempt decode from raw content
        if let Some((jw,jh,mut rgba)) = jbig2::decode_jbig2(&stream.content, globals_bytes.as_deref(), w, h) {
            if mask_stencil { stencilize(&mut rgba, fill_argb, mask_invert); return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba }); }
            // §7.4.7 makes a JBIG2 image one-bit DeviceGray, and §8.9.5.2 applies its
            // /Decode array like any other image's — `jbig2.rs` returns finished RGB, so
            // nothing further down would ever have applied it and `/Decode [1 0]`
            // rendered the scan as its own positive. Only reached when the stencil arm
            // above did not already consume the inversion.
            if decode_inverts_1bit { invert_rgb(&mut rgba); }
            let smask = read_smask(doc, dict, jw, jh);
            apply_smask(&mut rgba, &smask);
            // §8.9.6.4: a stencil /Mask applies to ANY base image, codec-compressed or
            // not. Only the raw-sample path honoured it, so a JBIG2/CCITT/JPX base with
            // an explicit mask rendered as an uncut opaque rectangle.
            if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) { apply_explicit_mask(&mut rgba, &mask_alpha); }
            if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
            return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
        }
        // If JBIG2 decode fails, try chain fallback then return transparent None (remove red placeholder artifact per P0 critical #6)
        let raw = stream.content.clone();
        if let Some(chain) = filters::decode_stream_chain(raw, &specs, doc) {
            if let Some((jw, jh, mut rgba)) = jbig2::decode_jbig2(&chain, globals_bytes.as_deref(), w, h) {
                if mask_stencil { stencilize(&mut rgba, fill_argb, mask_invert); return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba }); }
                if decode_inverts_1bit { invert_rgb(&mut rgba); }
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) { apply_explicit_mask(&mut rgba, &mask_alpha); }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
        }
        // Per lead's P0-4 decision: no placeholder. Stay transparent, but say why —
        // audit-a owns making these decodes actually succeed.
        image_warn!("JBIG2 {}x{} decode failed (globals: {}) - image dropped", w, h, globals_bytes.is_some());
        return None;
    }

    // JPEG2000 path
    if is_jpx {
        // §7.4.9 Table 89. /SMaskInData governs whether the codestream's own alpha
        // channel is used at all; its default is 0, i.e. "ignore it". Clamp anything
        // out of range to the default rather than guessing.
        let smask_in_data = match dict.get(b"SMaskInData").ok().and_then(|o| deref(doc, o)).and_then(num) {
            Some(1.0) => 1u8,
            Some(2.0) => 2u8,
            _ => 0u8,
        };
        // §7.4.9: "the value of ColorSpace shall override any colour space specified in
        // the JPEG2000 data". Only the spaces whose channel layout the JPX assembler
        // below can actually express are honoured. Indexed is deliberately excluded:
        // `cs_kind_image_ncomp` reports 1 for it (one palette index per sample), and
        // forcing Gray would paint raw palette indices as grey levels — worse than
        // trusting the codestream. Lab/Separation/DeviceN are excluded for the same
        // reason: their components are not device colour and would need the full
        // `eval_cs_to_rgb` path, which this decoder does not run.
        let cs_hint = dict
            .get(b"ColorSpace")
            .or_else(|_| dict.get(b"CS"))
            .ok()
            .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
            .and_then(|k| match k {
                CsKind::DeviceGray | CsKind::CalGray { .. } => Some(jp2::Interp::Gray),
                CsKind::DeviceRGB | CsKind::CalRGB { .. } => Some(jp2::Interp::Rgb),
                CsKind::DeviceCMYK => Some(jp2::Interp::Cmyk),
                CsKind::ICCBased { n, .. } => match n {
                    1 => Some(jp2::Interp::Gray),
                    3 => Some(jp2::Interp::Rgb),
                    4 => Some(jp2::Interp::Cmyk),
                    _ => None,
                },
                _ => None,
            });
        let opts = jp2::JpxOpts { smask_in_data, cs_hint };
        // Raw JPX may be after chain of Ascii/Flate decodes
        let raw = stream_data_with_doc(doc, stream);
        let try_data = [&raw[..], &stream.content[..]];
        for d in try_data {
            if let Some((jw, jh, mut rgba)) = jp2::decode_with_opts(d, opts) {
                // Table 89 forbids /SMask when /SMaskInData is nonzero, and `apply_smask`
                // REPLACES alpha rather than combining it — applying one here would throw
                // away the codestream alpha we were just told to use.
                if smask_in_data == 0 {
                    let smask = read_smask(doc, dict, jw, jh);
                    apply_smask(&mut rgba, &smask);
                    if smask.is_some() {
                        if let Some(matte) = read_matte(doc, dict) {
                            apply_matte(&mut rgba, matte);
                        }
                    }
                }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) { apply_explicit_mask(&mut rgba, &mask_alpha); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
        }
        // JPX decode failed: do not fall through and reinterpret the encoded
        // JPEG2000 stream as raw samples.
        image_warn!("JPX {}x{} decode failed ({} bytes) - image dropped", w, h, stream.content.len());
        return None;
    }

    // CCITTFax full Group3/4 with K,Columns,BlackIs1 params
    if is_ccitt {
        let params = {
            // Find first Ccitt parms dict from specs
            let mut found = None;
            for (kind, pd) in specs.iter() {
                if *kind == filters::FilterKind::Ccitt {
                    found = Some(filters::parse_ccitt_params(doc, pd.as_ref()));
                    break;
                }
            }
            found.unwrap_or_else(|| {
                // Try DecodeParms singly
                if let Some(Object::Dictionary(d)) = dict.get(b"DecodeParms").ok().and_then(|o| deref(doc,o)) {
                    filters::parse_ccitt_params(doc, Some(d))
                } else {
                    filters::CcittParams::default()
                }
            })
        };
        // Try chain decode first for possible Ascii/Flate wrappers before CCITT
        let raw = stream.content.clone();
        // `decode_stream_chain` returns None for a corrupt Flate/LZW/ASCII85 wrapper or
        // an unrecognised filter name, NOT just for an unknown filter. `unwrap_or(raw)`
        // then fed the still-COMPRESSED bytes to the fax decoder as if they were a
        // codestream. Bail instead: a fax decoded from zlib bytes is noise.
        let Some(chain_bytes) = filters::decode_stream_chain(raw, &specs, doc) else {
            image_warn!("CCITT {}x{}: filter chain before the codec failed to decode - image dropped", w, h);
            return None;
        };
        // If chain_bytes is CCITT, decode — fix #11 Columns vs Width: output raster is Columns, not max
        if let Some(packed) = filters::decode_ccitt(&chain_bytes, w, h, &params) {
            let columns = if params.columns > 0 { params.columns as usize } else { w as usize };
            let rows_est = if params.rows > 0 { params.rows as usize } else { h as usize };
            // Guard: packed already accounts for BlackIs1
            // `packed` is one bit per pixel, so it is cheap even for a huge fax — a 300 dpi
            // A0 scan is 139 Mpx but only 17 MB packed. It is the RGBA below that is 32x
            // that, so decimate the SAMPLING rather than refuse the image: dropping it
            // deleted exactly the large engineering and architectural scans that CCITT
            // exists to carry.
            //
            // NOTE: `step` is always 1 as the tree stands, because `filters.rs`'s
            // `decode_ccitt` applies `(cols * rows) > 16 * 1024 * 1024 { return None; }`
            // to the ONE-BIT buffer before we ever get here, using the same 16 Mpx number
            // that is sized for 4-byte RGBA. That cap is the live blocker for a large fax
            // and it is in a file this round does not assign; until it is raised, this
            // branch cannot see an oversized raster. It is still the correct shape here,
            // and it has to be, because raising the filters.rs cap alone would just move
            // the drop to this line.
            let step = decimation_step(columns as u32, rows_est as u32);
            let (out_w, out_h) = (columns.div_ceil(step) as u32, rows_est.div_ceil(step) as u32);
            if step > 1 {
                image_warn!(
                    "CCITT raster {}x{} over the {} pixel budget: decimated by {} to {}x{}",
                    columns, rows_est, MAX_IMAGE_PIXELS, step, out_w, out_h
                );
            }
            let row_bytes = columns.div_ceil(8);
            let mut rgba = vec![255u8; out_w as usize * out_h as usize * 4]; // white init
            // This is one-bit DeviceGray, so sample 0 is black and sample 1 is white, and
            // only `/Decode` may swap them (§8.9.5.2). `decode_ccitt` does NOT normalise to
            // that convention: it emits the polarity `/BlackIs1` asks for (§7.4.6 Table 11 —
            // with `/BlackIs1 true` a BLACK pel is a 1 bit), and `filters.rs`'s `fill_rows`
            // applies that flag once and nowhere else. Reading sample 0 as black regardless
            // of the flag is exactly what makes the pair correct: `/BlackIs1` belongs to the
            // filter, the meaning of a sample belongs to the colour space. Compensating for
            // it here would be the double inversion, not a fix for one. Painting a 1 bit
            // black instead rendered every default-parameter fax as its own negative, which
            // is where the solid dark blocks over scanned pages came from.
            let black_bit = if decode_inverts_1bit { 1 } else { 0 };
            for y in 0..out_h as usize {
                let sy = y * step;
                for x in 0..out_w as usize {
                    let sx = x * step;
                    let byte = packed.get(sy * row_bytes + sx / 8).copied().unwrap_or(0);
                    let bit = (byte >> (7 - (sx % 8))) & 1;
                    if bit == black_bit {
                        let idx = (y * out_w as usize + x) * 4;
                        if idx + 3 < rgba.len() { rgba[idx] = 0; rgba[idx+1] = 0; rgba[idx+2] = 0; rgba[idx+3] = 255; }
                    }
                }
            }
            if mask_stencil {
                // `/Decode` is already in the raster above, so the stencil must not re-apply it.
                stencilize(&mut rgba, fill_argb, false);
                return Some(ImageData { w: out_w, h: out_h, format: 0, data: rgba });
            }
            let smask = read_smask(doc, dict, out_w, out_h);
            apply_smask(&mut rgba, &smask);
            if let Some(mask_alpha) = read_explicit_mask(doc, dict, out_w, out_h) { apply_explicit_mask(&mut rgba, &mask_alpha); }
            if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
            return Some(ImageData { w: out_w, h: out_h, format: 0, data: rgba });
        }
        // CCITT decode failed: don't reinterpret the encoded fax data as raw 1-bit samples.
        image_warn!("CCITT {}x{} decode failed (k={}, cols={}) - image dropped", w, h, params.k, params.columns);
        return None;
    }

    // DCT with SMask/Mask: decode to RGBA + apply mask, with gray fallback
    if is_dct {
        let smask_present = dict.get(b"SMask").is_ok();
        let mask_present = dict.get(b"Mask").is_ok();
        // Chain decode to get JPEG bytes if wrapped in Ascii etc
        let raw = stream.content.clone();
        // Same reasoning as the CCITT branch: on failure these are still-compressed
        // bytes, and the no-mask arm below would hand them to the platform decoder as
        // if they were a JPEG, which renders nothing and says nothing.
        let Some(jpeg_bytes) = filters::decode_stream_chain(raw, &specs, doc) else {
            image_warn!("DCT {}x{}: filter chain before the codec failed to decode - image dropped", w, h);
            return None;
        };
        // §8.9.5.2: /Decode remaps samples for every image, DCT included. Only the
        // fully inverted form is honoured (see `decode_array_is_inverted`); when it is
        // present the platform passthrough below cannot be used, because Android's
        // decoder has no way to apply it.
        let decode_inverted = decode_array_is_inverted(
            doc,
            dict,
            jpeg_num_components(&jpeg_bytes).unwrap_or(0) as usize,
            cs_resources,
        );
        let mask_arm = smask_present || mask_present;
        if mask_arm {
            if let Some((jw,jh,mut rgba)) = decode_jpeg_rgba_decoded(&jpeg_bytes, decode_inverted) {
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if smask.is_some() { if let Some(matte) = read_matte(doc, dict) { apply_matte(&mut rgba, matte); } }
                if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) {
                    apply_explicit_mask(&mut rgba, &mask_alpha);
                }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
            // Fallback to gray JPEG decode + alpha
            if let Some((jw,jh,gray)) = decode_jpeg_gray(&jpeg_bytes) {
                let mut rgba = vec![0u8; (jw*jh*4) as usize];
                for i in 0..(jw*jh) as usize {
                    let g = gray.get(i).copied().unwrap_or(0);
                    let g = if decode_inverted { 255 - g } else { g };
                    rgba[i*4]=g; rgba[i*4+1]=g; rgba[i*4+2]=g; rgba[i*4+3]=255;
                }
                let smask = read_smask(doc, dict, jw, jh);
                apply_smask(&mut rgba, &smask);
                if let Some(mask_alpha) = read_explicit_mask(doc, dict, jw, jh) {
                    apply_explicit_mask(&mut rgba, &mask_alpha);
                }
                if let Some(ck) = read_color_key_mask(doc, dict) { apply_color_key_mask(&mut rgba, &Some(ck)); }
                return Some(ImageData{ w: jw, h: jh, format: 0, data: rgba });
            }
            // Both Rust decodes failed (they share `jpeg_decoder`, so if one cannot read
            // the codestream neither can the other). Fall through to the passthrough
            // below rather than dropping: an image was being deleted ONLY because it
            // carried an /SMask, while the identical bytes without one were handed to
            // the platform decoder and rendered. `decode_mask_stream_gray` already makes
            // this call the same way — a mask it cannot decode leaves the image
            // "unmasked, visible, but not silently so" — and the damage is bounded by
            // the image's own rectangle, unlike a clip or a full-bleed fill.
            image_warn!(
                "DCT {}x{} decode failed with mask present - passing through UNMASKED rather than dropping",
                w, h
            );
        }
        // Android's bitmap decoder cannot handle CMYK/YCCK JPEGs, so decode those in
        // Rust; RGB and gray pass through.
        //
        // The test used to be `jpeg_adobe_transform(..).is_some()`, i.e. ANY APP14
        // "Adobe" segment. Every Photoshop "Save As JPEG" writes one, so ordinary
        // 3-component RGB photographs took this branch, gave up the passthrough (and
        // with it the platform decoder's subsampling) for a full RGBA buffer, and on a
        // decode failure logged a warning claiming Android could not render them. The
        // marker's mere presence says nothing; only transform 2 (YCCK) is a colour
        // transform the platform cannot undo, and that implies 4 components anyway —
        // it is kept as a separate term only for a codestream whose SOF we failed to
        // find.
        let cmyk = jpeg_num_components(&jpeg_bytes) == Some(4)
            || jpeg_adobe_transform(&jpeg_bytes) == Some(2);
        if (cmyk || decode_inverted) && !mask_arm {
            if let Some((jw, jh, rgba)) = decode_jpeg_rgba_decoded(&jpeg_bytes, decode_inverted) {
                return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
            }
            // Passing a CMYK/YCCK JPEG through to the platform decoder is the same
            // as dropping it (Android cannot decode one), so say so rather than
            // leaving an unexplained hole. With /Decode [1 0 ...] the passthrough is
            // wrong in a different way — it paints the negative — but a negative is
            // more recoverable than nothing.
            image_warn!(
                "DCT {}x{} CMYK/YCCK/inverted-Decode decode failed - passing through, likely to render nothing or inverted",
                w, h
            );
        }
        // efficient passthrough
        return Some(ImageData { w, h, format: 1, data: jpeg_bytes });
    }

    let image_mask = dict_true(doc, dict, b"ImageMask");
    let bpc = if image_mask { 1 } else { dict.get(b"BitsPerComponent").ok().and_then(|o| deref(doc, o)).and_then(num).unwrap_or(8.0) as u32 };
    // This project's own chain, not lopdf's: lopdf cannot undo RunLength, ASCIIHex or a
    // predictor, and returns the still-compressed bytes on failure. Unpacked as one-bit
    // samples those are noise, and a stencil of noise is a solid block of fill colour.
    let samples = stream_data_with_doc(doc, stream);

    if image_mask {
        let invert = decode_inverts_1bit;
        let fr = ((fill_argb >> 16) & 0xFF) as u8;
        let fg = ((fill_argb >> 8) & 0xFF) as u8;
        let fb = (fill_argb & 0xFF) as u8;
        let row_bytes = w.div_ceil(8) as usize;
        // With the default /Decode [0 1] a sample of 0 MARKS the page (§8.9.6.2), so
        // absent bytes must default to the no-paint bit — defaulting them to 0 turned
        // an undecodable stencil into a solid block of fill colour over the content.
        // If not even one row survived, the buffer is unusable (e.g. `stream_data_with_doc`
        // handed back still-compressed bytes) and painting anything would be noise.
        if samples.len() < row_bytes {
            image_warn!(
                "stencil /ImageMask {}x{}: {} bytes for {} per row - unusable, skipping",
                w, h, samples.len(), row_bytes
            );
            return None;
        }
        let absent: u8 = if invert { 0x00 } else { 0xFF };
        // A stencil is one bit per pixel, so /Width x /Height of 20000 x 20000 costs
        // 2500 bytes on disk but 1.6 GB of RGBA here. Every other branch bounds its own
        // raster (the contone path decimates, the codecs cap their decoded size); this
        // one did not, so a tiny file could OOM the process. Decimate with the same
        // rule the contone path uses, which is the identity for any sane stencil.
        let step = decimation_step(w, h);
        let (ow, oh) = ((w as usize).div_ceil(step), (h as usize).div_ceil(step));
        if step > 1 {
            image_warn!("stencil /ImageMask {}x{} over pixel budget: decimated by {} to {}x{}", w, h, step, ow, oh);
        }
        let mut rgba = vec![0u8; ow * oh * 4];
        for oy in 0..oh {
            let y = oy * step;
            for ox in 0..ow {
                let x = ox * step;
                let byte = samples.get(y * row_bytes + x / 8).copied().unwrap_or(absent);
                let mut bit = (byte >> (7 - (x % 8))) & 1;
                if invert { bit ^= 1; }
                let idx = (oy * ow + ox) * 4;
                if bit == 0 {
                    rgba[idx]=fr; rgba[idx+1]=fg; rgba[idx+2]=fb; rgba[idx+3]=255;
                }
            }
        }
        return Some(ImageData { w: ow as u32, h: oh as u32, format: 0, data: rgba });
    }

    // Resolve the colourspace through the resource map so a NAMED entry
    // (`/ColorSpace /CS0` -> `[/ICCBased <</N 3>>]`) yields the right component
    // count. `colorspace_info` takes no cs_resources and so returned 1 for every
    // named space, making the image decode at 1/N of the true stride — the
    // classic sheared-grey-garbage look. §8.9.5.1 requires the name to be
    // resolved through the resource dictionary's /ColorSpace subdictionary.
    let cs_obj = dict.get(b"ColorSpace").or_else(|_| dict.get(b"CS")).ok();
    let ncomp = match cs_obj.and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)) {
        Some(k) => cs_kind_image_ncomp(&k),
        None => colorspace_info(doc, cs_obj).0.clamp(1, 32),
    };

    // `stream_data_with_doc` returns an EMPTY buffer when every decoder failed
    // (objects.rs:106 — "render nothing instead" of handing back encoded bytes).
    // `unpack_samples_decimated` then reads every absent byte as sample 0, and
    // `image_samples_to_rgba` maps an all-zero pixel to black with alpha 255, so a
    // total decode failure painted an OPAQUE BLACK RECTANGLE over the page. That is
    // strictly worse than the invisible hole it was trying not to be, and it is the
    // one case the zero-fill contract does not cover: §8.9.5.1 lets /Width x /Height
    // outrun the sample data, but a stream that yielded not even one scanline is a
    // failed decode, not a short image. The /ImageMask branch above already refuses
    // on the same rule.
    let src_row_bytes = {
        // The same normalisation `unpack_samples_decimated` applies, so the guard
        // cannot demand more bytes than the unpacker will actually read.
        let eff_bpc = match bpc {
            1 | 2 | 4 | 8 | 12 | 16 => bpc as usize,
            _ => 8,
        };
        (w as usize)
            .saturating_mul(ncomp as usize)
            .saturating_mul(eff_bpc)
            .div_ceil(8)
    };
    if samples.len() < src_row_bytes {
        image_warn!(
            "image {}x{} x {} comps at {} bpc: {} sample bytes for {} per row - unusable, dropped",
            w, h, ncomp, bpc, samples.len(), src_row_bytes
        );
        return None;
    }
    // An image over the pixel budget is DECIMATED, not dropped: the old guard
    // deleted every 600 dpi scan and every >16 MP photo outright. Keeping one
    // sample in `step` per axis bounds the RGBA buffer while still rendering.
    let step = decimation_step(w, h);
    let (dw, dh, decoded_comps) = match unpack_samples_decimated(&samples, w as usize, h as usize, ncomp as usize, bpc, step) {
        Some(t) => t,
        None => {
            // Only reachable for a zero dimension/arity or an unpacked buffer over
            // MAX_UNPACKED_SAMPLE_BYTES, i.e. a bogus /DeviceN arity or /N.
            image_warn!(
                "image {}x{} x {} comps at {} bpc (step {}) could not be unpacked - dropped",
                w, h, ncomp, bpc, step
            );
            return None;
        }
    };
    let (out_w, out_h) = (dw as u32, dh as u32);
    if step > 1 {
        image_warn!("image {}x{} over pixel budget: decimated by {} to {}x{}", w, h, step, out_w, out_h);
    }

    let mut rgba = image_samples_to_rgba(doc, dict, cs_resources, &decoded_comps, (dw, dh), ncomp as usize, bpc);

    // Masks are resampled to the (possibly decimated) raster, not to /Width x /Height.
    let smask = read_smask(doc, dict, out_w, out_h);
    apply_smask(&mut rgba, &smask);
    // /Matte: base colors are premultiplied against a matte background; undo it
    // using the SMask alpha we just applied.
    if smask.is_some() {
        if let Some(matte) = read_matte(doc, dict) {
            apply_matte(&mut rgba, matte);
        }
    }
    // Explicit stencil /Mask image (mutually exclusive with color-key /Mask).
    if let Some(mask_alpha) = read_explicit_mask(doc, dict, out_w, out_h) {
        apply_explicit_mask(&mut rgba, &mask_alpha);
    }
    // Color-key masking is compared against the pre-conversion samples so it is
    // correct for CMYK/DeviceN (not just DeviceRGB/Gray). Indexed images key on
    // the index value, which `decoded_comps` already holds (ncomp==1).
    if let Some(ranges_raw) = read_color_key_ranges_raw(doc, dict) {
        apply_color_key_mask_samples(&mut rgba, &decoded_comps, ncomp as usize, &ranges_raw, bpc);
    }
    Some(ImageData { w: out_w, h: out_h, format: 0, data: rgba })
}
