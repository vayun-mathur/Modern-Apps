//! Per-frame computer vision for the measure app's visual-inertial tracker.
//!
//! Our own Rust CV core, following the camera app's stitching precedent
//! (`camera/src/main/rust` + the shared `vision_core` primitives): no
//! third-party native CV library ships in the APK, so there is no
//! 4 KB-aligned prebuilt to fail on 16 KB-page devices — the `.so` is
//! linked by our own NDK toolchain, which emits 16 KB-aligned segments.
//!
//! Scope is deliberately the per-frame image work only: Shi-Tomasi corner
//! detection ([`corners`]), pyramidal Lucas-Kanade tracking with a
//! forward-backward consistency check ([`klt`]), and essential-matrix
//! pose recovery (normalised eight-point + RANSAC + cheirality,
//! [`epipolar`]). Everything above that — pose chaining, landmark
//! triangulation, plane fitting, IMU scale fusion, quality gating —
//! stays in Kotlin (`domain/VioMath.kt` and the engine that consumes
//! this crate), where it is unit-tested pure geometry.

pub mod corners;
pub mod epipolar;
pub mod klt;

#[cfg(test)] #[path = "lib_tests.rs"] mod lib_tests;

use epipolar::{recover_pose, RelPose};
use klt::Pyramid;
use vision_core::imgbuf::Gray;

const PYRAMID_LEVELS: usize = 3;
/// Forward-backward check tolerance, in pixels (matches the tuned tracker).
const FB_MAX_ERROR_PX: f32 = 1.5;
/// Essential-matrix RANSAC iterations.
const ESSENTIAL_ITERS: usize = 200;
/// RANSAC inlier threshold in pixels, converted to calibrated units per frame.
const ESSENTIAL_THRESH_PX: f64 = 1.0;

/// One tracked correspondence, in pixels: previous -> current.
/// `id` is stable for a feature's whole life in the session, so callers
/// can match a point seen now against the same point in an older frame
/// (keyframe triangulation) instead of assuming list indices line up.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TrackPair {
    pub id: u64,
    pub prev: (f64, f64),
    pub curr: (f64, f64),
}

/// Result of processing one frame.
pub struct FrameFlow {
    /// True on the first frame of a session (points seeded, no motion yet).
    pub first_frame: bool,
    /// Tracked (and, when a pose was recovered, RANSAC-inlier) correspondences.
    pub pairs: Vec<TrackPair>,
    /// Relative pose previous -> current, `X_curr = R * X_prev + t`, unit `t`.
    pub pose: Option<RelPose>,
    /// Feature points seeded for the next frame, after top-up.
    pub point_count: usize,
    /// Diagnostics: points carried into this frame.
    pub seeded: usize,
    /// Diagnostics: forward LK tracks that succeeded.
    pub forward_ok: usize,
    /// Diagnostics: pairs surviving the forward-backward check (pre-RANSAC).
    pub fb_survivors: usize,
    /// Diagnostics: RANSAC inlier count, or -1 when RANSAC did not run.
    pub ransac_inliers: i32,
}

struct Intrinsics {
    fx: f64,
    fy: f64,
    cx: f64,
    cy: f64,
}

/// A tracking session: previous frame + its feature points, nothing more.
/// All estimator state above the correspondences lives in Kotlin.
pub struct CvSession {
    intr: Intrinsics,
    prev_gray: Option<Gray>,
    prev_points: Vec<(u64, f32, f32)>,
    next_id: u64,
}

impl CvSession {
    pub fn new(fx: f64, fy: f64, cx: f64, cy: f64) -> CvSession {
        CvSession {
            intr: Intrinsics { fx, fy, cx, cy },
            prev_gray: None,
            prev_points: Vec::new(),
            next_id: 1,
        }
    }

    pub fn reset(&mut self) {
        self.prev_gray = None;
        self.prev_points = Vec::new();
        self.next_id = 1;
    }

    fn fresh_id(&mut self) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    pub fn process_frame(&mut self, gray: &Gray) -> FrameFlow {
        let prev_gray = match self.prev_gray.take() {
            Some(p) if p.w == gray.w && p.h == gray.h => p,
            _ => {
                let pts = corners::detect(gray);
                let count = pts.len();
                self.prev_points = pts
                    .into_iter()
                    .map(|(x, y)| {
                        let id = self.next_id;
                        self.next_id += 1;
                        (id, x, y)
                    })
                    .collect();
                self.prev_gray = Some(gray.clone());
                return FrameFlow {
                    first_frame: true,
                    pairs: Vec::new(),
                    pose: None,
                    point_count: count,
                    seeded: count,
                    forward_ok: 0,
                    fb_survivors: 0,
                    ransac_inliers: -1,
                };
            }
        };

        let prev_pyr = Pyramid::build(&prev_gray, PYRAMID_LEVELS);
        let curr_pyr = Pyramid::build(gray, PYRAMID_LEVELS);
        let prev_xy: Vec<(f32, f32)> =
            self.prev_points.iter().map(|&(_, x, y)| (x, y)).collect();
        let forward = klt::track(&prev_pyr, &curr_pyr, &prev_xy);
        let candidates: Vec<(f32, f32)> = forward
            .iter()
            .filter(|r| r.ok)
            .map(|r| (r.x, r.y))
            .collect();
        let backward = klt::track(&curr_pyr, &prev_pyr, &candidates);

        // Forward-backward check: a point that does not track back to where
        // it started (within tolerance) was never really tracked.
        let mut pairs: Vec<TrackPair> = Vec::new();
        let mut cand_idx = 0usize;
        for (i, f) in forward.iter().enumerate() {
            if !f.ok {
                continue;
            }
            let b = &backward[cand_idx];
            cand_idx += 1;
            if !b.ok {
                continue;
            }
            let (id, ox, oy) = self.prev_points[i];
            let dx = b.x - ox;
            let dy = b.y - oy;
            if dx * dx + dy * dy > FB_MAX_ERROR_PX * FB_MAX_ERROR_PX {
                continue;
            }
            pairs.push(TrackPair {
                id,
                prev: (ox as f64, oy as f64),
                curr: (f.x as f64, f.y as f64),
            });
        }

        let seeded = self.prev_points.len();
        let forward_ok = forward.iter().filter(|r| r.ok).count();
        let fb_survivors = pairs.len();
        // Essential pose on calibrated bearings; keep only RANSAC inliers
        // as correspondences when a pose was recovered.
        let mut pose = None;
        let mut ransac_inliers: i32 = -1;
        if pairs.len() >= 8 {
            let a: Vec<(f64, f64)> = pairs.iter().map(|p| self.calibrated(p.prev)).collect();
            let b: Vec<(f64, f64)> = pairs.iter().map(|p| self.calibrated(p.curr)).collect();
            let thresh = ESSENTIAL_THRESH_PX / ((self.intr.fx + self.intr.fy) * 0.5);
            if let Some((rel, inliers)) = recover_pose(&a, &b, ESSENTIAL_ITERS, thresh) {
                ransac_inliers = inliers.len() as i32;
                if inliers.len() >= 8 {
                    pairs = inliers.iter().map(|&i| pairs[i]).collect();
                    pose = Some(rel);
                }
            } else {
                ransac_inliers = -2;
            }
        }

        // Seed the next frame: tracked points, topped up with fresh corners
        // so slow attrition never starves the tracker.
        let mut next_points: Vec<(u64, f32, f32)> = pairs
            .iter()
            .map(|p| (p.id, p.curr.0 as f32, p.curr.1 as f32))
            .collect();
        if next_points.len() < corners::MAX_CORNERS / 2 {
            for pt in corners::detect(gray) {
                if next_points
                    .iter()
                    .all(|&(_, x, y)| (x - pt.0).powi(2) + (y - pt.1).powi(2) >= 100.0)
                {
                    let id = self.fresh_id();
                    next_points.push((id, pt.0, pt.1));
                    if next_points.len() >= corners::MAX_CORNERS {
                        break;
                    }
                }
            }
        }
        let point_count = next_points.len();
        self.prev_points = next_points;
        self.prev_gray = Some(gray.clone());
        FrameFlow {
            first_frame: false,
            pairs,
            pose,
            point_count,
            seeded,
            forward_ok,
            fb_survivors,
            ransac_inliers,
        }
    }

    fn calibrated(&self, px: (f64, f64)) -> (f64, f64) {
        (
            (px.0 - self.intr.cx) / self.intr.fx,
            (px.1 - self.intr.cy) / self.intr.fy,
        )
    }
}

// Not cfg-gated to Android: the host build must export the same JNI symbols
// or the Kotlin bridge's availability check lies in JVM tests.
mod jni_bindings {
    use crate::CvSession;
    use jni::objects::{JByteArray, JClass};
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
            let mut gray = Gray::new(w, h);
            for row in 0..h {
                let src = row * stride;
                let dst = row * w;
                for col in 0..w {
                    gray.px[dst + col] = buf[src + col] as u8;
                }
            }
            let mut guard = sessions().lock().unwrap();
            let vals: Vec<f64> = match guard.get_mut(&handle) {
                Some(s) => {
                    let flow = s.process_frame(&gray);
                    let mut v = Vec::with_capacity(16 + flow.pairs.len() * 4);
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
                None => Vec::new(),
            };
            Ok(make_double_array(env, &vals))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
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
}
