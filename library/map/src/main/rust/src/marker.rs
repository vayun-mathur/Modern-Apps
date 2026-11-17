//! Dynamic map markers: the app's pins, and (WS-F) simulated transit vehicles.
//!
//! A marker is the sprite counterpart of the user puck ([`crate::vulkan::renderer::UserPuck`]):
//! a screen-anchored icon glued to a ground `lon`/`lat`, billboarded upright under tilt through
//! [`crate::camera::Camera::screen_quad_to_clip`], and drawn from the shared unit quad by the
//! sprite pipeline against the process-global sprite atlas ([`crate::tile::sprite`]). Moving the
//! pins into the renderer is what stops them trailing the basemap on a pan or a tilt the way the
//! Compose overlays did.
//!
//! # The shared contract with WS-F (transit vehicles)
//!
//! WS-F draws simulated transit vehicles through this same path: an [`crate::vulkan::renderer`]
//! `Overlay::Vehicles(Vec<Marker>)` variant beside `Overlay::Markers`, the same billboarded
//! sprite draw, and the same atlas. A vehicle is just a [`Marker`] whose [`icon`](Marker::icon)
//! names a mode sprite (bus/tram/train/ferry), so the bulk many-sprites case drops in with no new
//! machinery — only a new `Overlay` arm and a bulk setter. The icon ids WS-F needs are already
//! reserved in [`icon`] below.
//!
//! # Why an icon *id* rather than a name across the JNI boundary
//!
//! Kotlin passes a small integer per marker, not a string: the boundary stays allocation-free and
//! ABI-stable (see [`crate::bridge`]), and the atlas can grow — a dedicated pin sheet added on the
//! build side — without changing the JNI signature. The renderer resolves the id to a sprite-atlas
//! **name** here, and a name the sheet does not carry simply draws nothing, exactly as a POI with
//! no icon does ([`crate::tile::sprite::SpriteAtlas::get`]).

/// One marker: a stable id for picking, a ground position, which atlas icon
/// to draw, and an optional route-colour ring.
///
/// [`id`](Self::id) is the app's own stable feature id (a pin's parking/search/saved id, or a
/// vehicle's trip id), returned verbatim by the id-buffer pick so the host can rejoin the tap to
/// its own data — it is *not* interpreted here.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Marker {
    /// The host's stable id for this marker, echoed back by [`crate::vulkan::renderer`] picking.
    pub id: u64,
    /// Ground longitude in degrees.
    pub lon: f64,
    /// Ground latitude in degrees.
    pub lat: f64,
    /// Which atlas icon to draw — see [`icon`] and [`icon_sprite_name`].
    pub icon: u32,
    /// Route colour (`0xRRGGBB`) for the ring drawn under a vehicle sprite so
    /// the icon reads in its line's colour; `0` draws no ring. Carried only on
    /// the vehicles path today (app pins pass `0`) — see
    /// [`crate::vulkan::renderer`] vehicle rings.
    pub colour: u32,
}

/// The screen size a marker icon is drawn at, in Dp.
///
/// Screen-constant like the puck's radius: the icon keeps this size as the camera zooms, because
/// it is placed by [`crate::camera::Camera::screen_quad_to_clip`] (a fixed Dp extent) rather than
/// by a tile matrix. A touch larger than a POI icon (19 Dp) so an app pin reads as foreground.
pub const MARKER_SIZE_DP: f32 = 28.0;

/// The screen size a marker's name label is drawn at, in Dp.
///
/// Screen-constant like the icon beside it: a pin is not tile data, so its label cannot follow
/// a zoom ramp the way a POI's does. 12 Dp is the `places-locality` size at browse zooms, which
/// is what a pin label reads alongside.
pub const MARKER_LABEL_DP: f32 = 12.0;

/// The gap between a marker icon's edge and its name label's start, in Dp.
///
/// The POI `text-offset` of 1.1 em is measured from the anchor (the icon's centre) for a 19 Dp
/// icon; scaled to a 28 Dp icon that same daylight is this gap past the half-extent.
pub const MARKER_LABEL_GAP_DP: f32 = 4.0;

/// A marker label's text colour, light and dark.
///
/// The neutral `places-locality` recipe verbatim: markers span kinds (parking, search, saved,
/// family) so no kind colour fits, and this grey is the proven legible neutral on both
/// basemaps with the halos below.
pub const MARKER_LABEL_LIGHT: u32 = 0xFF5C_5C5C;
/// A marker label's text colour on the dark basemap (see [`MARKER_LABEL_LIGHT`]).
pub const MARKER_LABEL_DARK: u32 = 0xFF5C_5C5C;
/// A marker label's halo colour on the light basemap (see [`MARKER_LABEL_LIGHT`]).
pub const MARKER_LABEL_HALO_LIGHT: u32 = 0xFFFF_FFFF;
/// A marker label's halo colour on the dark basemap (see [`MARKER_LABEL_LIGHT`]).
pub const MARKER_LABEL_HALO_DARK: u32 = 0xFF0D_1B2A;

/// A drawn marker icon's half-extents in Dp for `sprite`.
///
/// The single definition both the icon draw and the POI-placement blocker read: the icon
/// draws at [`MARKER_SIZE_DP`] on its larger side keeping its aspect ratio, so the box the
/// placer blocks with is the box the GPU draws — never a second implementation of it.
pub fn marker_icon_half_extents(sprite: crate::tile::sprite::Sprite) -> (f32, f32) {
    let scale = MARKER_SIZE_DP / sprite.width_dp.max(sprite.height_dp).max(1e-3);
    (
        sprite.width_dp * scale * 0.5,
        sprite.height_dp * scale * 0.5,
    )
}

/// Icon ids: the shared contract with WS-F. Kotlin passes these as ints; the renderer resolves
/// each to a sprite-atlas name via [`icon_sprite_name`].
///
/// The pin ids (0–4) are WS-C's; the vehicle ids (5–8) are reserved for WS-F so a mode maps to a
/// sprite through the same table and draw path. Ids are append-only — a value's meaning never
/// changes, so a host built against an older id set keeps working.
pub mod icon {
    /// The saved parking spot.
    pub const PARKING: u32 = 0;
    /// A transit stop pin (the app's live-departures pin, distinct from the basemap POI).
    pub const TRANSIT_STOP: u32 = 1;
    /// A search-result pin.
    pub const SEARCH: u32 = 2;
    /// A saved-place pin (home/work/starred).
    pub const SAVED: u32 = 3;
    /// A family-member location pin.
    pub const FAMILY: u32 = 4;

    /// WS-F: a bus vehicle.
    pub const VEHICLE_BUS: u32 = 5;
    /// WS-F: a tram/light-rail vehicle.
    pub const VEHICLE_TRAM: u32 = 6;
    /// WS-F: a train/subway vehicle.
    pub const VEHICLE_TRAIN: u32 = 7;
    /// WS-F: a ferry vehicle.
    pub const VEHICLE_FERRY: u32 = 8;
}

/// The sprite-atlas name an [`icon`] id resolves to, or `None` for an unknown id.
///
/// # Provisional pin art
///
/// The current sprite sheet (`assets/sprites/sprites@2x.png`) carries the Protomaps POI icons,
/// which have no dedicated *pin* pictograms, so the remaining pin ids map to the closest
/// existing sprite so a marker is visible today rather than blank. A follow-up build-side
/// asset pass can add dedicated `pin-*` sprites and repoint these names with no code change
/// beyond this table — the JNI ids stay the same.
///
/// # Vehicle and family art
///
/// The vehicle ids resolve to dedicated `vehicle-*` sprites: solid transport-blue badges with
/// white Maki glyphs, built by `analysis/spritepack/pack.py` alongside the `fuel`/`hotel`/`bank`
/// POI additions. Solid rather than the pale POI badge on purpose — vehicles dwell exactly on
/// stops, and pale badges read as duplicated station POIs (seen on-device 2026-09-14).
///
/// The family id resolves to a dedicated `family` sprite the same way: a solid family-indigo
/// badge with a white person glyph, so a family member never reads as a starred POI.
pub fn icon_sprite_name(icon: u32) -> Option<&'static str> {
    let name = match icon {
        // Pins — provisional mappings onto existing POI sprites (see the doc above).
        icon::PARKING => "fuel",
        icon::TRANSIT_STOP => "bus_stop",
        icon::SEARCH => "attraction",
        icon::SAVED => "artwork",
        icon::FAMILY => "family",
        // Vehicles (WS-F) — dedicated solid-badge sprites, not POI icons.
        icon::VEHICLE_BUS => "vehicle-bus",
        icon::VEHICLE_TRAM => "vehicle-tram",
        icon::VEHICLE_TRAIN => "vehicle-train",
        icon::VEHICLE_FERRY => "vehicle-ferry",
        _ => return None,
    };
    Some(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every declared icon id resolves to a name the sprite sheet actually carries, so a marker
    /// pushed by the host draws its icon rather than silently nothing. If the sheet is ever
    /// repacked without one of these, this fails here rather than as a blank pin on a device.
    #[test]
    fn every_icon_id_resolves_to_a_sprite_in_the_sheet() {
        let atlas = crate::tile::sprite::atlas();
        for id in [
            icon::PARKING,
            icon::TRANSIT_STOP,
            icon::SEARCH,
            icon::SAVED,
            icon::FAMILY,
            icon::VEHICLE_BUS,
            icon::VEHICLE_TRAM,
            icon::VEHICLE_TRAIN,
            icon::VEHICLE_FERRY,
        ] {
            let name = icon_sprite_name(id).unwrap_or_else(|| panic!("icon {id} has no name"));
            assert!(
                atlas.get(name).is_some(),
                "icon {id} -> `{name}` is not in the sheet"
            );
        }
    }

    /// An unknown id is a miss, not a panic and not a wrong icon: the renderer skips it, exactly
    /// as it skips a POI kind the sheet has no picture for.
    #[test]
    fn an_unknown_icon_id_is_a_miss() {
        assert!(icon_sprite_name(9999).is_none());
        assert!(icon_sprite_name(u32::MAX).is_none());
    }

    /// The vehicle ids WS-F builds on are the reserved 5-8 and resolve to the
    /// dedicated solid-badge `vehicle-*` sprites (not the ambient POI icons,
    /// which read as duplicated stations when vehicles dwell on stops). Pins
    /// them so a renumbering that collided with a pin id is caught here.
    #[test]
    fn the_reserved_vehicle_ids_map_to_transit_sprites() {
        assert_eq!(icon_sprite_name(icon::VEHICLE_BUS), Some("vehicle-bus"));
        assert_eq!(icon_sprite_name(icon::VEHICLE_TRAM), Some("vehicle-tram"));
        assert_eq!(icon_sprite_name(icon::VEHICLE_TRAIN), Some("vehicle-train"));
        assert_eq!(icon_sprite_name(icon::VEHICLE_FERRY), Some("vehicle-ferry"));
    }

    /// The family pin draws the dedicated indigo person badge, not the provisional
    /// `attraction` star it shared with search pins — a family member must never read
    /// as a starred POI.
    #[test]
    fn the_family_id_maps_to_its_own_sprite() {
        assert_eq!(icon_sprite_name(icon::FAMILY), Some("family"));
    }

    /// The family sprite draws at the POI icon size (38 sheet px at ratio 2 = 19 Dp),
    /// so the pin scales to `MARKER_SIZE_DP` on its larger side exactly like the rest.
    #[test]
    fn the_family_sprite_is_drawn_at_poi_icon_size() {
        let family = crate::tile::sprite::atlas()
            .get("family")
            .expect("family is in the sheet");
        assert!((family.width_dp - 19.0).abs() < 1e-6, "{}", family.width_dp);
        assert!((family.height_dp - 19.0).abs() < 1e-6, "{}", family.height_dp);
    }

    /// Half-extents scale the larger side to `MARKER_SIZE_DP`: a square sprite yields the
    /// 14 Dp half-box the icon draw and the POI-placement blocker both cover.
    #[test]
    fn icon_half_extents_scale_the_larger_side_to_marker_size() {
        let atlas = crate::tile::sprite::atlas();
        let family = atlas.get("family").expect("family is in the sheet");
        let (hw, hh) = marker_icon_half_extents(family);
        assert!((hw - 14.0).abs() < 1e-6, "{hw}");
        assert!((hh - 14.0).abs() < 1e-6, "{hh}");
    }

    /// A wide sprite scales by its wider side, so the draw and the blocker stay the same box.
    ///
    /// A 38x19 Dp sheet icon is twice as wide as tall: the larger side lands on
    /// `MARKER_SIZE_DP` (half 14) and the shorter side halves proportionally (half 7).
    #[test]
    fn icon_half_extents_scale_a_wide_sprite_by_its_wider_side() {
        let sprite = crate::tile::sprite::Sprite {
            uv: crate::tile::glyph::UvRect { u0: 0.0, v0: 0.0, u1: 1.0, v1: 1.0 },
            width_dp: 38.0,
            height_dp: 19.0,
        };
        let (hw, hh) = marker_icon_half_extents(sprite);
        assert!((hw - 14.0).abs() < 1e-6, "{hw}");
        assert!((hh - 7.0).abs() < 1e-6, "{hh}");
    }

    /// The pin label starts clear of the icon edge: half the icon plus the gap.
    ///
    /// The label offset is not a second implementation elsewhere, it is these two
    /// constants composed, so this pins both: a 28 Dp icon holds a 14 Dp half-box
    /// and the 4 Dp gap puts the label start 18 Dp from the anchor.
    #[test]
    fn marker_label_starts_clear_of_the_icon_edge() {
        const { assert!(MARKER_SIZE_DP > 0.0 && MARKER_LABEL_GAP_DP > 0.0) };
        const { assert!(MARKER_LABEL_DP > 0.0) };
        let label_start = MARKER_SIZE_DP * 0.5 + MARKER_LABEL_GAP_DP;
        assert!((label_start - 18.0).abs() < 1e-6, "{label_start}");
    }
}
