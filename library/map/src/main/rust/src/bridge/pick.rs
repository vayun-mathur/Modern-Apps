//! Tap picking: labels and markers.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::handle_mut;
use jni::objects::{JClass, JObject};
use jni::sys::{jdouble, jfloat, jlong, jobjectArray};
use jni::{jni_str, EnvUnowned};
/// Task-17 pick: placed labels intersecting the query box (Dp from the
/// viewport top-left). Returns `\u{1}`-joined `layerId/name/kind/lon/lat/featureId/regionId`
/// strings in placement order (topmost first); empty when nothing hits. Dp→device-px via the
/// last frame's density, remembered on the map handle (same density the
/// boxes were built with — boxes are device px, the query arrives in Dp).
///
/// `kind` is the feature's own kind, not its layer's first one, so a `poi-food` hit says
/// `cafe` rather than `restaurant`. `featureId` is the archive's stable id, or `0` for a
/// feature that has none. `regionId` is the tagged relation id of the boundary the label
/// names from the tile's `region_links` table, or `0` (`REGION_NONE`) when linked to nothing.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_pickLabels<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x0_dp: jfloat,
    y0_dp: jfloat,
    x1_dp: jfloat,
    y1_dp: jfloat,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let empty = env.new_object_array(0, jni_str!("java/lang/String"), JObject::null())?;
        let Some(map) = handle_mut(handle) else {
            return Ok(empty.into_raw());
        };
        let density = map.density;
        let hits = map.renderer.pick_labels((
            x0_dp as f32 * density,
            y0_dp as f32 * density,
            x1_dp as f32 * density,
            y1_dp as f32 * density,
        ));
        let layers = &map.layers;
        let out = match env.new_object_array(
            hits.len() as i32,
            jni_str!("java/lang/String"),
            JObject::null(),
        )
        {
            Ok(a) => a,
            Err(_) => return Ok(empty.into_raw()),
        };
        for (i, h) in hits.iter().enumerate() {
            let layer_id = layers
                .get(h.layer_index)
                .map(|l| l.id.as_str())
                .unwrap_or("");
            let s = format!(
                "{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}",
                layer_id, h.name, h.kind, h.lon, h.lat, h.feature_id, h.region_id,
            );
            let Ok(js) = env.new_string(s) else { continue };
            let _ = env.set_object_array_element(&out, i, js);
        }
        Ok(out.into_raw())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Pick the renderer-drawn marker under a tap: the id-buffer readback path.
///
/// `x`/`y` are Dp from the viewport top-left, converted to device px with the last frame's
/// density (the same conversion [`pickLabels`](Java_com_vayunmathur_library_map_MapNative_pickLabels)
/// makes), because the id buffer is device-px. Returns the tapped marker's own id — the value the
/// host set on it in [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) — so the
/// host rejoins the tap to its feature without matching on position, or `0` when the tap hit no
/// marker. That replaces the Compose CPU hit-test, which trailed the basemap on a pan and could not
/// place a pin's box under tilt at all.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_pickAt<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x_dp: jfloat,
    y_dp: jfloat,
) -> jlong {
    let Some(map) = handle_mut(handle) else {
        return 0;
    };
    let density = map.density;
    // Negative Dp is off the top-left of the viewport; clamp to zero before scaling so the cast to
    // an unsigned device coordinate cannot wrap. The native side clamps the far edges to the extent.
    let x = (x_dp.max(0.0) * density).round() as u32;
    let y = (y_dp.max(0.0) * density).round() as u32;
    map.renderer.pick_at(x, y) as jlong
}

/// Tilt-pivot elevation under a geo point, in world px at `zoom`.
///
/// The host-side counterpart of the renderer's per-frame pivot resolution (see
/// `Renderer::render`): the Compose overlay [`Projection`](../../../..) — which
/// positions markers and answers gesture unprojection on the CPU — must pivot
/// on the same terrain height the renderer drew with, or overlays drift off
/// their ground points under tilt. Returns 0.0 — the legacy sea-level pivot —
/// on a dead handle or where no resident tile has DEM (oceans, unloaded
/// tiles), exactly the fallback the renderer uses, so both sides agree there
/// too. `lon`/`lat`/`zoom` are `Float` for the same significant-digits reason
/// as [`render`](Java_com_vayunmathur_library_map_MapNative_render)'s camera.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_tiltPivotDp<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lon: jfloat,
    lat: jfloat,
    zoom: jfloat,
) -> jdouble {
    let Some(map) = handle_mut(handle) else {
        return 0.0;
    };
    map.renderer.pivot_dp_at(lon as f64, lat as f64, zoom as f64)
}
