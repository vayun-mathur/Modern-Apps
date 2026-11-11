//! The style module's small vocabulary types: pipeline kinds, theme variants,
//! optional layer toggles and label anchors.

/// What pipeline a layer draws with.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum LayerKind {
    Fill,
    Line,
    /// Text labels (M1: places). Tessellated as textured quads from the SDF glyph
    /// atlas; drawn by the symbol pipeline with per-frame size/color/halo.
    Symbol,
}

/// Light or dark basemap. The layer set is the same; only the paint differs.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Variant {
    Light,
    Dark,
}

/// An optional layer group the host app opts into at runtime.
///
/// A layer with no toggle is basemap and always drawn. The others carry data
/// every archive already ships but which most consumers do not want: POI icons clutter a
/// map whose job is to show one pin, transit lines are noise outside a transit app, and
/// live traffic is a per-component overlay only a navigation view wants. Defaulting them
/// **off** is what makes the five existing consumers cost nothing.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Toggle {
    Poi,
    Transit,
    /// The live traffic layer (`LAYER_TRAFFIC`, id 7). Gated at tessellation like the
    /// others so an archive without it — or a consumer that never enables it — pays
    /// nothing; the per-segment colours arrive separately as a pushed id→ARGB table
    /// (see [`crate::vulkan::renderer::Renderer::set_traffic_speeds`]).
    Traffic,
    /// Road furniture (`LAYER_SAFETY`): speed cameras, ALPR, surveillance, stop signs and
    /// traffic signals, drawn as icons when the host's safety overlay is on.
    Safety,
}

/// Which optional layers are on. All off by default.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct LayerToggles {
    pub poi: bool,
    pub transit: bool,
    pub traffic: bool,
    pub safety: bool,
}

impl LayerToggles {
    /// Is a layer carrying `toggle` drawn?
    ///
    /// `None` is basemap, and always yes — which is why this takes an `Option` rather
    /// than making every caller special-case the common layer.
    pub fn enabled(&self, toggle: Option<Toggle>) -> bool {
        match toggle {
            None => true,
            Some(Toggle::Poi) => self.poi,
            Some(Toggle::Transit) => self.transit,
            Some(Toggle::Traffic) => self.traffic,
            Some(Toggle::Safety) => self.safety,
        }
    }
}

/// Where a label sits relative to its anchor point.
///
/// Only the horizontal cases exist, because only they are used: place labels are centred
/// and the reference `pois` layer offers `["left", "right"]`. `Left` means the label's
/// left edge is at the anchor, so the text runs to the **right** of the point — which is
/// MapLibre's sense of the word and the opposite of the intuitive reading.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Anchor {
    Center,
    Left,
    Right,
}

impl Variant {
    pub fn from_dark(dark: bool) -> Variant {
        if dark {
            Variant::Dark
        } else {
            Variant::Light
        }
    }
}
