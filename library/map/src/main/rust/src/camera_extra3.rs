//! Camera tests part 2: bearing, pitch and terrain-unproject tests.
//!
//! Globe tests live in `camera_globe_tests` (file-length split); this file holds
//! the bearing/pitch half.
#[cfg(test)]
mod tests {
    use crate::camera::*;
    use crate::camera_extra2::tests::{camera, transform, transform4};

    #[test]
    fn rotation_preserves_ground_distance_on_a_square_viewport() {
        // A rotation must not scale: two points a tile apart have to stay a tile apart on
        // screen whichever way the camera faces, or roads change width as the car turns.
        let span = camera(10.0).tile_span_dp(10);
        let centre = project(0.0, 0.0, 10.0);
        let length = |bearing: f64| {
            let c = Camera {
                bearing_deg: bearing,
                ..camera(10.0)
            };
            let m = c.world_quad_to_clip(
                WorldPx {
                    x: centre.x + span,
                    y: centre.y,
                },
                span,
            );
            let (x, y) = transform(&m, 0.0, 0.0);
            (x * x + y * y).sqrt()
        };
        let north_up = length(0.0);
        for bearing in [17.0, 45.0, 90.0, 213.0] {
            assert!(
                (length(bearing) - north_up).abs() < 1e-5,
                "bearing {bearing} scaled the map: {} vs {north_up}",
                length(bearing),
            );
        }
    }

    #[test]
    fn the_viewport_bounds_are_the_plain_viewport_when_north_up() {
        let camera = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            ..camera(14.0)
        };
        let (min, max) = camera.viewport_bounds();
        let origin = camera.viewport_origin();
        assert!((min.x - origin.x).abs() < 1e-9, "{} vs {}", min.x, origin.x);
        assert!((min.y - origin.y).abs() < 1e-9);
        assert!((max.x - (origin.x + camera.width_dp as f64)).abs() < 1e-9);
        assert!((max.y - (origin.y + camera.height_dp as f64)).abs() < 1e-9);
    }

    #[test]
    fn a_rotated_viewport_covers_more_ground_than_an_axis_aligned_one() {
        // The corners of a rotated screen reach further out in world space than the
        // screen's own width and height. This is what tile selection has to be derived
        // from; deriving it from the unrotated box leaves the corners of the display
        // permanently empty.
        let square = Camera {
            bearing_deg: 45.0,
            ..camera(14.0)
        };
        let (min, max) = square.viewport_bounds();
        let across = max.x - min.x;
        let expected = 512.0 * 2f64.sqrt();
        assert!(
            (across - expected).abs() < 1e-6,
            "{across} should be {expected}"
        );

        // Every corner of the rotated viewport really is inside the box.
        let centre = project(square.center_lon, square.center_lat, square.zoom);
        let radians = 45f64.to_radians();
        for (sx, sy) in [
            (-256.0, -256.0),
            (256.0, -256.0),
            (256.0, 256.0),
            (-256.0, 256.0),
        ] {
            // Screen offset back to world: the inverse of the rotation the matrix applies.
            let wx: f64 = centre.x + radians.cos() * sx - radians.sin() * sy;
            let wy: f64 = centre.y + radians.sin() * sx + radians.cos() * sy;
            assert!(
                wx >= min.x - 1e-6 && wx <= max.x + 1e-6,
                "corner x {wx} outside the box"
            );
            assert!(
                wy >= min.y - 1e-6 && wy <= max.y + 1e-6,
                "corner y {wy} outside the box"
            );
        }
    }

    // --- pitch / perspective ------------------------------------------------

    #[test]
    fn an_explicit_zero_pitch_is_the_untilted_matrix_byte_for_byte() {
        // The regression guard for the whole flat/phone path: adding the pitch and time fields
        // must not perturb a single bit of the matrix a north-up, level camera produces.
        let level = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            ..camera(12.0)
        };
        let untilted = level.tile_to_clip(12, 654, 1583);
        assert_eq!(
            untilted,
            Camera {
                pitch_deg: 0.0,
                pivot_dp: 0.0,
                ..level
            }
            .tile_to_clip(12, 654, 1583)
        );
        // And the clock never reaches the matrix.
        assert_eq!(
            untilted,
            Camera {
                time_seconds: 98765.0,
                ..level
            }
            .tile_to_clip(12, 654, 1583)
        );
        let quad = level.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
        assert_eq!(
            quad,
            Camera {
                pitch_deg: 0.0,
                pivot_dp: 0.0,
                ..level
            }
            .screen_quad_to_clip(-122.4194, 37.7749, 28.0)
        );
    }

    #[test]
    fn a_pitched_matrix_actually_tilts() {
        // Sanity that the pitched path is a *different* matrix, and a real perspective one:
        // its bottom row is no longer the ortho `(_, _, 0, 1)`, so a w-divide happens.
        let tilted = Camera {
            pitch_deg: 45.0,
            pivot_dp: 0.0,
            ..camera(12.0)
        }
        .tile_to_clip(12, 2048, 2048);
        assert!(
            tilted[15] != 1.0 || tilted[3] != 0.0 || tilted[7] != 0.0,
            "no perspective term"
        );
    }

    #[test]
    fn the_pitched_centre_stays_on_the_clip_origin() {
        // The fixed point of the tilt: whatever the pitch, the camera centre projects to clip 0
        // and sits in front of the eye.
        for pitch in [15.0, 30.0, 45.0, 60.0] {
            let cam = Camera {
                center_lon: -122.4194,
                center_lat: 37.7749,
                pitch_deg: pitch,
                pivot_dp: 0.0,
                ..camera(12.0)
            };
            let m = cam.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
            let (x, y, _, w) = transform4(&m, 0.0, 0.0, 0.0);
            assert!(w > 0.0, "pitch {pitch}: centre behind the eye (w {w})");
            assert!(
                (x / w).abs() < 1e-5 && (y / w).abs() < 1e-5,
                "pitch {pitch}: centre at {},{}",
                x / w,
                y / w
            );
        }
    }

    #[test]
    fn screen_to_world_at_pitch_zero_is_the_plain_inverse() {
        let cam = Camera {
            center_lon: 10.0,
            center_lat: 20.0,
            ..camera(8.0)
        };
        let center = project(10.0, 20.0, 8.0);
        let w = cam.screen_to_world(256.0 + 30.0, 256.0 - 10.0).unwrap();
        assert!((w.x - (center.x + 30.0)).abs() < 1e-9, "x {}", w.x);
        assert!((w.y - (center.y - 10.0)).abs() < 1e-9, "y {}", w.y);
    }

    #[test]
    fn the_screen_centre_unprojects_to_the_camera_centre_at_any_pitch() {
        for pitch in [0.0, 20.0, 45.0, 60.0] {
            let cam = Camera {
                center_lon: -122.4,
                center_lat: 37.7,
                pitch_deg: pitch,
                pivot_dp: 0.0,
                ..camera(12.0)
            };
            let center = project(-122.4, 37.7, 12.0);
            let w = cam.screen_to_world(256.0, 256.0).unwrap();
            assert!(
                (w.x - center.x).abs() < 1e-6 && (w.y - center.y).abs() < 1e-6,
                "pitch {pitch}"
            );
        }
    }

    #[test]
    fn tilt_foreshortens_the_top_of_the_screen() {
        // The same screen distance above and below centre maps to *more* ground above, because
        // the top of a tilted view recedes toward the horizon. At pitch 0 the two are equal.
        let cam = Camera {
            pitch_deg: 45.0,
            pivot_dp: 0.0,
            ..camera(12.0)
        };
        let center = project(cam.center_lon, cam.center_lat, 12.0);
        let above = cam.screen_to_world(256.0, 256.0 - 100.0).unwrap();
        let below = cam.screen_to_world(256.0, 256.0 + 100.0).unwrap();
        let up = (center.y - above.y).abs();
        let down = (below.y - center.y).abs();
        assert!(up > down * 1.2, "top should recede: up {up} vs down {down}");
    }

    // --- ray/heightfield unproject (WS-G) -----------------------------------

    #[test]
    fn over_terrain_at_pitch_zero_is_the_flat_unproject() {
        // Overhead, height never touches x/y, so a tap lands on exactly the flat-plane point
        // whatever the relief — the pitch-0 map is unchanged.
        let cam = Camera {
            center_lon: 10.0,
            center_lat: 20.0,
            ..camera(12.0)
        };
        for &(x, y) in &[(256.0, 256.0), (120.0, 40.0), (400.0, 500.0)] {
            let flat = cam.screen_to_world(x, y).unwrap();
            let over = cam.screen_to_world_over_terrain(x, y, |_| 5000.0).unwrap();
            assert!((flat.x - over.x).abs() < 1e-9 && (flat.y - over.y).abs() < 1e-9);
        }
    }

    #[test]
    fn flat_zero_terrain_matches_the_plane() {
        // A terrain everywhere at sea level is the flat plane, so the heightfield hit must equal
        // the plane hit under tilt too.
        let cam = Camera {
            center_lon: -122.4,
            center_lat: 37.7,
            pitch_deg: 50.0,
            pivot_dp: 0.0,
            ..camera(13.0)
        };
        for &(x, y) in &[(256.0, 120.0), (256.0, 256.0), (300.0, 400.0)] {
            let flat = cam.screen_to_world(x, y).unwrap();
            let over = cam.screen_to_world_over_terrain(x, y, |_| 0.0).unwrap();
            assert!(
                (flat.x - over.x).abs() < 1e-4 && (flat.y - over.y).abs() < 1e-4,
                "zero terrain must match the plane: {flat:?} vs {over:?}",
            );
        }
    }

    #[test]
    fn higher_ground_is_hit_nearer_the_camera() {
        // A tap toward the top of a tilted screen looks up-map toward the horizon. Raising the
        // terrain makes the ray strike the hillside sooner, so the hit moves back toward the
        // camera centre (a smaller up-map distance) — the essence of hitting the displaced surface.
        let cam = Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            pitch_deg: 55.0,
            pivot_dp: 0.0,
            ..camera(14.0)
        };
        let pixel = (256.0, 80.0); // above the centre: up-map, toward the horizon
        let flat = cam.screen_to_world(pixel.0, pixel.1).unwrap();
        // World-px heights well under the eye height (~d·cos = 766 px here), as real ~30 m terrain
        // at z14 is (about 0.2 world-px per metre).
        let low = cam
            .screen_to_world_over_terrain(pixel.0, pixel.1, |_| 30.0)
            .unwrap();
        let high = cam
            .screen_to_world_over_terrain(pixel.0, pixel.1, |_| 90.0)
            .unwrap();
        // Up-map is toward smaller world y here (north), so a nearer hit has a larger y.
        assert!(
            low.y > flat.y,
            "raised terrain is hit nearer the camera than the flat plane"
        );
        assert!(high.y > low.y, "higher terrain is hit nearer still");
    }

    #[test]
    fn the_hit_lies_on_the_eye_ray_and_on_the_surface() {
        // The full guarantee for constant-height terrain: the returned point is (a) on the terrain
        // surface — its height is the sampled one — and (b) on the pixel's eye ray, i.e. collinear
        // with the eye and the flat-plane hit in the screen-flat (sx, sy, height) frame. Both are
        // checked against the documented camera model rather than the implementation.
        let height_dp = 891.0;
        let cam = Camera {
            center_lon: 2.35,
            center_lat: 48.85,
            pitch_deg: 45.0,
            pivot_dp: 0.0,
            width_dp: 411.0,
            height_dp,
            ..camera(13.0)
        };
        let center = project(2.35, 48.85, 13.0);
        let (cos, sin) = cam.rotation();
        // The eye in the (sx, sy, height) frame: (0, d·sin, d·cos), d = 1.5 viewport heights.
        let d = 1.5 * height_dp as f64;
        let (psin, pcos) = cam.pitch_deg.to_radians().sin_cos();
        let eye = (0.0, d * psin, d * pcos);

        let h0 = 300.0; // world-px, constant
        let pixel = (256.0, 150.0);
        let plane = cam.screen_to_world(pixel.0, pixel.1).unwrap();
        let hit = cam
            .screen_to_world_over_terrain(pixel.0, pixel.1, |_| h0)
            .unwrap();

        // Screen-flat offsets of the plane hit and the terrain hit.
        let sflat = |w: WorldPx| {
            let dx = w.x - center.x;
            let dy = w.y - center.y;
            (cos * dx + sin * dy, -sin * dx + cos * dy)
        };
        let (px, py) = sflat(plane); // height 0
        let (hx, hy) = sflat(hit); // height h0

        // Collinearity: hit = eye + t·(plane − eye) for one t across all three coordinates. Solve
        // t from height, then confirm x and y agree.
        let t = (h0 - eye.2) / (0.0 - eye.2);
        let want_x = eye.0 + t * (px - eye.0);
        let want_y = eye.1 + t * (py - eye.1);
        assert!(
            (hx - want_x).abs() < 1e-3,
            "hit off the ray in sx: {hx} vs {want_x}"
        );
        assert!(
            (hy - want_y).abs() < 1e-3,
            "hit off the ray in sy: {hy} vs {want_y}"
        );
    }

    #[test]
    fn a_ramp_surface_is_hit_where_the_ray_meets_it() {
        // A sloped terrain (height rising with world y): the returned hit's own sampled height must
        // match the ray height there, so the point really sits on the surface rather than the plane.
        let height_dp = 891.0;
        let cam = Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            pitch_deg: 50.0,
            pivot_dp: 0.0,
            width_dp: 411.0,
            height_dp,
            ..camera(14.0)
        };
        let center = project(0.0, 0.0, 14.0);
        let (cos, sin) = cam.rotation();
        let d = 1.5 * height_dp as f64;
        let (psin, pcos) = cam.pitch_deg.to_radians().sin_cos();
        let eye = (0.0, d * psin, d * pcos);

        // Height rises 0.2 world-px per world-px north of the centre (a gentle ramp).
        let ramp = |w: WorldPx| (center.y - w.y).max(0.0) * 0.2;
        let pixel = (256.0, 100.0);
        let hit = cam
            .screen_to_world_over_terrain(pixel.0, pixel.1, ramp)
            .unwrap();

        // The ray height at the hit (from collinearity in the screen-flat frame) must equal the
        // terrain height sampled there.
        let dx = hit.x - center.x;
        let dy = hit.y - center.y;
        let (hsx, hsy) = (cos * dx + sin * dy, -sin * dx + cos * dy);
        // Recover t from whichever ray component moves most, then the ray height.
        let plane = cam.screen_to_world(pixel.0, pixel.1).unwrap();
        let pdx = plane.x - center.x;
        let pdy = plane.y - center.y;
        let (psx, psy) = (cos * pdx + sin * pdy, -sin * pdx + cos * pdy);
        let t = if (psy - eye.1).abs() > (psx - eye.0).abs() {
            (hsy - eye.1) / (psy - eye.1)
        } else {
            (hsx - eye.0) / (psx - eye.0)
        };
        let ray_h = eye.2 + t * (0.0 - eye.2);
        assert!(
            (ray_h - ramp(hit)).abs() < 1.0,
            "the hit must sit on the ramp: ray height {ray_h} vs terrain {}",
            ramp(hit),
        );
        // And it is not merely the flat answer: the ramp pushes the hit off the plane.
        assert!(
            (hit.y - plane.y).abs() > 1e-3,
            "the ramp must move the hit off the flat plane"
        );
    }
}
