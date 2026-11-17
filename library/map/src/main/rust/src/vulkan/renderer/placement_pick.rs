//! Tap picking against the placed labels and markers of the last frame.
//!
//! File-length split from [`placement`](super::placement) (see `placement.rs`):
//! `pick_labels` filters the CPU snapshot `refresh_placed` built; `pick_at`
//! runs the GPU id-buffer pass for markers. Both moved verbatim.

use super::{Overlay, PlacedHit, Renderer, QUAD_INDICES};
use crate::marker::Marker;

impl Renderer {
    /// Task-17 pick: the placed labels of the last frame whose screen boxes
    /// contain the query box (device px), in placement order (topmost first).
    /// A linear scan — hundreds of labels, no index needed. Called from the
    /// JNI pick path with Dp already converted to device px by the caller.
    pub fn pick_labels(&self, query: (f32, f32, f32, f32)) -> Vec<PlacedHit> {
        let (qx0, qy0, qx1, qy1) = query;
        self.placed
            .borrow()
            .iter()
            .filter(|h| h.rect.0 <= qx1 && h.rect.2 >= qx0 && h.rect.1 <= qy1 && h.rect.3 >= qy0)
            .cloned()
            .collect()
    }

    /// The marker id under the device-pixel `(x, y)`, or `0` when the tap hit no marker.
    ///
    /// The GPU-picking counterpart of [`pick_labels`](Self::pick_labels): where labels are picked
    /// against the CPU snapshot of the last placed frame, markers are picked by rendering their
    /// ids into an offscreen `R32_UINT` buffer with the last frame's camera and reading back the
    /// tapped pixel (see [`crate::vulkan::pick`]). That is what keeps a pin tappable under tilt,
    /// where its screen box is no longer a plain projection of its lon/lat.
    ///
    /// Returns the marker's own id (the value the host set on it), so the host maps the tap back to
    /// its feature without matching on position. `0` covers "no marker here", "no frame drawn yet",
    /// and a pick that could not run — all of which the host treats as "fall through to the next
    /// probe", exactly as an empty [`pick_labels`](Self::pick_labels) result is treated.
    pub fn pick_at(&mut self, x: u32, y: u32) -> u64 {
        let Some(camera) = self.last_camera else {
            return 0;
        };
        // The markers this frame would draw, in draw order, so the topmost pin wins the pixel.
        let markers: Vec<Marker> = self
            .overlays
            .iter()
            .filter_map(|overlay| match overlay {
                Overlay::Markers(m, _) => Some(m.iter().copied()),
                _ => None,
            })
            .flatten()
            .collect();
        if markers.is_empty() {
            return 0;
        }
        let extent = self.swapchain.extent;
        let quad_indices = QUAD_INDICES.len() as u32;
        match unsafe {
            self.pick.at(
                &self.context,
                self.command_pool,
                self.quad.vertices.buffer,
                self.quad.indices.buffer,
                quad_indices,
                extent,
                &camera,
                &markers,
                x,
                y,
            )
        } {
            Ok(id) => id,
            Err(e) => {
                eprintln!("pick_at failed: {e}");
                0
            }
        }
    }
}
