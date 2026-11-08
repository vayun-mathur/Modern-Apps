//! Monocular visual-inertial odometry for the measure app.
//!
//! Recovers **metric** camera motion from the rear camera plus the IMU, which is what
//! lets a tapped screen point become a real distance in metres. The camera alone can
//! only ever produce a reconstruction up to an unknown scale factor; the accelerometer
//! supplies the missing metre.

pub mod align;
pub mod epipolar;
pub mod klt;
pub mod plane;
pub mod preintegrate;
pub mod triangulate;
pub mod vio;

// Not cfg-gated to Android: the host build must export the same JNI symbols or
// MeasureNative.available lies in JVM tests (library loads, every call throws).
mod jni_bindings {
    use crate::preintegrate::ImuSample;
    use crate::vio::{Intrinsics, VioSession};
    use jni::objects::{JByteArray, JClass, JDoubleArray};
    use jni::sys::{jdouble, jdoubleArray, jfloat, jint, jlong};
    use jni::{Env, EnvUnowned};
    use std::collections::HashMap;
    use std::sync::{Mutex, OnceLock};
    use vision_core::imgbuf::Gray;
    use vision_core::linalg::Vector3;

    /// Sessions are held behind a handle rather than a raw pointer so a stale handle
    /// from Kotlin is a lookup miss instead of a use-after-free.
    fn sessions() -> &'static Mutex<HashMap<i64, VioSession>> {
        static S: OnceLock<Mutex<HashMap<i64, VioSession>>> = OnceLock::new();
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
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativeCreateSession(
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
        sessions()
            .lock()
            .unwrap()
            .insert(h, VioSession::new(Intrinsics { fx, fy, cx, cy }));
        h
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativeDestroySession(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        sessions().lock().unwrap().remove(&handle);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativeReset(
        _env: EnvUnowned,
        _class: JClass,
        handle: jlong,
    ) {
        if let Some(s) = sessions().lock().unwrap().get_mut(&handle) {
            s.reset();
        }
    }

    /// Feed one luminance plane. `row_stride` is honoured so the caller can pass the
    /// `ImageProxy` Y plane straight through without repacking it.
    #[no_mangle]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativePushFrame<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
        y_plane: JByteArray<'l>,
        width: jint,
        height: jint,
        row_stride: jint,
        t_ns: jlong,
    ) -> jint {
        env.with_env(|env| -> jni::errors::Result<jint> {
            let lost = crate::vio::Quality::Lost as jint;
            let (w, h, stride) = (width as usize, height as usize, row_stride as usize);
            if w == 0 || h == 0 || stride < w {
                return Ok(lost);
            }
            let len = match env.get_array_length(&y_plane) {
                Ok(l) => l as usize,
                Err(_) => return Ok(lost),
            };
            if len < stride * (h - 1) + w {
                return Ok(lost);
            }

            let mut buf = vec![0i8; len];
            if env.get_byte_array_region(&y_plane, 0, &mut buf).is_err() {
                return Ok(lost);
            }

            // Repack stride -> tight, reinterpreting Java's signed bytes as luminance.
            let mut gray = Gray::new(w, h);
            for row in 0..h {
                let src = row * stride;
                let dst = row * w;
                for col in 0..w {
                    gray.px[dst + col] = buf[src + col] as u8;
                }
            }

            let mut guard = sessions().lock().unwrap();
            match guard.get_mut(&handle) {
                Some(s) => Ok(s.push_frame(&gray, t_ns) as jint),
                None => Ok(lost),
            }
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    /// Push IMU samples as a flat `[t_ns, gx, gy, gz, ax, ay, az]` array.
    ///
    /// Batched rather than one call per sample: at 400 Hz, per-sample JNI overhead
    /// would dominate the actual integration cost.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativePushImu<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
        packed: JDoubleArray<'l>,
    ) {
        env.with_env(|env| -> jni::errors::Result<()> {
            let len = match env.get_array_length(&packed) {
                Ok(l) => l as usize,
                Err(_) => return Ok(()),
            };
            if len == 0 || len % 7 != 0 {
                return Ok(());
            }
            let mut buf = vec![0f64; len];
            if env.get_double_array_region(&packed, 0, &mut buf).is_err() {
                return Ok(());
            }
            let samples: Vec<ImuSample> = buf
                .chunks_exact(7)
                .map(|c| ImuSample {
                    t_ns: c[0] as i64,
                    gyro: Vector3::new(c[1], c[2], c[3]),
                    accel: Vector3::new(c[4], c[5], c[6]),
                })
                .collect();
            if let Some(s) = sessions().lock().unwrap().get_mut(&handle) {
                s.push_imu(&samples);
            }
            Ok(())
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    /// Convert a screen tap to a metric world point.
    ///
    /// Returns `[x, y, z, onPlane]`, or an empty array when tracking is not yet
    /// metric — the caller must treat empty as "not measurable yet".
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativeRayToWorld<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
        px: jfloat,
        py: jfloat,
    ) -> jdoubleArray {
        env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
            let hit = {
                let guard = sessions().lock().unwrap();
                guard.get(&handle).and_then(|s| s.ray_to_world(px, py))
            };
            let vals: Vec<f64> = match hit {
                Some((p, on_plane)) => vec![p[0], p[1], p[2], if on_plane { 1.0 } else { 0.0 }],
                None => Vec::new(),
            };
            Ok(make_double_array(env, &vals))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    /// Diagnostics snapshot:
    /// `[quality, scaleConfidence, landmarkCount, trackedCount, hasPlane, gx, gy, gz]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativeGetState<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
    ) -> jdoubleArray {
        env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
            let guard = sessions().lock().unwrap();
            let vals: Vec<f64> = match guard.get(&handle) {
                Some(s) => {
                    let g = s.gravity();
                    vec![
                        s.quality() as i32 as f64,
                        s.scale_confidence(),
                        s.landmark_count() as f64,
                        s.tracked_count() as f64,
                        if s.has_plane() { 1.0 } else { 0.0 },
                        g[0],
                        g[1],
                        g[2],
                    ]
                }
                None => Vec::new(),
            };
            drop(guard);
            Ok(make_double_array(env, &vals))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    /// Project metric world points to normalised screen coordinates.
    ///
    /// Input is a flat `[x, y, z]` array; output is `[nx, ny, visible]` per point.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_measure_domain_MeasureNative_nativeProjectPoints<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        handle: jlong,
        world: JDoubleArray<'l>,
        width: jdouble,
        height: jdouble,
    ) -> jdoubleArray {
        env.with_env(|env| -> jni::errors::Result<jdoubleArray> {
            let len = match env.get_array_length(&world) {
                Ok(l) => l as usize,
                Err(_) => return Ok(make_double_array(env, &[])),
            };
            if len == 0 || len % 3 != 0 || !(width > 0.0 && height > 0.0) {
                return Ok(make_double_array(env, &[]));
            }
            let mut buf = vec![0f64; len];
            if env.get_double_array_region(&world, 0, &mut buf).is_err() {
                return Ok(make_double_array(env, &[]));
            }
            let pts: Vec<Vector3<f64>> =
                buf.chunks_exact(3).map(|c| Vector3::new(c[0], c[1], c[2])).collect();

            let guard = sessions().lock().unwrap();
            let out: Vec<f64> = match guard.get(&handle) {
                Some(s) => s
                    .project_to_screen(&pts, width, height)
                    .into_iter()
                    .flat_map(|(x, y, v)| [x as f64, y as f64, if v { 1.0 } else { 0.0 }])
                    .collect(),
                None => Vec::new(),
            };
            drop(guard);
            Ok(make_double_array(env, &out))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    fn make_double_array(env: &mut Env, vals: &[f64]) -> jdoubleArray {
        let arr = match env.new_double_array(vals.len()) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if !vals.is_empty() && env.set_double_array_region(&arr, 0, vals).is_err() {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    }
}
