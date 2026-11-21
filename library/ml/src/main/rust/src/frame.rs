//! Plane-direct camera frame to upright `ARGB_8888`, on the CPU.
//!
//! The Bitmap path this replaces was, per live camera-OCR pass:
//! `ImageProxy.toBitmap()` (RGBA plane -> Bitmap copy), `cropAndRotate`
//! (a second full Bitmap copy), a conditional `ARGB_8888` copy plus
//! `getPixels` into a Kotlin `IntArray`, a JNI `IntArray` copy into a Rust
//! `Vec<i32>`, and only then the detector letterbox. This module is the
//! first step of the replacement: it reads the RGBA plane's bytes in place
//! (stride included, padding never sampled), crops to the preview's
//! viewport rect and rotates to upright, writing the one `Vec<i32>` the
//! existing OCR pipeline (`post::ocr::Source`, `preprocess`) already
//! consumes. Crop and rotation are exact pixel permutations for the
//! quarter turns CameraX reports, which is also what
//! `Bitmap.createBitmap(..., Matrix.postRotate(90*k), filter=true)`
//! produces at pixel centres — so both paths feed byte-identical pixels,
//! and therefore identical letterboxed detector tensors, for the same
//! frame. Non-quarter-turn rotations are refused so the caller falls back
//! to the Bitmap path rather than approximating Skia's resampling.
//!
//! The crop clamp mirrors `translate`'s Kotlin `cropAndRotate` exactly:
//! left/top clamped into the frame, the crop's own width/height clamped
//! to what remains, and an empty result falling back to the whole frame.

/// A crop rectangle in source pixels, `right`/`bottom` exclusive — the
/// same convention as `android.graphics.Rect`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Crop {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

/// Bytes per pixel of `RGBA_8888`, the only format this path converts.
/// CameraX also reports it as the plane's pixel stride; anything else is
/// refused so the caller falls back instead of misreading the plane.
pub const RGBA_PIXEL_STRIDE: u32 = 4;

/// The upright `(width, height)` of a `crop_w` x `crop_h` crop rotated by
/// `rotation_degrees`. Quarter turns only; anything else is an error.
pub fn upright_size(crop_w: u32, crop_h: u32, rotation_degrees: i32) -> Result<(u32, u32), String> {
    match rotation_degrees.rem_euclid(360) {
        0 | 180 => Ok((crop_w, crop_h)),
        90 | 270 => Ok((crop_h, crop_w)),
        other => Err(format!("rotation {other} is not a quarter turn")),
    }
}

/// Clamp `crop` exactly as the Kotlin Bitmap path does, returning
/// `(left, top, width, height)` in source pixels.
fn clamped_crop(width: u32, height: u32, crop: Crop) -> (u32, u32, u32, u32) {
    let (src_w, src_h) = (width as i64, height as i64);
    let left = (crop.left as i64).clamp(0, src_w);
    let top = (crop.top as i64).clamp(0, src_h);
    let mut w = (crop.right as i64 - crop.left as i64).clamp(0, src_w - left);
    let mut h = (crop.bottom as i64 - crop.top as i64).clamp(0, src_h - top);
    let (mut left, mut top) = (left, top);
    if w <= 0 || h <= 0 {
        left = 0;
        top = 0;
        w = src_w;
        h = src_h;
    }
    (left as u32, top as u32, w as u32, h as u32)
}

/// Convert an RGBA plane into the upright cropped `ARGB_8888` image in
/// `out` (reused, so steady-state passes allocate nothing new here), and
/// return the upright `(width, height)`.
///
/// `bytes` is the plane exactly as the direct `ByteBuffer` exposes it:
/// `height` rows of `row_stride` bytes, each pixel `pixel_stride` bytes
/// in `R, G, B, A` order. Row padding and any bytes past a pixel's alpha
/// are never read as pixels. Errors name the reason; every error is a
/// "fall back to the Bitmap path" signal, never a wrong image.
#[allow(clippy::too_many_arguments)]
pub fn rgba_to_argb_upright(
    bytes: &[u8],
    width: u32,
    height: u32,
    row_stride: u32,
    pixel_stride: u32,
    crop: Crop,
    rotation_degrees: i32,
    out: &mut Vec<i32>,
) -> Result<(u32, u32), String> {
    if width == 0 || height == 0 {
        return Err(format!("a {width}x{height} frame"));
    }
    if pixel_stride != RGBA_PIXEL_STRIDE {
        return Err(format!("pixel stride {pixel_stride}, not RGBA_8888's {RGBA_PIXEL_STRIDE}"));
    }
    let min_row = (width as u64)
        .checked_mul(pixel_stride as u64)
        .ok_or("frame dimensions overflow")?;
    if (row_stride as u64) < min_row {
        return Err(format!("row stride {row_stride} for {width} RGBA pixels"));
    }
    // The last byte actually read: final row, final pixel, alpha channel.
    let last = (height as u64 - 1)
        .checked_mul(row_stride as u64)
        .and_then(|v| v.checked_add((width as u64 - 1) * pixel_stride as u64))
        .and_then(|v| v.checked_add(3))
        .ok_or("frame dimensions overflow")?;
    if last >= bytes.len() as u64 {
        return Err(format!("{} plane bytes for a {width}x{height} frame", bytes.len()));
    }
    let (left, top, crop_w, crop_h) = clamped_crop(width, height, crop);
    let (out_w, out_h) = upright_size(crop_w, crop_h, rotation_degrees)?;
    let count = (out_w as usize)
        .checked_mul(out_h as usize)
        .ok_or("upright dimensions overflow")?;
    out.clear();
    out.resize(count, 0);
    let rotation = rotation_degrees.rem_euclid(360);
    for out_y in 0..out_h {
        for out_x in 0..out_w {
            // Inverse of the clockwise rotation, in cropped coordinates:
            // 90 CW sends source (x, y) to (crop_h-1-y, x), etc. Exact
            // permutations, so no interpolation is involved at all.
            let (crop_x, crop_y) = match rotation {
                0 => (out_x, out_y),
                90 => (out_y, crop_h - 1 - out_x),
                180 => (crop_w - 1 - out_x, crop_h - 1 - out_y),
                _ => (crop_w - 1 - out_y, out_x), // 270, checked above
            };
            let src_x = left + crop_x;
            let src_y = top + crop_y;
            let offset = (src_y as usize) * (row_stride as usize)
                + (src_x as usize) * (pixel_stride as usize);
            let (r, g, b, a) = (
                bytes[offset] as u32,
                bytes[offset + 1] as u32,
                bytes[offset + 2] as u32,
                bytes[offset + 3] as u32,
            );
            out[(out_y * out_w + out_x) as usize] =
                ((a << 24) | (r << 16) | (g << 8) | b) as i32;
        }
    }
    Ok((out_w, out_h))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::preprocess::{to_letterboxed_f16, Letterbox, PPOCR_DET};

    /// A synthetic RGBA plane: pixel (x, y) is a distinct colour derived
    /// from its coordinates, padding poisoned with 0xEE.
    fn plane(w: u32, h: u32, row_pad: u32) -> Vec<u8> {
        let row_stride = w * RGBA_PIXEL_STRIDE + row_pad;
        let mut bytes = vec![0xEEu8; (row_stride * h) as usize];
        for y in 0..h {
            for x in 0..w {
                let o = (y * row_stride + x * RGBA_PIXEL_STRIDE) as usize;
                bytes[o] = (x * 37 + y * 3) as u8;
                bytes[o + 1] = (x * 5 + y * 41) as u8;
                bytes[o + 2] = (x * 11 + y * 17) as u8;
                bytes[o + 3] = 0xFF;
            }
        }
        bytes
    }

    /// The Bitmap-path reference: the same pixels as tight `ARGB_8888`
    /// `i32`s (what `Bitmap.getPixels` hands the old JNI), cropped and
    /// rotated by forward-mapping every source pixel — independent math
    /// from the inverse mapping under test.
    fn reference(bytes: &[u8], w: u32, h: u32, row_stride: u32, crop: Crop, rot: i32) -> (Vec<i32>, u32, u32) {
        let (left, top, cw, ch) = clamped_crop(w, h, crop);
        let (ow, oh) = upright_size(cw, ch, rot).expect("quarter turn");
        let mut out = vec![0i32; (ow * oh) as usize];
        for cy in 0..ch {
            for cx in 0..cw {
                let o = ((top + cy) * row_stride + (left + cx) * RGBA_PIXEL_STRIDE) as usize;
                let argb = ((bytes[o + 3] as u32) << 24
                    | (bytes[o] as u32) << 16
                    | (bytes[o + 1] as u32) << 8
                    | bytes[o + 2] as u32) as i32;
                let (ox, oy) = match rot.rem_euclid(360) {
                    0 => (cx, cy),
                    90 => (ch - 1 - cy, cx),
                    180 => (cw - 1 - cx, ch - 1 - cy),
                    _ => (cy, cw - 1 - cx),
                };
                out[(oy * ow + ox) as usize] = argb;
            }
        }
        (out, ow, oh)
    }

    fn argb_at(bytes: &[u8], w: u32, row_stride: u32, x: u32, y: u32) -> i32 {
        let _ = w;
        let o = (y * row_stride + x * RGBA_PIXEL_STRIDE) as usize;
        ((bytes[o + 3] as u32) << 24
            | (bytes[o] as u32) << 16
            | (bytes[o + 1] as u32) << 8
            | bytes[o + 2] as u32) as i32
    }

    #[test]
    fn padded_stride_padding_is_never_sampled() {
        let (w, h, pad) = (37u32, 23u32, 12u32);
        let bytes = plane(w, h, pad);
        let mut out = Vec::new();
        let size = rgba_to_argb_upright(
            &bytes, w, h, w * 4 + pad, 4, Crop { left: 0, top: 0, right: w as i32, bottom: h as i32 }, 0, &mut out,
        )
        .expect("converts");
        assert_eq!(size, (w, h));
        for y in 0..h {
            for x in 0..w {
                assert_eq!(out[(y * w + x) as usize], argb_at(&bytes, w, w * 4 + pad, x, y), "({x},{y})");
            }
        }
    }

    #[test]
    fn stride_crop_and_every_quarter_turn_match_the_bitmap_reference() {
        let (w, h, pad) = (40u32, 30u32, 8u32);
        let bytes = plane(w, h, pad);
        let row_stride = w * 4 + pad;
        for crop in [
            Crop { left: 0, top: 0, right: 40, bottom: 30 },
            Crop { left: 5, top: 4, right: 33, bottom: 21 },
            Crop { left: -10, top: -3, right: 500, bottom: 500 }, // clamped, like Kotlin
            Crop { left: 0, top: 0, right: 0, bottom: 0 },       // empty -> whole frame
        ] {
            for rot in [0, 90, 180, 270, 360, -90] {
                let (want, ww, wh) = reference(&bytes, w, h, row_stride, crop, rot);
                let mut got = Vec::new();
                let size = rgba_to_argb_upright(&bytes, w, h, row_stride, 4, crop, rot, &mut got)
                    .expect("converts");
                assert_eq!(size, (ww, wh), "crop {crop:?} rot {rot}");
                assert_eq!(got, want, "crop {crop:?} rot {rot}");
            }
        }
    }

    #[test]
    fn both_paths_produce_identical_detector_tensors() {
        // Equality at the OCR input: the buffer path's upright pixels and
        // the Bitmap path's reference pixels letterbox to the exact same
        // fp16 detector tensor (values and summary stats).
        let (w, h, pad) = (64u32, 48u32, 16u32);
        let bytes = plane(w, h, pad);
        let row_stride = w * 4 + pad;
        let crop = Crop { left: 4, top: 2, right: 60, bottom: 46 };
        let (reference_pixels, rw, rh) = reference(&bytes, w, h, row_stride, crop, 90);
        let mut buffer_pixels = Vec::new();
        let (bw, bh) = rgba_to_argb_upright(&bytes, w, h, row_stride, 4, crop, 90, &mut buffer_pixels)
            .expect("converts");
        assert_eq!((bw, bh), (rw, rh));
        let fit = Letterbox::square(bw, bh, 96).expect("fits");
        let shape = fit.shape();
        let (mut a, mut b) = (vec![0u16; shape.len() as usize], vec![0u16; shape.len() as usize]);
        to_letterboxed_f16(&buffer_pixels, bw, bh, &fit, &PPOCR_DET, &mut a).expect("buffer tensor");
        to_letterboxed_f16(&reference_pixels, rw, rh, &fit, &PPOCR_DET, &mut b).expect("bitmap tensor");
        assert_eq!(a, b, "detector tensors differ");
        let stats = |t: &[u16]| (t.len(), t.iter().map(|&v| v as u64).sum::<u64>(), t.iter().min().copied(), t.iter().max().copied());
        assert_eq!(stats(&a), stats(&b));
    }

    #[test]
    fn both_paths_recognise_the_same_text_through_the_pipeline() {
        // Pipeline-level equality: the buffer path's pixels and the Bitmap
        // reference pixels, run through detection-crop-recognition with a
        // stub recogniser, yield identical lines (text and geometry).
        use crate::post::ctc::{self, Dictionary};
        use crate::post::ocr;
        let mut dict_text = String::new();
        for index in 0..ctc::DICTIONARY_ENTRIES {
            dict_text.push((b'a' + (index % 26) as u8) as char);
            dict_text.push('\n');
        }
        let dictionary = Dictionary::parse(&dict_text).expect("fixture dictionary");
        let map = 64u32;
        let mut probability = vec![0.0f32; (map * map) as usize];
        for y in 24..36 {
            for x in 8..56 {
                probability[(y * map + x) as usize] = 1.0;
            }
        }
        let fit = Letterbox::square(map, map, map).expect("1:1 letterbox");
        let steps = (ocr::REC_WIDTH / crate::nets::ppocr_rec::WIDTH_MULTIPLE) as usize;
        let recognise = |_: &[i32], _: u32, _: u32| {
            let mut logits = vec![0.0f32; ctc::LOGITS * steps];
            for (step, label) in [1usize, 2, 3].iter().enumerate() {
                logits[label * steps + step] = 20.0;
            }
            Ok(logits)
        };
        let (w, h, pad) = (64u32, 64u32, 8u32);
        let bytes = plane(w, h, pad);
        let (reference_pixels, rw, rh) =
            reference(&bytes, w, h, w * 4 + pad, Crop { left: 0, top: 0, right: 64, bottom: 64 }, 0);
        let mut buffer_pixels = Vec::new();
        let (bw, bh) = rgba_to_argb_upright(
            &bytes, w, h, w * 4 + pad, 4, Crop { left: 0, top: 0, right: 64, bottom: 64 }, 0, &mut buffer_pixels,
        )
        .expect("converts");
        let run = |pixels: &[i32], pw: u32, ph: u32| {
            ocr::lines(
                &ocr::Detection { probability: &probability, width: map, height: map, fit: &fit },
                &ocr::Source { pixels, width: pw, height: ph },
                &dictionary,
                recognise,
            )
            .expect("pipeline runs")
        };
        let (from_buffer, from_bitmap) = (run(&buffer_pixels, bw, bh), run(&reference_pixels, rw, rh));
        assert_eq!(from_buffer, from_bitmap);
        assert_eq!(from_buffer.len(), 1);
        assert_eq!(from_buffer[0].text, "abc");
    }

    #[test]
    fn unreadable_planes_are_refused_for_fallback() {
        let mut out = Vec::new();
        let crop = Crop { left: 0, top: 0, right: 4, bottom: 4 };
        assert!(rgba_to_argb_upright(&[0u8; 64], 0, 4, 16, 4, crop, 0, &mut out).is_err(), "zero width");
        assert!(rgba_to_argb_upright(&[0u8; 64], 4, 4, 16, 1, crop, 0, &mut out).is_err(), "pixel stride 1 (a Y plane is not RGBA)");
        assert!(rgba_to_argb_upright(&[0u8; 64], 4, 4, 8, 4, crop, 0, &mut out).is_err(), "row stride too small");
        assert!(rgba_to_argb_upright(&[0u8; 10], 4, 4, 16, 4, crop, 0, &mut out).is_err(), "undersized buffer");
        assert!(rgba_to_argb_upright(&[0u8; 256], 4, 4, 16, 4, crop, 45, &mut out).is_err(), "non-quarter-turn");
    }

    #[test]
    fn upright_size_swaps_only_for_quarter_turns_sideways() {
        assert_eq!(upright_size(40, 30, 0).expect("0"), (40, 30));
        assert_eq!(upright_size(40, 30, 90).expect("90"), (30, 40));
        assert_eq!(upright_size(40, 30, 180).expect("180"), (40, 30));
        assert_eq!(upright_size(40, 30, 270).expect("270"), (30, 40));
        assert_eq!(upright_size(40, 30, -90).expect("-90"), (30, 40));
        assert!(upright_size(40, 30, 45).is_err());
    }
}
