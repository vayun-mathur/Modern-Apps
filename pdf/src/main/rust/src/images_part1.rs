impl OcConfig {
    pub(crate) fn from_doc(doc: &Document) -> Self {
        let mut cfg = OcConfig {
            on: std::collections::HashSet::new(),
            off: std::collections::HashSet::new(),
            base_on: true,
        };
        let Ok(catalog) = doc.catalog() else {
            return cfg;
        };
        let Some(Object::Dictionary(oc_props)) =
            catalog.get(b"OCProperties").ok().and_then(|o| deref(doc, o))
        else {
            return cfg;
        };
        let Some(d_dict) = oc_props
            .get(b"D")
            .ok()
            .and_then(|o| deref(doc, o))
            .and_then(|o| o.as_dict().ok())
        else {
            return cfg;
        };
        cfg.base_on = matches!(
            d_dict.get(b"BaseState").ok().and_then(|o| o.as_name().ok()),
            Some(b"ON") | None
        );
        let collect = |key: &[u8], out: &mut std::collections::HashSet<ObjectId>| {
            if let Some(list) = d_dict
                .get(key)
                .ok()
                .and_then(|o| deref(doc, o))
                .and_then(|o| o.as_array().ok())
            {
                out.extend(list.iter().filter_map(|obj| obj.as_reference().ok()));
            }
        };
        collect(b"ON", &mut cfg.on);
        collect(b"OFF", &mut cfg.off);
        cfg
    }

    /// Whether an OCG is visible. True for an unknown group, so a document that
    /// never declares `/OCProperties` renders in full.
    pub(crate) fn is_ocg_visible(&self, ocg_id: ObjectId) -> bool {
        // §8.11.4.3: /ON and /OFF override /BaseState, applied in the order
        // Table 101 lists them — /BaseState, then /ON, then /OFF — so /OFF wins
        // for a group named by both, matching mainstream viewers.
        if self.off.contains(&ocg_id) {
            return false;
        }
        if self.on.contains(&ocg_id) {
            return true;
        }
        self.base_on
    }

    /// Evaluate an OCMD (Optional Content Membership Dictionary) `/OCGs` + `/P`
    /// visibility policy. Returns true if the membership resolves to HIDDEN.
    fn ocmd_hidden(&self, d: &Dictionary) -> bool {
        let mut ids: Vec<ObjectId> = Vec::new();
        match d.get(b"OCGs").ok() {
            Some(Object::Reference(id)) => ids.push(*id),
            Some(Object::Array(a)) => {
                for o in a {
                    if let Ok(id) = o.as_reference() { ids.push(id); }
                }
            }
            _ => {}
        }
        if ids.is_empty() {
            return false; // no member groups -> visible
        }
        let vis: Vec<bool> = ids.iter().map(|id| self.is_ocg_visible(*id)).collect();
        let policy = d.get(b"P").ok().and_then(|o| o.as_name().ok());
        let visible = match policy {
            Some(b"AllOn") => vis.iter().all(|v| *v),
            Some(b"AnyOff") => vis.iter().any(|v| !*v),
            Some(b"AllOff") => vis.iter().all(|v| !*v),
            _ => vis.iter().any(|v| *v), // AnyOn (default)
        };
        !visible
    }

    /// Decide whether marked content / an XObject tagged with the given `/OC`
    /// object (an OCG or OCMD, possibly an indirect reference) should be HIDDEN.
    pub(crate) fn object_hidden(&self, doc: &Document, obj: &Object) -> bool {
        match obj {
            Object::Reference(id) => {
                if let Ok(Object::Dictionary(d)) = doc.get_object(*id) {
                    if d.get(b"Type").ok().and_then(|o| o.as_name().ok()) == Some(b"OCMD") {
                        return self.ocmd_hidden(d);
                    }
                }
                !self.is_ocg_visible(*id)
            }
            Object::Dictionary(d) if d.get(b"Type").ok().and_then(|o| o.as_name().ok()) == Some(b"OCMD") => {
                self.ocmd_hidden(d)
            }
            // Inline OCG dict without an object id can't be matched against the
            // ON/OFF lists; default to visible.
            _ => false,
        }
    }
}

/// Whether a colorspace requires the full `eval_cs_to_rgb` path (vs the fast
/// `comps_to_rgb` device path which is equivalent for plain RGB/Gray/CMYK).
fn cs_needs_eval(k: &CsKind) -> bool {
    match k {
        CsKind::DeviceGray | CsKind::DeviceRGB | CsKind::DeviceCMYK | CsKind::Pattern { .. } => false,
        CsKind::ICCBased { alt, .. } => alt.as_ref().map(|a| cs_needs_eval(a)).unwrap_or(false),
        CsKind::Lab { .. }
        | CsKind::Separation { .. }
        | CsKind::DeviceN { .. }
        | CsKind::CalRGB { .. }
        | CsKind::CalGray { .. }
        | CsKind::Indexed { .. } => true,
    }
}

/// Default `/Decode` ranges per component for a colorspace (used when the image
/// has no explicit `/Decode`). Lab uses [0,100] for L and its a*/b* ranges.
fn default_decode_for(k: &CsKind, ncomp: usize) -> Vec<(f64, f64)> {
    match k {
        CsKind::Lab { range, .. } => vec![
            (0.0, 100.0),
            (range[0][0], range[0][1]),
            (range[1][0], range[1][1]),
        ],
        _ => vec![(0.0, 1.0); ncomp.max(1)],
    }
}

/// Convert unpacked component bytes (w*h*ncomp, each 0..=255, already scaled
/// from `bpc`) into RGBA using the image's full `/ColorSpace`. Handles
/// Separation/DeviceN tint transforms, Lab, Cal*, ICCBased alternates and
/// Indexed palettes via `eval_cs_to_rgb`, building LUTs for single-component
/// and indexed images to stay fast. Falls back to `comps_to_rgb` for plain
/// device spaces.
fn image_samples_to_rgba(
    doc: &Document,
    dict: &lopdf::Dictionary,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
    decoded_comps: &[u8],
    // (width, height) in samples
    wh: (usize, usize),
    ncomp: usize,
    bpc: u32,
) -> Vec<u8> {
    let (w, h) = wh;
    let mut rgba = vec![0u8; w * h * 4];
    let cs_kind = dict
        .get(b"ColorSpace")
        .or_else(|_| dict.get(b"CS"))
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));

    let decode_arr: Vec<f64> = dict
        .get(b"Decode")
        .or_else(|_| dict.get(b"D"))
        .ok()
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_array().ok())
        .map(|a| a.iter().filter_map(|o| deref(doc, o).and_then(num)).collect())
        .unwrap_or_default();

    let put = |rgba: &mut [u8], idx: usize, argb: u32| {
        rgba[idx] = ((argb >> 16) & 0xFF) as u8;
        rgba[idx + 1] = ((argb >> 8) & 0xFF) as u8;
        rgba[idx + 2] = (argb & 0xFF) as u8;
        // Carry the alpha rather than forcing 255. Every colour space yields an opaque
        // 0xFF... except the /None colorant (§8.6.6.4), which "shall never produce any
        // visible output" — forcing 255 painted a /None Separation image as a SOLID
        // BLACK rectangle over the page instead of nothing at all.
        rgba[idx + 3] = ((argb >> 24) & 0xFF) as u8;
    };

    let kind = match cs_kind {
        Some(k) if cs_needs_eval(&k) => k,
        _ => {
            // Fast device path. Apply the image's /Decode array (default identity
            // [0,1] per component) — e.g. a DeviceGray image with /Decode [1 0]
            // must be inverted. Device spaces have <=4 components.
            let has_decode = decode_arr.len() >= ncomp * 2
                && (0..ncomp).any(|c| decode_arr[c * 2] != 0.0 || (decode_arr[c * 2 + 1] - 1.0).abs() > 1e-9);
            for i in 0..w * h {
                let base = i * ncomp;
                // A pixel the sample buffer does not cover is left fully transparent
                // rather than painted opaque black. The `samples.len()` guard in
                // `extract_image_inner` means a short buffer cannot reach here today,
                // but nothing pins that, and a solid black rectangle over the page is a
                // far worse degradation than a hole. Matches the general path below.
                if base + ncomp > decoded_comps.len() {
                    continue;
                }
                let (r, g, b) = if has_decode {
                    let mut tmp = [0u8; 4];
                    for c in 0..ncomp.min(4) {
                        let dmin = decode_arr[c * 2];
                        let dmax = decode_arr[c * 2 + 1];
                        let v = decoded_comps[base + c] as f64 / 255.0;
                        let mapped = (dmin + v * (dmax - dmin)).clamp(0.0, 1.0);
                        tmp[c] = (mapped * 255.0).round() as u8;
                    }
                    comps_to_rgb(&tmp[..ncomp.min(4)], ncomp as u8)
                } else {
                    comps_to_rgb(&decoded_comps[base..base + ncomp], ncomp as u8)
                };
                let idx = i * 4;
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
            return rgba;
        }
    };

    // Indexed: build a palette LUT over the base colorspace.
    if let CsKind::Indexed { base, lookup, base_ncomp, hival: declared_hival } = &kind {
        let bn = (*base_ncomp as usize).max(1);
        let maxidx = if bpc >= 8 { 255usize } else { (1usize << bpc) - 1 };
        // An unloadable palette (a lookup stream that failed to decompress) used to
        // yield a 1-entry all-black table, painting a solid black rectangle over the
        // page. Nothing is better than something wrong here.
        if lookup.len() < bn {
            image_warn!("Indexed palette empty ({} bytes, {} per entry) - image dropped", lookup.len(), bn);
            return rgba;
        }
        // §8.6.6.3: /hival is the largest valid index and is authoritative. Clamp it
        // to what the table can actually supply so a short table cannot read garbage.
        let hival = (*declared_hival as usize).min(lookup.len() / bn - 1);
        // Each palette byte spans the RANGE of the corresponding base component,
        // which is [0,1] for device spaces but [0,100] / /Range for a Lab base
        // (§8.6.6.3) — dividing by 255 unconditionally rendered Lab palettes black.
        let base_ranges = cs_kind_default_decode(base);
        let mut palette = vec![0xFF00_0000u32; hival + 1];
        for (i, slot) in palette.iter_mut().enumerate() {
            let off = i * bn;
            if off + bn <= lookup.len() {
                let comps: Vec<f64> = lookup[off..off + bn]
                    .iter()
                    .enumerate()
                    .map(|(c, b)| {
                        let (lo, hi) = base_ranges.get(c).copied().unwrap_or((0.0, 1.0));
                        lo + (*b as f64 / 255.0) * (hi - lo)
                    })
                    .collect();
                if let Some(argb) = eval_cs_to_rgb(doc, base, &comps, cs_resources) {
                    *slot = argb;
                }
            }
        }
        for i in 0..w * h {
            let byte = decoded_comps.get(i * ncomp).copied().unwrap_or(0) as usize;
            let raw = if bpc >= 8 { byte } else { (byte * maxidx + 127) / 255 };
            // §8.9.5.2 Table 90: an Indexed image's default /Decode is [0 2^bpc - 1], and
            // an explicit one REMAPS the sample onto that index range — e.g. /Decode
            // [0 15] on an 8-bpc image addresses only the first 16 palette entries. This
            // branch ignored /Decode entirely, so such an image read the wrong colours
            // straight through. The default is the identity, so this is a no-op for the
            // overwhelming majority of Indexed images.
            let index = if decode_arr.len() >= 2 && maxidx > 0 {
                let (dmin, dmax) = (decode_arr[0], decode_arr[1]);
                let mapped = dmin + (raw as f64 / maxidx as f64) * (dmax - dmin);
                mapped.round().clamp(0.0, hival as f64) as usize
            } else {
                raw
            };
            let argb = palette.get(index.min(hival)).copied().unwrap_or(0xFF00_0000);
            put(&mut rgba, i * 4, argb);
        }
        return rgba;
    }

    let default_decode = default_decode_for(&kind, ncomp);
    let comp_range = |i: usize| -> (f64, f64) {
        if decode_arr.len() >= (i + 1) * 2 {
            (decode_arr[i * 2], decode_arr[i * 2 + 1])
        } else {
            default_decode.get(i).copied().unwrap_or((0.0, 1.0))
        }
    };

    // Single-component spaces: 256-entry LUT.
    if ncomp == 1 {
        let (lo, hi) = comp_range(0);
        let mut lut = [0xFF00_0000u32; 256];
        for (v, slot) in lut.iter_mut().enumerate() {
            let comp = lo + (v as f64 / 255.0) * (hi - lo);
            if let Some(argb) = eval_cs_to_rgb(doc, &kind, &[comp], cs_resources) {
                *slot = argb;
            }
        }
        for i in 0..w * h {
            let byte = decoded_comps.get(i * ncomp).copied().unwrap_or(0) as usize;
            put(&mut rgba, i * 4, lut[byte]);
        }
        return rgba;
    }

    // General per-pixel evaluation (bounded by MAX_IMAGE_PIXELS at entry).
    for i in 0..w * h {
        let base = i * ncomp;
        let idx = i * 4;
        if base + ncomp <= decoded_comps.len() {
            let comps: Vec<f64> = (0..ncomp)
                .map(|c| {
                    let (lo, hi) = comp_range(c);
                    lo + (decoded_comps[base + c] as f64 / 255.0) * (hi - lo)
                })
                .collect();
            if let Some(argb) = eval_cs_to_rgb(doc, &kind, &comps, cs_resources) {
                put(&mut rgba, idx, argb);
            } else {
                let (r, g, b) = comps_to_rgb(&decoded_comps[base..base + ncomp], ncomp as u8);
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = 255;
            }
        }
    }
    rgba
}

/// Extract a drawable image from an image XObject stream, or `None` if the
/// format is unsupported (e.g. JPEG2000, exotic color spaces).
/// Turn a decoded 1-bit codec raster (black-on-white RGBA) into a stencil: dark
/// pixels are painted with `fill_argb` (opaque), light pixels become transparent.
/// `invert` swaps the sense (for `/Decode [1 0]`).
fn stencilize(rgba: &mut [u8], fill_argb: u32, invert: bool) {
    let fr = ((fill_argb >> 16) & 0xFF) as u8;
    let fg = ((fill_argb >> 8) & 0xFF) as u8;
    let fb = (fill_argb & 0xFF) as u8;
    for px in rgba.chunks_exact_mut(4) {
        // alpha 0 means the codec never wrote this pixel (a truncated JBIG2 leaves whole
        // trailing rows untouched). Its RGB is undefined, and luma 0 would read as "dark"
        // and paint it — turning missing data into a solid block of fill colour.
        if px[3] == 0 {
            continue;
        }
        let luma = (px[0] as u32 * 299 + px[1] as u32 * 587 + px[2] as u32 * 114) / 1000;
        let paint = if invert { luma >= 128 } else { luma < 128 };
        if paint {
            px[0] = fr; px[1] = fg; px[2] = fb; px[3] = 255;
        } else {
            px[3] = 0;
        }
    }
}

/// Invert the colour channels of an RGBA buffer, leaving alpha alone. Used where a
/// codec hands back finished RGB and the image dictionary's `/Decode [1 0]` (§8.9.5.2)
/// still has to be honoured.
fn invert_rgb(rgba: &mut [u8]) {
    for px in rgba.chunks_exact_mut(4) {
        px[0] = 255 - px[0];
        px[1] = 255 - px[1];
        px[2] = 255 - px[2];
    }
}

/// True when the image dictionary's `/Decode` is the fully inverted form `[1 0]`
/// repeated once per component.
///
/// §8.9.5.2 Table 89 puts `/Decode` on the image XObject dictionary and scopes it to
/// no particular filter, so it applies to DCTDecode too — but the JPEG passthrough
/// hands raw codestream bytes to the platform decoder, which cannot apply it. Only
/// this one array shape is detected: it is the form prepress and Distiller output
/// carries (`[1 0 1 0 1 0 1 0]` on CMYK JPEGs), and it is exactly the per-component
/// complement, so it can be honoured by inverting samples inside the codec decode
/// without building a general `Dmin + v(Dmax-Dmin)` mapping there. Any other array
/// keeps the existing passthrough.
///
/// Indexed and Lab are excluded: their `/Decode` operates on a palette index range
/// and on L*a*b* ranges respectively, so `1 - v` is not the complement there.
fn decode_array_is_inverted(
    doc: &Document,
    dict: &Dictionary,
    ncomp: usize,
    cs_resources: &HashMap<Vec<u8>, ObjectId>,
) -> bool {
    if ncomp == 0 {
        return false;
    }
    let Some(Object::Array(a)) = dict
        .get(b"Decode")
        .or_else(|_| dict.get(b"D"))
        .ok()
        .and_then(|o| deref(doc, o))
    else {
        return false;
    };
    let inverted = a.len() >= ncomp * 2
        && (0..ncomp).all(|c| {
            deref(doc, &a[c * 2]).and_then(num) == Some(1.0)
                && deref(doc, &a[c * 2 + 1]).and_then(num) == Some(0.0)
        });
    if !inverted {
        return false;
    }
    let cs = dict
        .get(b"ColorSpace")
        .or_else(|_| dict.get(b"CS"))
        .ok()
        .and_then(|o| parse_cs_kind(doc, Some(o), cs_resources));
    !matches!(cs, Some(CsKind::Indexed { .. }) | Some(CsKind::Lab { .. }))
}

/// Per-axis decimation factor that brings `w * h` inside `MAX_IMAGE_PIXELS`.
///
/// Returns 1 whenever the image already fits, which is all but a handful of images, so
/// the sampling loops that use it are unchanged for the common case. An oversized image
/// is DECIMATED rather than dropped: a 300 dpi A0 engineering scan is 9933 x 14043 =
/// 139 Mpx, which is legal, common, and was rendering as nothing at all.
fn decimation_step(w: u32, h: u32) -> usize {
    let px = (w as usize).saturating_mul(h as usize);
    let mut s = 1usize;
    while s < 64 && px / (s * s) > MAX_IMAGE_PIXELS {
        s += 1;
    }
    s
}
