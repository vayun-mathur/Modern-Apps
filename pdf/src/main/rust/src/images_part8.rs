/// As `decode_jpeg_rgba`, but with `invert_components` applying `255 - v` to every
/// COMPONENT of the decoded samples, i.e. the image dictionary's `/Decode [1 0 ...]`
/// (§8.9.5.2).
///
/// The inversion has to happen on the components, before any colour conversion. For
/// gray and RGB that is the same thing as complementing the output pixel, but for
/// CMYK it is not: `cmyk_to_argb` gives r = (1-c)(1-k), and complementing the two
/// inputs gives (1-(1-c))(1-(1-k)) = c*k, which is not 1 - (1-c)(1-k). Inverting the
/// finished RGB of a CMYK JPEG would trade one wrong image for another.
pub(crate) fn decode_jpeg_rgba_decoded(data: &[u8], invert_components: bool) -> Option<(u32,u32,Vec<u8>)> {
    let inv = |v: u8| if invert_components { 255 - v } else { v };
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(data));
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let w = info.width as u32;
    let h = info.height as u32;
    if w==0 || h==0 || w>20000 || h>20000 { return None; }
    // A dimension-only cap still allows 20000x20000 = 1.6 GB. The codestream's
    // dimensions can differ from the dict's /Width and /Height, so the caller's
    // guard does not cover this.
    //
    // DECIMATE, do not refuse. `decoder.decode()` above has already committed the
    // sample buffer, so returning None here spent the memory and then rendered
    // nothing — a >16 Mpx photo or scan simply vanished. Sampling every `step`th
    // pixel bounds the RGBA (4 bytes/px, the larger of the two buffers) and still
    // puts the image on the page. It does not bound the decoder's own allocation;
    // doing that needs jpeg-decoder's DCT-domain `scale()` before `decode()`, which
    // is a larger change to a path every JPEG takes.
    let step = decimation_step(w, h);
    let (dw, dh) = ((w as usize).div_ceil(step), (h as usize).div_ceil(step));
    if step > 1 {
        image_warn!(
            "JPEG codestream {}x{} over the {} pixel budget: decimated by {} to {}x{}",
            w, h, MAX_IMAGE_PIXELS, step, dw, dh
        );
    }
    // Source index of destination pixel (x, y).
    let src = |x: usize, y: usize| (y * step) * (w as usize) + (x * step);
    let rgba = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => {
            // gray -> rgba
            let mut out = vec![0u8; dw * dh * 4];
            for y in 0..dh {
                for x in 0..dw {
                    let g = inv(pixels.get(src(x, y)).copied().unwrap_or(0));
                    let o = (y * dw + x) * 4;
                    out[o]=g; out[o+1]=g; out[o+2]=g; out[o+3]=255;
                }
            }
            out
        }
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() < (w as usize * h as usize * 3) { return None; }
            let mut out = vec![0u8; dw * dh * 4];
            for y in 0..dh {
                for x in 0..dw {
                    let s = src(x, y) * 3;
                    let o = (y * dw + x) * 4;
                    out[o]=inv(pixels[s]);
                    out[o+1]=inv(pixels[s+1]);
                    out[o+2]=inv(pixels[s+2]);
                    out[o+3]=255;
                }
            }
            out
        }
        jpeg_decoder::PixelFormat::CMYK32 => {
            // The two Adobe transforms reach us in DIFFERENT polarities, verified
            // against real Photoshop/YCCK files (issue #924, the PETA certificate:
            // DeviceCMYK DCTDecode, APP14 transform 2):
            //  - transform 0 (`color_convert_line_cmyk`) emits 255 minus each stored
            //    sample, and Adobe stores CMYK inverted, so those bytes are already
            //    true ink amounts.
            //  - transform 2 (`color_convert_line_ycck`) converts the YCC triple —
            //    which Adobe computed FROM the inverted CMY — back to (255-C, 255-M,
            //    255-Y), and emits 255 minus the stored K, which is likewise the
            //    inverted K. All four bytes are therefore inverted ink amounts.
            // Inverting only CMY for YCCK (the previous code) left K in the wrong
            // polarity: RGB came out as A*(255-A_K)/255 instead of A*A_K/255 per
            // channel (A = inverted ink), which brightens every shadow toward a
            // solarised magenta — exactly the reported colour glitch. Invert all
            // four for YCCK so the CMYK->RGB conversion below sees true ink.
            if pixels.len() < (w as usize * h as usize * 4) { return None; }
            let invert_all = jpeg_adobe_transform(data) == Some(2);
            let mut out = vec![0u8; dw * dh * 4];
            for y in 0..dh {
                for x in 0..dw {
                    let s = src(x, y) * 4;
                    let (mut c, mut m, mut yy) = (pixels[s], pixels[s+1], pixels[s+2]);
                    let mut k = pixels[s+3];
                    if invert_all { c = 255 - c; m = 255 - m; yy = 255 - yy; k = 255 - k; }
                    // /Decode applies to the finished samples, so it comes after the
                    // Adobe/YCCK correction above and covers K as well as CMY.
                    if invert_components { c = 255 - c; m = 255 - m; yy = 255 - yy; k = 255 - k; }
                    let cf = c as f64 /255.0;
                    let mf = m as f64 /255.0;
                    let yf = yy as f64 /255.0;
                    let kf = k as f64 /255.0;
                    let r = ((1.0 - cf)*(1.0 - kf)*255.0) as u8;
                    let g = ((1.0 - mf)*(1.0 - kf)*255.0) as u8;
                    let b = ((1.0 - yf)*(1.0 - kf)*255.0) as u8;
                    let o = (y * dw + x) * 4;
                    out[o]=r; out[o+1]=g; out[o+2]=b; out[o+3]=255;
                }
            }
            out
        }
        // 9..=16-bit precision (§7.4.8 permits 12-bit DCT). Previously fell into the
        // `_ =>` arm and the image vanished with no diagnostic.
        jpeg_decoder::PixelFormat::L16 => {
            if pixels.len() < (w as usize * h as usize * 2) { return None; }
            let mut out = vec![0u8; dw * dh * 4];
            for y in 0..dh {
                for x in 0..dw {
                    // Little-endian u16 pairs; the high byte is the 8-bit approximation.
                    let g = inv(pixels.get(src(x, y) * 2 + 1).copied().unwrap_or(0));
                    let o = (y * dw + x) * 4;
                    out[o]=g; out[o+1]=g; out[o+2]=g; out[o+3]=255;
                }
            }
            out
        }
        // All four PixelFormat variants are handled above; jpeg-decoder's enum has no
        // others, so an exhaustive match is preferable to a catch-all that drops images.
    };
    Some((dw as u32, dh as u32, rgba))
}

/// Read the component count from a JPEG's SOF marker (1 = gray, 3 = YCbCr/RGB,
/// 4 = CMYK/YCCK). Returns `None` if no SOF is found.
pub(crate) fn jpeg_num_components(data: &[u8]) -> Option<u8> {
    if data.len() < 2 || data[0] != 0xFF || data[1] != 0xD8 { return None; }
    let mut i = 2;
    while i + 1 < data.len() {
        if data[i] != 0xFF { i += 1; continue; }
        let marker = data[i + 1];
        if marker == 0xFF { i += 1; continue; }
        if marker == 0x01 || (0xD0..=0xD9).contains(&marker) { i += 2; continue; }
        if marker == 0xDA { break; }
        if i + 4 > data.len() { break; }
        let len = ((data[i + 2] as usize) << 8) | data[i + 3] as usize;
        if len < 2 { break; }
        let seg_end = i + 2 + len;
        if seg_end > data.len() { break; }
        // SOF0..SOF15, excluding DHT(C4), JPG(C8), DAC(CC).
        let is_sof = (0xC0..=0xCF).contains(&marker)
            && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if is_sof {
            // payload: precision(1) height(2) width(2) ncomp(1)
            return data.get(i + 4 + 5).copied();
        }
        i = seg_end;
    }
    None
}

/// Scan JPEG markers for an APP14 "Adobe" segment, returning its transform flag
/// (0 = unknown/CMYK, 1 = YCbCr, 2 = YCCK) if present. Its mere presence signals
/// the Adobe inverted-CMYK convention. Returns `None` for non-Adobe JPEGs.
pub(crate) fn jpeg_adobe_transform(data: &[u8]) -> Option<u8> {
    if data.len() < 2 || data[0] != 0xFF || data[1] != 0xD8 { return None; }
    let mut i = 2;
    while i + 1 < data.len() {
        if data[i] != 0xFF { i += 1; continue; }
        let marker = data[i + 1];
        // Padding fill bytes.
        if marker == 0xFF { i += 1; continue; }
        // Standalone markers (no length): TEM, RSTn, SOI, EOI.
        if marker == 0x01 || (0xD0..=0xD9).contains(&marker) { i += 2; continue; }
        // Start of scan: entropy-coded data follows; stop looking.
        if marker == 0xDA { break; }
        if i + 4 > data.len() { break; }
        let len = ((data[i + 2] as usize) << 8) | data[i + 3] as usize;
        if len < 2 { break; }
        let seg_start = i + 4;
        let seg_end = i + 2 + len;
        if seg_end > data.len() { break; }
        if marker == 0xEE {
            let seg = &data[seg_start..seg_end];
            if seg.len() >= 12 && &seg[0..5] == b"Adobe" {
                return Some(seg[11]);
            }
        }
        i = seg_end;
    }
    None
}

pub(crate) fn decode_jpeg_gray(data: &[u8]) -> Option<(u32,u32,Vec<u8>)> {
    let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(data));
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let w = info.width as u32;
    let h = info.height as u32;
    if w==0 || h==0 || w>20000 || h>20000 { return None; }
    // A dimension-only cap still permits 20000x20000. The codestream's dimensions can
    // differ from the dict's /Width and /Height, so the caller's guard misses this.
    // Decimated rather than refused, for the same reason as `decode_jpeg_rgba_decoded`:
    // the decode is already paid for above, and both callers resample the result to the
    // base image's raster anyway, so the smaller buffer is transparent to them.
    let step = decimation_step(w, h);
    let (dw, dh) = ((w as usize).div_ceil(step), (h as usize).div_ceil(step));
    if step > 1 {
        image_warn!(
            "JPEG (gray) codestream {}x{} over the {} pixel budget: decimated by {} to {}x{}",
            w, h, MAX_IMAGE_PIXELS, step, dw, dh
        );
    }
    let src = |x: usize, y: usize| (y * step) * (w as usize) + (x * step);
    let wh = w as usize * h as usize;
    let gray = match info.pixel_format {
        jpeg_decoder::PixelFormat::L8 => {
            if pixels.len() < wh { return None; }
            if step == 1 {
                pixels[..wh].to_vec()
            } else {
                let mut out = vec![0u8; dw * dh];
                for y in 0..dh {
                    for x in 0..dw {
                        out[y * dw + x] = pixels[src(x, y)];
                    }
                }
                out
            }
        }
        jpeg_decoder::PixelFormat::RGB24 => {
            if pixels.len() < wh * 3 { return None; }
            let mut out = vec![0u8; dw * dh];
            for y in 0..dh {
                for x in 0..dw {
                    let s = src(x, y) * 3;
                    let r = pixels[s] as u16;
                    let g = pixels[s+1] as u16;
                    let b = pixels[s+2] as u16;
                    out[y * dw + x] = ((r*30 + g*59 + b*11)/100) as u8;
                }
            }
            out
        }
        jpeg_decoder::PixelFormat::CMYK32 => {
            // A CMYK JPEG used as an /SMask previously fell into `_ =>`, and the caller's
            // RGBA fallback then took the R channel as luminance. Convert properly here.
            if pixels.len() < wh * 4 { return None; }
            // Same YCCK polarity as `decode_jpeg_rgba_decoded`: all four samples
            // are inverted ink for transform 2, so K is inverted too (issue #924).
            let invert_all = jpeg_adobe_transform(data) == Some(2);
            let mut out = vec![0u8; dw * dh];
            for y in 0..dh {
                for x in 0..dw {
                    let s = src(x, y) * 4;
                    let (mut c, mut m, mut yy) = (pixels[s], pixels[s+1], pixels[s+2]);
                    let mut k = pixels[s+3];
                    if invert_all { c = 255 - c; m = 255 - m; yy = 255 - yy; k = 255 - k; }
                    let k = k as u16;
                    let kf = 255u16.saturating_sub(k);
                    let r = (255u16 - c as u16) * kf / 255;
                    let g = (255u16 - m as u16) * kf / 255;
                    let b = (255u16 - yy as u16) * kf / 255;
                    out[y * dw + x] = ((r*30 + g*59 + b*11)/100) as u8;
                }
            }
            out
        }
        jpeg_decoder::PixelFormat::L16 => {
            if pixels.len() < wh * 2 { return None; }
            let mut out = vec![0u8; dw * dh];
            for y in 0..dh {
                for x in 0..dw {
                    out[y * dw + x] = pixels.get(src(x, y) * 2 + 1).copied().unwrap_or(0);
                }
            }
            out
        }
    };
    Some((dw as u32, dh as u32, gray))
}

// Generic bit unpacker for BPC 2,4,12,16
/// Ceiling on the unpacked sample buffer (one byte per component). 16 MP of
/// 4-component data; guards against a bogus `/N` or `/DeviceN` arity turning
/// `w*h*ncomp` into a multi-gigabyte allocation.
pub(crate) const MAX_UNPACKED_SAMPLE_BYTES: usize = 64 * 1024 * 1024;

pub(crate) fn unpack_samples_to_bytes(samples: &[u8], w: usize, h: usize, ncomp: usize, bpc: u32) -> Option<Vec<u8>> {
    unpack_samples_decimated(samples, w, h, ncomp, bpc, 1).map(|(_, _, v)| v)
}

/// Unpack `bpc`-bit samples into one byte per component (0..=255), keeping every
/// `step`-th pixel and every `step`-th row. Returns `(out_w, out_h, bytes)` where
/// `bytes.len() == out_w * out_h * ncomp`.
///
/// A stream shorter than `/Width × /Height × ncomp` samples is NOT an error: §8.9.5.1
/// gives `/Width` and `/Height` authority over the sample count, so absent bytes read as
/// zero and the image still renders. Returning `None` here used to discard the whole
/// image for a single missing byte, which is common in the wild (a wrong `/Length`, a
/// truncated final scanline, or a Flate tail that failed to inflate).
///
/// The 0..=255 scaling per `bpc` is load-bearing and must not change: the Indexed
/// branch of [`image_samples_to_rgba`] inverts it to recover the palette index, and
/// [`apply_color_key_mask_samples`] scales `/Mask` ranges into the same domain.
pub(crate) fn unpack_samples_decimated(
    samples: &[u8],
    w: usize,
    h: usize,
    ncomp: usize,
    bpc: u32,
    step: usize,
) -> Option<(usize, usize, Vec<u8>)> {
    if w == 0 || h == 0 || ncomp == 0 {
        return None;
    }
    // An unsupported /BitsPerComponent is read as 8 rather than dropping the image.
    // §8.9.5.1 permits 1,2,4,8,16; 12 occurs in the wild and is handled too.
    let bpc = match bpc {
        1 | 2 | 4 | 8 | 12 | 16 => bpc,
        _ => 8,
    };
    let step = step.max(1);
    let ow = w.div_ceil(step);
    let oh = h.div_ceil(step);
    let out_len = ow.checked_mul(oh)?.checked_mul(ncomp)?;
    if out_len > MAX_UNPACKED_SAMPLE_BYTES {
        return None;
    }
    // Each scanline is padded to a byte boundary (§8.9.5.1).
    let row_bytes = w
        .checked_mul(ncomp)?
        .checked_mul(bpc as usize)?
        .div_ceil(8);
    let mut out = vec![0u8; out_len];
    for oy in 0..oh {
        let row_start = (oy * step) * row_bytes;
        for ox in 0..ow {
            let sx = ox * step;
            let obase = (oy * ow + ox) * ncomp;
            for c in 0..ncomp {
                let si = sx * ncomp + c;
                let raw: u32 = match bpc {
                    8 => samples.get(row_start + si).copied().unwrap_or(0) as u32,
                    // 16-bit: the high byte is the 8-bit approximation.
                    16 => samples.get(row_start + si * 2).copied().unwrap_or(0) as u32,
                    _ => {
                        let bit_off = si * bpc as usize;
                        let mut acc = 0u32;
                        for k in 0..bpc as usize {
                            let bp = bit_off + k;
                            let byte = samples.get(row_start + bp / 8).copied().unwrap_or(0);
                            acc = (acc << 1) | ((byte >> (7 - (bp % 8))) & 1) as u32;
                        }
                        acc
                    }
                };
                out[obase + c] = match bpc {
                    1 => {
                        if raw != 0 {
                            255
                        } else {
                            0
                        }
                    }
                    2 => (raw * 85) as u8,  // 255/3
                    4 => (raw * 17) as u8,  // 255/15
                    12 => (raw >> 4) as u8, // 4095 -> 255
                    _ => raw as u8,         // 8 and 16 (already the high byte)
                };
            }
        }
    }
    Some((ow, oh, out))
}

/// Bilinear sample of a `sw*sh` 1-channel (0..255) buffer at fractional (sx,sy).
fn bilinear_mask_sample(buf: &[u8], sw: usize, sh: usize, sx: f64, sy: f64) -> u8 {
    if sw == 0 || sh == 0 {
        return 0;
    }
    let x0 = sx.floor().clamp(0.0, (sw - 1) as f64) as usize;
    let y0 = sy.floor().clamp(0.0, (sh - 1) as f64) as usize;
    let x1 = (x0 + 1).min(sw - 1);
    let y1 = (y0 + 1).min(sh - 1);
    let fx = (sx - x0 as f64).clamp(0.0, 1.0);
    let fy = (sy - y0 as f64).clamp(0.0, 1.0);
    let s00 = buf.get(y0 * sw + x0).copied().unwrap_or(0) as f64;
    let s10 = buf.get(y0 * sw + x1).copied().unwrap_or(0) as f64;
    let s01 = buf.get(y1 * sw + x0).copied().unwrap_or(0) as f64;
    let s11 = buf.get(y1 * sw + x1).copied().unwrap_or(0) as f64;
    let top = s00 * (1.0 - fx) + s10 * fx;
    let bot = s01 * (1.0 - fx) + s11 * fx;
    (top * (1.0 - fy) + bot * fy).round().clamp(0.0, 255.0) as u8
}

/// Which convention a mask stream's samples follow. The two are OPPOSITE, and
/// sharing one mapping between them was inverting every 1-bit soft mask.
#[derive(Copy, Clone, PartialEq)]
pub(crate) enum MaskPolarity {
    /// `/SMask` (§11.6.5.3): the sample value IS the alpha, so with the default
    /// `/Decode [0 1]` a sample of 1 is fully OPAQUE.
    GrayLuminance,
    /// Explicit stencil `/Mask` (§8.9.6.4 with §8.9.6.2): a sample of 1 marks the
    /// area as MASKED OUT.
    StencilMaskedIf1,
}
