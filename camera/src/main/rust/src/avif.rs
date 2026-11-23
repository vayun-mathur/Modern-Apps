//! AVIF still encoding on top of `ravif` (pure-Rust rav1e AV1 encoder).
//!
//! Android has no platform AVIF encoder, so stills that the user sets to
//! AVIF are encoded here from raw RGBA pixels. The encoder is 8-bit only:
//! the capture pipeline hands us 8-bit RGBA, and asking ravif for a 10-bit
//! container would only pad those samples, so we do not offer it.
//!
//! EXIF: `ravif`'s `with_exif` hands a TIFF payload to `avif-serialize`,
//! which muxes it at the ISOBMFF level as a dedicated `Exif` item (an
//! `infe` entry in `iinf`, extents in `iloc`, a `cdsc` reference to the
//! colour item, and the item data in `mdat` framed as
//! `[u32 TIFF-offset = 0][TIFF header …]`, per ISO/IEC 23008-12). The
//! Kotlin side builds that TIFF payload from the same tag set the JPEG
//! path stamps (orientation baked upright, so the tag reads Normal, plus
//! GPS when enabled). The tests below parse the result back with the
//! in-file box parser and assert the item is really there.

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
    encode_avif_with_exif(rgba, width, height, quality, speed, None)
}

/// True when [exif] is a TIFF payload (`II*\0` / `MM\0*`) or an
/// already-framed HEIF Exif item (`[u32 offset][…][TIFF…]`).
fn looks_like_exif(exif: &[u8]) -> bool {
    if exif.starts_with(b"II\x2a\0") || exif.starts_with(b"MM\0\x2a") {
        return true;
    }
    let Some(offset) = exif.get(..4) else { return false };
    let Ok(offset) = <[u8; 4]>::try_from(offset) else { return false };
    let start = 4usize.checked_add(u32::from_be_bytes(offset) as usize);
    let Some(tiff) = start.and_then(|s| exif.get(s..)) else { return false };
    tiff.starts_with(b"II\x2a\0") || tiff.starts_with(b"MM\0\x2a")
}

/// Encode one RGBA frame to AVIF, muxing [exif] (a TIFF payload) in as
/// the file's Exif item when present. Returns `None` for invalid input,
/// a non-TIFF [exif], or any encoder failure — the Kotlin side then
/// falls back to JPEG rather than shipping an EXIF-less AVIF.
pub fn encode_avif_with_exif(
    rgba: &[u8],
    width: usize,
    height: usize,
    quality: f32,
    speed: u8,
    exif: Option<&[u8]>,
) -> Option<Vec<u8>> {
    if width == 0 || height == 0 || width > MAX_DIM || height > MAX_DIM {
        return None;
    }
    if let Some(exif) = exif {
        if exif.is_empty() || !looks_like_exif(exif) {
            return None;
        }
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
    // Explicit thread count: rav1e tiles the frame across threads, and the
    // default (`None`) silently inherits whatever size the global rayon
    // pool happened to be created with. Pinning it to the device's
    // parallelism makes the tile/thread split deterministic on every call.
    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let mut encoder = Encoder::new()
        .with_quality(quality.clamp(QUALITY_MIN, QUALITY_MAX))
        .with_speed(speed.clamp(SPEED_MIN, SPEED_MAX))
        .with_num_threads(Some(threads))
        .with_bit_depth(ravif::BitDepth::Eight);
    if let Some(exif) = exif {
        encoder = encoder.with_exif(exif.to_vec());
    }
    let encoded = encoder.encode_rgba(img).ok()?;
    if encoded.avif_file.is_empty() {
        None
    } else {
        Some(encoded.avif_file)
    }
}

/// True when `bytes` carry the AVIF file-type box (`....ftypavif`).
#[cfg(test)]
pub fn has_avif_magic(bytes: &[u8]) -> bool {
    bytes.len() >= 12 && &bytes[4..12] == b"ftypavif"
}

/// Read the `ispe` (image spatial extents) dimensions out of an AVIF
/// container by walking top-level/meta boxes. Used by tests to confirm a
/// real round-trip without pulling in a decoder crate.
#[cfg(test)]
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

/// Reconstructs the Exif item's data (`[u32 TIFF offset][TIFF…]`) by
/// walking `meta` → `iinf` (find the `Exif` infe id) → `iloc` (that id's
/// extents into the file). `None` when the file carries no Exif item.
/// This is the spec-level proof that the mux landed: a file that merely
/// contains the bytes somewhere does not pass unless iinf/iloc agree.
#[cfg(test)]
pub fn avif_exif_item(bytes: &[u8]) -> Option<Vec<u8>> {
    let top = parse_boxes(bytes, 0..bytes.len());
    let meta = top.iter().find(|b| &b.typ == b"meta")?;
    // `meta` is a full box: children start after version/flags.
    let children = parse_boxes(bytes, (meta.payload.start + 4)..meta.payload.end);

    let iinf = children.iter().find(|b| &b.typ == b"iinf")?;
    let mut pos = iinf.payload.start + 4; // version/flags
    let version = *bytes.get(iinf.payload.start)?;
    let count = if version == 1 {
        read_uint(bytes, &mut pos, 4)? as usize
    } else {
        read_uint(bytes, &mut pos, 2)? as usize
    };
    let infes = parse_boxes(bytes, pos..iinf.payload.end);
    let _ = count;
    let exif_id = infes.iter().find_map(|infe| {
        // infe v2: version/flags(4) item_id(2) protection(2) type(4)
        let p = infe.payload.start + 4;
        if bytes.get(p + 4..p + 8)? == b"Exif" {
            Some(u16::from_be_bytes([*bytes.get(p)?, *bytes.get(p + 1)?]))
        } else {
            None
        }
    })?;

    let iloc = children.iter().find(|b| &b.typ == b"iloc")?;
    let mut pos = iloc.payload.start + 4; // version/flags
    let sizes = *bytes.get(pos)?;
    pos += 1;
    let offset_size = (sizes >> 4) as usize;
    let length_size = (sizes & 0x0F) as usize;
    let base_sizes = *bytes.get(pos)?;
    pos += 1;
    let base_offset_size = (base_sizes >> 4) as usize;
    let iloc_version = *bytes.get(iloc.payload.start)?;
    let item_count = if iloc_version == 2 {
        read_uint(bytes, &mut pos, 4)? as usize
    } else {
        read_uint(bytes, &mut pos, 2)? as usize
    };
    for _ in 0..item_count {
        let item_id = read_uint(bytes, &mut pos, 2)? as u16;
        if iloc_version == 1 || iloc_version == 2 {
            read_uint(bytes, &mut pos, 2)?; // construction_method
        }
        read_uint(bytes, &mut pos, 2)?; // data_reference_index
        let base_offset = read_uint(bytes, &mut pos, base_offset_size)?;
        let extent_count = read_uint(bytes, &mut pos, 2)? as usize;
        let mut item = Vec::new();
        let found = item_id == exif_id;
        for _ in 0..extent_count {
            if iloc_version == 1 || iloc_version == 2 {
                let index_size = (base_sizes & 0x0F) as usize;
                if index_size > 0 {
                    read_uint(bytes, &mut pos, index_size)?;
                }
            }
            let offset = read_uint(bytes, &mut pos, offset_size)?;
            let length = read_uint(bytes, &mut pos, length_size)? as usize;
            if found {
                let start = (base_offset + offset) as usize;
                item.extend_from_slice(bytes.get(start..start + length)?);
            }
        }
        if found {
            return Some(item);
        }
    }
    None
}

/// The TIFF payload inside the Exif item (skips the u32 TIFF offset).
#[cfg(test)]
pub fn avif_exif_tiff(bytes: &[u8]) -> Option<Vec<u8>> {
    let item = avif_exif_item(bytes)?;
    let offset = u32::from_be_bytes(item.get(..4)?.try_into().ok()?) as usize;
    let start = 4usize.checked_add(offset)?;
    Some(item.get(start..)?.to_vec())
}

#[cfg(test)]
#[path = "avif_tests.rs"]
mod avif_tests;

/// JNI entry for `StitchNative.encodeAvifExif`: RGBA frame + TIFF Exif
/// payload in (empty array = no payload), AVIF bytes with a muxed Exif
/// item out, null on any failure so Kotlin falls back to JPEG.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_encodeAvifExif<'l>(
    mut env: jni::EnvUnowned<'l>,
    _class: jni::objects::JClass<'l>,
    rgba: jni::objects::JByteArray<'l>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    quality: jni::sys::jfloat,
    speed: jni::sys::jint,
    exif: jni::objects::JByteArray<'l>,
) -> jni::sys::jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jbyteArray> {
        let null = std::ptr::null_mut();
        if width <= 0 || height <= 0 {
            return Ok(null);
        }
        let bytes = match env.convert_byte_array(&rgba) {
            Ok(b) => b,
            Err(_) => return Ok(null),
        };
        let exif_bytes = match env.convert_byte_array(&exif) {
            Ok(b) => b,
            Err(_) => return Ok(null),
        };
        let exif_ref = if exif_bytes.is_empty() { None } else { Some(exif_bytes.as_slice()) };
        let encoded = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            encode_avif_with_exif(&bytes, width as usize, height as usize, quality, speed.clamp(1, 10) as u8, exif_ref)
        }))
        .unwrap_or(None);
        match encoded {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => Ok(arr.into_raw()),
                Err(_) => Ok(null),
            },
            None => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// JNI entry for `StitchNative.encodeAvif`: RGBA frame in, AVIF bytes out,
/// null on any failure so Kotlin falls back to JPEG.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_encodeAvif<'l>(
    mut env: jni::EnvUnowned<'l>,
    _class: jni::objects::JClass<'l>,
    rgba: jni::objects::JByteArray<'l>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    quality: jni::sys::jfloat,
    speed: jni::sys::jint,
) -> jni::sys::jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jbyteArray> {
        let null = std::ptr::null_mut();
        if width <= 0 || height <= 0 {
            return Ok(null);
        }
        let bytes = match env.convert_byte_array(&rgba) {
            Ok(b) => b,
            Err(_) => return Ok(null),
        };
        let encoded = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            encode_avif(&bytes, width as usize, height as usize, quality, speed.clamp(1, 10) as u8)
        }))
        .unwrap_or(None);
        match encoded {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => Ok(arr.into_raw()),
                Err(_) => Ok(null),
            },
            None => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
