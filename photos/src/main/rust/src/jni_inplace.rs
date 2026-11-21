//! Caller-owned-buffer (in-place) JNI variants for the photos native
//! filters: each reads the caller's `int[]`, computes, and writes the result
//! back into that same Java array instead of allocating a fresh one —
//! mirroring the reusable `state.pixels` scratch buffer in library/ml's
//! bridge.rs.

use crate::{blur, sharpen, stylize};
use jni::objects::{JClass, JIntArray};
use jni::sys::jint;
use jni::{Env, EnvUnowned};

/// Read a Java `int[]` into a `Vec<i32>`; `None` on error.
fn read_ints(env: &Env, arr: &JIntArray) -> Option<Vec<i32>> {
    let len = env.get_array_length(arr).ok()? as usize;
    let mut buf = vec![0i32; len];
    if env.get_int_array_region(arr, 0, &mut buf).is_err() {
        return None;
    }
    Some(buf)
}

/// Write `result` back into the caller's own `pixels` array (no new Java
/// array allocated), mirroring the reusable-buffer pattern used by
/// library/ml's bridge. Returns false on error.
fn write_into(env: &Env, pixels: &JIntArray, result: &[i32]) -> bool {
    env.set_int_array_region(pixels, 0, result).is_ok()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_stylizeInPlace<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    pixels: JIntArray<'l>,
    w: jint,
    h: jint,
    mode: jint,
) -> jni::sys::jboolean {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jboolean> {
        if w <= 0 || h <= 0 {
            return Ok(false);
        }
        let buf = match read_ints(env, &pixels) {
            Some(b) if b.len() == (w as usize) * (h as usize) => b,
            _ => return Ok(false),
        };
        let mut ok = false;
        if let Ok(result) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            stylize::stylize(&buf, w as usize, h as usize, mode)
        })) {
            if write_into(env, &pixels, &result) {
                ok = true;
            }
        }
        Ok(ok)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_unsharpInPlace<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    pixels: JIntArray<'l>,
    w: jint,
    h: jint,
    amount: jni::sys::jfloat,
    radius: jni::sys::jfloat,
    threshold: jint,
) -> jni::sys::jboolean {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jboolean> {
        if w <= 0 || h <= 0 {
            return Ok(false);
        }
        let buf = match read_ints(env, &pixels) {
            Some(b) if b.len() == (w as usize) * (h as usize) => b,
            _ => return Ok(false),
        };
        let mut ok = false;
        if let Ok(result) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            sharpen::unsharp(&buf, w as usize, h as usize, amount, radius, threshold)
        })) {
            if write_into(env, &pixels, &result) {
                ok = true;
            }
        }
        Ok(ok)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_blurParamsInPlace<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    pixels: JIntArray<'l>,
    w: jint,
    h: jint,
    mode: jint,
    center_x: jni::sys::jfloat,
    center_y: jni::sys::jfloat,
    radius: jni::sys::jfloat,
    intensity: jni::sys::jfloat,
    feather: jni::sys::jfloat,
    angle: jni::sys::jfloat,
) -> jni::sys::jboolean {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jboolean> {
        if w <= 0 || h <= 0 {
            return Ok(false);
        }
        let buf = match read_ints(env, &pixels) {
            Some(b) if b.len() == (w as usize) * (h as usize) => b,
            _ => return Ok(false),
        };
        let mut ok = false;
        if let Ok(result) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            blur::blur_params(
                &buf, w as usize, h as usize, mode, center_x, center_y, radius, intensity, feather,
                angle,
            )
        })) {
            if write_into(env, &pixels, &result) {
                ok = true;
            }
        }
        Ok(ok)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_filterBlurInPlace<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    pixels: JIntArray<'l>,
    w: jint,
    h: jint,
    mode: jint,
    amount: jni::sys::jfloat,
    angle: jni::sys::jfloat,
    center_x: jni::sys::jfloat,
    center_y: jni::sys::jfloat,
) -> jni::sys::jboolean {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jboolean> {
        if w <= 0 || h <= 0 {
            return Ok(false);
        }
        let buf = match read_ints(env, &pixels) {
            Some(b) if b.len() == (w as usize) * (h as usize) => b,
            _ => return Ok(false),
        };
        let mut ok = false;
        if let Ok(result) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            blur::filter_blur(&buf, w as usize, h as usize, mode, amount, angle, center_x, center_y)
        })) {
            if write_into(env, &pixels, &result) {
                ok = true;
            }
        }
        Ok(ok)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

