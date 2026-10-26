//! Camera projection implementation, moved wholesale out of `camera.rs`.
use crate::camera::{project, unproject, Camera, Perspective, WorldPx, TILE_SIZE};

impl Camera {
    /// `(cos, sin)` of the bearing: the 2x2 that turns a world-px offset from the camera
    /// centre into a screen-px offset.
    ///
    /// Public because the symbol path needs the inverse of it — labels counter-rotate to
    /// stay upright, see [`crate::tess::text::upright`] — and deriving the angle twice is
    /// how the two would eventually disagree.
    ///
    /// Short-circuited at zero so the north-up path — every phone frame — takes the
    /// literal `(1, 0)` rather than `cos(0)`/`sin(0)`, and the matrices below reduce
    /// term by term to the unrotated ones.
    pub fn rotation(&self) -> (f64, f64) {
        if self.bearing_deg == 0.0 {
            return (1.0, 0.0);
        }
        let radians = self.bearing_deg.to_radians();
        (radians.cos(), radians.sin())
    }

    /// The world-px position of the viewport's top-left corner.
    ///
    /// Only meaningful north-up: a rotated viewport has no axis-aligned corner in world
    /// space. Anything that needs the ground a rotated viewport covers wants
    /// [`viewport_bounds`](Self::viewport_bounds) instead.
    pub fn viewport_origin(&self) -> WorldPx {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        WorldPx {
            x: center.x - self.width_dp as f64 / 2.0,
            y: center.y - self.height_dp as f64 / 2.0,
        }
    }

    /// The world-px axis-aligned box the viewport covers.
    ///
    /// **Larger than the viewport whenever the camera is rotated**, and that is the whole
    /// reason this exists: a 45-degree bearing makes the covered box up to `sqrt(2)` times
    /// the viewport across each axis, so a caller that derived it from an unrotated
    /// `origin .. origin + size` leaves the four corners of the screen uncovered. At
    /// bearing zero it is exactly `viewport_origin() .. + (width, height)`.
    pub fn viewport_bounds(&self) -> (WorldPx, WorldPx) {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        // The half-extents of the rotated rectangle's bounding box: the support function
        // of a box under a rotation, which is why the terms are absolute values rather
        // than signed — the widest corner is on a different side for each quadrant.
        let extent_x = cos.abs() * half_w + sin.abs() * half_h;
        let extent_y = sin.abs() * half_w + cos.abs() * half_h;
        (
            WorldPx {
                x: center.x - extent_x,
                y: center.y - extent_y,
            },
            WorldPx {
                x: center.x + extent_x,
                y: center.y + extent_y,
            },
        )
    }

    /// The screen size of one tile at zoom level `z`, in logical px.
    pub fn tile_span_dp(&self, z: u8) -> f64 {
        TILE_SIZE * 2f64.powf(self.zoom - z as f64)
    }

    /// The screen size of one tile at zoom `z` in **device** px, which is what turns a
    /// pixel line width into tile-local units in the vertex shader.
    pub fn tile_span_px(&self, z: u8) -> f32 {
        (self.tile_span_dp(z) * self.density as f64) as f32
    }

    /// Column-major 4x4 taking tile-local 0..1 to Vulkan clip space.
    ///
    /// ```text
    /// clip.x =  2 * (tile_origin.x + u * span - viewport_origin.x) / width  - 1
    /// clip.y = -1 + 2 * (tile_origin.y + v * span - viewport_origin.y) / height
    /// ```
    ///
    /// Note the sign of y. Vulkan's clip space has **y down** — unlike OpenGL, and
    /// unlike WebGPU — and Mercator y also grows downward, so the two agree and no
    /// flip is needed. Adding one anyway mirrors the whole map vertically, which is
    /// easy to miss on a symmetric city and obvious on a coastline.
    pub fn tile_to_clip(&self, z: u8, x: u32, y: u32) -> [f32; 16] {
        if crate::camera::globe_active(self) {
            return self.globe_tile_to_clip(z, x, y);
        }
        let span = self.tile_span_dp(z);
        self.world_quad_to_clip(
            WorldPx {
                x: x as f64 * span,
                y: y as f64 * span,
            },
            span,
        )
    }

    /// Column-major 4x4 taking a local 0..1 square to Vulkan clip space, where the
    /// square's `(0, 0)` corner sits at `origin` world px and its side is `span` world px
    /// at this camera's zoom.
    ///
    /// [`tile_to_clip`](Self::tile_to_clip) is the case where the square is a tile. A
    /// geographic overlay — a route line — is the case where it is not: its geometry is
    /// normalised into its own bounding square rather than into a tile, because it is not
    /// tile-bound and never goes through tile decode. Sharing the derivation is what keeps
    /// the overlay glued to the same ground as the basemap under it.
    ///
    /// Bearing enters here and only here (plus its screen-anchored sibling below), as the
    /// rotation that takes a world-px offset from the camera centre to a screen-px offset:
    ///
    /// ```text
    /// screen = ( cos*dx + sin*dy,
    ///           -sin*dx + cos*dy )
    /// ```
    ///
    /// with `dx`/`dy` measured from the centre. Its sign is fixed by what heading-up
    /// means: at a bearing of 90 the camera faces east, so east has to come out pointing
    /// up the screen and north pointing left. Getting it backwards mirrors the turn.
    pub fn world_quad_to_clip(&self, origin: WorldPx, span: f64) -> [f32; 16] {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        // Measured from the camera centre, not from a viewport corner: a rotated viewport
        // has no world-space corner to measure from, and the centre is the fixed point of
        // the rotation.
        let dx = origin.x - center.x;
        let dy = origin.y - center.y;
        if self.pitch_deg == 0.0 {
            let kx = 2.0 / self.width_dp as f64;
            let ky = 2.0 / self.height_dp as f64;

            return [
                (kx * cos * span) as f32,
                (ky * -sin * span) as f32,
                0.0,
                0.0, //
                (kx * sin * span) as f32,
                (ky * cos * span) as f32,
                0.0,
                0.0, //
                0.0,
                0.0,
                1.0,
                0.0, //
                (kx * (cos * dx + sin * dy)) as f32,
                (ky * (-sin * dx + cos * dy)) as f32,
                0.0,
                1.0,
            ];
        }
        // Tilted: the same bearing-rotated screen offset as above, but kept as world px (Dp)
        // rather than pre-scaled to clip, and fed through the perspective divide. `a`/`b` are
        // the `u`/`v`/constant terms of a tile-local point's screen offset `sx`/`sy`.
        let a = [cos * span, sin * span, cos * dx + sin * dy];
        let b = [-sin * span, cos * span, -sin * dx + cos * dy];
        self.perspective_plane(a, b)
    }

    /// Column-major 4x4 taking a quad's local −1..1 coordinates to Vulkan clip space,
    /// for a quad of `radius_dp` centred on `lon`/`lat`.
    ///
    /// The screen-anchored sibling of [`tile_to_clip`](Self::tile_to_clip). Same
    /// derivation, but the extent is a fixed number of Dp instead of a tile span, so the
    /// quad keeps its screen size as the camera zooms while staying glued to its ground
    /// position. That is what an overlay wants and what a tile address cannot express:
    /// the alternative is to find the tile containing the point and borrow its matrix,
    /// which works for a POI icon because a POI *is* tile data, and is a fiction for
    /// anything that is not.
    ///
    /// The y-sign note on [`tile_to_clip`](Self::tile_to_clip) applies here too: Vulkan
    /// clip y and Mercator y both point down, so there is no flip.
    ///
    /// The quad's own axes **rotate with the map** under a bearing, rather than staying
    /// screen-aligned. That is what keeps the puck's bearing cone honest: the cone's angle
    /// is a geographic heading resolved inside the shader against the quad's local frame,
    /// so a frame that turns with the map leaves a north-pointing cone pointing north on
    /// the ground and up the screen when the camera faces north. Freezing the axes to the
    /// screen instead would leave the cone pointing at the top of a heading-up display no
    /// matter which way the car was going. The puck's dot and rim are circles, so the
    /// choice is invisible to everything else the quad draws.
    pub fn screen_quad_to_clip(&self, lon: f64, lat: f64, radius_dp: f64) -> [f32; 16] {
        let anchor = project(lon, lat, self.zoom);
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        // A point is wherever its nearest copy is: a pin just across the antimeridian is a
        // few Dp east, not a world-width west.
        let size = crate::camera::world_size(self.zoom);
        let mut dx = anchor.x - center.x;
        if dx > size / 2.0 {
            dx -= size;
        } else if dx < -size / 2.0 {
            dx += size;
        }
        let dy = anchor.y - center.y;
        if self.pitch_deg == 0.0 {
            let kx = 2.0 / self.width_dp as f64;
            let ky = 2.0 / self.height_dp as f64;

            return [
                (kx * cos * radius_dp) as f32,
                (ky * -sin * radius_dp) as f32,
                0.0,
                0.0, //
                (kx * sin * radius_dp) as f32,
                (ky * cos * radius_dp) as f32,
                0.0,
                0.0, //
                0.0,
                0.0,
                1.0,
                0.0, //
                (kx * (cos * dx + sin * dy)) as f32,
                (ky * (-sin * dx + cos * dy)) as f32,
                0.0,
                1.0,
            ];
        }
        // Tilted: project the anchor's ground point through the same perspective, then hang a
        // screen-aligned quad of fixed Dp size off it. The corners offset in clip by the
        // anchor's own `w`, so the perspective divide leaves a constant *screen* size — the quad
        // stays upright and keeps its radius rather than being smeared along the ground. Its
        // local axes still turn with the bearing, so the puck's cone points the right way.
        let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
        let p = self.perspective();
        let sx = cos * dx + sin * dy;
        let sy = -sin * dx + cos * dy;
        let aw = p.d - psin * sy;
        let ax = p.fx * sx;
        let ay = p.fy * pcos * sy;
        let az = p.depth_a * aw - p.depth_b;
        let rx = radius_dp / (self.width_dp as f64 / 2.0) * aw;
        let ry = radius_dp / (self.height_dp as f64 / 2.0) * aw;
        [
            (cos * rx) as f32,
            (-sin * ry) as f32,
            0.0,
            0.0, //
            (sin * rx) as f32,
            (cos * ry) as f32,
            0.0,
            0.0, //
            0.0,
            0.0,
            0.0,
            0.0, //
            ax as f32,
            ay as f32,
            az as f32,
            aw as f32,
        ]
    }

    /// The tilt-dependent perspective constants for this camera. See [`Perspective`].
    fn perspective(&self) -> Perspective {
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        // MapLibre-like: the centre sits 1.5 viewport-heights from the eye. `d` cancels at
        // pitch 0, so this only sets the foreshortening strength.
        let d = 1.5 * self.height_dp as f64;
        let n = 0.1 * d;
        let f = 10.0 * d;
        Perspective {
            fx: d / half_w,
            fy: d / half_h,
            d,
            depth_a: f / (f - n),
            depth_b: f * n / (f - n),
        }
    }

    /// Build the perspective clip matrix from the screen-flat plane coefficients.
    ///
    /// `a = [a_u, a_v, a_0]` and `b = [b_u, b_v, b_0]` give the bearing-rotated screen offset of
    /// a tile-local point `(u, v)` from the camera centre, in world px (Dp): `sx = a_u*u + a_v*v
    /// + a_0`, `sy = b_u*u + b_v*v + b_0`. The matrix maps `(u, v, height, 1)`; the third input
    /// is a world-px height above the ground plane (0 for every flat 2D layer, a real height for
    /// buildings/terrain).
    ///
    /// # Camera model (the seam WS-G extends to a heightfield)
    ///
    /// The ground is a plane at camera-to-centre distance `d`, tilted back by `pitch`. A point at
    /// screen-flat offset `(sx, sy)` and height `hh` sits in camera space at
    /// ```text
    /// Xc = sx
    /// Yc = -sy*cos + hh*sin
    /// Zc = -d + sy*sin + hh*cos      (w = -Zc, the perspective divisor)
    /// ```
    /// with `sy > 0` (lower on screen) nearer the eye. With `fx = d/half_w`, `fy = d/half_h` the
    /// projection reduces, at `pitch == 0` and `hh == 0`, term-for-term to the ortho matrix — the
    /// reason pitch 0 takes the fast path and this is only ever built when tilted. WS-G replaces
    /// the flat `hh` here (and the single plane solve in [`screen_to_world`](Self::screen_to_world))
    /// with a DEM sample; keep the forward and inverse in step.
    fn perspective_plane(&self, a: [f64; 3], b: [f64; 3]) -> [f32; 16] {
        let (sin, cos) = self.pitch_deg.to_radians().sin_cos();
        let Perspective {
            fx,
            fy,
            d,
            depth_a,
            depth_b,
        } = self.perspective();
        let [au, av, a0] = a;
        let [bu, bv, b0] = b;
        // Column-major: the u, v, height and constant columns of (Xclip, Yclip, Zclip, Wclip).
        [
            (fx * au) as f32,
            (fy * cos * bu) as f32,
            (depth_a * (-sin * bu)) as f32,
            (-sin * bu) as f32, //
            (fx * av) as f32,
            (fy * cos * bv) as f32,
            (depth_a * (-sin * bv)) as f32,
            (-sin * bv) as f32, //
            0.0,
            (fy * -sin) as f32,
            (depth_a * -cos) as f32,
            (-cos) as f32, //
            (fx * a0) as f32,
            (fy * cos * b0) as f32,
            (depth_a * (d - sin * b0) - depth_b) as f32,
            (d - sin * b0) as f32,
        ]
    }

    /// The ground world-px under a screen point (Dp from the viewport top-left), by intersecting
    /// the eye ray with the flat ground plane.
    ///
    /// `None` when the point is at or above the horizon — impossible on-screen while
    /// `pitch_deg <= `[`PITCH_MAX_DEG`], which is the cap's whole purpose. This is the flat-plane
    /// seam WS-G extends to ray/heightfield: swap the single plane solve for a march against the
    /// DEM. It is the exact inverse of [`perspective_plane`](Self::perspective_plane)'s forward
    /// projection; keep the two in step.
    pub fn screen_to_world(&self, screen_x_dp: f64, screen_y_dp: f64) -> Option<WorldPx> {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        let sx;
        let sy;
        if self.pitch_deg == 0.0 {
            sx = screen_x_dp - half_w;
            sy = screen_y_dp - half_h;
        } else {
            let p = self.perspective();
            let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
            let ndc_x = (screen_x_dp - half_w) / half_w;
            let ndc_y = (screen_y_dp - half_h) / half_h;
            // Invert clip.y = fy*cos*sy / (d - sin*sy) for sy, then clip.x for sx.
            let denom = p.fy * pcos + ndc_y * psin;
            if denom <= 0.0 {
                return None; // at or above the horizon: the ray never meets the ground.
            }
            sy = ndc_y * p.d / denom;
            let w = p.d - psin * sy;
            sx = ndc_x * w / p.fx;
        }
        // Inverse bearing rotation: screen-flat offset back to a world-px offset from centre.
        Some(WorldPx {
            x: center.x + cos * sx - sin * sy,
            y: center.y + sin * sx + cos * sy,
        })
    }

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
        let gap = |t: f64| ray_height(t) - height_at(world_at(t));

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

    /// The screen point (Dp from the viewport top-left) a ground world-px projects to, or `None`
    /// when it falls behind the eye. The forward twin of [`screen_to_world`](Self::screen_to_world);
    /// exists mainly so the round-trip is testable and the Kotlin `Projection` can mirror it.
    pub fn world_to_screen(&self, world: WorldPx) -> Option<(f64, f64)> {
        let center = project(self.center_lon, self.center_lat, self.zoom);
        let (cos, sin) = self.rotation();
        let half_w = self.width_dp as f64 / 2.0;
        let half_h = self.height_dp as f64 / 2.0;
        let dxw = world.x - center.x;
        let dyw = world.y - center.y;
        let sx = cos * dxw + sin * dyw;
        let sy = -sin * dxw + cos * dyw;
        if self.pitch_deg == 0.0 {
            return Some((half_w + sx, half_h + sy));
        }
        let p = self.perspective();
        let (psin, pcos) = self.pitch_deg.to_radians().sin_cos();
        let w = p.d - psin * sy;
        if w <= 0.0 {
            return None;
        }
        Some((
            half_w + (p.fx * sx / w) * half_w,
            half_h + (p.fy * pcos * sy / w) * half_h,
        ))
    }

    /// Lon/lat under a screen point (Dp from the viewport top-left), tilt-aware. See
    /// [`screen_to_world`](Self::screen_to_world).
    pub fn screen_to_lonlat(&self, screen_x_dp: f64, screen_y_dp: f64) -> Option<(f64, f64)> {
        self.screen_to_world(screen_x_dp, screen_y_dp)
            .map(|w| unproject(w.x, w.y, self.zoom))
    }
}
