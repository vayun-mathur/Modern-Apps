//! JNI bindings for `measure_cv` (split from `lib.rs` for the file-length gate).
//! Not cfg-gated to Android: the host build must export the same JNI symbols
//! or the Kotlin bridge's availability check lies in JVM tests.

use crate::{destride_into, CvSession, FrameFlow};
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::sys::{jdouble, jdoubleArray, jlong};
use jni::{Env, EnvUnowned};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use vision_core::imgbuf::Gray;

/// Sessions are held behind a handle rather than a raw pointer so a stale
/// handle from Kotlin is a lookup miss instead of a use-after-free.
fn sessions() -> &'static Mutex<HashMap<i64, CvSession>> {
    static S: OnceLock<Mutex<HashMap<i64, CvSession>>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> i64 {
    static N: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = N.get_or_init(|| Mutex::new(0));
    let mut g = m.lock().unwrap();
    *g += 1;
    *g
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_measure_platform_vio_MeasureCvNative_nativeCreateSession(
    _env: EnvUnowned,
    _class: JClass,
    fx: jdouble,
    fy: jdouble,
    cx: jdouble,
    cy: jdouble,
) -> jlong {
    if !(fx.is_finite() && fy.is_finite() && fx > 1.0 && fy > 1.0) {
        return 0;
    }
    let h = next_handle();
    sessions().lock().unwrap().insert(h, CvSession::new(fx, fy, cx, cy));
    h
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_measure_platform_vio_MeasureCvNative_nativeDestroySession(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    sessions().lock().unwrap().remove(&handle);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_measure_platform_vio_MeasureCvNative_nativeReset(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if let Some(s) = sessions().lock().unwrap().get_mut(&handle) {
        s.reset();
    }
}

/// Feed one luminance plane; `row_stride` is honoured so the caller can
/// pass the CameraX Y plane straight through without repacking it.
///
/// Returns `[pairCount, poseFlag, firstFlag, pointCount, R(9), t(3),
/// then prevX, prevY, currX, currY per pair]`; empty for a bad handle.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_measure_platform_vio_MeasureCvNative_nativeProcessFrame<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    y_plane: JByteArray<'l>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    row_stride: jni::sys::jint,
) -> jdoubleArray {
    env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
        let (w, h, stride) = (width as usize, height as usize, row_stride as usize);
        if w == 0 || h == 0 || stride < w {
            return Ok(make_double_array(env, &[]));
        }
        let len = match y_plane.len(env) {
            Ok(l) => l,
            Err(_) => return Ok(make_double_array(env, &[])),
        };
        if len < stride * (h - 1) + w {
            return Ok(make_double_array(env, &[]));
        }
        let mut buf = vec![0i8; len];
        if y_plane.get_region(env, 0, &mut buf).is_err() {
            return Ok(make_double_array(env, &[]));
        }
        // Byte-wise reinterpretation of the copied plane; the values are
        // luminance bytes, so the i8/u8 bit pattern is what matters.
        let bytes: &[u8] = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const u8, buf.len()) };
        let mut gray = Gray::new(w, h);
        destride_into(bytes, w, h, stride, &mut gray);
        let mut guard = sessions().lock().unwrap();
        let vals: Vec<f64> = match guard.get_mut(&handle) {
            Some(s) => encode_flow(&s.process_frame(&gray)),
            None => Vec::new(),
        };
        Ok(make_double_array(env, &vals))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Direct-`ByteBuffer` twin of `nativeProcessFrame`: the caller hands over
/// the CameraX Y plane's (direct) buffer, and the plane is de-strided
/// straight from its native address into the session's retained Gray
/// buffer — no Kotlin `ByteArray` copy and no Rust `Vec` for the frame.
/// The buffer must outlive this call, which the synchronous JNI call
/// guarantees for the caller's `ImageProxy` (closed after it returns).
/// Empty for a bad handle, a non-direct buffer, or a short plane.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_measure_platform_vio_MeasureCvNative_nativeProcessFrameDirect<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    y_plane: JByteBuffer<'l>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    row_stride: jni::sys::jint,
) -> jdoubleArray {
    env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
        let (w, h, stride) = (width as usize, height as usize, row_stride as usize);
        if w == 0 || h == 0 || stride < w {
            return Ok(make_double_array(env, &[]));
        }
        let addr = match env.get_direct_buffer_address(&y_plane) {
            Ok(a) if !a.is_null() => a,
            _ => return Ok(make_double_array(env, &[])),
        };
        let capacity = match env.get_direct_buffer_capacity(&y_plane) {
            Ok(c) => c,
            Err(_) => return Ok(make_double_array(env, &[])),
        };
        if capacity < stride * (h - 1) + w {
            return Ok(make_double_array(env, &[]));
        }
        // Safety: `addr`/`capacity` describe the direct buffer, which the
        // JVM keeps alive while this JNI call (and its local ref) runs.
        let src: &[u8] = unsafe { std::slice::from_raw_parts(addr as *const u8, capacity) };
        let mut guard = sessions().lock().unwrap();
        let vals: Vec<f64> = match guard.get_mut(&handle) {
            Some(s) => match s.process_frame_strided(src, w, h, stride) {
                Some(flow) => encode_flow(&flow),
                None => Vec::new(),
            },
            None => Vec::new(),
        };
        Ok(make_double_array(env, &vals))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn encode_flow(flow: &FrameFlow) -> Vec<f64> {
    let mut v = Vec::with_capacity(20 + flow.pairs.len() * 5);
    v.push(flow.pairs.len() as f64);
    v.push(if flow.pose.is_some() { 1.0 } else { 0.0 });
    v.push(if flow.first_frame { 1.0 } else { 0.0 });
    v.push(flow.point_count as f64);
    match &flow.pose {
        Some(p) => {
            for row in 0..3 {
                for col in 0..3 {
                    v.push(p.r[(row, col)]);
                }
            }
            v.push(p.t[0]);
            v.push(p.t[1]);
            v.push(p.t[2]);
        }
        None => v.extend(std::iter::repeat_n(0.0, 12)),
    }
    v.push(flow.seeded as f64);
    v.push(flow.forward_ok as f64);
    v.push(flow.fb_survivors as f64);
    v.push(flow.ransac_inliers as f64);
    for pair in &flow.pairs {
        v.push(pair.id as f64);
        v.push(pair.prev.0);
        v.push(pair.prev.1);
        v.push(pair.curr.0);
        v.push(pair.curr.1);
    }
    v
}

fn make_double_array(env: &mut Env, vals: &[f64]) -> jdoubleArray {
    let arr = match env.new_double_array(vals.len()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    if !vals.is_empty() && arr.set_region(env, 0, vals).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}
