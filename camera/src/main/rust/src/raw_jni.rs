//! JNI bindings for the RAW night session (split from `lib.rs` for the
//! 500-line Rust file cap). Symbols are `#[no_mangle]` and named for
//! `StitchNative` exactly as the other night bindings in `lib.rs`.

use crate::{encode_jpeg, next_handle, raw_night, raw_night_registry};
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jfloat, jint, jlong};
use jni::EnvUnowned;

// --- RAW night path: packed Bayer frames, linear merge ---

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_newRawNightSession<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jlong {
    let h = next_handle();
    raw_night_registry().lock().unwrap().insert(h, Vec::new());
    h
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_addRawFrame<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    bytes: JByteArray<'l>,
    width: jint,
    height: jint,
    format: jint,
    black_level: jfloat,
    white_level: jfloat,
    cfa: jint,
    r_gain: jfloat,
    g_gain: jfloat,
    b_gain: jfloat,
    exposure_scale: jfloat,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let (w, h) = (width as usize, height as usize);
        if w == 0 || h == 0 || w > 20000 || h > 20000 {
            return Ok(());
        }
        let data = match env.convert_byte_array(&bytes) {
            Ok(b) => b,
            Err(_) => return Ok(()),
        };
        let samples = match format {
            10 => raw_night::unpack_raw10(&data, w, h),
            _ => raw_night::unpack_raw16(&data, w, h),
        };
        let (Some(samples), Some(cfa)) = (samples, raw_night::Cfa::from_code(cfa)) else {
            return Ok(());
        };
        let frame = raw_night::RawFrame {
            w,
            h,
            samples,
            meta: raw_night::RawMeta {
                black_levels: [black_level; 4],
                white_level,
                cfa,
                gains: [r_gain, g_gain, b_gain],
                exposure_scale,
            },
        };
        if let Some(v) = raw_night_registry().lock().unwrap().get_mut(&handle) {
            v.push(frame);
        }
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_mergeRawNight<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    exposure_gain: jfloat,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let bytes = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let frames = raw_night_registry().lock().unwrap().remove(&handle)?;
            raw_night::merge_raw_burst(&frames, exposure_gain)
                .and_then(|img| encode_jpeg(&img, 95))
        }))
        .unwrap_or(None);
        match bytes {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => Ok(arr.into_raw()),
                Err(_) => Ok(null),
            },
            None => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_freeRawNight<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    raw_night_registry().lock().unwrap().remove(&handle);
}
