pub(crate) fn extract_inline_image(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let mut img = extract_inline_image_inner(doc, stream, fill_argb, cs_resources)?;
    // The XObject path has always done this; the inline path did not, so it could hand
    // the wire a full MAX_IMAGE_PIXELS raster — 64 MB of RGBA — and a few KB of inline
    // Flate or CCITT expands to exactly that. The consumer only materialises 16 MB for
    // one image, so anything larger was a dropped primitive at best.
    if img.format == 0 {
        let bilevel = is_bilevel(doc, &stream.dict);
        if let Some((nw, nh, ndata)) =
            downscale_rgba(&img.data, img.w, img.h, IMAGE_DOWNSCALE_MAX_DIM, !bilevel)
        {
            img.w = nw;
            img.h = nh;
            img.data = ndata;
        }
    }
    Some(img)
}

fn extract_inline_image_inner(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let dict = &stream.dict;
    let (Some(w), Some(h)) = (
        dict.get(b"Width").or_else(|_| dict.get(b"W")).ok().and_then(num),
        dict.get(b"Height").or_else(|_| dict.get(b"H")).ok().and_then(num),
    ) else {
        image_warn!("inline image has no usable /W or /H - dropped");
        return None;
    };
    let (w, h) = (w as u32, h as u32);
    if w==0 || h==0 || w>MAX_IMAGE_DIM || h>MAX_IMAGE_DIM {
        image_warn!("inline image {}x{} outside 1..{} - dropped", w, h, MAX_IMAGE_DIM);
        return None;
    }
    if (w as usize)*(h as usize) > MAX_IMAGE_PIXELS {
        image_warn!("inline image {}x{} over the {} pixel budget - dropped", w, h, MAX_IMAGE_PIXELS);
        return None;
    }

    // §8.9.7 Table 93 abbreviates the stencil flag to `/IM`, and this function checked
    // only the long form — so an inline image mask was decoded as an ordinary one-bit
    // DeviceGray image and painted as an OPAQUE black-and-white rectangle over the page
    // instead of stencilling the current fill colour. Inline stencils are the classic way
    // to embed a small logo or rule, so this showed up as black boxes hiding content.
    // (`fill_argb` was even bound as `_fill_argb`, which is how it went unnoticed.)
    let image_mask = dict_true(doc, dict, b"ImageMask") || dict_true(doc, dict, b"IM");
    // A stencil is one bit per sample by definition (§8.9.6.2), whatever /BPC claims.
    let bpc = if image_mask {
        1
    } else {
        dict.get(b"BitsPerComponent").or_else(|_| dict.get(b"BPC")).ok().and_then(num).unwrap_or(8.0) as u32
    };

    let cs_obj = dict.get(b"ColorSpace").or_else(|_| dict.get(b"CS")).ok().cloned();
    // Resolve through the resource map, as the XObject path does. `colorspace_info`
    // takes no `cs_resources` and returns 1 for every NAMED space — including
    // `/CS /Cs0` and the `/I` (Indexed) abbreviation §8.9.7 permits — so the image
    // decoded at 1/N of its true stride and came out as sheared grey garbage. This is
    // the same defect round 1 fixed for image XObjects and left here.
    let ncomp = if image_mask {
        1
    } else {
        match cs_obj.as_ref().and_then(|o| parse_cs_kind(doc, Some(o), cs_resources)) {
            Some(k) => cs_kind_image_ncomp(&k),
            None => cs_obj.as_ref().map(|o| colorspace_info(doc, Some(o)).0).unwrap_or(1).clamp(1, 32),
        }
    };

    // Support filter chain for inline BI: may have Flate, AHx, A85 etc.
    let specs = filters::filter_specs_from_dict(doc, dict);
    let raw = stream.content.clone();
    // On failure these are still-encoded bytes. Unpacked as raw samples they are noise,
    // and for an inline stencil noise is a solid block of fill colour over the page —
    // the same failure mode the mask path had. Nothing is better than something wrong.
    let Some(samples) = filters::decode_stream_chain(raw, &specs, doc) else {
        image_warn!("inline image {}x{}: filter chain failed to decode - dropped", w, h);
        return None;
    };

    // DCT inline
    let legacy_filters = filter_names(doc, dict);
    let mut all_has_dct = specs.iter().any(|(k,_)| *k == filters::FilterKind::Dct);
    if !all_has_dct {
        all_has_dct = legacy_filters.iter().any(|f| f.eq_ignore_ascii_case("DCTDecode")||f.eq_ignore_ascii_case("DCT"));
    }
    if all_has_dct {
        let smask = read_smask(doc, dict, w, h);
        let colorkey = read_color_key_mask(doc, dict);
        let decode_inverted = decode_array_is_inverted(
            doc,
            dict,
            jpeg_num_components(&samples).unwrap_or(0) as usize,
            cs_resources,
        );
        if let Some((jw,jh,mut rgba)) = decode_jpeg_rgba_decoded(&samples, decode_inverted) {
            apply_smask(&mut rgba, &smask);
            apply_color_key_mask(&mut rgba, &colorkey);
            return Some(ImageData { w: jw, h: jh, format: 0, data: rgba });
        }
        return Some(ImageData { w, h, format: 1, data: samples });
    }

    // For other filters, samples is already chain-decoded — EXCEPT the image codecs,
    // which `decode_stream_chain` deliberately leaves encoded because only this layer
    // knows /Width and /Height (§7.4, and filters.rs:545). §8.9.7 Table 93 lists /CCF
    // among the abbreviations an inline image may use, so an inline fax reached the
    // unpacker below as a raw G3/G4 CODESTREAM and was read as one-bit samples: noise,
    // and for the `/IM true` case noise means a speckled block of fill colour painted
    // over the page. Decoding it here yields exactly what §7.4.6 says the filter
    // produces — one-bit DeviceGray samples with /BlackIs1 already applied — so the
    // /Decode, /ImageMask and /ColorSpace handling below all stay correct unchanged.
    let samples = if specs.iter().any(|(k, _)| *k == filters::FilterKind::Ccitt) {
        let params = filters::parse_ccitt_params(
            doc,
            specs
                .iter()
                .find(|(k, _)| *k == filters::FilterKind::Ccitt)
                .and_then(|(_, d)| d.as_ref()),
        );
        // The unpacker strides rows by /Width; `decode_ccitt` strides them by /Columns
        // (default 1728 per Table 11). Substituting its raster is only sound when the
        // two agree, and a sheared fax is no better than a dropped one.
        if params.columns as usize != w as usize {
            image_warn!(
                "inline CCITT image {}x{}: /Columns {} disagrees with /W - dropped",
                w, h, params.columns
            );
            return None;
        }
        match filters::decode_ccitt(&samples, w, h, &params) {
            Some(mut packed) => {
                // `/Rows` shorter than /Height leaves the tail of the raster missing;
                // absent bytes unpack to sample 0, which is BLACK under the /BlackIs1
                // default. Pad with the white sample value instead.
                let want = (w as usize).div_ceil(8).saturating_mul(h as usize);
                if packed.len() < want {
                    packed.resize(want, if params.black_is1 { 0x00 } else { 0xFF });
                }
                packed
            }
            None => {
                image_warn!(
                    "inline CCITT image {}x{} decode failed (k={}, cols={}) - dropped",
                    w, h, params.k, params.columns
                );
                return None;
            }
        }
    } else if specs.iter().any(|(k, _)| {
        matches!(k, filters::FilterKind::Jbig2 | filters::FilterKind::Jpx)
    }) {
        // No inline decoder for these. Falling through would unpack the codestream as
        // samples, which is the same noise-as-a-stencil failure as CCITT above.
        image_warn!("inline image {}x{}: JBIG2/JPX inline codec unsupported - dropped", w, h);
        return None;
    } else {
        samples
    };
    // Same rule as the XObject path: a buffer that cannot supply even one scanline is a
    // failed decode, not a short image, and unpacking it reads every sample as 0 — an
    // opaque black rectangle for a contone image, a solid block of fill colour for a
    // stencil (sample 0 MARKS the page, §8.9.6.2).
    let src_row_bytes = {
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
            "inline image {}x{}x{} at {} bpc: {} sample bytes for {} per row - unusable, dropped",
            w, h, ncomp, bpc, samples.len(), src_row_bytes
        );
        return None;
    }
    let unpacked = match unpack_samples_to_bytes(&samples, w as usize, h as usize, ncomp as usize, bpc) {
        Some(u) => u,
        None => {
            image_warn!("inline image {}x{}x{} at {} bpc could not be unpacked - dropped", w, h, ncomp, bpc);
            return None;
        }
    };
    if image_mask {
        // `/D [1 0]` (`/Decode`) swaps the paint/skip sense (§8.9.6.2). With the default
        // decode a sample of 0 MARKS the page; `unpack_samples_to_bytes` has already
        // scaled the one-bit samples to 0 or 255.
        let invert = matches!(
            dict.get(b"Decode").or_else(|_| dict.get(b"D")).ok().and_then(|o| deref(doc, o)),
            Some(Object::Array(a)) if a.first().and_then(num) == Some(1.0)
        );
        let (fr, fg, fb) = (
            ((fill_argb >> 16) & 0xFF) as u8,
            ((fill_argb >> 8) & 0xFF) as u8,
            (fill_argb & 0xFF) as u8,
        );
        let mut rgba = vec![0u8; (w as usize) * (h as usize) * 4];
        // Rows the sample data does not reach. `unpack_samples_to_bytes` zero-fills
        // them, and with the default /Decode a sample of 0 MARKS the page (§8.9.6.2),
        // so a stencil whose stream stops early painted a solid block of fill colour
        // over everything below the last real scanline. The XObject stencil branch
        // defaults absent bytes to the no-paint value for exactly this reason; the
        // inline one reached the unpacker first and lost the distinction.
        let rows_present = samples.len().checked_div(src_row_bytes).unwrap_or(0);
        for (i, px) in rgba.chunks_exact_mut(4).enumerate() {
            if i / (w as usize) >= rows_present {
                continue;
            }
            let mut sample = unpacked.get(i).copied().unwrap_or(255);
            if invert {
                sample = 255 - sample;
            }
            if sample == 0 {
                px[0] = fr; px[1] = fg; px[2] = fb; px[3] = 255;
            }
        }
        return Some(ImageData { w, h, format: 0, data: rgba });
    }
    let mut rgba = image_samples_to_rgba(doc, dict, cs_resources, &unpacked, (w as usize, h as usize), ncomp as usize, bpc);
    let smask = read_smask(doc, dict, w, h);
    apply_smask(&mut rgba, &smask);
    if let Some(ranges_raw) = read_color_key_ranges_raw(doc, dict) {
        apply_color_key_mask_samples(&mut rgba, &unpacked, ncomp as usize, &ranges_raw, bpc);
    }
    Some(ImageData { w, h, format: 0, data: rgba })
}

/// Radial (Type 3) shading parameter at a point in shading space.
///
/// The gradient is defined by the family of circles C(s) centered at
/// c0 + s·(c1−c0) with radius r0 + s·(r1−r0), for `coords` = [x0 y0 r0 x1 y1 r1].
/// For a point (fx,fy) this returns the largest `s` such that the point lies on
/// C(s) with a non-negative radius, honoring the two `/Extend` flags (`e0` past
/// s<0, `e1` past s>1). Solving |p − c(s)| = r(s) yields the quadratic
/// a·s² − 2b·s + c = 0. Returns `None` when no circle covers the point (the
/// caller leaves that pixel transparent).
pub(crate) fn radial_shading_param(coords: &[f64], e0: bool, e1: bool, fx: f64, fy: f64) -> Option<f64> {
    if coords.len() < 6 { return None; }
    let (x0, y0, r0) = (coords[0], coords[1], coords[2]);
    let (x1, y1, r1) = (coords[3], coords[4], coords[5]);
    let dx = x1 - x0;
    let dy = y1 - y0;
    let dr = r1 - r0;
    let px = fx - x0;
    let py = fy - y0;
    let a = dx*dx + dy*dy - dr*dr;
    let b = px*dx + py*dy + r0*dr;
    let c = px*px + py*py - r0*r0;

    let mut best: Option<f64> = None;
    let mut consider = |s: f64| {
        // The interpolated circle radius must be non-negative.
        if r0 + s*dr < 0.0 { return; }
        // Respect the shading domain unless extended past an end.
        let in_range = (0.0..=1.0).contains(&s) || (s < 0.0 && e0) || (s > 1.0 && e1);
        if !in_range { return; }
        best = Some(match best { Some(cur) if cur >= s => cur, _ => s });
    };
    if a.abs() < 1e-9 {
        // Degenerate to a linear equation: -2b·s + c = 0.
        if b.abs() > 1e-12 { consider(c / (2.0*b)); }
    } else {
        let disc = b*b - a*c;
        if disc >= 0.0 {
            let sq = disc.sqrt();
            consider((b + sq)/a);
            consider((b - sq)/a);
        }
    }
    best
}

/// Whether a placement matrix can actually be drawn with.
///
/// A non-finite entry — from a `/BBox`, `/Matrix` or `/Domain` holding a real that
/// overflowed on parse, or from an inherited CTM that already went non-finite —
/// reaches `Canvas.drawBitmap` as a broken transform, where the bitmap silently does
/// not draw. Nothing downstream catches it: interpret.rs drops non-finite PATH
/// operands, but a shading's matrix travels to the wire by a different route.
fn mat_is_finite(m: &Mat) -> bool {
    m.iter().all(|v| v.is_finite())
}

pub(crate) fn rasterize_shading(doc: &Document, shading_obj: &Object, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32, clip_bbox_device: Option<[f64;4]>) -> Option<(Mat, u32, u32, Vec<u8>)> {
    rasterize_shading_inner(doc, shading_obj, base_ctm, cs_resources, size, clip_bbox_device, false)
}

/// As [`rasterize_shading`], but honouring `/Background`.
///
/// §8.7.4.3 Table 78: `/Background` fills the parts of the painted area that lie
/// outside the shading's own extent — and it "shall be ignored by the `sh`
/// operator", applying only when the shading is painted as a shading PATTERN.
/// `rasterize_shading` serves both callers and cannot tell them apart, so the
/// plain entry point takes the `sh` semantics (ignore it) and pattern painting
/// must opt in here. Applying it under `sh` floods the whole clip region with the
/// background colour instead of leaving it clear, which is the damaging direction.
///
/// Pattern painting (`paint_pattern_fill` / `paint_pattern_stroke`, PatternType 2)
/// calls this; the `sh` operator must keep calling [`rasterize_shading`]. The two
/// have IDENTICAL signatures, so nothing but a test stops an edit from swapping
/// them and it fails silently in both directions — `background_is_ignored_by_sh_but
/// _honoured_by_a_pattern` here, and `interpret::blind_reaudit_r4_tests::background
/// _applies_to_shading_patterns_but_not_to_the_sh_operator` at the call sites.
pub(crate) fn rasterize_shading_as_pattern(doc: &Document, shading_obj: &Object, base_ctm: &Mat, cs_resources: &HashMap<Vec<u8>, ObjectId>, size: u32, clip_bbox_device: Option<[f64;4]>) -> Option<(Mat, u32, u32, Vec<u8>)> {
    rasterize_shading_inner(doc, shading_obj, base_ctm, cs_resources, size, clip_bbox_device, true)
}

/// Fill every fully-transparent pixel of an already-rasterized shading with
/// `/Background`.
///
/// §8.7.4.3 Table 78 defines it as filling "those portions of the area to be painted
/// that lie outside the bounds of the shading object", so a pixel the shading did not
/// cover is exactly the case — for a MESH. Types 2 and 3 apply it inline while walking
/// the axial or radial parameter; the mesh types (`shading::rasterize_shading_mesh`)
/// hand back a finished raster that does not read `/Background`, so without this
/// post-pass a mesh PATTERN silently dropped the entry.
///
/// NOT used for type 1: `rasterize_shading_function_based` rasterizes exactly the
/// `/Domain` rect, so it has no pixels outside the shading's bounds at all and an
/// uncoloured one is an in-domain evaluation failure. See the type 1 arm of
/// `rasterize_shading_inner`.
fn fill_background_outside(
    doc: &Document,
    dict: &lopdf::Dictionary,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    rgba: &mut [u8],
) {
    let comps: Vec<f64> = match dict
        .get(b"Background")
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
    {
        Some(a) => a.iter().filter_map(num).collect(),
        None => return,
    };
    if comps.is_empty() {
        return;
    }
    let cs = dict
        .get(b"ColorSpace")
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources))
        .unwrap_or(CsKind::DeviceRGB);
    let argb = match eval_cs_to_rgb(doc, &cs, &comps, cs_resources) {
        Some(v) => v,
        None => return,
    };
    let (r, g, b) = (
        ((argb >> 16) & 0xFF) as u8,
        ((argb >> 8) & 0xFF) as u8,
        (argb & 0xFF) as u8,
    );
    for px in rgba.chunks_exact_mut(4) {
        if px[3] == 0 {
            px[0] = r;
            px[1] = g;
            px[2] = b;
            px[3] = 255;
        }
    }
}
