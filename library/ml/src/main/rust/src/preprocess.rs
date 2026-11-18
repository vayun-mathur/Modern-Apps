//! Bitmap to fp16 NCHW, on the CPU.
//!
//! # Why this is not a shader
//!
//! Both networks take at most 320x320, and both callers already downscale to a 512-pixel
//! long side before they get here, so this is a few hundred thousand bilinear taps —
//! well under a millisecond, and less than the cost of the extra upload, dispatch and
//! barrier a preprocessing shader would need. It also keeps the input path testable on
//! the host, which is where the sign of a normalisation or a transposed channel gets
//! caught.
//!
//! This replaces ncnn's `from_android_bitmap_resize` + `substract_mean_normalize`, which
//! lived inside the out-of-repo AAR and so was never reviewable.
//!
//! # The half-pixel convention
//!
//! Bilinear sampling here uses `src = (dst + 0.5) * scale - 0.5`, matching ONNX
//! `half_pixel` and PyTorch's `align_corners=False`. The resize shader uses the same
//! formula, so the input resize and the 38 in-graph resizes agree — which matters
//! because U^2-Netp's decoder repeatedly upsamples and adds.

use crate::nets::Shape;

/// How a pixel becomes a network's input: the channel order, and the per-channel affine
/// applied after scaling to `0..1`.
///
/// The affine is always `(value / 255 - mean) / std`. `mean` and `std` are indexed by
/// **destination** channel, not by colour — so for a BGR net `mean[0]` applies to blue.
/// That is PaddleOCR's own semantics: its `DecodeImage` produces BGR and its
/// `NormalizeImage` then subtracts `[0.485, 0.456, 0.406]` positionally, which lands the
/// ImageNet *red* mean on the blue channel. Reproducing the quirk matters more than
/// tidying it.
#[derive(Clone, Copy, Debug)]
pub struct Normalise {
    /// Subtracted per destination channel.
    pub mean: [f32; 3],
    /// Divided per destination channel.
    pub std: [f32; 3],
    /// Write blue first rather than red — what both PP-OCRv5 exports want.
    ///
    /// A channel order rather than a mean is the honest place for this: swapping the mean
    /// and std entries would normalise correctly and still feed the net red where it
    /// expects blue, which is a shift no output range would reveal.
    pub bgr: bool,
}

impl Normalise {
    /// The index into [`pixel`]'s `[R, G, B]` that destination channel `channel` reads.
    fn source(&self, channel: usize) -> usize {
        if self.bgr {
            2 - channel.min(2)
        } else {
            channel.min(2)
        }
    }
}

/// Scale to `0..1` and nothing else.
///
/// What MediaPipe Selfie Segmentation wants: its processor sets `do_normalize: false`
/// with `rescale_factor: 1/255`.
pub const RESCALE_ONLY: Normalise = Normalise { mean: [0.0; 3], std: [1.0; 3], bgr: false };

/// ImageNet statistics, which is what U^2-Netp was trained with and what the ncnn path
/// this replaces already used.
pub const IMAGENET: Normalise =
    Normalise { mean: [0.485, 0.456, 0.406], std: [0.229, 0.224, 0.225], bgr: false };

/// What PP-OCRv5 **detection** wants: ImageNet statistics over BGR channels.
///
/// From the export's own `inference.yml`: `DecodeImage: img_mode: BGR`, then
/// `NormalizeImage` with the ImageNet constants and `order: hwc`, then `ToCHWImage`. So
/// channel 0 of the input is blue and carries the 0.485 mean. See [`Normalise`].
pub const PPOCR_DET: Normalise =
    Normalise { mean: IMAGENET.mean, std: IMAGENET.std, bgr: true };

/// What PP-OCRv5 **recognition** wants: `(value / 255 - 0.5) / 0.5` over BGR channels.
///
/// Its `inference.yml` has no `NormalizeImage` at all — `RecResizeImg` normalises inside
/// itself, as `img.transpose(2,0,1) / 255; img -= 0.5; img /= 0.5` on the BGR array
/// `DecodeImage` produced. Numerically the same affine as [`FACE_EMBED`], and a separate
/// constant because it is not the same channel order.
pub const PPOCR_REC: Normalise =
    Normalise { mean: [0.5; 3], std: [0.5; 3], bgr: true };

// The channel order is per net, not global, and swapping one silently degrades that model
// alone. Checked at compile time rather than in a test, because it is knowable there.
const _: () = assert!(PPOCR_DET.bgr && PPOCR_REC.bgr);
const _: () = assert!(!IMAGENET.bgr && !RESCALE_ONLY.bgr);
const _: () = assert!(!SCRFD.bgr && !FACE_EMBED.bgr);
const _: () = assert!(!MODNET.bgr);

/// `(value - 127.5) / 128` on the `0..255` scale, which is what SCRFD wants.
///
/// Expressed against the `0..1` rescale every [`Normalise`] applies: `127.5 / 255` is
/// exactly `0.5`, and dividing by `128 / 255` is dividing by 128 after the rescale.
///
/// **Not** [`FACE_EMBED`], whose divisor is 127.5. The two differ by 0.4% and share a
/// mean, so a swapped constant shifts every embedding slightly and every box slightly,
/// with no symptom either would fail on. `scrfd.cpp:310` and the MobileFaceNet
/// preprocessing are the two places that disagree.
pub const SCRFD: Normalise = Normalise {
    mean: [0.5, 0.5, 0.5],
    std: [128.0 / 255.0, 128.0 / 255.0, 128.0 / 255.0],
    bgr: false,
};

/// `(value - 127.5) / 127.5` on the `0..255` scale, which is what MobileFaceNet wants.
/// See [`SCRFD`] for why these are two constants and not one.
pub const FACE_EMBED: Normalise =
    Normalise { mean: [0.5, 0.5, 0.5], std: [0.5, 0.5, 0.5], bgr: false };

/// `(value / 255 - 0.5) / 0.5` on RGB channels, which is what MODNet wants.
///
/// Numerically the same affine as [`FACE_EMBED`], and a separate constant
/// because it is not the same net: the demo preprocess is
/// `ToTensor()` then `Normalize((0.5, 0.5, 0.5), (0.5, 0.5, 0.5))` over RGB,
/// and a shared constant would silently couple the two nets' preprocessing.
/// See [`SCRFD`] for why near-identical constants stay separate.
pub const MODNET: Normalise =
    Normalise { mean: [0.5, 0.5, 0.5], std: [0.5, 0.5, 0.5], bgr: false };

/// How an image was fitted into the detector's input, and what it takes to undo it.
///
/// SCRFD runs at 640 on the long side with the short side padded up to a multiple of 32,
/// the image centred in the padding. Every box and keypoint the net predicts is in this
/// padded space, so [`Letterbox`] is carried alongside them until
/// `post::nms::to_source` maps them back.
///
/// The arithmetic is `scrfd.cpp:286-308` reproduced exactly, integer truncation
/// included. Two details are load-bearing:
///
/// * The resized extent is `(original * scale) as u32` — a **truncation**, not a round.
///   At 640 on the long side that differs by a pixel often enough to matter, and the
///   padding is derived from it.
/// * The padding is split `pad / 2` above and `pad - pad / 2` below, with **integer**
///   division, so an odd pad puts the extra row at the bottom. Undoing it subtracts the
///   same integer `pad / 2`, which is why that value is stored rather than recomputed
///   from a float.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Letterbox {
    /// What the source was multiplied by to reach [`Letterbox::resized`].
    pub scale: f32,
    /// `(width, height)` after scaling, before padding.
    pub resized: (u32, u32),
    /// `(width, height)` after padding. Both are multiples of the requested multiple.
    pub padded: (u32, u32),
    /// `(left, top)` padding, which is what a coordinate has subtracted from it.
    pub offset: (u32, u32),
}

impl Letterbox {
    /// Fit a `width` x `height` image to `long_side`, padding up to `multiple`.
    pub fn new(
        width: u32,
        height: u32,
        long_side: u32,
        multiple: u32,
    ) -> Result<Letterbox, String> {
        if width == 0 || height == 0 {
            return Err(format!("a {width}x{height} image"));
        }
        if long_side == 0 || multiple == 0 {
            return Err(format!("a {long_side} long side at a multiple of {multiple}"));
        }
        // The long side goes to `long_side`; the short side follows by the same scale,
        // truncated. `.max(1)` only bites at aspect ratios beyond about 640:1.
        let (scale, resized) = if width > height {
            let scale = long_side as f32 / width as f32;
            (scale, (long_side, ((height as f32 * scale) as u32).max(1)))
        } else {
            let scale = long_side as f32 / height as f32;
            (scale, (((width as f32 * scale) as u32).max(1), long_side))
        };
        let pad = |extent: u32| extent.div_ceil(multiple) * multiple - extent;
        let (pad_w, pad_h) = (pad(resized.0), pad(resized.1));
        Ok(Letterbox {
            scale,
            resized,
            padded: (resized.0 + pad_w, resized.1 + pad_h),
            offset: (pad_w / 2, pad_h / 2),
        })
    }

    /// Fit a `width` x `height` image into a `side` x `side` square, padding both axes.
    ///
    /// The scale is identical to [`Letterbox::new`]'s — `side / long side` — so a
    /// detection lands in the same place; only the amount of padding differs, and padding
    /// is a constant border either way.
    ///
    /// # Why the square and not the tight fit
    ///
    /// `scrfd.cpp` pads only the short side, to the next multiple of 32, which makes the
    /// net's input shape a function of the photo's aspect ratio. Supporting that properly
    /// means compiling a plan and re-recording a command buffer per shape, which is real
    /// machinery `Net::rebuild` now provides for Supertonic, whose every plan is
    /// utterance-shaped.
    ///
    /// A square costs up to 2x the arithmetic on a panorama and nothing on a square
    /// photo. Detection runs once per photo during indexing rather than per frame, and
    /// SCRFD's arena at 640x640 is 9.3 MB, so that is a cheap way to avoid the machinery
    /// until something actually needs it. [`crate::nets::scrfd::build`] already accepts
    /// any multiple of 32, so nothing is given up by starting here.
    pub fn square(width: u32, height: u32, side: u32) -> Result<Letterbox, String> {
        if width == 0 || height == 0 {
            return Err(format!("a {width}x{height} image"));
        }
        if side == 0 {
            return Err("a zero-sided square".into());
        }
        // Same truncation as `new`: the long side lands exactly on `side`, the short one
        // follows by the same scale and is truncated.
        let (scale, resized) = if width > height {
            let scale = side as f32 / width as f32;
            (scale, (side, ((height as f32 * scale) as u32).max(1).min(side)))
        } else {
            let scale = side as f32 / height as f32;
            (scale, (((width as f32 * scale) as u32).max(1).min(side), side))
        };
        Ok(Letterbox {
            scale,
            resized,
            padded: (side, side),
            offset: ((side - resized.0) / 2, (side - resized.1) / 2),
        })
    }

    /// The net's input shape for this fit.
    pub fn shape(&self) -> Shape {
        Shape::new(3, self.padded.1, self.padded.0)
    }
}

/// Resize `pixels` into `fit`'s padded extent and write it as planar fp16.
///
/// The image is scaled to `fit.resized`, centred at `fit.offset`, and everything outside
/// it is filled with **raw zero put through `norm`** — not with zero. `scrfd.cpp` pads
/// before it normalises, so the border a detection can see is `(0 - 127.5) / 128`, which
/// is about `-0.996` rather than neutral grey. Filling with 0.0 instead changes what the
/// net sees along two edges of every non-square photo.
pub fn to_letterboxed_f16(
    pixels: &[i32],
    width: u32,
    height: u32,
    fit: &Letterbox,
    norm: &Normalise,
    dst: &mut [u16],
) -> Result<(), String> {
    if width == 0 || height == 0 {
        return Err("a zero-sized bitmap".into());
    }
    let expected = (width as usize)
        .checked_mul(height as usize)
        .ok_or("bitmap dimensions overflow")?;
    if pixels.len() != expected {
        return Err(format!("{} pixels for a {width}x{height} bitmap", pixels.len()));
    }
    let shape = fit.shape();
    if dst.len() != shape.len() as usize {
        return Err(format!("{} output elements for {shape:?}", dst.len()));
    }

    let plane = (shape.h * shape.w) as usize;
    let (out_w, out_h) = fit.resized;
    let (left, top) = fit.offset;
    let x_scale = width as f32 / out_w as f32;
    let y_scale = height as f32 / out_h as f32;

    // The border value, computed once: raw 0 through the normalisation.
    let border: [u16; 3] = [0, 1, 2].map(|c| {
        let mean = norm.mean.get(c).copied().unwrap_or(0.0);
        let std = norm.std.get(c).copied().unwrap_or(1.0);
        f32_to_f16((0.0 - mean) / std)
    });

    for out_y in 0..shape.h {
        let inside_row = out_y >= top && out_y < top + out_h;
        let (y0, y1, wy) = if inside_row {
            sample(out_y - top, y_scale, height)
        } else {
            (0, 0, 0.0)
        };
        for out_x in 0..shape.w {
            let at = (out_y * shape.w + out_x) as usize;
            if !inside_row || out_x < left || out_x >= left + out_w {
                for channel in 0..3usize {
                    let slot = dst
                        .get_mut(channel * plane + at)
                        .ok_or("letterboxing wrote past the end of its output")?;
                    *slot = border.get(channel).copied().unwrap_or(0);
                }
                continue;
            }
            let (x0, x1, wx) = sample(out_x - left, x_scale, width);

            let p00 = pixel(pixels, width, x0, y0);
            let p10 = pixel(pixels, width, x1, y0);
            let p01 = pixel(pixels, width, x0, y1);
            let p11 = pixel(pixels, width, x1, y1);

            for channel in 0..3usize {
                let source = norm.source(channel);
                let top_row = lerp(p00[source], p10[source], wx);
                let bottom_row = lerp(p01[source], p11[source], wx);
                let value = lerp_f32(top_row, bottom_row, wy) / 255.0;
                let mean = norm.mean.get(channel).copied().unwrap_or(0.0);
                let std = norm.std.get(channel).copied().unwrap_or(1.0);
                let slot = dst
                    .get_mut(channel * plane + at)
                    .ok_or("letterboxing wrote past the end of its output")?;
                *slot = f32_to_f16((value - mean) / std);
            }
        }
    }
    Ok(())
}

/// Resize `pixels` to `shape` and write it as planar fp16.
///
/// `pixels` is `ARGB_8888` as `Bitmap.getPixels` produces it — `0xAARRGGBB` per entry,
/// row-major, `width` wide. Alpha is ignored: neither network has a fourth input
/// channel, and both callers hand over opaque camera or gallery frames.
///
/// The resize is a straight scale to `shape`, deliberately **not** the aspect-preserving
/// letterbox the U^2-Netp processor config describes. The ncnn path this replaces
/// stretched, so letterboxing would change existing behaviour in `:photos` rather than
/// preserve it.
pub fn to_planar_f16(
    pixels: &[i32],
    width: u32,
    height: u32,
    shape: Shape,
    norm: &Normalise,
    dst: &mut [u16],
) -> Result<(), String> {
    if shape.c != 3 {
        return Err(format!("preprocessing writes 3 channels, not {}", shape.c));
    }
    if width == 0 || height == 0 {
        return Err("a zero-sized bitmap".into());
    }
    let expected = (width as usize)
        .checked_mul(height as usize)
        .ok_or("bitmap dimensions overflow")?;
    if pixels.len() != expected {
        return Err(format!("{} pixels for a {width}x{height} bitmap", pixels.len()));
    }
    if dst.len() != shape.len() as usize {
        return Err(format!("{} output elements for {shape:?}", dst.len()));
    }

    let plane = (shape.h * shape.w) as usize;
    let x_scale = width as f32 / shape.w as f32;
    let y_scale = height as f32 / shape.h as f32;

    for out_y in 0..shape.h {
        let (y0, y1, wy) = sample(out_y, y_scale, height);
        for out_x in 0..shape.w {
            let (x0, x1, wx) = sample(out_x, x_scale, width);

            let p00 = pixel(pixels, width, x0, y0);
            let p10 = pixel(pixels, width, x1, y0);
            let p01 = pixel(pixels, width, x0, y1);
            let p11 = pixel(pixels, width, x1, y1);

            let at = (out_y * shape.w + out_x) as usize;
            for channel in 0..3usize {
                // Interpolating the 0..255 values and normalising once is both cheaper
                // and closer to the reference than normalising four taps first.
                let source = norm.source(channel);
                let top = lerp(p00[source], p10[source], wx);
                let bottom = lerp(p01[source], p11[source], wx);
                let value = lerp_f32(top, bottom, wy) / 255.0;
                let normalised = (value - norm.mean[channel]) / norm.std[channel];
                let slot = dst
                    .get_mut(channel * plane + at)
                    .ok_or("preprocessing wrote past the end of its output")?;
                *slot = f32_to_f16(normalised);
            }
        }
    }
    Ok(())
}

/// The two source indices bracketing `out` and the weight of the second, under the
/// `half_pixel` convention. Coordinates outside the source clamp to the edge.
fn sample(out: u32, scale: f32, extent: u32) -> (u32, u32, f32) {
    let source = (out as f32 + 0.5) * scale - 0.5;
    let clamped = source.max(0.0);
    let low = clamped.floor();
    let weight = clamped - low;
    let low = (low as u32).min(extent - 1);
    let high = (low + 1).min(extent - 1);
    (low, high, weight)
}

fn pixel(pixels: &[i32], width: u32, x: u32, y: u32) -> [f32; 3] {
    let packed = pixels.get((y * width + x) as usize).copied().unwrap_or(0) as u32;
    [
        ((packed >> 16) & 0xff) as f32,
        ((packed >> 8) & 0xff) as f32,
        (packed & 0xff) as f32,
    ]
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

fn lerp_f32(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// fp32 to fp16, round-to-nearest-even, with subnormals.
///
/// Hand-written because Rust's `f16` is not stable on the toolchain this repo pins
/// (`rust-toolchain.toml`), and because a `half` dependency would be a whole crate for
/// two functions. Weights are converted by numpy in `scripts/ml/maml_convert.py`; this
/// is only for the input, whose values sit in roughly `-2.2..2.2` after normalisation.
pub fn f32_to_f16(value: f32) -> u16 {
    let bits = value.to_bits();
    let sign = ((bits >> 16) & 0x8000) as u16;
    let exponent = ((bits >> 23) & 0xff) as i32;
    let mantissa = bits & 0x007f_ffff;

    if exponent == 0xff {
        // Infinity, or a NaN kept as a NaN rather than collapsed to infinity.
        return sign | 0x7c00 | if mantissa != 0 { 0x0200 } else { 0 };
    }
    let unbiased = exponent - 127;
    if unbiased > 15 {
        return sign | 0x7c00;
    }
    if unbiased < -24 {
        // Below half of the smallest subnormal, so it rounds to zero.
        return sign;
    }
    if unbiased < -14 {
        // Subnormal: shift the implicit leading 1 into the mantissa.
        let shift = (-unbiased - 14) as u32;
        let full = mantissa | 0x0080_0000;
        let shifted = full >> (13 + shift);
        let round = round_bit(full, 13 + shift);
        return sign | (shifted + round) as u16;
    }
    let half_exponent = ((unbiased + 15) as u32) << 10;
    let half_mantissa = mantissa >> 13;
    let round = round_bit(mantissa, 13);
    // A mantissa that rounds up past 0x3ff carries into the exponent, which the plain
    // addition handles because the fields are adjacent.
    sign | (half_exponent + half_mantissa + round) as u16
}

include!("preprocess_part1.rs");
#[cfg(test)]
#[path = "preprocess_tests.rs"]
mod preprocess_tests;
