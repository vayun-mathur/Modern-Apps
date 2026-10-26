//! Moon raster pair upload (maps-only lunar globe).
//!
//! Pure JNI entry point; the GPU work lives in `Renderer::set_moon_textures`
//! (upload path) and the Moon record branch (draw path).
use super::handle::handle_mut;
use super::log::log;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jint, jlong};
use jni::EnvUnowned;
/// Upload the Moon raster pair: the converted LROC color (RGBA8) and the
/// converted LDEM (RG8 packed uint16, see `analysis/convert_moon.py`).
///
/// Two bulk byte arrays plus their dimensions — the same convention as the
/// marker/route pushes: one crossing, no per-pixel traffic. The renderer copies
/// them to GPU textures (replacing any previous pair, retiring the old images
/// through the frames-in-flight grace queue); unreadable arrays leave the
/// previous pair unchanged rather than blanking the Moon.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setMoonTextures<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    color_rgba: JByteArray<'l>,
    width: jint,
    height: jint,
    dem_rg: JByteArray<'l>,
    dem_width: jint,
    dem_height: jint,
) {
    let Some(map) = handle_mut(handle) else {
        return;
    };
    let (width, height) = (width.max(0) as usize, height.max(0) as usize);
    let (dem_width, dem_height) = (dem_width.max(0) as usize, dem_height.max(0) as usize);
    if width == 0 || height == 0 || dem_width == 0 || dem_height == 0 {
        log("moon textures: refusing a zero-sized upload");
        return;
    }
    if width
        .checked_mul(height)
        .and_then(|n| n.checked_mul(4))
        .is_none()
        || dem_width
            .checked_mul(dem_height)
            .and_then(|n| n.checked_mul(2))
            .is_none()
    {
        log("moon textures: refusing an overflowing size");
        return;
    }
    let color_len = width * height * 4;
    let dem_len = dem_width * dem_height * 2;
    // Cap the crossing at the converted asset sizes (32MB + 4MB): anything
    // larger is a caller bug, and the transient allocation lands on the frame
    // thread's host heap.
    if color_len > 64 * 1024 * 1024 || dem_len > 16 * 1024 * 1024 {
        log("moon textures: refusing an oversized upload");
        return;
    }
    let mut color_buf = vec![0u8; color_len];
    let mut dem_buf = vec![0u8; dem_len];
    let read_ok: bool = env
        .with_env(|env| {
            Ok::<bool, jni::errors::Error>(
                env.get_byte_array_region(&color_rgba, 0, bytemuck_cast(&mut color_buf))
                    .is_ok()
                    && env
                        .get_byte_array_region(&dem_rg, 0, bytemuck_cast(&mut dem_buf))
                        .is_ok(),
            )
        })
        .resolve::<jni::errors::LogErrorAndDefault>();
    if !read_ok {
        log("moon textures: arrays could not be read; leaving the Moon unchanged");
        return;
    }
    if let Err(e) = map.renderer.set_moon_textures(
        &color_buf,
        width as u32,
        height as u32,
        &dem_buf,
        dem_width as u32,
        dem_height as u32,
    ) {
        log(&format!("moon textures: upload failed: {e}"));
    }
}

/// Reinterpret a `&mut [u8]` as `&mut [i8]` for the JNI byte-array read.
///
/// `get_byte_array_region` takes `&mut [i8]` (Java bytes are signed); the bit
/// pattern is what matters and the cast keeps it. Sound: `u8` and `i8` share
/// size and alignment, and the length is preserved.
fn bytemuck_cast(bytes: &mut [u8]) -> &mut [i8] {
    unsafe { std::slice::from_raw_parts_mut(bytes.as_mut_ptr() as *mut i8, bytes.len()) }
}

/// Drop the Moon raster pair (~40 MB of GPU images) when the host leaves the Moon; a later
/// [`setMoonTextures`](Java_com_vayunmathur_library_map_MapNative_setMoonTextures) uploads it
/// again.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearMoonTextures<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.clear_moon_textures();
    }
}
