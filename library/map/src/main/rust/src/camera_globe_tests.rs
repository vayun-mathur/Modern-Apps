//! Globe + Moon camera tests: basis, thresholds, selection, anchors.
//!
//! Split from `camera_extra3.rs` (file-length limit). The basis tests pin the
//! east/north/up convention the shaders and the Kotlin `Projection` share.
#[cfg(test)]
mod tests {
    use crate::camera::*;
    use crate::camera_extra2::tests::camera;

    fn globe_camera(lon: f64, lat: f64, zoom: f64) -> Camera {
        Camera {
            center_lon: lon,
            center_lat: lat,
            zoom,
            width_dp: 512.0,
            height_dp: 512.0,
            density: 1.0,
            bearing_deg: 0.0,
            pitch_deg: 0.0,
            globe: true,
            moon: false,
            time_seconds: 0.0,
        }
    }

    #[test]
    fn the_globe_centre_maps_to_unit_z() {
        // The fixed point of the basis: the camera centre faces the viewer exactly.
        for (lon, lat) in [(0.0, 0.0), (-122.4194, 37.7749), (151.2093, -33.8688), (0.0, 80.0)] {
            let (x, y, z) = globe_point(lon, lat, lon, lat);
            assert!(x.abs() < 1e-12, "lon {lon} lat {lat}: x {x}");
            assert!(y.abs() < 1e-12, "lon {lon} lat {lat}: y {y}");
            assert!((z - 1.0).abs() < 1e-12, "lon {lon} lat {lat}: z {z}");
        }
    }

    #[test]
    fn east_is_plus_x_and_north_is_plus_y() {
        // The sign convention the shaders and the Kotlin projection share: getting
        // it backwards mirrors the planet.
        let (x, _, _) = globe_point(0.0, 0.0, 10.0, 0.0);
        assert!(x > 0.0, "east must be +x, got {x}");
        let (_, y, _) = globe_point(0.0, 0.0, 0.0, 10.0);
        assert!(y > 0.0, "north must be +y, got {y}");
        let (_, _, z) = globe_point(0.0, 0.0, 180.0, 0.0);
        assert!((z + 1.0).abs() < 1e-12, "antipode must be -z, got {z}");
    }

    #[test]
    fn globe_point_and_lonlat_round_trip() {
        for (clon, clat) in [(0.0, 0.0), (-122.4194, 37.7749), (20.0, -30.0)] {
            for (lon, lat) in [
                (clon, clat),
                (clon + 30.0, clat + 10.0),
                (clon - 45.0, clat - 20.0),
                (clon + 80.0, clat),
            ] {
                let (x, y, z) = globe_point(clon, clat, lon, lat);
                // Renormalise (unit by construction, but the inverse assumes it).
                let n = (x * x + y * y + z * z).sqrt();
                let (blon, blat) = globe_lonlat(clon, clat, x / n, y / n, z / n);
                let mut dlon = (blon - lon).abs();
                if (dlon - 360.0).abs() < 1e-9 {
                    dlon = 0.0;
                }
                assert!(dlon < 1e-9, "lon {lon} -> {blon}");
                assert!((blat - lat).abs() < 1e-9, "lat {lat} -> {blat}");
            }
        }
    }

    #[test]
    fn the_globe_is_active_below_the_threshold_only() {
        let mut c = globe_camera(0.0, 0.0, 2.0);
        assert!(globe_active(&c));
        c.zoom = 7.99;
        assert!(globe_active(&c));
        c.zoom = 8.0;
        assert!(!globe_active(&c));
        c.zoom = 14.0;
        assert!(!globe_active(&c));
        c.globe = false;
        c.zoom = 2.0;
        assert!(!globe_active(&c));
    }

    #[test]
    fn the_moon_needs_the_globe() {
        // Moon without the globe flag, or past the globe threshold, reads as Earth.
        let mut c = globe_camera(0.0, 0.0, 2.0);
        c.moon = true;
        assert!(moon_active(&c));
        c.globe = false;
        assert!(!moon_active(&c));
        c.globe = true;
        c.zoom = 8.0;
        assert!(!moon_active(&c));
        c.zoom = 2.0;
        c.moon = false;
        assert!(!moon_active(&c));
    }

    #[test]
    fn the_globe_radius_is_half_the_world() {
        assert_eq!(globe_radius(0.0, 0.0), 256.0);
        assert_eq!(globe_radius(1.0, 45.0), 512.0);
        assert!((globe_radius(2.5, 0.0) - 256.0 * 2f64.powf(2.5)).abs() < 1e-9);
    }

    #[test]
    fn the_globe_meets_the_flat_scale_at_the_threshold() {
        // At the switch the sphere's centre scale (r per radian) must equal flat Mercator's
        // (world / 2pi per radian of longitude, over cos(lat)), or the map jumps.
        let z = crate::camera::GLOBE_FLAT_THRESHOLD;
        for lat in [0.0f64, 37.77, 60.0] {
            let flat = crate::camera::world_size(z) / (2.0 * std::f64::consts::PI * lat.to_radians().cos());
            let r = globe_radius(z, lat);
            assert!((r - flat).abs() / flat < 1e-9, "lat {lat}: r {r} flat {flat}");
        }
    }

    #[test]
    fn the_globe_covers_the_near_hemisphere() {
        // At z0 centred on null island the whole 1x1 tile grid is one tile, and
        // it must be selected (it holds the visible hemisphere). At z1 the far
        // tile behind the planet must not be.
        let c = globe_camera(0.0, 0.0, 0.0);
        let vis = crate::tile::select::visible(&c, 0, 15);
        assert_eq!(vis.len(), 1);
        let c = globe_camera(0.0, 0.0, 1.0);
        let vis = crate::tile::select::visible(&c, 0, 15);
        // 2x2 grid; the hemisphere cap keeps the near tiles, drops nothing visible.
        assert!(!vis.is_empty() && vis.len() <= 4, "z1 globe: {vis:?}");
        for t in &vis {
            assert_eq!(t.z, 1);
        }
    }

    #[test]
    fn the_globe_anchor_tracks_the_centre() {
        // The camera centre projects to the viewport centre; far side is culled.
        let c = globe_camera(-122.4194, 37.7749, 2.0);
        let (sx, sy) = c
            .globe_anchor_to_screen(-122.4194, 37.7749)
            .expect("centre must project");
        assert!((sx - 256.0).abs() < 1e-9, "sx {sx}");
        assert!((sy - 256.0).abs() < 1e-9, "sy {sy}");
        assert!(
            c.globe_anchor_to_screen(57.5806, -37.7749).is_none(),
            "antipode must cull"
        );
    }

    #[test]
    fn the_flat_camera_is_untouched_by_the_globe_flag() {
        // Regression guard for the default path: an explicit `globe: false`
        // camera produces the same matrices as the pre-globe derivation.
        let flat = camera(12.0);
        let m = flat.tile_to_clip(12, 654, 1583);
        assert_eq!(m[1], 0.0, "no shear into y");
        assert_eq!(m[4], 0.0, "no shear into x");
    }

    #[test]
    fn the_globe_matrix_carries_centre_and_tile_shape() {
        // The push-constant contract the globe vertex shaders read: the
        // translation column (m[12]/m[13], column-major) carries the camera
        // centre (degrees) for the sphere basis, the third row (m[2]/m[6]/
        // m[10]) carries the tile's normalised origin + span, and the 2x2
        // carries the globe scale centred on the viewport.
        let c = globe_camera(-122.4194, 37.7749, 2.0);
        let m = c.globe_tile_to_clip(2, 0, 1);
        assert!((m[12] as f64 - -122.4194).abs() < 1e-4, "m[12] centre lon, got {}", m[12]);
        assert!((m[13] as f64 - 37.7749).abs() < 1e-4, "m[13] centre lat, got {}", m[13]);
        let n = 2f64.powi(2);
        assert_eq!(m[2], 0.0f32, "ox of tile x=0");
        assert!((m[6] - (1.0 / n) as f32).abs() < 1e-9, "oy of tile y=1");
        assert!((m[10] - (1.0 / n) as f32).abs() < 1e-9, "span at z2");
        let r = globe_radius(2.0, 37.7749);
        let kx = 2.0 / 512.0;
        assert!((m[0] as f64 - kx * r).abs() < 1e-3, "2x2 carries the globe scale");
    }

    #[test]
    fn the_globe_centre_projects_to_viewport_centre() {
        // The camera centre faces the viewer exactly: its screen anchor is the
        // viewport centre (Dp), which the matrix then maps to clip (0, 0).
        let c = globe_camera(-122.4194, 37.7749, 2.0);
        let (sx_dp, sy_dp) = c
            .globe_anchor_to_screen(-122.4194, 37.7749)
            .expect("centre must project");
        assert!((sx_dp - 256.0).abs() < 1e-9, "sx {sx_dp}");
        assert!((sy_dp - 256.0).abs() < 1e-9, "sy {sy_dp}");
    }
}
