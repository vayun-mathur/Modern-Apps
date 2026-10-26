//! One frame from a camera snapshot.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::{handle_mut, TileResult, RESIDENT_BYTE_BUDGET, RESIDENT_TILE_CAP, UPLOADS_PER_FRAME, WASH_MAX_ZOOM};
use super::log::{log, log_info};
use crate::camera::Camera;
use crate::style;
use crate::tile::select;
use crate::tile::source::retry_delay_ms;
use crate::timing::{nanos_since, Step};
use jni::objects::JClass;
use jni::sys::{jfloat, jlong};
use jni::EnvUnowned;
/// Draw one frame from a camera snapshot. Returns false if the frame was skipped.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_render<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    center_lon: jfloat,
    center_lat: jfloat,
    zoom: jfloat,
    bearing: jfloat,
    pitch: jfloat,
    width_dp: jfloat,
    height_dp: jfloat,
    density: jfloat,
    frame_time_nanos: jlong,
    globe: bool,
    moon: bool,
) -> bool {
    let Some(map) = handle_mut(handle) else {
        return false;
    };
    // Per-step timing for the per-frame report: `Instant` deltas only, never the camera clock
    // (which wraps hourly). The render thread is the only writer.
    let jni_start = std::time::Instant::now();

    // The camera zoom crosses the boundary untouched. MapLibre parity is
    // `camera::TILE_SIZE` being 512, the convention the archives are authored on, so
    // tile addressing is the plain floor of this zoom and every style ramp is
    // evaluated at the zoom the authored `basemap.json` meant by it. This was
    // previously a 256 grid with a +1 offset applied here, which reached the same
    // ground scale but fetched z+1 tiles — four times as many as MapLibre for the
    // same screenful — and read every ramp one level deep.
    //
    // `bearing` is degrees clockwise from north for whatever points up the screen: 0 on
    // every phone frame, and the car's heading during heading-up navigation.
    //
    // `pitch` is the tilt away from straight-down, clamped to the renderer's supported band
    // here so no matrix has to defend against a wild value. `frame_time_nanos` is the host's
    // Choreographer clock, reduced modulo an hour before it becomes an `f32` so a long uptime
    // does not blow past the ~7 significant digits an `f32` has and coarsen the animation clock
    // to tens of milliseconds — a once-an-hour wrap is invisible to the periodic effects that
    // read it.
    let camera = Camera {
        center_lon: center_lon as f64,
        center_lat: center_lat as f64,
        zoom: zoom as f64,
        width_dp,
        height_dp,
        density,
        bearing_deg: bearing as f64,
        pitch_deg: (pitch as f64).clamp(0.0, crate::camera::PITCH_MAX_DEG),
        globe,
        moon,
        time_seconds: ((frame_time_nanos.rem_euclid(crate::camera::CLOCK_WRAP_NANOS)) as f64
            / 1_000_000_000.0) as f32,
    };
    // Task-17 pick needs the frame's density for Dp→device-px; remember it.
    map.density = density;

    // Upload whatever the workers finished, up to the frame's budget (see
    // `upload_budget`: a zoom-out drains faster). Doing it here rather than
    // on a worker keeps every Vulkan call on one thread; bounding it keeps a burst of finished
    // tiles from landing in a single frame.
    //
    // Skipped on Moon frames: the Moon draws one uploaded texture pair, not tiles —
    // no selection runs, nothing is in flight, and draining here would upload Earth
    // tiles the Moon frame never draws (wasted uploads + residency churn under the
    // Moon). The Earth set resumes untouched when the body switches back.
    //
    // The drain sample below still records (zero elapsed on Moon frames) so the
    // frame-time rollup keeps its shape.
    let drain_start = std::time::Instant::now();
    // Finished tiles that scrolled out while in flight, skipped before upload (issue 815).
    // Declared outside the Moon guard so the per-frame report below can cite it; Moon frames
    // skip the drain and leave it zero.
    let mut stale_dropped = 0usize;
    if crate::camera::moon_active(&camera) {
        // No selection runs for the Moon, so no zoom is recorded — and the Earth set
        // resumes mid-stream when the body switches back, which deserves the burst path.
        map.forget_zoom();
    } else {
    let budget = map.upload_budget(camera.zoom);
    let mut uploads = 0usize;
    while uploads < budget {
        let Ok((key, result)) = map.finished.try_recv() else {
            break;
        };
        map.in_flight.remove(&key);
        // Scrolled out while in flight (issue 815): the bytes are real but nobody looks at this
        // tile anymore, so it skips the upload slot — visible tiles drain first. Absent/failed
        // bookkeeping below still runs (an absence is a fact whatever the viewport), only the
        // Vulkan upload is spared. Disabled until the first Earth frame selects a set.
        if !map.last_visible.is_empty() && !map.last_visible.contains(&key) {
            stale_dropped += 1;
            match result {
                TileResult::Ready(_) => {
                    map.retry.remove(&key);
                }
                TileResult::Absent => {
                    map.absent.insert(key);
                    map.retry.remove(&key);
                }
                TileResult::Failed => {
                    let attempts = map.retry.get(&key).map_or(0, |(n, _)| *n).saturating_add(1);
                    let wait = std::time::Duration::from_millis(retry_delay_ms(attempts));
                    map.retry
                        .insert(key, (attempts, std::time::Instant::now() + wait));
                }
            }
            continue;
        }
        match result {
            TileResult::Ready(mesh) => {
                uploads += 1;
                // It arrived, so whatever was failing has stopped. Anything else would leave a
                // tile that recovered still carrying a ten-second backoff for the session.
                map.retry.remove(&key);
                if let Err(e) = map.renderer.upload(key, mesh) {
                    log(&format!("uploading a tile failed: {e}"));
                }
            }
            // Remembered, so a mostly-ocean viewport does not re-request the same empty
            // tiles every frame for the life of the surface.
            TileResult::Absent => {
                map.absent.insert(key);
                map.retry.remove(&key);
            }
            // Deliberately not recorded as absent: clearing `in_flight` above is what lets it
            // be tried again, which is the whole point of distinguishing this from `Absent`.
            // What is recorded is *when* — without a deadline the retry lands on the very next
            // frame, so a tile that keeps failing is re-requested sixty times a second and the
            // in-flight set never empties, which both storms the network and stops the
            // on-demand frame loop ever idling.
            TileResult::Failed => {
                let attempts = map.retry.get(&key).map_or(0, |(n, _)| *n).saturating_add(1);
                let wait = std::time::Duration::from_millis(retry_delay_ms(attempts));
                map.retry
                    .insert(key, (attempts, std::time::Instant::now() + wait));
            }
        }
    }
    } // end Moon drain skip.

    // Keep the visible tiles plus any ancestor of one that we already have, but **fetch
    // only the visible tiles**. An ancestor is a fallback for a tile that has not arrived yet, so
    // it is only useful if we **already have it** — fetching one costs a round trip to draw a
    // blurrier version of a tile that is being fetched anyway. (Moon frames skip this
    // whole block: `visible`/`keep` below are empty and `retain` keeps the Earth set
    // untouched — see the guard.)
    //
    // `visible` goes to `retain` as well as to the fetch loop, because the other half of the
    // fallback — already-resident *descendants*, which are what stops a zoom-out blanking the
    // map — cannot be named in a keep list without enumerating tiles that were never fetched.
    //
    // The drain sample lands here rather than right after the loop: it covers the channel
    // receives and the uploads, not the select below.
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::UploadDrain, nanos_since(drain_start));
    let select_start = std::time::Instant::now();
    let (min_zoom, max_zoom) = map.zoom_range.get();
    // A republish under the stable URL moves every byte offset: absences and backoffs
    // recorded against the previous build would refuse or delay tiles that exist now.
    // The worker publishes the real build id with the range; a change clears both sets.
    // (0 is "header not yet fetched" — nothing is cleared until the first real id lands,
    // so cold-start absences recorded under the guessed range survive the header.)
    let build_id = map.zoom_range.build_id();
    if build_id != 0 && build_id != map.absent_build_id {
        map.absent.clear();
        map.retry.clear();
        map.absent_build_id = build_id;
    }
    // A tile is "had" only if it was tessellated at the current toggle generation, so a
    // toggle change re-requests the resident set through this same loop rather than
    // needing a path of its own. The stale mesh keeps drawing until its replacement
    // arrives. Read once: the snapshot, filter and generation must agree with each
    // other (and with the `kinds` the render call below takes), so two `get`s must
    // never straddle a `set`.
    let (snap_toggles, snap_kinds, generation) = map.toggles.get();
    // Gesture debounce (MapLibre's "load while moving" rule): while a gesture is active
    // the *fetch* zoom freezes at the gesture-start level, so a pinch does not churn the
    // wanted set every frame with tiles that scroll out before they land. Rendering keeps
    // the live camera — resident ancestors/descendants cover the gap with overzoom — and
    // the real zoom fetches once the camera settles. Pan-only motion never freezes (the
    // zoom is unchanged, so ideal and fetch agree). Skipped on Moon frames with the
    // selection below.
    let moon = crate::camera::moon_active(&camera);
    let fetch_zoom = if moon {
        camera.zoom
    } else {
        map.gesture.fetch_zoom(camera.zoom)
    };
    // Moon frames: no selection, no fetch, no eviction. The Earth resident set,
    // in-flight set and backoffs below are all left exactly as they were, so
    // switching back to Earth resumes mid-stream rather than refetching.
    let (visible, keep): (Vec<select::TileId>, Vec<u64>) = if moon {
        (Vec::new(), Vec::new())
    } else {
        // The fetch camera shares everything but the zoom: selection at the frozen level
        // keeps the wanted set stable while the draw camera tracks the finger.
        let fetch_camera = Camera {
            zoom: fetch_zoom,
            ..camera
        };
        let visible = select::visible(&fetch_camera, min_zoom, max_zoom);
        let keep: Vec<u64> = select::resident_set(&camera, min_zoom, max_zoom)
            .iter()
            .map(|t| t.key())
            .collect();
        (visible, keep)
    };
    let now = std::time::Instant::now();
    // Wash overzoom: the `landtype` wash tiles to z14 (`mamaps_build::DEFAULT_MAX_ZOOM`),
    // so past z14 nothing `visible` carries it. Its z14 ancestors are kept
    // resident by `resident_set` — but kept is not fetched, so a cold start at
    // z15+ would show no wash until the user zooms through z14. Fetch the z14
    // covering tiles alongside the visible ones; the draw loop already draws
    // every resident tile, so the wash shows through where z15+ have none.
    // Bounded: one z14 tile covers 2^(z-14) visible tiles, so this adds at most
    // a quarter of the visible count, and the existing has_tile/absent/retry
    // gates dedup it exactly like a visible fetch.
    let wash_extra: Vec<select::TileId> = if fetch_zoom > WASH_MAX_ZOOM as f64 {
        let mut seen = std::collections::HashSet::new();
        let mut extra = Vec::new();
        for tile in &visible {
            if tile.z <= WASH_MAX_ZOOM {
                continue;
            }
            let shift = tile.z - WASH_MAX_ZOOM;
            let ancestor = select::TileId {
                z: WASH_MAX_ZOOM,
                x: tile.x >> shift,
                y: tile.y >> shift,
            };
            if seen.insert(ancestor.key()) {
                extra.push(ancestor);
            }
        }
        extra
    } else {
        Vec::new()
    };
    // The wanted set for the drain-and-replace generation (issue 815): when it changes, the
    // queued remainder of the previous set is stale and is drained below, so a pan does not
    // wait behind fetches for tiles that scrolled out. `wash_extra` counts as wanted — it is
    // enqueued through the same gates.
    let wanted_keys: std::collections::HashSet<u64> = visible
        .iter()
        .chain(wash_extra.iter())
        .map(|t| t.key())
        .collect();
    // Drain-and-replace, Earth frames only: the Moon guard below leaves the Earth queue
    // untouched so switching back resumes mid-stream. `try_lock` never blocks — a worker
    // parked in `recv` holds the lock, and blocking behind it would deadlock the render
    // thread against its own send — so a contended frame skips the drain and the normal gates
    // below still dedup. Drained keys leave `in_flight` and are re-sent when still wanted;
    // keys a worker already took stay marked and are never duplicated. Skipped while the
    // wanted set is steady (the common case) and before the first Earth frame.
    //
    // The debounce counts here too: while the fetch zoom is frozen, keys that fell out of
    // the frozen set are dropped without fetching (they belong to an intermediate zoom the
    // gesture already left behind), and the count lands in `Step::FetchCancelled` below.
    // `fetch_held` counts the debounce holds below; drain-and-replace discards count
    // too. Both are "wanted, never fetched" — the `Step::FetchCancelled` proof that the
    // gesture path is holding back storms rather than issuing them.
    let mut fetch_cancelled = 0usize;
    if !moon && !map.last_visible.is_empty() && wanted_keys != map.last_visible {
        if let Ok(queue) = map.wanted_queue.try_lock() {
            let mut drained = 0usize;
            while let Ok(tile) = queue.try_recv() {
                map.in_flight.remove(&tile.key());
                drained += 1;
            }
            if drained > 0 {
                log_info(&format!("queue generation changed: drained {drained} stale tiles"));
                fetch_cancelled += drained;
            }
        }
    }
    // Centre-first enqueue (issue 815): the middle of the screen fills before the edges, so a
    // cold pan shows something useful as early as possible instead of scan order.
    //
    // Debounce hold: while the fetch zoom is frozen, tiles at the *live* zoom that are not
    // in the frozen set are held back, not fetched. They belong to an intermediate level the
    // gesture is passing through; fetching them spends a worker on bytes that scroll out
    // before they land. The frozen set plus resident overzoom covers the screen instead.
    // Held keys never enter `in_flight`, so they are re-considered (not retried) next frame.
    let frozen_set: std::collections::HashSet<u64> = wanted_keys.clone();
    let live_keys: std::collections::HashSet<u64> = if (fetch_zoom - camera.zoom).abs() > f64::EPSILON
        && !moon
    {
        let live_camera = camera;
        select::visible(&live_camera, min_zoom, max_zoom)
            .iter()
            .map(|t| t.key())
            .collect()
    } else {
        std::collections::HashSet::new()
    };
    // Held keys never enter `in_flight`, so they are re-considered (not retried) next frame.
    let fetch_held = live_keys.difference(&frozen_set).count();
    for tile in center_first(&visible).iter().chain(wash_extra.iter()) {
        let key = tile.key();
        if map.renderer.has_tile(key, generation, &snap_toggles, &snap_kinds)
            || map.absent.contains(&key)
            // Still inside its backoff after a failure. Left in `retry` rather than removed
            // here, so the attempt count keeps climbing if it fails again.
            || map.retry.get(&key).is_some_and(|(_, at)| now < *at)
            || !map.in_flight.insert(key)
        {
            continue;
        }
        // A closed channel means every worker died; the map keeps drawing what it has.
        // The key is removed again on failure: it is only taken out of `in_flight` when a
        // result is drained, so a dropped send would otherwise stick the tile there for
        // the rest of the session and it would never be re-requested.
        if map.wanted.send(*tile).is_err() {
            map.in_flight.remove(&key);
        }
    }
    // Drop backoffs for tiles that are no longer visible (see
    // `RetryMap::retain_visible` for why this is load-bearing, not housekeeping).
    //
    // Skipped on Moon frames along with the fetch above: `visible` is empty, and
    // retaining against it would drop every Earth backoff (harmless but wasteful)
    // — worse, `retain` below with an empty keep would EVICT the Earth set. The
    // Moon guard keeps all three out, and `last_visible` keeps the Earth set it
    // resumes against.
    if !moon {
        map.retry.retain_visible(&visible);
        map.renderer
            .retain(&keep, &visible, RESIDENT_TILE_CAP, RESIDENT_BYTE_BUDGET);
        map.last_visible = wanted_keys;
    }
    // `fetch_held` (the hold count from the live-set diff above) plus the drain count
    // from the drain-and-replace above both land here. Recorded every frame (including
    // zero) so the overlay row tracks holds rather than going stale.
    fetch_cancelled += fetch_held;
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::FetchCancelled, fetch_cancelled as u64);
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::Select, nanos_since(select_start));

    // The active category filter goes to the renderer as well as to tessellation: a chip both
    // narrows which POIs are drawn and pulls its own kinds in earlier than the ambient map shows
    // them. See `Layer::draws_at_focused`. This is the same snapshot the fetch loop above read —
    // re-reading here could straddle a `set` and disagree with it.
    let kinds = snap_kinds;
    let outcome = map.renderer.render(
        &camera,
        &map.layers,
        map.palette,
        style::background(map.palette.variant),
        &kinds,
    );
    // The JNI-entry sample closes here: it covers drain + select + render, which is the whole
    // native half of the frame the host asked for.
    map.renderer
        .step_times
        .borrow_mut()
        .record(Step::JniEntry, nanos_since(jni_start));
    // Every frame, state what the renderer just did. Every bug in this file so far has
    // been invisible from the outside: a viewport nobody measured, a zoom level the archive
    // does not contain, a tile stuck in flight forever. All of them would have been one line
    // of this away.
    //
    // After `render`, so `draws`/`tris` and every step sample below belong to the frame this
    // line describes rather than the one before it.
    {
        let (tiles, meshes, draws, triangles) = map.renderer.stats();
        let (width_px, height_px) = map.renderer.extent();
        // `meshes` is what is resident, `draws` what the frame just submitted. They
        // differ wherever the authored style ramps a layer's width to zero, so reporting only
        // the first would claim roads are being drawn at zooms where they are gated out.
        //
        // The step report is built (and its window reset) every frame: `avg/max`
        // per step in ms for this frame alone.
        let steps = map.renderer.step_times.borrow_mut().report(1);
        log_info(&format!(
            "z{:.2} @{:.4},{:.4} b{:.0} vp {}x{}dp {}x{}px msaa {}x | resident {} tiles, {} meshes, \
             {} draws, {} tris | {} in flight, {} absent, {} stale-dropped | archive z{}..{} | {}",
            camera.zoom,
            camera.center_lon,
            camera.center_lat,
            camera.bearing_deg,
            camera.width_dp,
            camera.height_dp,
            width_px,
            height_px,
            map.renderer.samples(),
            tiles,
            meshes,
            draws,
            triangles,
            map.in_flight.len(),
            map.absent.len(),
            stale_dropped,
            min_zoom,
            max_zoom,
            steps,
        ));
    }
    match outcome {
        Ok(drawn) => drawn,
        Err(e) => {
            log(&format!("frame failed: {e}"));
            false
        }
    }
}

/// The wanted tiles nearest the viewport centre first.
///
/// A cold pan shows its middle before its edges: the centre tile is what the user is looking
/// at, and scan order would spend the first fetches on a corner. Sorts by squared distance to
/// the set's own mean, which is the viewport centre for a coverage box. A handful of tiles, so
/// the sort is noise next to one fetch.
fn center_first(visible: &[select::TileId]) -> Vec<select::TileId> {
    if visible.len() < 2 {
        return visible.to_vec();
    }
    let (sx, sy) = visible
        .iter()
        .fold((0i64, 0i64), |(sx, sy), t| (sx + t.x as i64, sy + t.y as i64));
    let n = visible.len() as i64;
    let (cx, cy) = (sx / n, sy / n);
    let mut out = visible.to_vec();
    out.sort_by_key(|t| {
        let dx = t.x as i64 - cx;
        let dy = t.y as i64 - cy;
        dx * dx + dy * dy
    });
    out
}

/// The last frame's per-step times in nanos, in [`Step::ALL`](crate::timing::Step) order.
///
/// For the debug overlay's slow poll (~2–4 Hz): a copy, so the render thread never blocks on it.
/// A dead handle (or a JNI failure) answers an empty array rather than crashing — the overlay
/// reads that as "no data yet". Must never drive `needs_frame` or alter the frame loop.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_lastFrameStepTimesNanos<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jni::sys::jlongArray {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jlongArray> {
        let empty = env.new_long_array(0)?;
        let Some(map) = handle_mut(handle) else {
            return Ok(empty.into_raw());
        };
        let last = map.renderer.step_times.borrow();
        let nanos = last.last_nanos();
        let out = match env.new_long_array(nanos.len()) {
            Ok(a) => a,
            Err(_) => return Ok(empty.into_raw()),
        };
        // `i64` is only the JNI carrier: the samples are `u64` nanos, and no step of a frame can
        // reach the sign bit, so the cast is exact.
        let wide: Vec<i64> = nanos.iter().map(|&n| n as i64).collect();
        if env.set_long_array_region(&out, 0, &wide).is_err() {
            return Ok(empty.into_raw());
        }
        Ok(out.into_raw())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
