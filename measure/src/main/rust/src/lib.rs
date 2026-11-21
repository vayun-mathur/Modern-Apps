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
    /// Retained scratch for the strided/direct path: refilled in place each
    /// frame, then swapped with `prev_gray` (the old previous frame becomes
    /// the next scratch), so the incoming frame needs no per-frame allocation.
    incoming_gray: Option<Gray>,
    prev_points: Vec<(u64, f32, f32)>,
    next_id: u64,
}

impl CvSession {
    pub fn new(fx: f64, fy: f64, cx: f64, cy: f64) -> CvSession {
        CvSession {
            intr: Intrinsics { fx, fy, cx, cy },
            prev_gray: None,
            incoming_gray: None,
            prev_points: Vec::new(),
            next_id: 1,
        }
    }

    pub fn reset(&mut self) {
        self.prev_gray = None;
        self.incoming_gray = None;
        self.prev_points = Vec::new();
        self.next_id = 1;
    }

    fn fresh_id(&mut self) -> u64 {
        let id = self.next_id;
        self.next_id += 1;
        id
    }

    pub fn process_frame(&mut self, gray: &Gray) -> FrameFlow {
        self.process_frame_owned(gray.clone())
    }

    /// Process one frame handed over as a strided luminance plane (e.g. a
    /// direct `ByteBuffer` from CameraX): de-stride straight into the retained
    /// incoming buffer, with no per-frame `Vec` for the incoming frame.
    /// `None` when the plane is too short or the stride cannot hold a row.
    pub fn process_frame_strided(
        &mut self,
        src: &[u8],
        width: usize,
        height: usize,
        row_stride: usize,
    ) -> Option<FrameFlow> {
        if width == 0 || height == 0 || row_stride < width {
            return None;
        }
        if src.len() < row_stride * (height - 1) + width {
            return None;
        }
        let mut gray = self
            .incoming_gray
            .take()
            .unwrap_or_else(|| Gray::new(width, height));
        destride_into(src, width, height, row_stride, &mut gray);
        Some(self.process_frame_owned(gray))
    }

    fn process_frame_owned(&mut self, gray: Gray) -> FrameFlow {
        let prev_gray = match self.prev_gray.take() {
            Some(p) if p.w == gray.w && p.h == gray.h => p,
            other => {
                if let Some(old) = other {
                    self.incoming_gray = Some(old);
                }
                let pts = corners::detect(&gray);
                let count = pts.len();
                self.prev_points = pts
                    .into_iter()
                    .map(|(x, y)| {
                        let id = self.next_id;
                        self.next_id += 1;
                        (id, x, y)
                    })
                    .collect();
                self.prev_gray = Some(gray);
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
        let curr_pyr = Pyramid::build(&gray, PYRAMID_LEVELS);
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
            for pt in corners::detect(&gray) {
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
        // The current frame becomes the previous frame with no copy; the old
        // previous buffer is retained as the next incoming scratch.
        self.incoming_gray = Some(prev_gray);
        self.prev_gray = Some(gray);
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

/// Copy a strided luminance plane into [dst], dropping row padding.
/// [dst] keeps its allocation when the size is unchanged. Callers validate
/// `stride >= width` and that [src] holds `stride * (height - 1) + width` bytes.
pub fn destride_into(src: &[u8], width: usize, height: usize, row_stride: usize, dst: &mut Gray) {
    if dst.w != width || dst.h != height {
        dst.w = width;
        dst.h = height;
        dst.px.resize(width * height, 0);
    }
    if row_stride == width {
        dst.px.copy_from_slice(&src[..width * height]);
        return;
    }
    for row in 0..height {
        let src_off = row * row_stride;
        let dst_off = row * width;
        dst.px[dst_off..dst_off + width].copy_from_slice(&src[src_off..src_off + width]);
    }
}

mod jni_bindings;
