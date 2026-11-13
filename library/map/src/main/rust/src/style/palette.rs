//! Palette selection, the muted derivation, the lane-rendering switch and the
//! layer-table accessors.

use super::paint;
use super::{Layer, Variant};
use tilecodec::mamaps::dict;

/// Which basemap to paint: light or dark, and whether to mute it.
///
/// `muted` is what `weather` needs and what CARTO's Positron gave it: the basemap has to
/// recede so a colour-ramp overlay drawn on top of it stays readable. It is **derived**
/// rather than authored — every colour is blended toward the background by
/// [`MUTED_BLEND`] — which is what muting a basemap does, and it means there is one
/// palette to keep correct rather than four.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Palette {
    pub variant: Variant,
    pub muted: bool,
}

impl Palette {
    pub fn new(dark: bool, muted: bool) -> Palette {
        Palette {
            variant: Variant::from_dark(dark),
            muted,
        }
    }
}

/// How far a muted colour moves toward the background. Enough for an overlay to dominate,
/// little enough that roads still read as roads.
pub const MUTED_BLEND: f32 = 0.45;

/// Behind everything, before any tile has loaded.
///
/// This is the **water** colour, not the land colour. The sea is the thing a world map is
/// mostly made of, `water.kind` includes `ocean`, and any area with no tile yet is far more
/// likely to be sea than land — so an unloaded map should read as ocean with land appearing
/// on top of it, rather than the reverse.
pub fn background(variant: Variant) -> u32 {
    let (light, dark) = paint::style().background;
    match variant {
        Variant::Light => light,
        Variant::Dark => dark,
    }
}

/// The empty space around the globe: pure black in the dark palette, pure white in the light.
///
/// The globe is a ball floating in space, so what surrounds it must not read as sea. It used
/// to: the frame cleared to [`background`] (water) on globe frames too, so the area outside
/// the sphere was ocean-coloured and the planet's edge dissolved into it. Space is a fixed
/// colour rather than a style entry — it is not part of the map, and muting it would only
/// make the limb harder to see. The globe's own ocean is drawn as a disc in [`background`]
/// (see `Renderer::record_globe_backdrop`), so the sea on the ball is unchanged.
pub fn space(variant: Variant) -> u32 {
    match variant {
        Variant::Light => 0xFFFF_FFFF,
        Variant::Dark => 0xFF00_0000,
    }
}

/// The colour a frame clears to: [`space`] when the globe leaves part of the viewport
/// uncovered, [`background`] (water) otherwise.
///
/// When the sphere covers every corner (zoomed in past the point where its limb leaves the
/// screen) there is no space visible at all, so the frame clears straight to water and the
/// renderer skips the ocean disc — the zoomed-in globe pays exactly what the flat map does.
pub fn clear_color(camera: &crate::camera::Camera, variant: Variant) -> u32 {
    if crate::camera::globe_active(camera) && !camera.globe_covers_viewport() {
        space(variant)
    } else {
        background(variant)
    }
}

/// Whether the lane-level road rendering is built and drawn at all.
///
/// The one switch for the whole feature set: the `roads-carriageway` asphalt surface and its
/// painted markings, the `junction-connector` surface that continues it through an intersection,
/// the carriageway taper, and the road-surface turn arrows. Off for release; the code stays.
///
/// **This is the map surface, not the navigation lane guidance bar.** The two share the word
/// "lane" and nothing else: the guidance bar is a Compose overlay fed by the router's per-step
/// lane data, it has no connection to a style layer, and it draws whatever this says. Do not
/// read a blank map surface as the guidance bar being off, or vice versa.
/// It works by dropping the two [`Layer::carriageway`] layers from [`layers`], which is the one
/// point every part of the feature already funnels through. No carriageway layer means
/// `tile::geometry`'s carriageway branch is never taken (so no ribbon mesh, no taper), and
/// [`road_carriageway_layer`] answers `None`, which is what
/// [`crate::vulkan::renderer::Renderer::record_arrows`] gates the turn arrows on. Dropping the
/// layers rather than clearing the flag matters: a `carriageway` layer with the flag off would
/// fall through to the ordinary stroke path and draw a 40px grey band over every road at z20.
///
/// The plain road line layers (`roads-major` and friends) are untouched and already draw
/// *underneath* the carriageway pass, so removing that pass uncovers them rather than leaving a
/// hole — the map returns to its pre-carriageway appearance.
///
/// Not a [`super::Toggle`]: those are a host-app opt-in the user sees. This is a release kill switch,
/// so it is a `const` and flipping it is a code change.
///
/// # Before turning this back on
///
/// Check that the frame loop can still idle. Turning this on re-enables
/// [`crate::vulkan::renderer::Renderer::record_arrows`], which uploads a transient buffer per
/// arrow batch per frame; if the on-demand loop still treats a non-empty transient list as
/// "needs another frame", arrows re-arm it every frame and the map redraws forever with nothing
/// moving. The switch being off is the only reason that is unreachable today, so it will surface
/// as a battery regression the day the feature returns rather than as a failing test.
///
/// The tests that pin carriageway behaviour turn it on by reading
/// [`layers_with_lane_rendering`] instead, so they keep asserting against the real style rather
/// than being weakened to match the switch.
pub const LANE_RENDERING: bool = false;

/// Every layer the renderer draws, in draw order.
///
/// A borrow rather than a fresh list: the flat style is a constant table that happens to need a
/// parser, so it is parsed once for the process and handed out.
///
/// Carries no carriageway layer while [`LANE_RENDERING`] is off.
pub fn layers() -> &'static [Layer] {
    &paint::style().layers
}

/// Every layer the style declares, carriageways included, whatever [`LANE_RENDERING`] says.
///
/// For the tests that pin carriageway behaviour: they assert against the feature as authored, so
/// they have to name it explicitly rather than read a layer set the switch may have emptied.
#[cfg(test)]
pub fn layers_with_lane_rendering() -> &'static [Layer] {
    &paint::style_with_lane_rendering().layers
}

/// The road carriageway layer, whose zoom window gates the per-lane road detail and whose width
/// ramp says how wide one lane of it is.
///
/// Matched on [`Layer::carriageway`] **and** on the roads source, because the flag alone does not
/// name one layer: `junction-connector` draws a surface too, and would answer with a connector's
/// single lane. Its predecessor matched [`Layer::lane_fan`] — "does this layer have a spread" — and
/// `transit-rail` has one for its corridor colours and is declared first, so matching that way
/// silently answered with the rail layer, whose `minzoom` is 8 against the road detail's 16. That
/// is what drew turn arrows from z8, offset by the rail corridor's ramp instead of the road's.
///
/// Naming the source restores what that fix did before the `carriageway` flag replaced it, and is
/// what keeps the answer out of the hands of the declaration order in `basemap.flat.json`: exactly
/// one layer matches, so a reordered style or a third carriageway layer cannot re-point the gate.
pub fn road_carriageway_layer(layers: &[Layer]) -> Option<&Layer> {
    layers
        .iter()
        .find(|l| l.carriageway && l.source_layer_id == dict::LAYER_ROADS)
}
