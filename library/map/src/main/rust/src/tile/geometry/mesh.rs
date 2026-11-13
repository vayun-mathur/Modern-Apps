//! The tessellated-mesh types for one tile: per-layer draws plus the special passes.
use crate::style::LayerKind;
use crate::tile::arrow::ArrowInstance;

/// The shallowest zoom the traffic overlay is tessellated at.
///
/// Component segments are one line per graph vertex pair — roughly the whole drivable
/// network duplicated — so they are only worth building where a traffic overlay is legible.
/// WS2 zoom-gates emission in the archive too, so a coarser tile simply carries no traffic
/// layer; this is the matching cost gate on the render side.
pub const TRAFFIC_MIN_ZOOM: u8 = 12;

/// The shallowest zoom the per-lane road detail (the carriageway surface and its turn arrows) is
/// built at.
///
/// Matches the `roads-carriageway` style layer's `minzoom` in `basemap.flat.json`: lane markings
/// are only legible zoomed right in, so below this the road draws as the ordinary stroked layers
/// and carries no arrows.
pub const ROAD_LANE_MIN_ZOOM: u8 = 16;

/// The height a `buildings` feature with no `height` tag extrudes to, in metres — about three
/// storeys, so an unattributed building still reads as a building rather than a flat patch.
pub(crate) const DEFAULT_BUILDING_HEIGHT_M: f64 = 9.0;

/// The Earth's equatorial circumference in metres, for turning a building's metric height into the
/// tile-normalised height the 3D vertex format carries. Web Mercator, matching the archive.
pub(crate) const EARTH_CIRCUMFERENCE_M: f64 = 40_075_016.686;

/// Tessellated geometry for one layer of one tile.
pub struct LayerMesh {
    /// Index into the style's layer list, which is also draw order.
    pub layer_index: usize,
    pub kind: LayerKind,
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
    /// An ARGB colour that replaces the layer's own, when the **feature** carries one.
    ///
    /// Only transit lines do: a subway line's colour is the operator's, tagged per route
    /// in OSM and carried per feature in `transit_color`, so one style layer draws many
    /// colours. A layer whose features disagree therefore emits one mesh per distinct
    /// value rather than one mesh.
    ///
    /// A sub-mesh split rather than a per-vertex colour attribute: the alternative would
    /// change the vertex format and the pipeline that **every road** shares, to serve one
    /// layer. `record_symbol` already batches by resolved text size for the same reason.
    pub color_override: Option<u32>,
    /// The lane inputs every feature in this mesh shares: the colour's ordinal within its
    /// corridor, the corridor's colour count, and how far into the lane the piece sits
    /// (over 255).
    ///
    /// Splits the sub-meshes alongside [`color_override`], because two routes of one colour
    /// on different ordinals are two parallel lines. Zero for every layer but transit.
    ///
    /// Inputs rather than an offset, so the mesh is zoom-independent: the lane count is a
    /// property of the camera, and re-tessellating a tile whenever it changed is the cost
    /// this avoids.
    pub lane: (u8, u8, u8),
}

/// The 3D building geometry for one tile: every `buildings` feature extruded into walls and a roof
/// cap, combined into one mesh in the 7-float [`crate::tess::roof`] vertex format so the whole
/// tile's buildings draw in a single depth-tested pass.
///
/// Kept apart from [`LayerMesh`] because it is neither a flat 2D layer nor drawn through the fill
/// pipeline: its vertices carry a height, a normal and a per-vertex colour, and it draws through
/// the building pipeline WS-A added with depth on. Empty on every tile below z14 and on any tile
/// with no `buildings` layer.
#[derive(Default)]
pub struct BuildingMesh {
    /// Interleaved `x, y, z, nx, ny, nz, colour` per vertex — see [`crate::tess::roof`].
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
}

/// The DEM-displaced ground grid for one tile (WS-G, 3D terrain relief): the tile's ground
/// tessellated into a grid whose per-vertex `z` is sampled from the tile's heightmap, in the
/// 6-float [`crate::tess::terrain`] vertex format so it draws in a single depth-tested pass.
///
/// Kept apart from [`LayerMesh`] for the same reason [`BuildingMesh`] is: it is not a flat 2D layer
/// and does not draw through the fill pipeline — its vertices carry a height and a normal and it
/// draws through the terrain pipeline with depth on. Empty on any tile with no heightmap (open
/// ocean, off-DEM coverage), which then keeps its flat `earth` fill instead.
#[derive(Default)]
pub struct TerrainMesh {
    /// Interleaved `x, y, z, nx, ny, nz` per vertex — see [`crate::tess::terrain`].
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
}

/// One shaped label candidate for per-frame symbol emission.
#[derive(Clone)]
pub struct ShapedLabel {
    /// Index into the style's layer list (the symbol layer that owns it).
    pub layer_index: usize,
    /// Anchor in tile-local 0..1.
    pub anchor: (f32, f32),
    /// Display name as shaped (for the task-17 pick path).
    pub name: String,
    /// The shaped run, one entry per line. A place label is always one line; a POI label
    /// wraps at its layer's `text_max_width`.
    pub lines: Vec<crate::tess::text::ShapedLine>,
    /// The widest line's advance in font units — the block's width, for centring and for
    /// the collision box.
    pub total_advance: f32,
    /// Glyph weight (the layer's `medium` flag).
    pub weight: crate::tile::glyph::Weight,
    /// Placement rank from the symbol layer id: country 0, subplace 3.
    /// Decided at shape time so the per-frame path only sorts.
    pub rank: u8,
    /// Population weight within the rank (the feature's numeric `kind_detail`,
    /// 0–3 from the tiler, 0 unknown). Placement prefers higher weight on ties,
    /// so a big city beats a town at the same collision.
    pub pop: u16,
    /// The icon to draw beside the label, for a POI layer whose kind the sprite sheet
    /// carries. `None` for every place label, and for the one POI kind (`townhall`) the
    /// sheet has no picture of.
    pub sprite: Option<crate::tile::sprite::Sprite>,
    /// The feature's **own** interned `kind`, not its layer's whitelist.
    ///
    /// A symbol layer filters on several kinds — `poi-food` draws `restaurant`, `fast_food`,
    /// `cafe` and `bar` — so the layer cannot say which one a given label is. A pick that
    /// reports the layer's first kind reports `restaurant` for every food POI, which is what
    /// this field exists to stop.
    pub kind: u16,
    /// The archive's stable id for this feature, or
    /// [`ID_NONE`](tilecodec::mamaps::body::ID_NONE) when its layer carries no id table (every
    /// layer but `places` and `poi`) or the generator could not attribute it to an OSM element.
    pub feature_id: u64,
    /// The tagged relation id of the admin boundary this label names, or
    /// [`REGION_NONE`](tilecodec::mamaps::body::REGION_NONE) when the label links to none. Only a
    /// `places` label ever carries a non-zero value; it lets a tap outline that exact region
    /// (`RegionBuffers::id`) instead of guessing one by point + level.
    pub region_id: u64,
    /// A line feature's centreline in tile-local 0..1, for a **curved** label laid along a road
    /// or river; `None` for an ordinary point label.
    ///
    /// A point label anchors one shaped block at [`anchor`](Self::anchor) and billboards upright;
    /// a curved label lays its single shaped line along this polyline at emit time, one glyph per
    /// vertex rotated to the local tangent (see [`crate::tess::text::emit_curved`]). Kept as the
    /// tile-local polyline rather than pre-placed glyphs because the along-line spacing depends on
    /// the frame's `text_px`, so the walk happens per frame like the point path's quad emission.
    pub centreline: Option<Vec<(f32, f32)>>,
}

/// One tile's road carriageway surface, for one set of road-shape inputs.
///
/// Kept apart from [`LayerMesh`] because it is neither a stroke nor drawn through the line
/// pipeline: its vertices carry an across-road coordinate ([`crate::tess::ribbon`]) and it draws
/// through the ribbon pipeline, whose push block reads three slots differently. That split is
/// deliberate rather than a wider shared format — [`TrafficMesh`] and the route overlay both ride
/// the 7-float stroke vertex, so widening it to carry a coordinate only roads read would charge
/// every one of them for a layer they do not draw.
///
/// One mesh per distinct ([`lanes`](Self::lanes), [`split`](Self::split), [`oneway`](Self::oneway))
/// rather than one per feature or one per tile: those three are push constants, so features that
/// agree on all of them can share a draw, and features that disagree cannot. Most roads in a tile
/// are ordinary two-way streets, so the split is usually into very few meshes.
///
/// # Lane connectors ride this too
///
/// A connector through a junction is the same asphalt with the same markings — it is the
/// carriageway continued across the intersection — so it is this type on the same pipeline in the
/// same pass, not a fourth mesh kind with a fourth pass. It differs only in what it pushes:
/// [`lanes`](Self::lanes) 1 and [`oneway`](Self::oneway) set, always, because a connector is one
/// lane of traffic in one direction whatever the feature carries. Its own
/// [`layer_index`](Self::layer_index) keeps it in its own mesh, so a connector never shares a draw
/// with a road.
pub struct CarriagewayMesh {
    /// Index into the style's layer list, for the asphalt colour and the lane width ramp.
    pub layer_index: usize,
    /// Lanes across the whole road, both directions together — `Push.line.y`, and the multiplier
    /// that turns the style's per-lane width into this road's own.
    pub lanes: u8,
    /// The across-road coordinate the opposing streams meet at, in -1..=1 — `Push.line.z`.
    /// Meaningless, and zero, when [`oneway`](Self::oneway) is set.
    pub split: f32,
    /// Traffic runs one way, so the carriageway has no centre line at all — `Push.line.w`.
    pub oneway: bool,
    /// Interleaved `x, y, nx, ny, t, distance` per vertex — see [`crate::tess::ribbon`].
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
}

/// One region's shape within one tile, for the selection mask.
///
/// Kept apart from [`LayerMesh`] because it is not styled and not drawn in layer order: nothing
/// paints it, the mask pass rasterises it into the stencil so the scrim can be punched out. A
/// region is clipped to one polygon per tile, so [`id`](Self::id) — the OSM relation it came from
/// — is the only thing that says two tiles' pieces are the same region.
pub struct RegionMesh {
    pub id: u64,
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
    /// The exterior rings in tile-local 0..1, kept for the point-in-polygon test that turns a
    /// tapped place into a region id. Holes are excluded: a city's exclaves matter for the test,
    /// its inner voids do not, and treating a hole as solid is the safer error here.
    pub rings: Vec<Vec<(f32, f32)>>,
    /// Total absolute ring area in tile-local units, for preferring the smallest region that
    /// contains a point - a city rather than the state around it.
    pub area: f32,
    /// The OSM `admin_level` this region was drawn at, carried through as the boundary
    /// feature's numeric `kind_detail`.
    ///
    /// Without it a point lookup can only prefer the smallest shape that contains the tap,
    /// which answers the wrong question: tapping a state's label lands somewhere inside one
    /// of its counties, and the county is smaller. The selection already knows whether it is
    /// a country, a region or a city, so the level is what matches the two up.
    pub level: u16,
}

/// One component-segment of the live traffic layer within one tile.
///
/// Kept apart from [`LayerMesh`] for the same reason [`RegionMesh`] is: it is not styled in
/// layer order and its colour is not the style's. The geometry is tessellated once from the
/// archive; the colour arrives per update as a pushed `component_id → ARGB` table and is
/// resolved at **draw** time, so a new speed reading recolours the map without re-tessellating
/// anything. [`id`](Self::id) is the segment's `component_id` (`packed(big_edge_id, seg_index)`,
/// the shared contract) and the key into that table.
pub struct TrafficMesh {
    /// The segment's `component_id` from the traffic layer's id side-table; the key the
    /// pushed colour table is looked up by.
    pub id: u64,
    /// Stroke geometry in the same 7-float-per-vertex format every line layer uploads, so it
    /// draws through the existing line pipeline with the width as a per-frame push constant.
    pub vertices: Vec<f32>,
    pub indices: Vec<u32>,
}

/// Every layer's geometry for one tile, ready to upload.
pub struct TileMesh {
    pub z: u8,
    pub x: u32,
    pub y: u32,
    pub meshes: Vec<LayerMesh>,
    /// The tile's extruded 3D buildings, or an empty mesh below z14 / where the tile has none.
    /// Drawn depth-tested through the building pipeline, not in the flat layer loop.
    pub buildings: BuildingMesh,
    /// The tile's DEM-displaced ground grid (WS-G), or an empty mesh where the tile carries no
    /// heightmap. Drawn depth-tested through the terrain pipeline *before* the flat layer loop, so
    /// hills rise under tilt and the flat layers paint over it; empty on a no-heightmap tile, which
    /// then draws its flat `earth` fill as before.
    pub terrain: TerrainMesh,
    /// Region shapes for the selection mask, one per `region_area` feature in this tile.
    ///
    /// Tessellated unconditionally rather than on selection: which region is selected changes
    /// with a tap, and re-tessellating every resident tile at that moment would stall the frame.
    /// A tile holds a handful of these, so the cost is small and paid once.
    pub regions: Vec<RegionMesh>,
    /// Live-traffic component segments, one per drivable component of the traffic layer, each
    /// carrying its `component_id`. Empty unless the traffic toggle is on and the tile is at or
    /// below [`TRAFFIC_MIN_ZOOM`]. Colour is resolved at draw time from a pushed table, so this
    /// is built once and survives every recolour.
    pub traffic: Vec<TrafficMesh>,
    /// The road carriageways in this tile: the road surfaces the lane markings are painted on,
    /// one mesh per distinct set of road-shape push inputs. Empty below the carriageway layer's
    /// zoom window, and on any tile with no roads.
    ///
    /// Also carries the lane connectors through junctions, which are the same surface continued
    /// across an intersection — see [`CarriagewayMesh`]. Empty of those on every tile that has no
    /// junction layer, which today is all of them.
    pub carriageways: Vec<CarriagewayMesh>,
    /// The tile's driving convention says the line between opposing streams is yellow (the
    /// Americas) rather than white — `Push.misc.z`.
    ///
    /// A property of the tile, not of a road, and `false` on any archive that carries no
    /// convention at all: right-hand traffic with white markings is most of the world by land
    /// area and the safer thing to be wrong about.
    pub yellow_centre: bool,
    /// Symbol candidates: shaped once at tessellation time, sized per frame.
    pub labels: Vec<ShapedLabel>,
    /// Per-lane turn arrows at road junctions, from the archive's turn-lane table. One per marked
    /// lane, placed on the centreline near the junction and pushed sideways onto its lane of the
    /// carriageway. Empty below the lane zoom gate and on any tile with no
    /// `turn:lanes`. The renderer draws these as glyphs; the anchors and directions are computed
    /// here so the placement is testable off-device.
    pub arrows: Vec<ArrowInstance>,
    /// The [`crate::style::SharedToggles`] generation this was built at.
    ///
    /// Optional layers are gated here, not at draw time, so a mesh is only valid for the
    /// toggles it saw. The renderer re-requests anything whose generation has fallen
    /// behind; without the tag it could not tell a tile built with POI off from one whose
    /// tile simply has no POI in it.
    pub generation: u32,
    /// The toggle snapshot this was built at, plus what its source could have emitted.
    ///
    /// The kinds-aware second opinion behind the generation: when the generation has
    /// moved on, [`has_tile`](crate::vulkan::renderer::Renderer::has_tile) consults
    /// [`ToggleStamp::stale_under`](crate::style::ToggleStamp::stale_under) before
    /// re-requesting, so a chip change rebuilds only tiles whose POI set actually
    /// changed. See [`crate::style::GatedSummary`].
    pub toggle_stamp: crate::style::ToggleStamp,
    /// The tile's heightmap, retained so per-frame label emission can sample ground height
    /// under anchors (mirroring how `labels`/`arrows` are retained for per-frame draws).
    /// `None` where the tile carries none; a few hundred bytes at DEM resolution.
    pub heightmap: Option<tilecodec::mamaps::body::Heightmap>,
}

/// Each symbol layer's labels, as indices into the tile's label list in ascending order.
///
/// Built once when a tile becomes resident. The frame path visits labels layer by layer
/// (placement per symbol layer, then emission per tile per symbol layer), and used to find a
/// layer's labels by filtering the tile's whole list — twice per (tile, layer) on every frame,
/// fourteen symbol layers deep. The index turns that into a direct walk over the layer's own
/// labels, in the same order and with the same indices, so candidate ids and draw order are
/// unchanged.
pub fn labels_by_layer(labels: &[ShapedLabel]) -> std::collections::HashMap<usize, Vec<u32>> {
    index_from_layers(labels.iter().map(|label| label.layer_index))
}

/// [`labels_by_layer`] over the labels' layer indices, in tile order.
fn index_from_layers(layers: impl Iterator<Item = usize>) -> std::collections::HashMap<usize, Vec<u32>> {
    let mut index: std::collections::HashMap<usize, Vec<u32>> = std::collections::HashMap::new();
    for (i, layer) in layers.enumerate() {
        index.entry(layer).or_default().push(i as u32);
    }
    index
}


#[cfg(test)]
mod label_index_tests {
    #[test]
    fn the_layer_index_lists_every_label_once_in_tile_order() {
        // Built from a real tile shape rather than a hand-rolled ShapedLabel: the fields are
        // many, and only `layer_index` matters to the index.
        let layer_of = [3usize, 1, 3, 7, 1, 3];
        let mut index = std::collections::HashMap::<usize, Vec<u32>>::new();
        for (i, layer) in layer_of.iter().enumerate() {
            index.entry(*layer).or_default().push(i as u32);
        }
        // The production builder over the same layer sequence must agree.
        let built = super::index_from_layers(layer_of.iter().copied());
        assert_eq!(built, index);
        assert_eq!(built[&3], vec![0, 2, 5]);
        assert_eq!(built[&1], vec![1, 4]);
        assert_eq!(built[&7], vec![3]);
        assert_eq!(built.values().map(Vec::len).sum::<usize>(), layer_of.len());
    }
}
