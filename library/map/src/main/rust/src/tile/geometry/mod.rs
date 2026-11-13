//! Turns a decoded tile into per-layer triangles.
//!
//! Pure CPU and a pure function: no Vulkan types, no JNI, so the expensive half of a
//! frame is testable on the host and can run on any thread. This is the step the plan
//! expects to be the actual bottleneck — a vector map is slow on the CPU, not the GPU.
//!
//! Reads a `.mamaps` body rather than an MVT tile, and that is most of why the format exists: a
//! feature's `kind` is a `u16` tested against a sorted slice instead of a property-map lookup
//! yielding a `String`, and a part's points are a slice of an already-decoded arena instead of a
//! geometry-command walk. Nothing downstream of here changed - fills are 3 floats a vertex,
//! strokes 8, ribbons 7, and the shaders read the trailing draped z.

mod arrows;
mod build;
mod buildings;
mod convert;
mod mesh;
mod regions;
mod split;
mod terrain;
mod traffic;
mod wash_overzoom;

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_wash;
#[cfg(test)]
mod tests_drape;
#[cfg(test)]
mod tests_extra;
#[cfg(test)]
mod tests_extra5;
#[cfg(test)]
mod tests_extra2;
#[cfg(test)]
mod tests_extra6;
#[cfg(test)]
mod tests_extra3;
#[cfg(test)]
mod tests_extra7;
#[cfg(test)]
mod tests_extra4;
#[cfg(test)]
mod tests_extra8;

pub use build::{build, build_toggled};

/// Whether tessellation may skip its ring-repair pass for an archive whose header sets
/// `FLAG_RINGS_VALIDATED`. **Off**: the flag is not trustworthy on the published planets.
///
/// Measured on `planet4.mamaps` (10 regions, z6-z14, analysis/ringprobe): holed polygons whose
/// holes properly cross their exterior or each other are 9-11% at z6-z7, 4-7% at z8-z9, ~1-2% at
/// z10-z11 and 0.1-1% at z12-z14 — simplification/quantisation after the writer's validation
/// pass. Trusting the flag hands those self-intersecting bridged rings straight to earcut, which
/// answers with slivers across the polygon (forest/water/landuse washes). Repairing costs
/// +2-8% tessellation time per tile at z12-z14 and ~+25% at z8-z10 (same measurement), paid once
/// per tile build, not per frame. Flip back on only for an archive whose writer validates after
/// its final coordinate change.
pub const TRUST_RINGS_VALIDATED: bool = false;
pub use wash_overzoom::{needs_wash_underlay, parent_of, stitch_parent_wash, WASH_CAP_ZOOM};
pub use mesh::{
    labels_by_layer, BuildingMesh, CarriagewayMesh, LayerMesh, RegionMesh, ShapedLabel, TerrainMesh,
    TileMesh, TrafficMesh, ROAD_LANE_MIN_ZOOM, TRAFFIC_MIN_ZOOM,
};
pub(crate) use terrain::{sample_ground_metres, tile_ground_width_m};
