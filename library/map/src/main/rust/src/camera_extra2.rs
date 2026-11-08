//! Camera tests part 1: helpers and projection/matrix tests.
#[cfg(test)]
pub(crate) mod tests {
    use crate::camera::*;

    pub(crate) fn camera(zoom: f64) -> Camera {
        Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            zoom,
            width_dp: 512.0,
            height_dp: 512.0,
            density: 1.0,
            bearing_deg: 0.0,
            pitch_deg: 0.0,
            pivot_dp: 0.0,
            time_seconds: 0.0,
            globe: false,
            moon: false,
        }
    }

    /// Apply a column-major 4x4 to a 2D point, as the vertex shader does.
    pub(crate) fn transform(m: &[f32; 16], u: f32, v: f32) -> (f32, f32) {
        (m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])
    }

    /// Full 4-vector transform of `(x, y, z, 1)` — needed on the pitched path, where the
    /// perspective divide by `w` is not the identity `transform` assumes.
    pub(crate) fn transform4(m: &[f32; 16], x: f32, y: f32, z: f32) -> (f32, f32, f32, f32) {
        (
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14],
            m[3] * x + m[7] * y + m[11] * z + m[15],
        )
    }

    #[test]
    fn the_world_is_512_dp_per_tile() {
        // MapLibre's convention, and the one the archives are authored on: tile
        // addressing is the plain floor of the camera zoom.
        assert_eq!(world_size(0.0), 512.0);
        assert_eq!(world_size(1.0), 1024.0);
        assert_eq!(world_size(14.0), 512.0 * 16384.0);
    }

    #[test]
    fn project_and_unproject_round_trip() {
        for zoom in [0.0, 5.0, 11.0, 14.0, 18.0] {
            for &(lon, lat) in &[
                (0.0, 0.0),
                (-122.4194, 37.7749),
                (151.2093, -33.8688),
                (2.3522, 48.8566),
            ] {
                let p = project(lon, lat, zoom);
                let (back_lon, back_lat) = unproject(p.x, p.y, zoom);
                assert!(
                    (back_lon - lon).abs() < 1e-9,
                    "lon at z{zoom}: {back_lon} vs {lon}"
                );
                assert!(
                    (back_lat - lat).abs() < 1e-9,
                    "lat at z{zoom}: {back_lat} vs {lat}"
                );
            }
        }
    }

    #[test]
    fn null_island_is_the_centre_of_the_world() {
        let p = project(0.0, 0.0, 0.0);
        assert!((p.x - TILE_SIZE / 2.0).abs() < 1e-9);
        assert!((p.y - TILE_SIZE / 2.0).abs() < 1e-9);
    }

    #[test]
    fn the_tile_containing_the_camera_covers_the_viewport_centre() {
        // At z1 centred on null island, the four tiles meet exactly at the centre of a
        // 1024 Dp viewport, so tile 0/0's bottom-right corner lands at clip (0, 0).
        let camera = Camera {
            width_dp: 1024.0,
            height_dp: 1024.0,
            ..camera(1.0)
        };
        let m = camera.tile_to_clip(1, 0, 0);
        let (x, y) = transform(&m, 1.0, 1.0);
        assert!(x.abs() < 1e-5, "x {x}");
        assert!(y.abs() < 1e-5, "y {y}");
    }

    /// Task-1 camera-scale parity: the viewport centre always maps to clip
    /// origin through the shared matrix, whatever the tile grid. Pins the
    /// matrix half of the anchor contract on the 512 grid.
    #[test]
    fn an_anchor_at_viewport_centre_projects_to_clip_origin() {
        // z10 centred on SF: the SF tile-local centre must land at clip (0,0)
        // through tile_to_clip.
        let camera = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom: 10.0,
            width_dp: 512.0,
            height_dp: 512.0,
            density: 1.0,
            bearing_deg: 0.0,
            pitch_deg: 0.0,
            pivot_dp: 0.0,
            time_seconds: 0.0,
            globe: false,
            moon: false,
        };
        // The tile containing SF at z10, and SF's tile-local position in it.
        let world = project(-122.4194, 37.7749, 10.0);
        let span = camera.tile_span_dp(10);
        let tx = (world.x / span).floor() as u32;
        let ty = (world.y / span).floor() as u32;
        let u = (world.x - tx as f64 * span) / span;
        let v = (world.y - ty as f64 * span) / span;
        assert!((0.0..=1.0).contains(&u) && (0.0..=1.0).contains(&v));
        let m = camera.tile_to_clip(10, tx, ty);
        let (cx, cy) = transform(&m, u as f32, v as f32);
        assert!(
            cx.abs() < 1e-4,
            "SF anchor clip x {cx} (tile {tx},{ty} local {u:.4},{v:.4})"
        );
        assert!(
            cy.abs() < 1e-4,
            "SF anchor clip y {cy} (tile {tx},{ty} local {u:.4},{v:.4})"
        );
    }

    #[test]
    fn clip_space_y_grows_downward_as_vulkan_and_mercator_both_do() {
        // The tile's top edge must land at a *smaller* clip y than its bottom edge. A
        // flip here mirrors the map.
        let camera = camera(1.0);
        let m = camera.tile_to_clip(1, 0, 0);
        let (_, top) = transform(&m, 0.0, 0.0);
        let (_, bottom) = transform(&m, 0.0, 1.0);
        assert!(
            top < bottom,
            "top {top} must be above bottom {bottom} in clip space"
        );
    }

    #[test]
    fn a_full_screen_tile_fills_clip_space() {
        // At z0 with a 512 Dp viewport the single tile is exactly the screen, so its
        // corners are the corners of clip space.
        let camera = Camera {
            width_dp: 512.0,
            height_dp: 512.0,
            ..camera(0.0)
        };
        let m = camera.tile_to_clip(0, 0, 0);
        let (x0, y0) = transform(&m, 0.0, 0.0);
        let (x1, y1) = transform(&m, 1.0, 1.0);
        assert!((x0 - -1.0).abs() < 1e-5, "left {x0}");
        assert!((y0 - -1.0).abs() < 1e-5, "top {y0}");
        assert!((x1 - 1.0).abs() < 1e-5, "right {x1}");
        assert!((y1 - 1.0).abs() < 1e-5, "bottom {y1}");
    }

    #[test]
    fn adjacent_tiles_share_an_edge_with_no_gap() {
        // A seam here is a visible hairline between every pair of tiles.
        let camera = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            ..camera(12.0)
        };
        let left = camera.tile_to_clip(12, 654, 1583);
        let right = camera.tile_to_clip(12, 655, 1583);
        let (left_edge, _) = transform(&left, 1.0, 0.0);
        let (right_edge, _) = transform(&right, 0.0, 0.0);
        assert!(
            (left_edge - right_edge).abs() < 1e-5,
            "{left_edge} vs {right_edge}"
        );
    }

    #[test]
    fn overzoom_scales_a_tile_up_rather_than_leaving_a_hole() {
        // The archive stops at z16 and users keep zooming, so a z16 tile drawn at z19
        // must be 8x its normal size.
        let camera = camera(19.0);
        assert!((camera.tile_span_dp(16) - 512.0 * 8.0).abs() < 1e-9);
    }

    #[test]
    fn density_only_affects_the_pixel_span() {
        // tile_span_dp is a logical measurement and must not move with density;
        // tile_span_px is the only thing that scales, because it feeds a pixel width.
        let one = Camera {
            density: 1.0,
            ..camera(14.0)
        };
        let three = Camera {
            density: 3.0,
            ..camera(14.0)
        };
        assert_eq!(one.tile_span_dp(14), three.tile_span_dp(14));
        assert!((three.tile_span_px(14) / one.tile_span_px(14) - 3.0).abs() < 1e-5);
    }

    #[test]
    fn latitude_is_clamped_at_the_mercator_limit_rather_than_returning_infinity() {
        // Mercator y goes to infinity at the poles. The clamp puts +-90 exactly on the
        // top and bottom edges of the world, up to floating-point slack — so the
        // tolerance is one world pixel rather than zero.
        let size = world_size(4.0);
        for lat in [90.0, -90.0, 89.9, -89.9] {
            let p = project(0.0, lat, 4.0);
            assert!(p.y.is_finite(), "y at lat {lat} is {}", p.y);
            assert!(
                p.y >= -1.0 && p.y <= size + 1.0,
                "y at lat {lat} is {}, off the map",
                p.y
            );
        }
    }

    #[test]
    fn a_screen_quad_on_the_camera_centre_lands_on_the_clip_origin() {
        // The puck's whole point is being glued to a ground position, and the camera
        // centre is the one position whose clip coordinate is known without arithmetic.
        let camera = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            ..camera(14.0)
        };
        let m = camera.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
        let (x, y) = transform(&m, 0.0, 0.0);
        assert!(x.abs() < 1e-5, "x {x}");
        assert!(y.abs() < 1e-5, "y {y}");
    }

    #[test]
    fn a_screen_quad_keeps_its_dp_size_across_zooms_and_grows_with_the_viewport() {
        // A tile quad doubles on screen every zoom; this one must not, or the puck would
        // swell into a blue disc the size of a city block at z18.
        let close = Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            ..camera(18.0)
        };
        let far = Camera {
            center_lon: 0.0,
            center_lat: 0.0,
            ..camera(4.0)
        };
        let (near_x, _) = transform(&close.screen_quad_to_clip(0.0, 0.0, 28.0), 1.0, 0.0);
        let (wide_x, _) = transform(&far.screen_quad_to_clip(0.0, 0.0, 28.0), 1.0, 0.0);
        assert!((near_x - wide_x).abs() < 1e-6, "{near_x} vs {wide_x}");
        // 28 Dp of a 512 Dp viewport is 28/256 of the half-width of clip space.
        assert!((near_x - 28.0 / 256.0).abs() < 1e-5, "{near_x}");
    }

    #[test]
    fn a_screen_quad_is_the_same_size_at_every_density() {
        // The radius is Dp, like `tile_span_dp`. Density enters only where a Dp becomes a
        // device pixel, which for the puck is the shader's radii — not this matrix.
        let one = Camera {
            density: 1.0,
            ..camera(14.0)
        };
        let three = Camera {
            density: 3.0,
            ..camera(14.0)
        };
        assert_eq!(
            one.screen_quad_to_clip(0.0, 0.0, 28.0),
            three.screen_quad_to_clip(0.0, 0.0, 28.0)
        );
    }

    #[test]
    fn a_screen_quad_far_off_screen_falls_outside_the_clip_cube() {
        // A fix taken in another country must not smear a puck across the edge of the
        // viewport: the whole quad has to clip out.
        let camera = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            ..camera(14.0)
        };
        let m = camera.screen_quad_to_clip(2.3522, 48.8566, 28.0);
        let (left, _) = transform(&m, -1.0, 0.0);
        let (right, _) = transform(&m, 1.0, 0.0);
        assert!(
            left > 1.0 && right > 1.0,
            "Paris at {left}..{right} should be off to the right"
        );
    }

    // --- bearing ------------------------------------------------------------

    #[test]
    fn a_zero_bearing_leaves_every_matrix_exactly_as_it_was() {
        // The whole Compose/phone path runs at bearing zero, so this is the regression
        // guard for it: the rotated derivation must reduce term for term, not merely to
        // within a tolerance.
        let north_up = Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            ..camera(12.0)
        };
        let m = north_up.tile_to_clip(12, 654, 1583);
        assert_eq!(m[1], 0.0, "no shear into y");
        assert_eq!(m[4], 0.0, "no shear into x");
        let quad = north_up.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
        assert_eq!(quad[1], 0.0);
        assert_eq!(quad[4], 0.0);
    }

    #[test]
    fn a_bearing_of_ninety_puts_east_at_the_top_of_the_screen() {
        // The sign of the rotation, which is the one thing easy to get backwards: facing
        // east means east is up and north is to the left. A mirrored rotation sends the
        // car around every corner the wrong way.
        let heading_east = Camera {
            bearing_deg: 90.0,
            ..camera(10.0)
        };
        let centre = project(0.0, 0.0, 10.0);
        let span = heading_east.tile_span_dp(10);
        // A point one tile-span due east of the camera centre, addressed through the
        // shared quad matrix so this pins the same arithmetic every draw uses.
        let m = heading_east.world_quad_to_clip(
            WorldPx {
                x: centre.x + span,
                y: centre.y,
            },
            span,
        );
        let (x, y) = transform(&m, 0.0, 0.0);
        assert!(
            x.abs() < 1e-5,
            "east must sit on the vertical centreline, not at x {x}"
        );
        assert!(y < -1e-3, "east must be above the centre, not at y {y}");

        // And due north lands to the left.
        let north = heading_east.world_quad_to_clip(
            WorldPx {
                x: centre.x,
                y: centre.y - span,
            },
            span,
        );
        let (nx, ny) = transform(&north, 0.0, 0.0);
        assert!(nx < -1e-3, "north must be left of centre, not at x {nx}");
        assert!(
            ny.abs() < 1e-5,
            "north must sit on the horizontal centreline, not at y {ny}"
        );
    }

    #[test]
    fn the_camera_centre_stays_on_the_clip_origin_at_every_bearing() {
        // The rotation's fixed point. If it drifts, the map slides sideways as the car
        // turns instead of pivoting under the puck.
        for bearing in [0.0, 37.0, 90.0, 180.0, 271.5, -45.0] {
            let camera = Camera {
                center_lon: -122.4194,
                center_lat: 37.7749,
                bearing_deg: bearing,
                ..camera(14.0)
            };
            let m = camera.screen_quad_to_clip(-122.4194, 37.7749, 28.0);
            let (x, y) = transform(&m, 0.0, 0.0);
            assert!(x.abs() < 1e-5, "bearing {bearing}: x {x}");
            assert!(y.abs() < 1e-5, "bearing {bearing}: y {y}");
        }
    }

    /// Antimeridian and high-latitude round-trips plus the pole clamp and the doubling scale.
    ///
    /// The existing round-trip pins mid-latitude cities; this pins the edges the map hits:
    /// +-180 lon (the Pacific), +-85 lat (near the Mercator limit), and that a clamped pole
    /// projects exactly where the limit does rather than leaving the world.
    #[test]
    fn project_round_trips_across_the_antimeridian_and_clamped_poles() {
        for zoom in [0.0, 8.0, 14.0, 22.0] {
            for &(lon, lat) in &[
                (-179.99, 0.0),
                (179.99, 0.0),
                (-122.4194, 85.0),
                (139.6917, -85.0),
                (0.0, 85.051_128_78),
                (0.0, -85.051_128_78),
            ] {
                let p = project(lon, lat, zoom);
                assert!(p.x.is_finite() && p.y.is_finite(), "z{zoom} {lon},{lat}: {p:?}");
                let (back_lon, back_lat) = unproject(p.x, p.y, zoom);
                assert!((back_lon - lon).abs() < 1e-9, "z{zoom} lon: {back_lon} vs {lon}");
                assert!((back_lat - lat).abs() < 1e-9, "z{zoom} lat: {back_lat} vs {lat}");
            }
        }
        // The world edges: -180 is x 0 and +180 is x size, so the Pacific meets itself.
        let size = world_size(0.0);
        assert!((project(-180.0, 0.0, 0.0).x - 0.0).abs() < 1e-9);
        assert!((project(180.0, 0.0, 0.0).x - size).abs() < 1e-9);
        // Past the limit clamps exactly onto it rather than leaving the world.
        assert_eq!(project(0.0, 90.0, 4.0), project(0.0, 85.051_128_78, 4.0));
        assert_eq!(project(0.0, -90.0, 4.0), project(0.0, -85.051_128_78, 4.0));
        // Each zoom doubles the world.
        assert_eq!(world_size(1.0), world_size(0.0) * 2.0);
        assert_eq!(world_size(14.0), 512.0 * 16384.0);
    }
}
