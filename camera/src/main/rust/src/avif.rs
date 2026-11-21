//! AVIF still encoding on top of `ravif` (pure-Rust rav1e AV1 encoder).
//!
//! Android has no platform AVIF encoder, so stills that the user sets to
//! AVIF are encoded here from raw RGBA pixels. The encoder is 8-bit only:
//! the capture pipeline hands us 8-bit RGBA, and asking ravif for a 10-bit
//! container would only pad those samples, so we do not offer it.
//!
//! EXIF is intentionally not written here. ravif/avif-serialize expose no
//! EXIF item API, so the Kotlin side bakes orientation (rotation + mirror)
//! into the pixels before calling in, instead of stamping an orientation
//! tag the way the JPEG path does. GPS/maker tags are likewise not carried
//! into AVIF stills; see the Kotlin `StillEncoder` report comment.

use ravif::{Encoder, Img, RGBA8};

/// Largest width/height accepted, matching the night-frame JNI guard.
const MAX_DIM: usize = 20_000;

/// Quality bounds mirroring the JPEG quality scale (0..=100).
const QUALITY_MIN: f32 = 1.0;
const QUALITY_MAX: f32 = 100.0;

/// Speed bounds accepted by rav1e (1 = slowest/best, 10 = fastest).
const SPEED_MIN: u8 = 1;
const SPEED_MAX: u8 = 10;

/// Encode one RGBA frame (`rgba.len() == width * height * 4`, straight
/// alpha) to AVIF bytes. `quality` is on the JPEG-like 1..=100 scale and
/// `speed` is rav1e's 1..=10 scale; both are clamped. Returns `None` for
/// empty/oversized/mismatched input or any encoder failure, so the Kotlin
/// side can fall back to JPEG.
///
/// # Panics
///
/// Never panics on untrusted sizes: dimensions are validated before any
/// allocation sized by them.
pub fn encode_avif(rgba: &[u8], width: usize, height: usize, quality: f32, speed: u8) -> Option<Vec<u8>> {
    if width == 0 || height == 0 || width > MAX_DIM || height > MAX_DIM {
        return None;
    }
    let expected = width.checked_mul(height)?.checked_mul(4)?;
    if rgba.len() != expected {
        return None;
    }
    let pixels: Vec<RGBA8> = rgba
        .chunks_exact(4)
        .map(|px| match px {
            [r, g, b, a] => RGBA8::new(*r, *g, *b, *a),
            _ => RGBA8::new(0, 0, 0, 0),
        })
        .collect();
    let img = Img::new(pixels.as_slice(), width, height);
    let encoded = Encoder::new()
        .with_quality(quality.clamp(QUALITY_MIN, QUALITY_MAX))
        .with_speed(speed.clamp(SPEED_MIN, SPEED_MAX))
        .with_bit_depth(ravif::BitDepth::Eight)
        .encode_rgba(img)
        .ok()?;
    if encoded.avif_file.is_empty() {
        None
    } else {
        Some(encoded.avif_file)
    }
}

/// True when `bytes` carry the AVIF file-type box (`....ftypavif`).
pub fn has_avif_magic(bytes: &[u8]) -> bool {
    bytes.len() >= 12 && &bytes[4..12] == b"ftypavif"
}

/// Read the `ispe` (image spatial extents) dimensions out of an AVIF
/// container by walking top-level/meta boxes. Used by tests to confirm a
/// real round-trip without pulling in a decoder crate.
pub fn avif_dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    fn boxes(bytes: &[u8]) -> Vec<(&[u8], &[u8])> {
        let mut out = Vec::new();
        let mut off = 0usize;
        while off + 8 <= bytes.len() {
            let size = u32::from_be_bytes([bytes[off], bytes[off + 1], bytes[off + 2], bytes[off + 3]]) as usize;
            if size < 8 || off + size > bytes.len() {
                break;
            }
            out.push((&bytes[off + 4..off + 8], &bytes[off + 8..off + size]));
            off += size;
        }
        out
    }
    let meta = boxes(bytes).into_iter().find(|(t, _)| *t == b"meta")?.1;
    // `meta` starts with 4 bytes of version/flags before its children.
    let meta = meta.get(4..)?;
    let iprp = boxes(meta).into_iter().find(|(t, _)| *t == b"iprp")?.1;
    let ipco = boxes(iprp).into_iter().find(|(t, _)| *t == b"ipco")?.1;
    let ispe = boxes(ipco).into_iter().find(|(t, _)| *t == b"ispe")?.1;
    // `ispe`: version/flags (4) then width, height as big-endian u32.
    let ispe = ispe.get(4..12)?;
    Some((
        u32::from_be_bytes([ispe[0], ispe[1], ispe[2], ispe[3]]),
        u32::from_be_bytes([ispe[4], ispe[5], ispe[6], ispe[7]]),
    ))
}

#[cfg(test)]
mod avif_tests {
    use super::{avif_dimensions, encode_avif, has_avif_magic};

    fn gradient(width: usize, height: usize) -> Vec<u8> {
        let mut px = Vec::with_capacity(width * height * 4);
        for y in 0..height {
            for x in 0..width {
                px.extend_from_slice(&[
                    (x % 256) as u8,
                    (y % 256) as u8,
                    ((x + y) % 256) as u8,
                    255,
                ]);
            }
        }
        px
    }

    #[test]
    fn encodes_synthetic_rgba_with_avif_header_and_dimensions() {
        let rgba = gradient(64, 48);
        let bytes = encode_avif(&rgba, 64, 48, 80.0, 10).expect("encode must succeed");
        assert!(has_avif_magic(&bytes), "missing ftypavif magic");
        assert_eq!(avif_dimensions(&bytes), Some((64, 48)));
    }

    #[test]
    fn rejects_mismatched_and_oversized_input() {
        assert!(encode_avif(&[0u8; 10], 64, 48, 80.0, 10).is_none());
        assert!(encode_avif(&[], 0, 0, 80.0, 10).is_none());
        assert!(encode_avif(&[0u8; 4], 20_001, 1, 80.0, 10).is_none());
    }

    /// Host-side encode-time probe for a 12 MP frame. Ignored by default
    /// (it takes seconds); run with `cargo test -- --ignored bench_12mp`
    /// to reproduce the latency figure in the change report.
    #[test]
    #[ignore]
    fn bench_12mp_encode_time() {
        let rgba = gradient(4000, 3000);
        let start = std::time::Instant::now();
        let bytes = encode_avif(&rgba, 4000, 3000, 80.0, 10).expect("encode must succeed");
        println!(
            "12MP AVIF encode: {:?} -> {} bytes",
            start.elapsed(),
            bytes.len()
        );
        assert!(has_avif_magic(&bytes));
    }
}
