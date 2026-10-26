//! Which tiles cover the viewport.
//!
//! A pure function of the camera, so it cannot accidentally depend on GPU state. Three
//! behaviours are load-bearing:
//!
//! * **Overzoom.** Past the archive's `max_zoom` the same tiles are kept and drawn
//!   larger, because the tile span is `256 * 2^(zoom - z)`. The archive stops at z16 and
//!   users zoom past it; without this the map goes blank.
//! * **No horizontal wrap.** The renderer draws one world, not a repeating one. `maps`
//!   gets antimeridian wrapping from MapLibre; the five consumer apps show a city, and
//!   duplicating every tile's geometry to render the seam twice would cost more than it
//!   is worth.
//! * **Rotation widens the footprint.** Coverage comes from
//!   [`crate::camera::Camera::viewport_bounds`], which is the bounding box of the *rotated* viewport,
//!   not `origin .. origin + size`. A heading-up camera at 45 degrees covers `sqrt(2)`
//!   times the viewport across each axis, and a selection derived from the unrotated box
//!   leaves the four corners of the display blank — which is exactly where the road the
//!   driver is about to turn onto is.

pub mod coverage;
pub mod cover;
pub mod fade;
pub mod resident;
pub mod tile_id;

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_cover;
#[cfg(test)]
mod tests_part2;
#[cfg(test)]
mod tests_part3;

pub use coverage::{bound, visible};
pub use cover::{relevant_keys, redundant_tiles, COVER_MAX_DEPTH};
pub use fade::{
    fade_in_progress, has_resident_ancestor, lod_fade_alpha, tile_lod_alpha, LOD_FADE_SECONDS,
};
pub use resident::{evict, resident_set, stands_in_for_visible, zoom_out_burst, ANCESTOR_DEPTH, DESCENDANT_DEPTH, RESIDENT_BYTE_BUDGET, RESIDENT_TILE_CAP};
pub use tile_id::TileId;
