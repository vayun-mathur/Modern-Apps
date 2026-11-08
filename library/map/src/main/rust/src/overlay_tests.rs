    use super::*;
    use crate::camera::Camera;

    /// The car's route paint: `#1A73E8` over a white casing, at the phone's authored route
    /// width (`maps/.../ui/map/RouteOverlayBuilder.kt`). The old renderer's `12f`/`18f` were
    /// physical px and have no Dp equivalent — see `RouteStyle`'s KDoc on the Kotlin side.
    fn style() -> RouteStyle {
        RouteStyle {
            width_dp: 8.0,
            casing_dp: 2.0,
            casing_color: 0xFFFF_FFFF,
        }
    }

    /// The fill colour the car draws: what a single-run route is painted.
    const FILL: u32 = 0xFF1A_73E8;

    /// Wrap a bare polyline as the one coloured run a single-colour route is.
    fn one(points: &[(f64, f64)]) -> Vec<RouteSegment> {
        vec![RouteSegment {
            points: points.to_vec(),
            color: FILL,
        }]
    }

    /// A three-point route across San Francisco.
    fn sf_route() -> Vec<(f64, f64)> {
        vec![
            (-122.4194, 37.7749),
            (-122.3894, 37.7949),
            (-122.3694, 37.7849),
        ]
    }

    fn camera(zoom: f64, bearing: f64) -> Camera {
        Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom,
            width_dp: 411.0,
            height_dp: 891.0,
            density: 3.0,
            bearing_deg: bearing,
            pitch_deg: 0.0,
            pivot_dp: 0.0,
            time_seconds: 0.0,
            globe: false,
            moon: false,
        }
    }

    /// Where a local `(u, v)` in the route's square lands on screen, in Dp from the
    /// viewport centre — the same arithmetic the vertex shader and the viewport transform
    /// do between them.
    fn screen_dp(mesh: &RouteMesh, camera: &Camera, u: f32, v: f32) -> (f64, f64) {
        let (origin, span) = mesh.placement.at_zoom(camera.zoom);
        let m = camera.world_quad_to_clip(origin, span);
        let x = m[0] * u + m[4] * v + m[12];
        let y = m[1] * u + m[5] * v + m[13];
        (
            x as f64 * camera.width_dp as f64 / 2.0,
            y as f64 * camera.height_dp as f64 / 2.0,
        )
    }

    #[test]
    fn a_route_becomes_a_stroked_band() {
        let mesh = tessellate(&one(&sf_route()), style()).expect("three points stroke");
        assert_eq!(
            mesh.vertices.len() / stroke::FLOATS_PER_VERTEX,
            6,
            "two vertices per point, one band",
        );
        assert_eq!(mesh.indices.len(), 12, "two triangles per segment");
        assert!(mesh.vertices.iter().all(|f| f.is_finite()));
        // One run, owning the whole index buffer, in the fill colour.
        assert_eq!(mesh.segments.len(), 1);
        assert_eq!(mesh.segments[0].color, FILL);
        assert_eq!(mesh.segments[0].index_offset, 0);
        assert_eq!(mesh.segments[0].index_count, mesh.indices.len() as u32);
    }

    #[test]
    fn coloured_runs_each_get_their_own_index_slice() {
        // Two runs, two colours: the mesh is one buffer, but each run owns a contiguous,
        // non-overlapping slice of the indices that together cover the whole thing — which
        // is what lets the casing draw the lot once and each fill draw its own slice.
        let segments = vec![
            RouteSegment {
                points: vec![(-122.42, 37.77), (-122.40, 37.79)],
                color: 0xFF00_FF00,
            },
            RouteSegment {
                points: vec![(-122.40, 37.79), (-122.38, 37.78)],
                color: 0xFFFF_0000,
            },
        ];
        let mesh = tessellate(&segments, style()).expect("two runs");
        assert_eq!(mesh.segments.len(), 2);
        assert_eq!(mesh.segments[0].color, 0xFF00_FF00);
        assert_eq!(mesh.segments[1].color, 0xFFFF_0000);
        assert_eq!(mesh.segments[0].index_offset, 0);
        assert_eq!(
            mesh.segments[1].index_offset, mesh.segments[0].index_count,
            "the second run starts where the first ends",
        );
        assert_eq!(
            mesh.segments[1].index_offset + mesh.segments[1].index_count,
            mesh.indices.len() as u32,
            "the runs together cover every index",
        );
        assert!(mesh.vertices.iter().all(|f| f.is_finite()));
    }

    #[test]
    fn a_degenerate_run_is_skipped_without_failing_the_route() {
        // One good run and one glitchy single-point run: the good one still draws, and the
        // degenerate one contributes no range rather than blanking the whole route.
        let segments = vec![
            RouteSegment {
                points: vec![(-122.42, 37.77), (-122.40, 37.79)],
                color: FILL,
            },
            RouteSegment {
                points: vec![(-122.40, 37.79)],
                color: 0xFFFF_0000,
            },
        ];
        let mesh = tessellate(&segments, style()).expect("the good run draws");
        assert_eq!(mesh.segments.len(), 1);
        assert_eq!(mesh.segments[0].color, FILL);
    }

    #[test]
    fn a_degenerate_route_draws_nothing_rather_than_dividing_by_zero() {
        assert!(tessellate(&[], style()).is_none());
        assert!(tessellate(&one(&[(-122.4, 37.7)]), style()).is_none());
        // Every point in the same place: no bounding box, no direction, no line.
        assert!(tessellate(
            &one(&[(-122.4, 37.7), (-122.4, 37.7), (-122.4, 37.7)]),
            style()
        )
        .is_none());
    }

    #[test]
    fn a_route_running_due_north_still_has_a_square_to_live_in() {
        // Zero width on x, which is what squaring the bounding box exists to survive.
        let mesh =
            tessellate(&one(&[(-122.4, 37.7), (-122.4, 37.8)]), style()).expect("a meridian");
        assert!(mesh.placement.span > 0.0);
        assert!(mesh.vertices.iter().all(|f| f.is_finite()));
    }

    #[test]
    fn repeated_points_do_not_produce_a_nan() {
        // A router emits coincident points, and a zero-length segment has no direction —
        // a NaN normal takes the whole strip off screen, not just that segment.
        let doubled = vec![
            (-122.4194, 37.7749),
            (-122.4194, 37.7749),
            (-122.3894, 37.7949),
            (-122.3894, 37.7949),
            (-122.3694, 37.7849),
        ];
        let mesh = tessellate(&one(&doubled), style()).expect("a route");
        for (at, value) in mesh.vertices.iter().enumerate() {
            assert!(value.is_finite(), "float {at} is {value}");
        }
    }

    #[test]
    fn the_local_geometry_is_the_same_number_at_every_zoom() {
        // The claim the whole design rests on: Mercator is a pure scale in zoom, so
        // normalising by the bounding box divides that scale out and the mesh never needs
        // rebuilding. If this stops holding, a route in a car starts re-tessellating on
        // every pinch — which is the cost this design exists to avoid.
        let route = sf_route();
        let mesh = tessellate(&one(&route), style()).expect("a route");
        let reference: Vec<(f64, f64)> = route
            .iter()
            .map(|&(lon, lat)| {
                let p = project(lon, lat, 0.0);
                (
                    (p.x - mesh.placement.origin.x) / mesh.placement.span,
                    (p.y - mesh.placement.origin.y) / mesh.placement.span,
                )
            })
            .collect();
        for zoom in [4.0, 10.0, 14.0, 18.0, 22.0] {
            let (origin, span) = mesh.placement.at_zoom(zoom);
            for (at, &(lon, lat)) in route.iter().enumerate() {
                let p = project(lon, lat, zoom);
                let u = (p.x - origin.x) / span;
                let v = (p.y - origin.y) / span;
                let (ru, rv) = reference[at];
                assert!((u - ru).abs() < 1e-12, "z{zoom} point {at}: u {u} vs {ru}");
                assert!((v - rv).abs() < 1e-12, "z{zoom} point {at}: v {v} vs {rv}");
            }
        }
    }

    #[test]
    fn a_routes_points_land_where_the_camera_projects_them() {
        // The overlay is glued to the ground or it is useless, so its square has to place
        // a point exactly where `project` puts it. Checked in Dp from the viewport centre,
        // which is where a drift of even a few pixels would be visible against the road
        // the route is following.
        let route = sf_route();
        let mesh = tessellate(&one(&route), style()).expect("a route");
        for zoom in [8.0, 12.0, 16.0] {
            let camera = camera(zoom, 0.0);
            let centre = project(camera.center_lon, camera.center_lat, zoom);
            for &(lon, lat) in &route {
                let world = project(lon, lat, zoom);
                let (origin, span) = mesh.placement.at_zoom(zoom);
                let u = ((world.x - origin.x) / span) as f32;
                let v = ((world.y - origin.y) / span) as f32;
                let (sx, sy) = screen_dp(&mesh, &camera, u, v);
                let (wx, wy) = (world.x - centre.x, world.y - centre.y);
                assert!((sx - wx).abs() < 0.05, "z{zoom} x {sx} vs {wx}");
                assert!((sy - wy).abs() < 0.05, "z{zoom} y {sy} vs {wy}");
            }
        }
    }

    #[test]
    fn a_route_turns_with_the_camera() {
        // Under a bearing the overlay has to rotate with the basemap, not stay north-up:
        // it goes through the same matrix, so this pins that it really is the same one.
        let route = sf_route();
        let mesh = tessellate(&one(&route), style()).expect("a route");
        let zoom = 14.0;
        let north_up = camera(zoom, 0.0);
        let turned = camera(zoom, 90.0);
        let world = project(route[1].0, route[1].1, zoom);
        let (origin, span) = mesh.placement.at_zoom(zoom);
        let u = ((world.x - origin.x) / span) as f32;
        let v = ((world.y - origin.y) / span) as f32;

        let (nx, ny) = screen_dp(&mesh, &north_up, u, v);
        let (tx, ty) = screen_dp(&mesh, &turned, u, v);
        // A 90-degree bearing maps (x, y) to (y, -x).
        assert!((tx - ny).abs() < 0.05, "{tx} should be the north-up y {ny}");
        assert!(
            (ty - -nx).abs() < 0.05,
            "{ty} should be minus the north-up x {nx}"
        );
        // And the distance from the centre is unchanged, because a rotation is not a
        // scale.
        assert!(
            ((tx * tx + ty * ty).sqrt() - (nx * nx + ny * ny).sqrt()).abs() < 0.05,
            "the rotation scaled the route",
        );
    }

    #[test]
    fn the_casing_is_wider_than_the_route() {
        let mesh = tessellate(&one(&sf_route()), style()).expect("a route");
        let fill = mesh.placement.fill_half(3.0);
        let casing = mesh.placement.casing_half(3.0).expect("a casing");
        assert!(casing > fill, "{casing} must stand outside {fill}");
        // 8 Dp wide at density 3 is a 12 device-px half-width, plus 2 Dp of casing a side.
        assert!((fill - 12.0).abs() < 1e-6, "{fill}");
        assert!((casing - 18.0).abs() < 1e-6, "{casing}");
    }

    #[test]
    fn no_casing_means_no_casing_pass_at_all() {
        // `casing_dp = 0` is the documented way to switch the outline off. It must give no
        // casing half-width rather than a zero one: `line.vert` floors a band at half a
        // pixel so a hairline still rasterises, so a zero-width casing would poke a pixel
        // of casing colour out from under the route.
        let plain = RouteStyle {
            casing_dp: 0.0,
            ..style()
        };
        let mesh = tessellate(&one(&sf_route()), plain).expect("a route");
        assert!(mesh.placement.casing_half(2.0).is_none());
        assert!(mesh.placement.fill_half(2.0) > 0.0);
    }
