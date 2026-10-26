//! Geographic overlay polylines: the navigation route drawn inside the renderer.
//!
//! On the phone the route is a Compose overlay *above* the map surface. Android Auto
//! hands the app a bare `Surface` with no view hierarchy, so there is nothing to put an
//! overlay in and the line has to be drawn by the renderer itself.
//!
//! # A list of coloured segments, one casing
//!
//! The phone colours the route per navigation step — traffic red/amber/green, transit
//! brand colours, a travelled grey behind the puck — and that colouring now lives here
//! rather than in a Compose overlay, so the route pans in lock-step with the basemap the
//! way the puck, region mask and traffic already do. A route is therefore a *list* of
//! coloured runs: [`tessellate`] takes a slice of [`RouteSegment`], each its own polyline
//! and fill colour, and strokes them into one shared mesh.
//!
//! The casing is drawn once over the whole mesh at the wider width and one colour, then
//! each segment's fill is drawn over it in segment colour — so the outline stays
//! continuous across the colour changes while every run keeps its own fill. The car
//! (Android Auto) passes a single segment through the same path, which is the old
//! single-colour route with no special case.
//!
//! # It reuses the road tessellator, and it has to
//!
//! [`crate::tess::stroke`] already strokes every road in the basemap: miter joins, butt
//! caps, a unit join normal per vertex and the width supplied as a push constant. A route
//! is a line with a width, so it goes through the same function and comes out in the same
//! vertex format, drawn by the same `line` pipeline and the same shaders. Writing a second
//! tessellator would be a second set of join bugs to find.
//!
//! # Why there is no re-tessellation on zoom
//!
//! `stroke` wants tile-local coordinates in `0..extent` and produces positions in `0..1`.
//! A route is not tile-bound, so it gets a square of its own: its bounding box, squared
//! off, with the geometry normalised into it. That square is then placed by
//! [`crate::camera::Camera::world_quad_to_clip`] exactly the way a tile is placed by
//! [`crate::camera::Camera::tile_to_clip`].
//!
//! The pay-off is that **the mesh is zoom-independent**. Web Mercator is a pure scale in
//! zoom — `project(lon, lat, z)` is `project(lon, lat, 0) * 2^z` — so normalising by the
//! bounding box divides that factor out and the local coordinates are the same number at
//! every zoom. Only the matrix and the pixel width change per frame, and both are already
//! per-frame values. So a route is tessellated **once, when it is set**, and a car that
//! sits in a navigation session for an hour re-tessellates nothing: no per-frame work, no
//! per-zoom-step work, no threshold to tune. That was the thing worth getting right here,
//! because the phone can afford to redo geometry on a zoom step and a car on battery
//! cannot.
//!
//! The cost is precision. Positions are `f32` in `0..1` against a span that is the whole
//! route's bounding box, so a continent-crossing route resolves to about a hundredth of a
//! Dp on screen — invisible — while a city-scale one is exact to well under a pixel.

use crate::camera::{project, WorldPx};
use crate::tess::stroke;

/// Local coordinate resolution of the route's bounding square.
///
/// A tile uses 4096; this is far finer because the square is the whole route rather than
/// one tile, and a long route quantised on a tile-sized grid would visibly stair-step.
/// `1 << 20` keeps quantisation to a millionth of the bounding box while staying exactly
/// representable in the `f32` the tessellator divides it into.
pub const EXTENT: u32 = 1 << 20;

/// How a route line is painted. Widths are Dp; colours are ARGB, as everywhere else here.
///
/// The fill colour is **not** here: it is per-segment (see [`RouteSegment`]), because the
/// whole point of the route overlay is to colour each run differently. What stays shared
/// is the geometry of the stroke — the line width, and the casing that outlines the lot.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RouteStyle {
    /// The route line's own width.
    pub width_dp: f32,
    /// The casing's width **on each side** of the route line. Zero draws no casing.
    ///
    /// A route without one is genuinely hard to follow: it crosses roads of its own
    /// colour family and a busy basemap, and the outline is what separates it from them.
    pub casing_dp: f32,
    /// The casing's colour, ARGB. Unused when [`casing_dp`](Self::casing_dp) is zero.
    ///
    /// Passed rather than derived from the palette, because the reference draws a **white**
    /// casing on a light basemap — a contrast rule computed here would pick a dark one and
    /// silently change the look the port is supposed to restore.
    pub casing_color: u32,
}

/// One coloured run of a route: a polyline and the ARGB its fill is painted.
///
/// A route arrives as a list of these — a driving route split into traffic bands, a transit
/// route into per-line colours, an active navigation leg into a travelled grey behind the
/// puck and the mode colour ahead. Each run carries its whole polyline, including the point
/// it shares with its neighbours, so the strokes meet end to end under the shared casing.
pub struct RouteSegment {
    /// The run's lon/lat points, in order.
    pub points: Vec<(f64, f64)>,
    /// The run's fill colour, ARGB.
    pub color: u32,
}

/// Where one coloured run lands in a [`RouteMesh`]'s shared index buffer.
///
/// The whole route is stroked into one vertex/index pair so the casing can be drawn over it
/// in a single pass; each run's fill is then a `draw_indexed` of its own slice of that index
/// buffer, in its own colour.
#[derive(Clone, Copy, Debug)]
pub struct RouteSegmentRange {
    /// The run's fill colour, ARGB.
    pub color: u32,
    /// First index into [`RouteMesh::indices`] belonging to this run.
    pub index_offset: u32,
    /// How many indices this run owns.
    pub index_count: u32,
}

/// Where a route's bounding square sits and how it is painted.
///
/// Separate from the vertices so the renderer can keep it beside the GPU buffers after the
/// CPU-side mesh has been dropped: it is 40 bytes, and it is everything a frame needs to
/// build the matrix and the two push blocks.
#[derive(Clone, Copy, Debug)]
pub struct RoutePlacement {
    /// The bounding square's top-left corner, in world px at zoom 0.
    pub origin: WorldPx,
    /// The bounding square's side, in world px at zoom 0.
    pub span: f64,
    /// How the two passes over this geometry are painted.
    pub style: RouteStyle,
}

/// A route tessellated into its own bounding square, ready to upload.
pub struct RouteMesh {
    /// Where it goes and how it looks.
    pub placement: RoutePlacement,
    /// [`stroke::FLOATS_PER_VERTEX`] floats per vertex, in the `line` pipeline's format.
    /// All segments share this one buffer.
    pub vertices: Vec<f32>,
    /// Triangle indices into [`vertices`](Self::vertices), all segments concatenated.
    pub indices: Vec<u32>,
    /// Each coloured run's slice of [`indices`](Self::indices), in draw order. The casing
    /// draws every index at once; the fills draw one slice each, in run colour.
    pub segments: Vec<RouteSegmentRange>,
}

impl RoutePlacement {
    /// The bounding square's origin and side in world px at `zoom`.
    ///
    /// Mercator scales by `2^zoom` and nothing else, so this is the whole of what the
    /// camera needs to place a mesh built at zoom 0.
    pub fn at_zoom(&self, zoom: f64) -> (WorldPx, f64) {
        let scale = 2f64.powf(zoom);
        (
            WorldPx {
                x: self.origin.x * scale,
                y: self.origin.y * scale,
            },
            self.span * scale,
        )
    }

    /// [`at_zoom`](Self::at_zoom) at the camera's zoom, shifted to the world copy nearest the
    /// camera centre: a route crossing the antimeridian is stored unwrapped past the world edge
    /// (`overlay_parts::tessellate`), so the copy beside the camera is the one to draw.
    pub fn at_zoom_near(&self, camera: &crate::camera::Camera) -> (WorldPx, f64) {
        let (mut origin, span) = self.at_zoom(camera.zoom);
        let world = crate::camera::world_size(camera.zoom);
        let centre_x = crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom).x;
        origin.x -= ((origin.x + span * 0.5 - centre_x) / world).round() * world;
        (origin, span)
    }

    /// The route line's half-width in **device px** — what a fill pass pushes.
    pub fn fill_half(&self, density: f32) -> f32 {
        self.style.width_dp * 0.5 * density
    }

    /// The casing's half-width in **device px**, or `None` when there is no casing.
    ///
    /// The casing stands `casing_dp` outside the route line on each side, so it is the
    /// fill half-width plus that. `None` when [`casing_dp`](RouteStyle::casing_dp) is zero:
    /// `line.vert` floors every band at half a pixel so a hairline still rasterises, so a
    /// zero-width casing pass would paint a pixel of casing colour poking out from under
    /// the route rather than nothing.
    pub fn casing_half(&self, density: f32) -> Option<f32> {
        (self.style.casing_dp > 0.0)
            .then(|| self.fill_half(density) + self.style.casing_dp * density)
    }
}

// `tessellate` lives in `overlay_parts` (file-length split); re-exported here so every
// existing `crate::overlay::tessellate` path keeps working.
pub use crate::overlay_parts::tessellate;

#[cfg(test)]
#[path = "overlay_tests.rs"]
mod tests;
