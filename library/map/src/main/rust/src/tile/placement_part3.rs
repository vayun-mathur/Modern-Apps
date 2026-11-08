#[cfg(test)]
mod tests_tilt {
    use super::*;

    // --- pitch-aware projection (tilt declutter) ----------------------------

    use crate::camera::Camera;

    /// A phone viewport over SF at z14, at the given tilt.
    fn tilted_camera(pitch_deg: f64) -> Camera {
        Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom: 14.0,
            width_dp: 411.0,
            height_dp: 891.0,
            density: 2.0,
            bearing_deg: 0.0,
            pitch_deg,
            pivot_dp: 0.0,
            time_seconds: 0.0,
            globe: false,
            moon: false,
        }
    }

    /// POI-scale collision inputs in device px: 12 Dp text at density 2 with a 19 Dp icon,
    /// no offset, no padding.
    fn poi_inputs() -> BoxInputs {
        BoxInputs {
            text_px: 24.0,
            advance: crate::tile::glyph::UP_EM as f32,
            line_count: 1,
            offset_em: (0.0, 0.0),
            icon_px: Some((38.0, 38.0)),
            pad_px: 0.0,
        }
    }

    /// **The tilt-declutter contract.** A collision box is centred where the billboarded label
    /// draws — the anchor's post-divide screen position — not where the tile matrix's linear part
    /// puts it pre-divide. Checked against [`Camera::world_to_screen`], which derives the same
    /// screen point from the camera model independently of `tile_to_clip`, so reverting the divide
    /// fails here instead of testing boxes that sit nowhere near the drawn labels.
    #[test]
    fn a_tilted_box_is_centred_where_the_billboard_draws_its_anchor() {
        let cam = tilted_camera(55.0);
        // A ground point 200 Dp above the screen centre: up-map, where foreshortening bites.
        // Starting from a screen point keeps the fixture on-screen by construction.
        let ground = cam.screen_to_world(205.5, 245.5).expect("below the horizon under the cap");
        let z = 14u8;
        let span = cam.tile_span_dp(z);
        let tx = (ground.x / span).floor() as u32;
        let ty = (ground.y / span).floor() as u32;
        let anchor = (
            ((ground.x - tx as f64 * span) / span) as f32,
            ((ground.y - ty as f64 * span) / span) as f32,
        );
        assert!((0.0..=1.0).contains(&anchor.0) && (0.0..=1.0).contains(&anchor.1));
        let m = cam.tile_to_clip(z, tx, ty);
        let extent = ((cam.width_dp * cam.density) as u32, (cam.height_dp * cam.density) as u32);
        let rect = anchored_rect(anchor, m, extent, &poi_inputs(), Anchor::Center)
            .expect("the anchor is on-screen, so it projects");
        let centre = ((rect.0 + rect.2) * 0.5, (rect.1 + rect.3) * 0.5);
        let want = (205.5f32 * cam.density, 245.5f32 * cam.density);
        assert!((centre.0 - want.0).abs() < 0.05, "x centre {centre:?} vs drawn {want:?}");
        assert!((centre.1 - want.1).abs() < 0.05, "y centre {centre:?} vs drawn {want:?}");
    }

    /// **Invalid collision is unrepresentable.** A label on or behind the eye has no screen
    /// position, so it has no box: the projection returns `None` and the caller skips the label
    /// rather than colliding a garbage box. The old code always returned a box; a revert fails here.
    #[test]
    fn a_label_on_or_behind_the_eye_has_no_collision_box() {
        let extent = (822u32, 1782u32);
        let inputs = poi_inputs();
        // w = -1 everywhere: every anchor is behind the eye.
        let behind = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, -1.0,
        ];
        assert!(project_to_screen(behind, (0.5, 0.5), extent).is_none());
        assert!(anchored_rect((0.5, 0.5), behind, extent, &inputs, Anchor::Center).is_none());
        // w = 0 at the anchor: exactly on the eye — no finite screen position either.
        // (w = m[3]*x + m[7]*y + m[15] = -0.5 - 0.5 + 1.0.)
        let on_eye = [
            2.0, 0.0, 0.0, -1.0, //
            0.0, 2.0, 0.0, -1.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        assert!(project_to_screen(on_eye, (0.5, 0.5), extent).is_none());
        assert!(anchored_rect((0.5, 0.5), on_eye, extent, &inputs, Anchor::Center).is_none());
        // NaN poisons the comparison the same way: `!(w > 0.0)` catches it, so a NaN matrix
        // builds no box that collides with nothing and always draws.
        let nan = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, f32::NAN,
        ];
        assert!(anchored_rect((0.5, 0.5), nan, extent, &inputs, Anchor::Center).is_none());
    }

    /// **Declutter follows projected density, not zoom.** Two POI-scale boxes at different ground
    /// depths share one tile; flat they are far apart and both place, tilted the far one compresses
    /// onto the near one and rank decides. Same camera zoom throughout — the zoom-only gate cannot
    /// tell the two views apart, the projected boxes can.
    #[test]
    fn tilt_compresses_distant_poi_boxes_onto_each_other_so_rank_decides() {
        let flat = tilted_camera(0.0);
        let tilted = tilted_camera(55.0);
        let (z, x, y) = (14u8, 2620u32, 6332u32);
        let extent = (822u32, 1782u32);
        // Depth-separated anchors in the centred tile: the tile-local point under the camera
        // centre is (0.6, 0.4), so (0.6, 0.35) is nearer and (0.6, 0.27) is farther up-map.
        // Measured: the pair sits ~82 device px apart flat (disjoint 38px POI boxes) and
        // ~36 device px apart at pitch 55 (overlapping), at the same camera zoom.
        let (near, far) = ((0.6f32, 0.35f32), (0.6f32, 0.27f32));
        let gap = |cam: &Camera| {
            let m = cam.tile_to_clip(z, x, y);
            let a = project_to_screen(m, near, extent).expect("on-screen");
            let b = project_to_screen(m, far, extent).expect("on-screen");
            (a.1 - b.1).abs()
        };
        let flat_gap = gap(&flat);
        let tilted_gap = gap(&tilted);
        assert!(
            tilted_gap < flat_gap,
            "tilt must compress depth: tilted gap {tilted_gap} vs flat gap {flat_gap}",
        );
        // At POI scale the compressed boxes overlap, so one candidate survives the placer;
        // flat they are disjoint and both draw.
        let boxes = |cam: &Camera| {
            let m = cam.tile_to_clip(z, x, y);
            [near, far]
                .iter()
                .map(|a| {
                    Obb::from_rect(
                        anchored_rect(*a, m, extent, &poi_inputs(), Anchor::Center)
                            .expect("on-screen"),
                    )
                })
                .collect::<Vec<_>>()
        };
        let candidates = |cam: &Camera| {
            boxes(cam)
                .into_iter()
                .enumerate()
                .map(|(i, b)| SegmentedCandidate {
                    id: i as u64,
                    rank: 4,
                    pop: 0,
                    boxes: vec![b],
                    alternate: None,
                    feature_id: i as u64 + 1,
                    layer_index: 0,
                })
                .collect::<Vec<_>>()
        };
        assert_eq!(place_segmented(&candidates(&flat)).len(), 2, "flat: both POIs draw");
        assert_eq!(
            place_segmented(&candidates(&tilted)).len(),
            1,
            "tilted: the far POI compresses onto the near one",
        );
    }
}
