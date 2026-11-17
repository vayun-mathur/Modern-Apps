/// Bradford chromatic adaptation of an XYZ triple from `src_white` to the D65
/// white used by the sRGB matrix. Used for CalRGB/CalGray with non-D65 whites.
fn adapt_to_d65(x: f64, y: f64, z: f64, src_white: [f64; 3]) -> (f64, f64, f64) {
    const B: [[f64; 3]; 3] = [
        [0.8951, 0.2664, -0.1614],
        [-0.7502, 1.7135, 0.0367],
        [0.0389, -0.0685, 1.0296],
    ];
    const BINV: [[f64; 3]; 3] = [
        [0.9869929, -0.1470543, 0.1599627],
        [0.4323053, 0.5183603, 0.0492912],
        [-0.0085287, 0.0400428, 0.9684867],
    ];
    let mul = |m: &[[f64; 3]; 3], v: [f64; 3]| {
        [
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
        ]
    };
    let d65 = [0.95047, 1.0, 1.08883];
    let s = mul(&B, src_white);
    let d = mul(&B, d65);
    let lms = mul(&B, [x, y, z]);
    let scaled = [
        lms[0] * d[0] / s[0].abs().max(1e-9),
        lms[1] * d[1] / s[1].abs().max(1e-9),
        lms[2] * d[2] / s[2].abs().max(1e-9),
    ];
    let out = mul(&BINV, scaled);
    (out[0], out[1], out[2])
}

pub(crate) fn eval_cs_to_rgb(_doc: &Document, kind: &CsKind, comps: &[f64], _cs_resources: &HashMap<Vec<u8>, ObjectId>) -> Option<u32> {
    match kind {
        CsKind::DeviceGray => {
            let v = comps.first().copied().unwrap_or(0.0);
            Some(gray_to_argb(v))
        }
        CsKind::DeviceRGB => {
            if comps.len()>=3 {
                Some(rgb_to_argb(comps[0], comps[1], comps[2]))
            } else { None }
        }
        CsKind::DeviceCMYK => {
            if comps.len()>=4 {
                Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3]))
            } else { None }
        }
        CsKind::Lab { white, range, .. } => {
            // PDF spec 8.6.5.4 Lab -> XYZ -> (D50->D65 adapt via Bradford) -> sRGB
            let l = comps.first().copied().unwrap_or(0.0).clamp(0.0,100.0);
            // §8.6.5.4 Table 66: /Range bounds a* and b*, and "values outside the
            // range shall be adjusted to the nearest valid value". The image path
            // already arrives in range via /Decode, but `sc`/`scn` operands and a
            // tint transform whose own /Range is absent do NOT — and an unbounded
            // a*/b* drives `fx`/`fz` far outside the cube-root branch and produces a
            // saturated primary instead of the nearest in-gamut colour.
            let clamp_pair = |v: f64, r: [f64; 2]| v.max(r[0].min(r[1])).min(r[0].max(r[1]));
            let a = clamp_pair(comps.get(1).copied().unwrap_or(0.0), range[0]);
            let b = clamp_pair(comps.get(2).copied().unwrap_or(0.0), range[1]);
            let fy = (l + 16.0)/116.0;
            let fx = a / 500.0 + fy;
            let fz = fy - b / 200.0;
            let eps = 0.008856;
            let kappa = 903.3;
            let fx3 = fx.powi(3);
            let fz3 = fz.powi(3);
            let fy3 = fy.powi(3);
            let xr = if fx3 > eps { fx3 } else { (fx - 16.0/116.0)/7.787 };
            let yr = if l > kappa*eps { fy3 } else { l/kappa };
            let zr = if fz3 > eps { fz3 } else { (fz - 16.0/116.0)/7.787 };
            let wx = white[0];
            let wy = white[1];
            let wz = white[2];
            let mut x = xr * wx;
            let mut y = yr * wy;
            let mut z = zr * wz;
            // Bradford D50->D65 adaptation (approximate)
            // Src WP approx D50 [0.96422,1.0,0.82521] is already `white` per spec; dest D65 0.95047,1.0,1.08883
            // Using fixed Bradford matrices for XYZ D50->D65 to improve sRGB fidelity.
            const BRAD: [[f64;3];3] = [
                [ 0.8951,  0.2664, -0.1614],
                [-0.7502,  1.7135,  0.0367],
                [ 0.0389, -0.0685,  1.0296],
            ];
            const BRAD_INV: [[f64;3];3] = [
                [ 0.9869929, -0.1470543,  0.1599627],
                [ 0.4323053,  0.5183603,  0.0492912],
                [-0.0085287,  0.0400428,  0.9684867],
            ];
            // CIE reference white point of the DESTINATION. The Lab source white is the
            // space's own /WhitePoint (`white`) per §8.6.5.4, not a fixed D50, which is
            // why only the D65 trio is needed here.
            const LMS_D65_X: f64 = 0.95047;
            const LMS_D65_Y: f64 = 1.0;
            const LMS_D65_Z: f64 = 1.08883;
            // LMS = BRAD * XYZ
            let lms_src = [
                BRAD[0][0]*x + BRAD[0][1]*y + BRAD[0][2]*z,
                BRAD[1][0]*x + BRAD[1][1]*y + BRAD[1][2]*z,
                BRAD[2][0]*x + BRAD[2][1]*y + BRAD[2][2]*z,
            ];
            // White in LMS
            let src_wp_lms = [
                BRAD[0][0]*wx + BRAD[0][1]*wy + BRAD[0][2]*wz,
                BRAD[1][0]*wx + BRAD[1][1]*wy + BRAD[1][2]*wz,
                BRAD[2][0]*wx + BRAD[2][1]*wy + BRAD[2][2]*wz,
            ];
            let dst_wp_lms = [
                BRAD[0][0]*LMS_D65_X + BRAD[0][1]*LMS_D65_Y + BRAD[0][2]*LMS_D65_Z,
                BRAD[1][0]*LMS_D65_X + BRAD[1][1]*LMS_D65_Y + BRAD[1][2]*LMS_D65_Z,
                BRAD[2][0]*LMS_D65_X + BRAD[2][1]*LMS_D65_Y + BRAD[2][2]*LMS_D65_Z,
            ];
            let scale = [
                if src_wp_lms[0].abs() > 1e-9 { dst_wp_lms[0]/src_wp_lms[0] } else { 1.0 },
                if src_wp_lms[1].abs() > 1e-9 { dst_wp_lms[1]/src_wp_lms[1] } else { 1.0 },
                if src_wp_lms[2].abs() > 1e-9 { dst_wp_lms[2]/src_wp_lms[2] } else { 1.0 },
            ];
            let lms_ad = [lms_src[0]*scale[0], lms_src[1]*scale[1], lms_src[2]*scale[2]];
            x = BRAD_INV[0][0]*lms_ad[0] + BRAD_INV[0][1]*lms_ad[1] + BRAD_INV[0][2]*lms_ad[2];
            y = BRAD_INV[1][0]*lms_ad[0] + BRAD_INV[1][1]*lms_ad[1] + BRAD_INV[1][2]*lms_ad[2];
            z = BRAD_INV[2][0]*lms_ad[0] + BRAD_INV[2][1]*lms_ad[1] + BRAD_INV[2][2]*lms_ad[2];
            // XYZ D65 -> linear sRGB
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma = |u: f64| -> f64 {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma(r_lin), gamma(g_lin), gamma(b_lin)))
        }
        CsKind::CalRGB { white, gamma, matrix } => {
            // CalRGB: A^GammaR, B^GammaG, C^GammaB -> XYZ via Matrix, then adapt
            // from the space's /WhitePoint to D65 before XYZ -> sRGB (PDF 8.6.5.3).
            let a = comps.first().copied().unwrap_or(0.0).clamp(0.0,1.0).powf(gamma[0].clamp(0.1,10.0));
            let b = comps.get(1).copied().unwrap_or(0.0).clamp(0.0,1.0).powf(gamma[1].clamp(0.1,10.0));
            let c = comps.get(2).copied().unwrap_or(0.0).clamp(0.0,1.0).powf(gamma[2].clamp(0.1,10.0));
            let x = matrix[0][0]*a + matrix[0][1]*b + matrix[0][2]*c;
            let y = matrix[1][0]*a + matrix[1][1]*b + matrix[1][2]*c;
            let z = matrix[2][0]*a + matrix[2][1]*b + matrix[2][2]*c;
            let (x, y, z) = adapt_to_d65(x, y, z, *white);
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma_corr = |u: f64| -> f64 {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma_corr(r_lin), gamma_corr(g_lin), gamma_corr(b_lin)))
        }
        CsKind::CalGray { gamma, white, .. } => {
            let g = comps.first().copied().unwrap_or(0.0).clamp(0.0,1.0);
            let a = g.powf(gamma.clamp(0.1,10.0));
            // Scale the whitepoint by the gray value, then adapt to D65.
            let (x, y, z) = adapt_to_d65(white[0]*a, white[1]*a, white[2]*a, *white);
            let r_lin =  3.2406 * x -1.5372 * y -0.4986 * z;
            let g_lin = -0.9689 * x +1.8758 * y +0.0415 * z;
            let b_lin =  0.0557 * x -0.2040 * y +1.0570 * z;
            let gamma_corr = |u: f64| {
                let u = u.clamp(0.0,1.0);
                if u <= 0.0031308 { 12.92*u } else { 1.055 * u.powf(1.0/2.4) -0.055 }
            };
            Some(rgb_to_argb(gamma_corr(r_lin), gamma_corr(g_lin), gamma_corr(b_lin)))
        }
        CsKind::ICCBased { n, alt } => {
            // Use alt if present — already handles Separation/DeviceN alt may be device (fast path)
            if let Some(alt_kind) = alt {
                if let Some(rgb) = eval_cs_to_rgb(_doc, alt_kind, comps, _cs_resources) {
                    return Some(rgb);
                }
            }
            // P0 fix critical #3: ICCBased had no ICC handling, silent fallback. Use alt or component-count fallback but warn.
            // Ideally parse ICC profile, but use RGB/Gray/CMYK by N with alpha-preserved alt lookup.
            match n {
                1 => {
                    let v = comps.first().copied().unwrap_or(0.0);
                    Some(gray_to_argb(v))
                }
                3 => {
                    if comps.len() >= 3 { Some(rgb_to_argb(comps[0], comps[1], comps[2])) } else { None }
                }
                4 => {
                    if comps.len() >= 4 { Some(cmyk_to_argb(comps[0], comps[1], comps[2], comps[3])) } else { None }
                }
                _ => None,
            }
        }
        CsKind::Indexed { base, lookup, base_ncomp, hival } => {
            let idx = (comps.first().copied().unwrap_or(0.0) as usize).clamp(0, *hival as usize);
            let off = idx * *base_ncomp as usize;
            if off + *base_ncomp as usize <= lookup.len() {
                let slice = &lookup[off..off+*base_ncomp as usize];
                // Each lookup byte 0..255 maps to the RANGE of the corresponding base
                // component (PDF 8.6.6.3): [0,1] for device spaces, but [0,100]/Range
                // for a Lab base — dividing by 255 unconditionally would darken Lab.
                let ranges = cs_kind_default_decode(base);
                let comps_f: Vec<f64> = slice.iter().enumerate().map(|(i, b)| {
                    let (lo, hi) = ranges.get(i).copied().unwrap_or((0.0, 1.0));
                    lo + (*b as f64 / 255.0) * (hi - lo)
                }).collect();
                eval_cs_to_rgb(_doc, base, &comps_f, _cs_resources)
            } else {
                None
            }
        }
        CsKind::Separation { name, alt, tint_fn } => {
            // The special colorant /None produces no marks (fully transparent).
            if name == b"None" {
                return Some(0x0000_0000);
            }
            let t = comps.first().copied().unwrap_or(1.0).clamp(0.0, 1.0);
            if let Some(tf) = tint_fn {
                let alt_comps = tf.eval(&[t]);
                // The tint transform must yield one value per component of the
                // alternate space (§8.6.6.4). A short return means a broken function;
                // passing it through would silently paint the wrong colour, so fall
                // back instead. (audit-e owns making well-formed Type 2/3 functions
                // return the full /Range arity; this is the backstop, not the fix.)
                if alt_comps.len() >= cs_kind_ncomp(alt).max(1) as usize {
                    if let Some(rgb) = eval_cs_to_rgb(_doc, alt, &alt_comps, _cs_resources) {
                        return Some(rgb);
                    }
                }
            }
            // No usable tint transform. Tint is SUBTRACTIVE per §8.6.6.4: 0 means no
            // colorant and 1 means maximum, so it must DARKEN. The old code fed `t`
            // straight into the alternate space, and for a DeviceGray/CalGray alternate
            // (which is also what a missing /Alternate defaults to) that inverted the
            // ramp — maximum ink rendered as WHITE, making spot-colour artwork
            // invisible on a white page. The correctly-polarised fallback below already
            // existed but was unreachable, because DeviceGray always returned Some.
            Some(gray_to_argb(1.0 - t))
        }
        CsKind::DeviceN { names, alt, tint_fn } => {
            // If every colorant is /None the region produces no marks.
            if !names.is_empty() && names.iter().all(|n| n == b"None") {
                return Some(0x0000_0000);
            }
            if let Some(tf) = tint_fn {
                // Evaluate the tint transform over all N input components.
                let alt_comps = tf.eval(comps);
                // Same arity check the Separation arm applies (§8.6.6.5 defers to
                // §8.6.6.4 for the tint transform): the function shall yield one value
                // per component of the alternate space. Without this the DeviceN arm
                // returned whatever `eval_cs_to_rgb` made of a short tuple — `None` for
                // an RGB/CMYK alternate, which leaves the PREVIOUS fill colour in place,
                // and plain black for a Gray alternate. Falling through to the
                // subtractive ramp below at least keeps the ink polarity right.
                if alt_comps.len() >= cs_kind_ncomp(alt).max(1) as usize {
                    if let Some(rgb) = eval_cs_to_rgb(_doc, alt, &alt_comps, _cs_resources) {
                        return Some(rgb);
                    }
                }
            }
            // No usable tint transform: treat each colorant as an independent
            // subtractive ink so all components contribute (0 tint = white, full
            // tint = darker).
            let light: f64 = comps.iter().map(|c| 1.0 - c.clamp(0.0, 1.0)).product();
            Some(gray_to_argb(light))
        }
        CsKind::Pattern { .. } => {
            // Pattern color handling: SCN may include base color, we already evaluated base if comps present
            // For pattern-only, we have no color - return None to keep current
            None
        }
    }
}




/// Resolve the page's (inherited) `/Resources` as an owned dictionary.
pub(crate) fn resources_dict(doc: &Document, page_id: ObjectId) -> Option<lopdf::Dictionary> {
    inherited(doc, page_id, b"Resources")
        .and_then(|o| deref(doc, o))
        .and_then(|o| o.as_dict().ok())
        .cloned()
}

/// Collect the `/Filter` names of a stream (single name or array).
pub(crate) fn filter_names(doc: &Document, dict: &lopdf::Dictionary) -> Vec<String> {
    match dict.get(b"Filter").ok().and_then(|o| deref(doc, o)) {
        Some(Object::Name(n)) => vec![String::from_utf8_lossy(n).into_owned()],
        Some(Object::Array(a)) => a
            .iter()
            .filter_map(|o| o.as_name().ok())
            .map(|n| String::from_utf8_lossy(n).into_owned())
            .collect(),
        _ => Vec::new(),
    }
}

/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`.
/// Number of color components for a colorspace object, plus an optional Indexed
/// palette `(base_components, lookup_bytes)`. Now also handles Lab, Pattern etc returning fallback.
pub(crate) fn colorspace_info(
    doc: &Document,
    cs: Option<&Object>,
) -> (u8, Option<(u8, Vec<u8>)>) {
    colorspace_info_at(doc, cs, 0)
}

/// `MAX_CS_DEPTH` applies here for exactly the reason given at its definition, which
/// this second, parallel implementation of the same walk did not inherit:
/// `[/Indexed 5 0 R 255 <00FF00>]` stored AS object 5 makes the `Indexed` arm below
/// recurse on a reference that resolves back to the same array, forever. A Rust stack
/// overflow is not a panic and cannot be caught, so it takes the process with it.
fn colorspace_info_at(
    doc: &Document,
    cs: Option<&Object>,
    depth: u32,
) -> (u8, Option<(u8, Vec<u8>)>) {
    if depth >= MAX_CS_DEPTH {
        return (1, None);
    }
    let cs = match cs.and_then(|o| deref(doc, o)) {
        Some(o) => o,
        None => return (1, None),
    };
    match cs {
        Object::Name(n) => match n.as_slice() {
            b"DeviceRGB" | b"RGB" => (3, None),
            b"DeviceCMYK" | b"CMYK" => (4, None),
            b"CalRGB" => (3, None),
            b"Lab" => (3, None),
            _ => (1, None), // DeviceGray / CalGray / fallback
        },
        Object::Array(a) => {
            let head = a.first().and_then(|o| o.as_name().ok()).unwrap_or(b"");
            match head {
                b"ICCBased" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| match o {
                            Object::Stream(s) => s.dict.get(b"N").ok().and_then(num),
                            Object::Dictionary(d) => d.get(b"N").ok().and_then(num),
                            _ => None,
                        })
                        .unwrap_or(1.0) as u8;
                    (n.max(1), None)
                }
                b"Indexed" | b"I" => {
                    let (base_n, _) = colorspace_info_at(doc, a.get(1), depth + 1);
                    let lookup = match a.get(3).and_then(|o| deref(doc, o)) {
                        Some(Object::String(s, _)) => s.clone(),
                        // Same reasoning as the `parse_cs_array` Indexed arm above: an
                        // indirect or array /DecodeParms makes lopdf's decoder return Ok
                        // with the predictor unapplied, so the palette is silently garbage.
                        Some(Object::Stream(s)) => stream_data_with_doc(doc, s),
                        _ => Vec::new(),
                    };
                    (1, Some((base_n, lookup)))
                }
                b"CalRGB" => (3, None),
                b"CalGray" => (1, None),
                b"Lab" => (3, None),
                b"DeviceN" => {
                    let n = a
                        .get(1)
                        .and_then(|o| deref(doc, o))
                        .and_then(|o| o.as_array().ok())
                        .map(|arr| arr.len() as u8)
                        .unwrap_or(1);
                    (n.max(1), None)
                }
                b"Separation" => (1, None),
                b"Pattern" => (0, None),
                _ => (1, None),
            }
        }
        _ => (0, None),
    }
}


pub(crate) fn comps_to_rgb(comps: &[u8], n: u8) -> (u8, u8, u8) {
    match n {
        3 => (comps[0], comps[1], comps[2]),
        4 => {
            let c = comps[0] as f64 / 255.0;
            let m = comps[1] as f64 / 255.0;
            let y = comps[2] as f64 / 255.0;
            let k = comps[3] as f64 / 255.0;
            let r = (1.0 - c) * (1.0 - k);
            let g = (1.0 - m) * (1.0 - k);
            let b = (1.0 - y) * (1.0 - k);
            ((r * 255.0).round().clamp(0.0,255.0) as u8, (g * 255.0).round().clamp(0.0,255.0) as u8, (b * 255.0).round().clamp(0.0,255.0) as u8)
        }
        _ => {
            // includes 1 and also maybe DeviceN fallback
            if comps.is_empty() { (0,0,0) } else { (comps[0], comps[0], comps[0]) }
        }
    }
}
