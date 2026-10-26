use super::{Overlay, Renderer, UserPuck, FRAMES_IN_FLIGHT};
use crate::camera::Camera;
use crate::marker::Marker;
use crate::style::{KindFilter, Layer, LayerToggles, Palette};
use crate::tile::select;
use crate::timing::{nanos_since, Step};
use ash::vk;

impl Renderer {
    /// Show the user-location puck, or take it away with `None`.
    ///
    /// Pure state, like [`set_palette`](crate::bridge): a fix arrives at about 1 Hz while
    /// the frame loop runs at 60, so the puck is set out of band and read by whichever
    /// frame happens next, rather than being an argument on [`render`](Self::render).
    pub fn set_user_puck(&mut self, puck: Option<UserPuck>) {
        self.overlays
            .retain(|overlay| !matches!(overlay, Overlay::Puck(_)));
        if let Some(puck) = puck {
            self.overlays.push(Overlay::Puck(puck));
        }
        self.bump_state();
    }

    /// Replace the app's pins with `markers`, or clear them with an empty slice.
    ///
    /// Pure state like [`set_user_puck`](Self::set_user_puck): the host pushes the whole visible
    /// pin set out of band (from a tap, a search, a family fix), and whichever frame runs next
    /// draws it. Replacing rather than merging, for the same reason [`set_traffic_speeds`] does —
    /// a stale pin left behind would sit under the finger and pick wrong.
    ///
    /// Cheap: the geometry is the shared unit quad billboarded per marker in
    /// [`record_overlays`](Self::record_overlays), so nothing is tessellated or uploaded here.
    /// WS-F's `set_vehicles` is modelled on this exactly.
    ///
    /// [`set_traffic_speeds`]: Self::set_traffic_speeds
    pub fn set_markers(&mut self, markers: Vec<Marker>, labels: Vec<String>) {
        self.overlays
            .retain(|overlay| !matches!(overlay, Overlay::Markers(..)));
        // The placement key below reads this, so a push that changes nothing still re-places —
        // a stale accept-set is a POI drawn over a new pin, while a redundant re-place is a
        // millisecond on a state change, not per frame.
        self.marker_epoch = self.marker_epoch.wrapping_add(1);
        self.bump_state();
        if !markers.is_empty() {
            // Parallel arrays cross JNI together and are truncated to the shortest there, so
            // these lengths already agree; a defensive trunc here keeps a future direct caller
            // from indexing past the end.
            let n = markers.len().min(labels.len());
            self.overlays.push(Overlay::Markers(
                markers.into_iter().take(n).collect(),
                labels.into_iter().take(n).collect(),
            ));
        }
    }

    /// Replace the simulated transit vehicles with `vehicles`, or clear them with an empty vec.
    ///
    /// Modelled exactly on [`set_markers`](Self::set_markers): the host's 1 Hz ticker recomputes the
    /// in-service vehicles for the visible bbox and pushes the whole set out of band, and whichever
    /// frame runs next draws it. Replacing rather than merging so a trip that has ended, left the
    /// bbox, or been cancelled drops out cleanly rather than lingering at a stale position.
    ///
    /// A separate [`Overlay`] arm from the pins so the two are pushed on their own cadences — the
    /// vehicles churn every second while the pins change only on a tap/search — and so the vehicles
    /// stay out of the marker id-buffer pick (see [`pick_at`](Self::pick_at)).
    ///
    /// Cheap in the same sense as [`set_markers`](Self::set_markers): the geometry is the shared
    /// unit quad billboarded per vehicle in [`record_overlays`](Self::record_overlays), so nothing
    /// is tessellated or uploaded here. Between the 1 Hz recomputes the sprites hold their last
    /// pushed position; the native side folds schedule + realtime delay into each recompute.
    pub fn set_vehicles(&mut self, vehicles: Vec<Marker>) {
        self.overlays
            .retain(|overlay| !matches!(overlay, Overlay::Vehicles(_)));
        if !vehicles.is_empty() {
            self.overlays.push(Overlay::Vehicles(vehicles));
        }
        self.bump_state();
    }

    /// Dim everything outside one region, or take the mask away with `None`.
    ///
    /// Takes the region's OSM relation id, not a point: a region reaches the archive as one
    /// clipped polygon per tile, and the id is what says those pieces are the same region. Pure
    /// state for the same reason as [`set_user_puck`](Self::set_user_puck) — a selection arrives
    /// from a tap, not from the frame loop.
    pub fn set_region_mask(&mut self, region: Option<u64>) {
        self.selected_region = region;
        self.bump_state();
    }

    /// Replace the live-traffic colour table with a host-pushed `component_id → ARGB` set.
    ///
    /// `ids` and `colors` are parallel: `colors[i]` is the fully-resolved ARGB the device
    /// (which owns the theme and palette) wants drawn for segment `ids[i]`. A mismatched pair
    /// of lengths is truncated to the shorter, so a malformed push degrades to fewer coloured
    /// segments rather than a panic.
    ///
    /// This is the entire cost of a recolour: the map is rebuilt and read at draw, and no
    /// vertex buffer is touched — the geometry was tessellated once and stays. Ids not present
    /// after this call draw nothing (see [`record_traffic`](Self::record_traffic)).
    pub fn set_traffic_speeds(&mut self, ids: &[u64], colors: &[u32]) {
        let n = ids.len().min(colors.len());
        self.traffic_colors.clear();
        self.traffic_colors.reserve(n);
        for (&id, &color) in ids.iter().zip(colors).take(n) {
            self.traffic_colors.insert(id, color);
        }
        self.bump_state();
    }

    /// Drop every pushed traffic colour, so the overlay draws nothing until the next push.
    ///
    /// What the host calls on toggle-off or when the viewport moves off the fetched squares:
    /// it clears the visible overlay in the very next frame without waiting for the
    /// toggle-driven re-tessellation to evict the geometry.
    pub fn clear_traffic(&mut self) {
        self.traffic_colors.clear();
        self.bump_state();
    }

    /// Turn drawing of the traffic overlay on or off for subsequent frames.
    ///
    /// The geometry is gated at tessellation by the same toggle, so this is only the
    /// per-frame guard that stops resident meshes drawing in the brief window between a
    /// toggle-off and the re-tessellation that removes them.
    pub fn set_traffic_enabled(&mut self, enabled: bool) {
        self.traffic_enabled = enabled;
        self.bump_state();
    }

    /// Resident tiles, the geometry they carry, and what the last frame actually submitted.
    ///
    /// For the frame log in [`crate::bridge`]. Guessing at why nothing appears on screen is
    /// far slower than asking the renderer what it actually drew — so `meshes` and `draws` are
    /// reported separately. They differ exactly when the style gates a resident layer out, and
    /// a diagnostic that conflated them would say roads are being drawn while they are not.
    pub fn stats(&self) -> (usize, usize, usize, usize) {
        let tiles = self.tiles.len();
        let meshes: usize = self.tiles.values().map(|t| t.layers.len()).sum();
        let triangles: usize = self
            .tiles
            .values()
            .flat_map(|t| t.layers.iter())
            .map(|l| l.index_count as usize / 3)
            .sum();
        (tiles, meshes, self.submitted_draws.get(), triangles)
    }

    /// The swapchain's current extent, for the frame log.
    pub fn extent(&self) -> (u32, u32) {
        (self.swapchain.extent.width, self.swapchain.extent.height)
    }

    /// Samples per pixel actually in use, for the frame log.
    ///
    /// Worth reporting because it is negotiated with the device rather than chosen: a
    /// driver that offers no multisampled colour attachment silently drops to 1, and
    /// aliased edges on one device but not another is otherwise a hard thing to explain.
    pub fn samples(&self) -> u32 {
        self.swapchain.samples.as_raw()
    }

    /// Is this tile resident **and** tessellated at the current toggle generation?
    ///
    /// A stale tile answers `false`, so the caller re-requests it exactly the way it
    /// requests one it has never seen. That is the whole re-tessellation mechanism: the
    /// old mesh keeps drawing until the new one lands, so a toggle change never blanks
    /// the map, and nothing is evicted or refetched.
    ///
    /// Kinds-aware (P5): when the generation has moved on, the tile gets a second
    /// opinion before it is re-requested. [`ToggleStamp::stale_under`](crate::style::ToggleStamp::stale_under)
    /// proves a toggle change affects nothing here — a chip change on a tile with no
    /// POIs, a transit flip with no transit features — and the tile stays fresh without
    /// a rebuild. The generation fast path is unchanged: an exact match never consults
    /// the stamp.
    pub fn has_tile(
        &self,
        key: u64,
        generation: u32,
        toggles: &LayerToggles,
        kinds: &KindFilter,
    ) -> bool {
        self.tiles.get(&key).is_some_and(|tile| {
            tile.generation == generation || !tile.toggle_stamp.stale_under(toggles, kinds)
        })
    }

    /// Whether another frame would do something this one did not — the renderer's half of
    /// the host's on-demand frame loop (see `SurfaceMapRenderer`).
    ///
    /// The host drives frames only while something has changed, and none of the state below
    /// is visible to it: it cannot see a swapchain that still needs rebuilding, buffers
    /// waiting out their in-flight grace, or a cross-fade partway up. Answering `false` while
    /// any of them holds stops the clock on work in progress, so each errs toward another
    /// frame.
    ///
    /// Tiles still in flight are deliberately **not** here — that is `MapHandle::in_flight`,
    /// which lives on the bridge side and is checked there.
    ///
    /// # `retiring` and `transients` are only safe wake reasons while nothing refills them
    ///
    /// Both hold GPU memory that a later frame frees in `collect_retired`, so draining them is
    /// real work worth a frame. But that only holds while every push is a **one-off event** —
    /// [`set_route`](Self::set_route) retiring the previous route mesh, or a tile being
    /// evicted. A path that pushes once per *frame* turns this into a self-sustaining loop:
    /// the vec refills as fast as it drains, so `needs_frame` never goes false and the map
    /// pins at 60fps with nothing changing, which is the exact defect the on-demand loop
    /// exists to remove.
    ///
    /// `draw_fill_batch` was such a path and is now on the [`ScratchRing`], the same
    /// conversion `draw_symbol_batch` had — which is why a screenful of labels cannot pin the
    /// loop. **Anything added here that draws every frame must suballocate from the ring
    /// rather than push a transient**, or it will silently reintroduce this.
    ///
    /// Read after [`render`](Self::render), which is what makes the fade check exact:
    /// `last_camera` is that frame's camera, so `time_seconds` is the clock the fade was just
    /// evaluated against rather than a frame-old one.
    pub fn needs_frame(&self) -> bool {
        if self.needs_rebuild {
            return true;
        }
        if !self.retiring.is_empty() || !self.transients.is_empty() {
            return true;
        }
        let Some(now) = self.last_camera.map(|c| c.time_seconds) else {
            // Nothing has been drawn yet, so there is nothing to compare a fade against and
            // the first frame is owed regardless.
            return true;
        };
        self.tiles
            .values()
            .any(|tile| select::fade_in_progress(now, tile.uploaded_at, select::LOD_FADE_SECONDS))
    }

    /// Draw one frame.
    ///
    /// Returns `Ok(false)` when the frame was skipped because the swapchain needs
    /// rebuilding, which the caller answers by calling again.
    /// Draw one frame.
    ///
    /// `filter` is the active category filter. It reaches the gate as well as tessellation,
    /// because a chip both narrows which POIs are drawn and pulls its own kinds in earlier than
    /// the ambient map shows them — see [`Layer::draws_at_focused`].
    ///
    /// When [`moon_active`](crate::camera::moon_active) holds, everything below is
    /// skipped and the Moon disc draws instead (see
    /// [`record_moon`](Self::record_moon)): no tile selection has run for it (the
    /// frame bridge skips fetch on Moon frames), so there is nothing resident to
    /// draw and no symbols to place.
    pub fn render(
        &mut self,
        camera: &Camera,
        layers: &[Layer],
        palette: Palette,
        clear: u32,
        filter: &crate::style::KindFilter,
    ) -> Result<bool, String> {
        self.last_camera = Some(*camera);
        if self.width == 0 || self.height == 0 {
            return Ok(true);
        }
        if self.needs_rebuild {
            self.rebuild()?;
            self.needs_rebuild = false;
            // A new swapchain must present at least once: the stored version describes
            // an image on the old one, and reusing it would skip that present.
            self.forget_submitted();
        }
        // Moon fast path: the textured disc INSTEAD of the vector frame (no tile
        // draws, no symbols, no overlays). Textures missing (host never pushed)
        // draws nothing but the clear colour — the frame still presents, so the
        // globe reads as empty space rather than freezing the loop.
        if crate::camera::moon_active(camera) {
            return self.render_moon(camera, clear);
        }

        // MapLibre-style submit early-out: when nothing changed since the last submitted
        // frame, re-recording + `queue_submit` would present a bit-identical image, so the
        // whole fence/acquire/record/submit/present sequence is skipped. The previously
        // presented image persists — `Ok(true)` tells the host a frame was "drawn", which
        // keeps the on-demand loop's accounting (grace windows, retry delays) exact.
        //
        // The version covers the camera pose (exact bits), the animation clock (50 ms bins,
        // so dash-phase animation still steps during grace windows), tile residency,
        // placement and all push-constant state. The `needs_frame` gate is what makes this
        // exact rather than optimistic: a fade in progress, pending retirements or a
        // rebuild all force a real submit, as does any epoch bump. Placement runs here —
        // before any fence or acquire — because its version is part of the key, and it is
        // free on a hit (a key compare) on exactly the frames this skips.
        self.scratch_ordered.clear();
        self.scratch_ordered.extend(self.tiles.keys().copied());
        self.scratch_ordered
            .sort_by_key(|k| self.tiles.get(k).map(|t| t.z).unwrap_or(0));
        // Covered-stand-in suppression, before the early-out key: a fade
        // completing newly suppresses a stand-in, so the map must be current
        // for the version below to differ and this frame to actually submit.
        self.refresh_suppression(camera.time_seconds);
        let extent = self.swapchain.extent;
        let accepted = self.place_symbols(camera, layers, &self.scratch_ordered, extent, filter);
        // The pick snapshot tracks the accept-set, not the submit: a reused placement keeps
        // its ids, so this is a no-op on exactly the frames the early-out below skips — and
        // on a placement hit with an identical camera the boxes would re-project identically
        // anyway. Kept outside the skip so taps never read a stale frame's boxes.
        let mut accept_ids: Vec<u64> = accepted.keys().copied().collect();
        accept_ids.sort_unstable();
        if self.last_accept_ids.borrow().as_ref() != Some(&accept_ids) {
            self.refresh_placed(camera, layers, &accepted, extent);
            *self.last_accept_ids.borrow_mut() = Some(accept_ids);
        }
        let version = super::mod_extra::FrameVersion::current(
            camera,
            palette,
            self.residency_epoch.get(),
            self.placement_version.get(),
            self.state_epoch.get(),
            self.suppression_epoch.get(),
        );
        if !self.needs_frame() && self.last_frame_version == Some(version) {
            // A skip implies a placement reuse (a recompute would have bumped the version
            // above), so all three P0 counters are settled here: the skip itself, the hit
            // behind it, and a zero cull (no draw pass ran to cull anything).
            self.step_times.borrow_mut().record(Step::EarlyOut, 1);
            self.step_times
                .borrow_mut()
                .record(Step::PlacementHit, 1);
            self.step_times
                .borrow_mut()
                .record(Step::CullSkipped, 0);
            return Ok(true);
        }

        let frame = &self.frames[self.frame_index];
        let device = &self.context.device;
        let fence_start = std::time::Instant::now();
        unsafe {
            device
                .wait_for_fences(std::slice::from_ref(&frame.in_flight), true, u64::MAX)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
        }
        let fence_nanos = nanos_since(fence_start);
        // Only now is it safe to free what previous frames referenced.
        let retire_start = std::time::Instant::now();
        self.collect_retired();
        // Same fence, same reason: it says this frame slot's previous commands have retired, so
        // nothing is still reading the scratch they drew from.
        unsafe { self.scratch[self.frame_index].reset() };
        let retire_nanos = nanos_since(retire_start);
        let fence_sample = fence_nanos;
        self.step_times
            .borrow_mut()
            .record(Step::FenceWait, fence_sample);
        self.step_times
            .borrow_mut()
            .record(Step::CollectRetired, retire_nanos);

        let frame = &self.frames[self.frame_index];
        let acquire_start = std::time::Instant::now();
        let acquired = unsafe {
            self.swapchain.loader.acquire_next_image(
                self.swapchain.swapchain,
                u64::MAX,
                frame.image_available,
                vk::Fence::null(),
            )
        };
        self.step_times
            .borrow_mut()
            .record(Step::Acquire, nanos_since(acquire_start));
        let image_index = match acquired {
            Ok((index, _suboptimal)) => index,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
                self.needs_rebuild = true;
                return Ok(false);
            }
            Err(e) => return Err(format!("acquire_next_image {e:?}")),
        };

        // Copy frame handles out by value so no borrow of `self.frames` lives
        // across the `&mut self` calls below (`record` uploads transients).
        let (command_buffer, in_flight, image_available, render_finished) = {
            let frame = &self.frames[self.frame_index];
            (
                frame.command_buffer,
                frame.in_flight,
                frame.image_available,
                frame.render_finished,
            )
        };
        unsafe {
            // Clone the device handle (ash::Device is Clone): `record` takes
            // `&mut self` for transient symbol uploads, so no `&self.context`
            // borrow may live across the call.
            let device = self.context.device.clone();
            device
                .reset_fences(std::slice::from_ref(&in_flight))
                .map_err(|e| format!("reset_fences {e:?}"))?;
            let record_start = std::time::Instant::now();
            let record_outcome = self.record(
                command_buffer,
                image_index as usize,
                camera,
                layers,
                palette,
                clear,
                filter,
            );
            self.step_times
                .borrow_mut()
                .record(Step::RecordTotal, nanos_since(record_start));
            record_outcome?;

            let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
            let submit = vk::SubmitInfo::default()
                .wait_semaphores(std::slice::from_ref(&image_available))
                .wait_dst_stage_mask(&wait_stages)
                .command_buffers(std::slice::from_ref(&command_buffer))
                .signal_semaphores(std::slice::from_ref(&render_finished));
            let queue = self.context.queue;
            let swapchain = self.swapchain.swapchain;
            let loader = self.swapchain.loader.clone();
            let submit_start = std::time::Instant::now();
            device
                .queue_submit(queue, std::slice::from_ref(&submit), in_flight)
                .map_err(|e| format!("queue_submit {e:?}"))?;
            self.step_times
                .borrow_mut()
                .record(Step::Submit, nanos_since(submit_start));

            let swapchains = [swapchain];
            let indices = [image_index];
            let present = vk::PresentInfoKHR::default()
                .wait_semaphores(std::slice::from_ref(&render_finished))
                .swapchains(&swapchains)
                .image_indices(&indices);
            let present_start = std::time::Instant::now();
            let present_outcome = loader.queue_present(queue, &present);
            self.step_times
                .borrow_mut()
                .record(Step::Present, nanos_since(present_start));
            match present_outcome {
                Ok(false) => {}
                // `VK_SUBOPTIMAL_KHR` is a success code, not an error: the swapchain still
                // presents correctly, it just no longer matches the surface's ideal properties.
                // Rebuilding on it is disproportionate — `rebuild` is a `device_wait_idle`, a
                // swapchain teardown and a recompile of every pipeline, on the Choreographer
                // callback, inside a frame.
                //
                // It is also not self-limiting. Nothing guarantees the rebuild clears the
                // condition, and on Android it routinely does not: a swapchain whose
                // `preTransform` does not match the display's `currentTransform` reports
                // suboptimal on *every* present, so the old code recompiled all eleven pipelines
                // every frame for as long as that held. Note `acquire_next_image` above already
                // discards its own suboptimal flag, so this is now consistent rather than novel.
                //
                // The two cases that genuinely invalidate the swapchain still rebuild:
                // `ERROR_OUT_OF_DATE_KHR` here and at acquire, and `resize` from the host.
                Ok(true) => {}
                Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => self.needs_rebuild = true,
                Err(e) => return Err(format!("queue_present {e:?}")),
            }
        }

        self.frame_index = (self.frame_index + 1) % FRAMES_IN_FLIGHT;
        // The submit landed: this version is what is on screen, so an identical next
        // frame may skip. Set only here — skipped frames, rebuilds and failures leave
        // the previous (or no) version in place. `EarlyOut` reads 0 for a real frame so
        // the overlay row tracks skips rather than going stale.
        self.last_frame_version = Some(version);
        self.step_times.borrow_mut().record(Step::EarlyOut, 0);
        Ok(true)
    }
}
