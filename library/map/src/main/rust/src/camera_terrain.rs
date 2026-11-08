//! Terrain-aware screen-ray casting for the tilted camera.
//!
//! File-length split from `camera_extra`: the eye-ray march that intersects a screen ray
//! with the terrain surface instead of the flat ground plane. It lives apart because the
//! flat projection helpers stay sea-level pure; this is the one camera method that reads
//! a heightfield — pivot-relative, so high ground resolves against the pivot the frame
//! drew with (see [`Camera::pivot_dp`](crate::camera::Camera)).
use crate::camera::{project, Camera, WorldPx};

impl Camera {
    /// The ground world-px under a screen point, intersecting the eye ray with the **displaced
    /// terrain** rather than the flat plane — the ray/heightfield extension of
    /// [`screen_to_world`](Self::screen_to_world) that WS-G's seam in
    /// [`perspective_plane`](Self::perspective_plane) anticipates.
    ///
    /// `height_at` returns the terrain height at a world-px ground position, in **world px** — the
    /// same unit the perspective matrix's `z` input uses (metres scaled by the tile's
    /// world-px-per-metre; see [`crate::tess::terrain`]). It is a closure rather than a field so
    /// this stays a pure function of the camera: the renderer passes a sampler over its resident
    /// heightmaps, tests pass a synthetic surface.
    ///
    /// At `pitch_deg == 0` the projection ignores height for x/y, so a tap lands on exactly the
    /// flat-plane point regardless of relief and this returns
    /// [`screen_to_world`](Self::screen_to_world) unchanged. Above zero it walks the eye ray from
    /// the eye toward the flat-plane hit, sampling `height_at`, and returns the first crossing of
    /// the terrain surface, refined by bisection.
    ///
    /// The march exploits that the map from screen-flat `(sx, sy, height)` to camera space is
    /// affine, so the eye ray is a straight line there: the eye is `(0, d·sin, d·cos)` (solving the
    /// camera model in [`perspective_plane`](Self::perspective_plane) for the origin) and the
    /// flat-plane hit is the ray's `height == 0` point, so the segment between them — extended a
    /// little past the plane for below-sea terrain — is the ray. `None` above the horizon
    /// (impossible on-screen under [`PITCH_MAX_DEG`]) or when the ray never meets the terrain, in
    /// which case the flat-plane hit is the best answer.
    pub fn screen_to_world_over_terrain(
        &self,
        screen_x_dp: f64,
        screen_y_dp: f64,
        height_at: impl Fn(WorldPx) -> f64,
    ) -> Option<WorldPx> {
        if self.pitch_deg == 0.0 {
            return self.screen_to_world(screen_x_dp, screen_y_dp);
        }
        // The flat-plane hit is the ray's height-0 point and, with the eye, fixes its direction.
        let plane = self.screen_to_world(screen_x_dp, screen_y_dp)?;
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        // The plane hit as a screen-flat offset from the centre: the inverse of the final rotation
        // `screen_to_world` applies.
        let dxw = plane.x - center.x;
        let dyw = plane.y - center.y;
        let sx1 = cos * dxw + sin * dyw;
        let sy1 = -sin * dxw + cos * dyw;
        // The eye in the same (sx, sy, height) frame, and the ray toward the plane hit.
        let p = self.perspective();
        let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
        let eye = (0.0f64, p.d * psin, p.d * pcos);
        let dir = (sx1 - eye.0, sy1 - eye.1, 0.0 - eye.2);

        let world_at = |t: f64| {
            let sx = eye.0 + t * dir.0;
            let sy = eye.1 + t * dir.1;
            WorldPx {
                x: center.x + cos * sx - sin * sy,
                y: center.y + sin * sx + cos * sy,
            }
        };
        let ray_height = |t: f64| eye.2 + t * dir.2;
        // Positive while the ray is above the terrain, non-positive once it has crossed below.
        // Pivot-relative: `plane` above is the pivot-plane hit (via the pivoted
        // `screen_to_world`) and the eye frame rides H0 with it, so the march
        // compares the ray against `height − H0`. H0 = 0 is the legacy march.
        let h0 = self.pivot_dp;
        let gap = |t: f64| ray_height(t) - (height_at(world_at(t)) - h0);

        // March from the eye toward (and a little past) the plane hit. Terrain above sea rises
        // toward the ray, so its crossing is at t <= 1; below-sea terrain can push it past 1, so the
        // march overshoots before giving up. The pitch cap keeps the whole thing bounded.
        const STEPS: usize = 96;
        const T_MAX: f64 = 1.5;
        if gap(0.0) <= 0.0 {
            // The eye is at or under the terrain: degenerate, fall back to the plane hit.
            return Some(plane);
        }
        let mut prev_t = 0.0;
        for i in 1..=STEPS {
            let t = T_MAX * i as f64 / STEPS as f64;
            if gap(t) <= 0.0 {
                // A crossing is bracketed in (prev_t, t]; bisect to refine it.
                let (mut lo, mut hi) = (prev_t, t);
                for _ in 0..40 {
                    let mid = 0.5 * (lo + hi);
                    if gap(mid) > 0.0 {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                return Some(world_at(0.5 * (lo + hi)));
            }
            prev_t = t;
        }
        // The ray grazed above every sample: the flat-plane hit is the best available answer.
        Some(plane)
    }
}
