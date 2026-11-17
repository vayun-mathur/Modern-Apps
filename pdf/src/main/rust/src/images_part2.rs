/// Area-average downscale of an RGBA8888 buffer so its longer side is at most
/// `max_dim`, preserving aspect. Returns `None` when no downscale is needed.
///
/// `smooth` averages each source block, which is what a photograph wants. Bilevel art —
/// barcodes, QR codes, scanned fax pages, stencils — must pass `false`: averaging turns its
/// two colours into a spread of greys, and its hard 0/255 alpha into a translucent fringe,
/// which is the difference between a scannable QR code and an unreadable one.
fn downscale_rgba(data: &[u8], w: u32, h: u32, max_dim: u32, smooth: bool) -> Option<(u32, u32, Vec<u8>)> {
    if w == 0 || h == 0 || (w <= max_dim && h <= max_dim) {
        return None;
    }
    if data.len() < (w as usize) * (h as usize) * 4 {
        return None; // malformed buffer; leave as-is
    }
    let scale = max_dim as f64 / w.max(h) as f64;
    let nw = ((w as f64 * scale).round() as u32).clamp(1, w);
    let nh = ((h as f64 * scale).round() as u32).clamp(1, h);
    let mut out = vec![0u8; (nw as usize) * (nh as usize) * 4];
    for oy in 0..nh {
        let sy0 = ((oy as u64) * (h as u64) / (nh as u64)) as u32;
        let sy1 = ((((oy + 1) as u64) * (h as u64) / (nh as u64)) as u32).clamp(sy0 + 1, h);
        for ox in 0..nw {
            let sx0 = ((ox as u64) * (w as u64) / (nw as u64)) as u32;
            let sx1 = ((((ox + 1) as u64) * (w as u64) / (nw as u64)) as u32).clamp(sx0 + 1, w);
            let o = ((oy as usize) * (nw as usize) + ox as usize) * 4;
            if !smooth {
                // Nearest neighbour: the block's own first sample, kept exactly.
                let i = (sy0 as usize) * (w as usize) * 4 + (sx0 as usize) * 4;
                out[o..o + 4].copy_from_slice(&data[i..i + 4]);
                continue;
            }
            let (mut r, mut g, mut b, mut a, mut cnt) = (0u64, 0u64, 0u64, 0u64, 0u64);
            for sy in sy0..sy1 {
                let row = (sy as usize) * (w as usize) * 4;
                for sx in sx0..sx1 {
                    let i = row + (sx as usize) * 4;
                    // Average in PREMULTIPLIED space. Averaging straight RGBA lets a
                    // fully-transparent pixel contribute its colour with full weight,
                    // which bleeds masked-out colour into every visible edge — the halo
                    // around soft-masked and colour-keyed images.
                    let pa = data[i + 3] as u64;
                    r += data[i] as u64 * pa;
                    g += data[i + 1] as u64 * pa;
                    b += data[i + 2] as u64 * pa;
                    a += pa;
                    cnt += 1;
                }
            }
            if cnt > 0 {
                // Un-premultiply: the output buffer stays straight-alpha, which is what
                // the Kotlin side's Bitmap.createBitmap(int[], ...) expects.
                out[o] = r.checked_div(a).unwrap_or(0) as u8;
                out[o + 1] = g.checked_div(a).unwrap_or(0) as u8;
                out[o + 2] = b.checked_div(a).unwrap_or(0) as u8;
                out[o + 3] = a.checked_div(cnt).unwrap_or(0) as u8;
            }
        }
    }
    Some((nw, nh, out))
}

/// Decode an image XObject to [`ImageData`], downscaling oversized decoded
/// rasters (format 0) so a single image cannot blow the device bitmap budget.
/// JPEG passthrough (format 1) is left untouched; Kotlin subsamples it on decode.
pub(crate) fn extract_image(doc: &Document, stream: &lopdf::Stream, fill_argb: u32, cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<ImageData> {
    let mut img = extract_image_inner(doc, stream, fill_argb, cs_resources)?;
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

/// Whether the renderer should SMOOTH this image when it MAGNIFIES it.
///
/// §8.9.5.1 Table 89: `/Interpolate` defaults to **false** — "no image interpolation
/// shall be performed". Nothing in this crate reads it today and the Kotlin side sets
/// `isFilterBitmap = true` unconditionally, so every image is bilinearly smoothed on
/// upscale. For a photograph that is what a reader wants and no viewer does otherwise.
/// For BILEVEL art it is destructive in exactly the way round 1 established for the
/// downscale path: smoothing a barcode, a QR code, a scanned fax page or a stencil turns
/// its two colours into a ramp of greys and its hard 0/255 alpha into a translucent
/// fringe, which is the difference between a scannable QR code and an unreadable one.
///
/// So: honour an explicit `/Interpolate true`; otherwise smooth contone images and never
/// smooth bilevel ones. That deviates from the literal default for contone images, and
/// deliberately — the deviation is invisible, whereas obeying it would make every
/// magnified photograph blocky.
///
/// `interp` should pass this onto the `Prim::Image` record and `viewer` should carry it
/// on the wire and use it to choose `isFilterBitmap`. It is a standalone predicate rather
/// than an `ImageData` field so it can land without breaking either of their files.
///
/// UNWIRED, deliberately, and NOT dead code — do not delete it looking for a caller. The
/// policy is complete and tested (`interpolation_is_refused_for_bilevel_art`), but
/// `Prim::Image` has no field for it, so nothing serializes it and the Kotlin parser's
/// pre-v11 default (smooth) applies to everything. `wire.rs` records the rest: the wire
/// version bump to 11 and the `u8 interpolate` byte must land in the SAME change, because
/// bumping without writing the byte makes the parser eat the first byte of the image's
/// `u32 len` and desync every primitive after it. That crosses wire.rs and the Kotlin
/// side, so it is not this file's to finish.
#[allow(dead_code)]
pub(crate) fn image_should_interpolate(doc: &Document, dict: &Dictionary) -> bool {
    // `/I` is the §8.9.7 Table 93 abbreviation for /Interpolate in an inline image
    // dictionary. (As a /CS *value* `/I` means Indexed; as a KEY it is unambiguous.)
    if dict_true(doc, dict, b"Interpolate") || dict_true(doc, dict, b"I") {
        return true;
    }
    !is_bilevel(doc, dict)
}

/// A dictionary entry that is boolean `true`, dereferencing indirect objects.
/// §7.3.10 allows any object — booleans included — to be indirect, and a bare
/// `matches!(d.get(k), Some(Object::Boolean(true)))` misses `/ImageMask 12 0 R`,
/// which silently demotes a stencil to an ordinary image.
fn dict_true(doc: &Document, dict: &Dictionary, key: &[u8]) -> bool {
    matches!(
        dict.get(key).ok().and_then(|o| deref(doc, o)),
        Some(Object::Boolean(true))
    )
}

/// Whether the source image had two colours per component: a stencil, or one bit per
/// component. Fax-encoded images are bilevel by definition even without `/BitsPerComponent`.
fn is_bilevel(doc: &Document, dict: &Dictionary) -> bool {
    // `/IM` and `/BPC` are the §8.9.7 Table 93 abbreviations. Checking only the long
    // forms made every INLINE stencil and every inline one-bit image look contone, so
    // it got area-averaged on downscale and smoothed on magnification.
    if dict_true(doc, dict, b"ImageMask") || dict_true(doc, dict, b"IM") {
        return true;
    }
    if dict
        .get(b"BitsPerComponent")
        .or_else(|_| dict.get(b"BPC"))
        .ok()
        .and_then(num)
        == Some(1.0)
    {
        return true;
    }
    let specs = filters::filter_specs_from_dict(doc, dict);
    specs.iter().any(|(kind, _)| {
        matches!(kind, filters::FilterKind::Ccitt | filters::FilterKind::Jbig2)
    })
}

/// Resolve `/JBIG2Globals`, the symbol dictionary shared across pages (§7.4.7).
///
/// `specs` pairs an ARRAY `/DecodeParms` with the filter array index-by-index, which is
/// where `/Filter [/FlateDecode /JBIG2Decode]` keeps its parameters. Both JBIG2 call
/// sites then fell back to matching only a direct `Object::Dictionary` on the stream
/// dict, so an array `/DecodeParms` that `specs` could not pair — producers do emit it
/// misaligned with the filter chain — lost the globals entirely. A JBIG2 decode without
/// them fails, and a failed decode renders the region silently transparent.
///
/// §7.4 Table 5 allows `/DecodeParms` to be a dictionary or an array; `/DP` is its
/// inline-image abbreviation (§8.9.7 Table 93).
fn jbig2_globals(
    doc: &Document,
    dict: &Dictionary,
    specs: &[(filters::FilterKind, Option<Dictionary>)],
) -> Option<Vec<u8>> {
    // NOT `decompressed_content()`: that is lopdf's decoder, which implements only
    // Flate/LZW/ASCII85 and whose `unwrap_or_else` fallback hands back the still-ENCODED
    // bytes to be parsed as a symbol dictionary.
    let from_parms = |pd: &Dictionary| -> Option<Vec<u8>> {
        match pd.get(b"JBIG2Globals").ok().and_then(|o| deref(doc, o)) {
            Some(Object::Stream(gs)) => Some(stream_data_with_doc(doc, gs)),
            _ => None,
        }
    };
    if let Some(g) = specs
        .iter()
        .filter(|(k, _)| *k == filters::FilterKind::Jbig2)
        .find_map(|(_, pd)| pd.as_ref().and_then(&from_parms))
    {
        return Some(g);
    }
    match dict
        .get(b"DecodeParms")
        .ok()
        .or_else(|| dict.get(b"DP").ok())
        .and_then(|o| deref(doc, o))
    {
        Some(Object::Dictionary(d)) => from_parms(d),
        Some(Object::Array(a)) => a
            .iter()
            .filter_map(|el| deref(doc, el).and_then(|o| o.as_dict().ok()))
            .find_map(from_parms),
        _ => None,
    }
}
