//! The frame: tile residency, and one render pass per frame.

use crate::camera::Camera;
use crate::marker::Marker;
use crate::overlay::{RoutePlacement, RouteSegmentRange};
use crate::style::{Anchor, Layer, LayerKind};
use crate::tile::geometry;
use crate::vulkan::buffers::Buffer;
use ash::vk;
use std::collections::HashMap;

mod fog;
mod bind_state;
mod frame;
mod mod_extra;
mod placement;
mod placement_markers;
mod placement_refresh;
mod placement_reuse;
mod quad;
mod rebuild;
mod record;
mod record_arrows;
mod record_extra;
mod record_extra2;
mod record_extra3;
mod record_extra4;
mod record_markers;
mod record_moon;
mod record_symbol;
mod suppression;
mod upload;
mod upload_extra;
pub(super) mod upload_moon;
mod upload_pool;
mod upload_tail;

pub use mod_extra::Renderer;
pub(crate) use {mod_extra::SuppressedDraws, placement::globe_screen_quad};
pub(crate) use quad::{Quad, QUAD_INDICES, QUAD_VERTICES};

/// How many frames may be in flight. Two is enough to keep the GPU fed behind vsync
/// without adding latency the user can feel when panning.
const FRAMES_IN_FLIGHT: usize = 2;

/// One layer's geometry, resident on the GPU.
///
/// The mesh lives in its format pool on the tile ([`ResidentTile::flat`] for fills,
/// [`ResidentTile::lines`] for strokes) and this names its slice: indices are rebased to
/// absolute at upload, so every draw binds the pool at offset 0 and passes
/// [`first_index`](Self::first_index).
struct LayerBuffers {
    layer_index: usize,
    kind: LayerKind,
    first_index: u32,
    index_count: u32,
    /// Set when the mesh carries its own colour — see
    /// [`geometry::LayerMesh::color_override`].
    color_override: Option<u32>,
    /// The lane inputs this mesh draws with — see [`geometry::LayerMesh::lane`].
    lane: (u8, u8, u8),
}

/// One tile's geometry, resident on the GPU.
///
/// Same-format meshes share one vertex + one index buffer (see
/// [`upload_packed`](crate::vulkan::buffers::upload_packed)): 2 `vkAllocateMemory` per pool
/// instead of 2 per mesh. A dense tile used to cost ~60-100 allocations on the Choreographer
/// callback and ~6400 live across 64 resident tiles, near the ~4096 driver hard limit; pooled
/// it costs at most 3 pairs (flat fills, stroked lines, ribbon carriageways) plus the
/// already-single buildings and terrain meshes.
///
/// The pooled-upload path ([`upload_pool`]) goes one step further: the pools below name
/// ranges of long-lived arenas instead of fresh buffers, so a tile costs no allocation at
/// all. Either way a pool is one buffer pair plus per-mesh slices — the only difference is
/// who owns the memory — which is why the draw path binds `(buffer, offset)` pairs plus a
/// per-draw `firstIndex` and never asks which path produced them.
///
/// Ownership on retire: fresh pools are destroyed with the tile; arena ranges go back to the
/// pool's free lists ([`arena_ranges`](Self::arena_ranges)) once the grace period is over.
///
/// `pub(crate)`: the pick-snapshot split (`placement_refresh`) resolves one
/// label against a tile, so it names this type.
pub(crate) struct ResidentTile {
    /// Position-only 2-float fills: every `LayerKind::Fill` mesh plus the region-mask shapes.
    ///
    /// `(vertex handle, vertex byte offset, index handle, index byte offset)`: raw handles,
    /// never owned `Buffer`s — fresh pools bind at offset 0, arena ranges at their committed
    /// offsets, and the draws read them back without caring which path produced them.
    /// Owned fresh buffers (fresh path only) live in [`fresh_owned`](Self::fresh_owned);
    /// the pooled path holds none.
    flat: Option<(vk::Buffer, vk::DeviceSize, vk::Buffer, vk::DeviceSize)>,
    /// 7-float stroked lines: every `LayerKind::Line` mesh plus the live-traffic segments.
    /// Same `(handle, offset, handle, offset)` shape as [`flat`](Self::flat).
    lines: Option<(vk::Buffer, vk::DeviceSize, vk::Buffer, vk::DeviceSize)>,
    /// 6-float ribbon carriageways, one per distinct road-shape push set.
    /// Same `(handle, offset, handle, offset)` shape as [`flat`](Self::flat).
    ribbons: Option<(vk::Buffer, vk::DeviceSize, vk::Buffer, vk::DeviceSize)>,
    /// Fresh-path owned buffers (up to 6: flat/lines/ribbons × vertices/indices), destroyed
    /// with the tile. Empty on the pooled path.
    fresh_owned: Vec<Buffer>,
    /// Pooled-path arena ranges, released to the pool when the tile leaves the retiring queue.
    arena_ranges: Vec<upload_pool::ArenaRange>,
    layers: Vec<LayerBuffers>,
    /// The tile's extruded 3D buildings, or `None` below z14 / where the tile has none. Drawn in
    /// its own depth-tested pass ([`Renderer::record_buildings`]), not the flat layer loop.
    /// One combined mesh per tile, so this keeps its own buffer pair — nothing to pool.
    buildings: Option<BuildingBuffers>,
    /// The tile's DEM-displaced ground grid, or `None` where the tile carries no heightmap. Drawn
    /// in its own depth-tested pass ([`Renderer::record_terrain`]) before the flat layer loop; a
    /// tile with no terrain draws its flat `earth` fill in the layer loop as before.
    terrain: Option<TerrainBuffers>,
    /// The region shapes in this tile, for the selection mask. Uploaded with the rest of the
    /// tile so selecting a region costs no tessellation and no allocation.
    regions: Vec<RegionBuffers>,
    /// The live-traffic component segments in this tile, each keyed by its `component_id`.
    /// Uploaded with the rest of the tile; coloured per frame from the pushed table so a new
    /// speed reading never re-uploads or re-tessellates them.
    traffic: Vec<TrafficBuffers>,
    /// The road carriageways in this tile, one per distinct set of road-shape push inputs, plus
    /// the lane connectors through its junctions. Drawn in their own pass
    /// ([`Renderer::record_carriageways`]) through the ribbon pipeline, not the flat layer loop,
    /// because the vertex format and three of the push slots differ. Empty below the carriageway
    /// layer's zoom window.
    carriageways: Vec<CarriagewayBuffers>,
    /// The tile's driving convention paints the line between opposing streams yellow rather than
    /// white. A property of the tile, not of a road, so it rides here and not on each mesh.
    yellow_centre: bool,
    /// Shaped symbol candidates (CPU-side): the renderer emits quads per frame
    /// at the frame's text size. Shaped once on the worker thread.
    labels: Vec<geometry::ShapedLabel>,
    /// The tile's heightmap (CPU-side): per-frame label emission samples ground height
    /// under anchors from it. Cloned at upload like `labels`; `None` with no DEM.
    heightmap: Option<tilecodec::mamaps::body::Heightmap>,
    /// Per-lane turn arrows (CPU-side): placed once on the worker thread from the archive's
    /// turn-lane table. The renderer builds their triangles per frame — rotated, scaled to a screen
    /// size and offset into their lane — because all three follow the camera, exactly as the
    /// carriageway's own width does. Empty below the lane zoom gate and on any tile with no
    /// `turn:lanes`.
    arrows: Vec<crate::tile::arrow::ArrowInstance>,
    z: u8,
    x: u32,
    y: u32,
    /// The clock (`Camera::time_seconds`) when this tile's GPU buffers were created, in the
    /// same epoch as `Push.misc.w`. WS-D ramps the tile's `Push.morph.x` opacity from 0 to 1
    /// over [`LOD_FADE_SECONDS`] from this stamp so a finer LOD fades in over its coarse
    /// ancestor instead of popping.
    uploaded_at: f32,
    /// The toggle generation this was tessellated at — see
    /// [`crate::style::SharedToggles`]. The kinds-aware second opinion behind it
    /// ([`ToggleStamp`](crate::style::ToggleStamp)) rides alongside.
    generation: u32,
    toggle_stamp: crate::style::ToggleStamp,
}

/// One live-traffic component segment, on the GPU.
///
/// The geometry lives in the tile's [`lines`](ResidentTile::lines) pool; this names its slice.
/// Drawn exactly like a road — the only thing that differs is the colour, looked up from
/// [`Renderer::traffic_colors`] by [`id`](Self::id) at draw time rather than from a style layer.
struct TrafficBuffers {
    /// The segment's `component_id`, the key into the pushed colour table.
    id: u64,
    first_index: u32,
    index_count: u32,
}
/// One tile's carriageway surface for one set of road-shape inputs, on the GPU.
///
/// The geometry lives in the tile's [`ribbons`](ResidentTile::ribbons) pool; this names its
/// slice. The three shape fields are push constants rather than vertex attributes, which is why
/// they key the mesh split: every road in the tile that agrees on all three shares this draw.
struct CarriagewayBuffers {
    /// Index into the style's layer list, for the asphalt colour and the lane width ramp.
    layer_index: usize,
    lanes: u8,
    split: f32,
    oneway: bool,
    first_index: u32,
    index_count: u32,
}

/// One tile's extruded 3D buildings, on the GPU.
///
/// One combined mesh per tile — every building in the tile, walls and roofs — in the 7-float
/// `tess::roof` vertex format, drawn depth-tested through [`Pipelines::building`]. `None` on a tile
/// with no buildings, which is every tile below z14.
struct BuildingBuffers {
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
}

/// One tile's DEM-displaced ground grid, on the GPU (WS-G, 3D terrain relief).
///
/// One combined mesh per tile in the 6-float `tess::terrain` vertex format (position + height +
/// normal), drawn depth-tested through [`Pipelines::terrain`] before the flat layer loop. `None` on
/// a tile with no heightmap, which then draws its flat `earth` fill instead.
struct TerrainBuffers {
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
}

/// One region's tessellated shape within one tile, on the GPU.
///
/// The geometry lives in the tile's [`flat`](ResidentTile::flat) pool; this names its slice.
/// `rings`/`area`/`level` stay on the CPU for [`Renderer::region_at`].
struct RegionBuffers {
    /// The OSM relation this piece came from, matched against the selected region.
    id: u64,
    first_index: u32,
    index_count: u32,
    /// Exterior rings in tile-local 0..1, kept on the CPU for [`Renderer::region_at`].
    rings: Vec<Vec<(f32, f32)>>,
    /// Total absolute ring area, for preferring the smallest region containing a point.
    area: f32,
    /// The region's OSM `admin_level`, so a lookup can ask for a state rather than whatever
    /// happens to be smallest at that point.
    level: u16,
}

/// A transient per-frame buffer pair, held for [`FRAMES_IN_FLIGHT`] frames (same grace
/// rule as [`Renderer::retiring`], but its own queue — no `device_wait_idle` stall).
struct TransientBuffers {
    vbuf: Buffer,
    ibuf: Buffer,
    frames: usize,
}

/// What the symbol placer accepted: candidate id → (it took its alternate anchor, where in
/// acceptance order it landed).
type AcceptSet = HashMap<u64, (bool, u32)>;

/// Everything [`Renderer::place_symbols`] reads that can change what it decides.
///
/// Floats are compared as bit patterns rather than by value. This is an identity test — "is this
/// the same camera the last accept-set was computed from" — and not a question about numeric
/// closeness, so bits are both the correct comparison and the one that needs no epsilon.
///
/// `center_lon`/`center_lat`/`zoom`/`bearing`/`pitch` carry the exact camera for the fast exact-hit
/// path; `bucket_*` carry the quantised camera for the retained-collision slow path (see
/// [`same_bucket`](PlacementKey::same_bucket)): a small pan inside one bucket reuses the accept-set
/// without re-running the quadratic placer.
#[derive(PartialEq, Eq, Clone)]
struct PlacementKey {
    center_lon: u64,
    center_lat: u64,
    zoom: u64,
    bearing: u64,
    pitch: u64,
    /// Quantised camera bucket: zoom in 1/32-level bins, bearing/pitch in 4° bins, centre in
    /// ~1/1024-tile bins at the floored zoom. Two frames in one bucket share a collision answer
    /// (only the outcome lags; boxes still re-project per frame in `refresh_placed`).
    bucket_zoom: i64,
    bucket_bearing: i64,
    bucket_pitch: i64,
    bucket_lon: i64,
    bucket_lat: i64,
    /// Whether the globe path was active: globe and flat project through different box
    /// builders, so a bucket never spans the projection switch.
    globe: bool,
    width_dp: u32,
    height_dp: u32,
    density: u32,
    extent: (u32, u32),
    filter: crate::style::KindFilter,
    /// Every resident tile with its upload stamp, so a tile that arrives or is replaced re-places
    /// even though the camera has not moved. `camera.time_seconds` is deliberately *not* in this
    /// key: it changes every frame and enters no collision box.
    tiles: Vec<(u64, u32)>,
    /// The style, by layer count. A style or toggle change re-tessellates the resident set, which
    /// restamps every tile above, so this only has to catch the layer set itself changing.
    layers: usize,
    /// The marker generation ([`Renderer::marker_epoch`]): a pushed pin set must re-place even
    /// when the camera has not moved, so a new pin immediately culls the POIs it covers.
    markers: u64,
}

/// Per-frame synchronisation and its command buffer.
struct Frame {
    command_buffer: vk::CommandBuffer,
    /// Signalled when this frame's commands have finished, so its buffers can be reused.
    in_flight: vk::Fence,
    /// Signalled when the swapchain image is ready to draw into.
    image_available: vk::Semaphore,
    /// Signalled when drawing is done, so presentation can start.
    render_finished: vk::Semaphore,
}

/// The user's own location, as the host last reported it.
///
/// `bearing` is degrees clockwise from north, and `None` when the fix carries no heading
/// — which is what a cold start looks like before the compass has settled. That is a
/// different thing from a heading of zero, and drawing them the same way points the cone
/// spuriously north for the first second of every session.
#[derive(Clone, Copy, Debug)]
pub struct UserPuck {
    pub lon: f64,
    pub lat: f64,
    pub bearing: Option<f32>,
}

/// Something drawn on top of every tile, from the same camera value as the tiles.
///
/// Every variant draws from the shared unit quad and is pure `Copy`/owned state — no vertex
/// buffers with a retirement rule — which is why the route line is deliberately *not* one of
/// these (it lives in [`Renderer::route`] beside [`Renderer::selected_region`]).
///
/// Draw order is fixed in [`record_overlays`](Renderer::record_overlays), not by position in the
/// vec: markers (and WS-F's vehicles) draw first, the puck last, so the user's own location stays
/// on top of the pins around it.
///
/// # The shared sprite/billboard contract (WS-C owns; WS-F extends)
///
/// [`Markers`](Self::Markers) draws app pins as billboarded atlas sprites (see [`crate::marker`]).
/// [`Vehicles`](Self::Vehicles) (WS-F) is a sibling arm for simulated transit vehicles that reuses
/// the exact same [`draw_markers`](Renderer::draw_markers) path and sprite atlas — a vehicle is a
/// [`Marker`] whose icon names a mode sprite (bus/tram/train/ferry) — so the bulk many-sprites case
/// is one extra match arm and one bulk setter, with no new pipeline or atlas. Vehicles are pushed
/// on their own ~1 Hz cadence, replaced as a set by [`set_vehicles`](Renderer::set_vehicles)
/// independently of the app pins, and are deliberately *not* pickable (see
/// [`pick_at`](Renderer::pick_at)) — a moving simulated sprite is not a tap target.
enum Overlay {
    Puck(UserPuck),
    /// App pins: parking, transit stops, search results, saved places, family members. Replaces
    /// the Compose pin overlays so they pan and tilt in lock-step with the basemap.
    ///
    /// The labels ride parallel to the markers (same index, same length): an empty string draws
    /// the icon alone. A tuple rather than a field on [`Marker`](crate::marker::Marker) so the
    /// marker stays `Copy` for the pick path (`iter().copied()`); the whole set is replaced
    /// atomically by [`set_markers`](Renderer::set_markers), so the two can never drift.
    Markers(Vec<Marker>, Vec<String>),
    /// WS-F simulated transit vehicles: a bus/tram/train/ferry sprite per in-service trip in the
    /// visible bbox, pushed at ~1 Hz. Drawn through the same billboarded sprite path as
    /// [`Markers`](Self::Markers), under the pins and the puck.
    Vehicles(Vec<Marker>),
}

/// The navigation route, resident on the GPU.
///
/// Uploaded once by [`Renderer::set_route`] and never touched again until the route
/// changes: the mesh is zoom-independent by construction (see [`crate::overlay`]), so a
/// frame does nothing but build one matrix and push a casing plus one colour/width pair
/// per coloured run. That is the difference between a route that costs nothing in a
/// two-hour drive and one that re-tessellates on every zoom step.
struct RouteBuffers {
    placement: RoutePlacement,
    vertices: Buffer,
    indices: Buffer,
    index_count: u32,
    /// Each coloured run's slice of [`indices`](Self::indices) and its fill colour. The
    /// casing draws the whole index buffer once; each fill draws one of these slices.
    segments: Vec<RouteSegmentRange>,
}

/// Column-major identity, for an overlay whose vertices are already in clip space.
const IDENTITY: [f32; 16] = [
    1.0, 0.0, 0.0, 0.0, //
    0.0, 1.0, 0.0, 0.0, //
    0.0, 0.0, 1.0, 0.0, //
    0.0, 0.0, 0.0, 1.0,
];

/// How dark the world outside the selected region goes. Alpha, not a colour swap, so the map
/// stays legible underneath — the point is to say "this is the boundary", not to hide the rest.
const SCRIM_COLOR: u32 = 0x8C00_0000;

/// The unit quad every screen-anchored overlay draws with: four vertices in −1..1 and the
/// two triangles over them.
///
/// Uploaded once in [`Renderer::new`], because everything that varies about an overlay —
/// where it is, how big, what colour, which way it points — rides in the matrix and the
/// push constants. So the per-frame cost is one `cmd_push_constants` and one
/// `cmd_draw_indexed`, not the pair of `vkAllocateMemory` calls a transient buffer pays.
///
/// Lives in `quad.rs` alongside its vertex/index constants (file-length split) and is
/// re-exported at the top of this file, so the record passes keep their paths.

/// The puck's blue, from the `drawUserIcon` in `maps` this replaces.
const PUCK_COLOR: u32 = 0xFF0E_35F1;
/// The white rim's radius in Dp, and the blue dot's on top of it.
const PUCK_RIM_DP: f32 = 9.5;
const PUCK_DOT_DP: f32 = 8.0;
/// The bearing cone: the radius its stroke is centred on, and half that stroke's width.
const PUCK_CONE_DP: f32 = 20.0;
const PUCK_CONE_HALF_STROKE_DP: f32 = 4.0;
/// The quad's radius. The cone's outer edge is at 24 Dp, but its radial gradient only
/// reaches zero at 28 — the radius the Compose original's `Brush.radialGradient` used —
/// so a quad any tighter would clip the falloff.
const PUCK_QUAD_DP: f32 = 28.0;

/// A simulated vehicle's route-colour ring: the disc radius in Dp, and the quad
/// half-extent that holds it. The disc is wider than the 28 Dp vehicle sprite
/// (half 14) so its edge reads as an outline in the line's colour around the
/// mode glyph; the sprite draws over the disc's middle on top of it.
const VEHICLE_RING_DP: f32 = 16.5;
const VEHICLE_RING_QUAD_DP: f32 = 19.0;

/// The turn-arrow glyph: its screen size in Dp (the unit arrow spans roughly `-1..1`, so this is a
/// touch under its half-extent), and its colour. A muted near-white so the arrows read on the dark
/// carriageway without competing with the route line's saturated blue.
const ARROW_DP: f32 = 9.0;
const ARROW_COLOR: u32 = 0xE6EE_F1F5;

/// Stroke width of a live-traffic segment, in Dp.
///
/// A single constant width rather than a style ramp: the overlay is one legible band drawn
/// over the road casing, not a road class that has to grow and shrink with zoom. A shade
/// wider than a minor road so the colour reads as an overlay on top of the network rather
/// than as the road itself. Applied as a per-frame push constant (halved and density-scaled
/// like every other stroke), so it re-tessellates nothing.
const TRAFFIC_WIDTH_DP: f32 = 4.0;

/// The shallowest camera zoom the 3D buildings draw at, matching the `buildings` style layer's
/// `minzoom`. A tile may carry building geometry as a deeper-zoom ancestor stand-in, but the pass
/// is gated on the camera's own zoom so buildings appear only once the map is zoomed in far enough
/// for the extruded detail to read — below it the map is the flat basemap it always was.
const BUILDINGS_DRAW_MIN_ZOOM: f64 = 14.0;

/// One placed label as the pick path sees it: everything `pickLabels` needs
/// to answer without touching tiles, layers, or the camera.
#[derive(Clone)]
pub struct PlacedHit {
    /// Screen box in device px (same box the placer accepted).
    pub rect: (f32, f32, f32, f32),
    /// Index into the style layer list (maps to the flat layer id).
    pub layer_index: usize,
    /// Display name as shaped.
    pub name: String,
    /// Kind string (`country`/`region`/`locality`/…).
    pub kind: String,
    /// The archive's stable id for the feature, or
    /// [`ID_NONE`](tilecodec::mamaps::body::ID_NONE) when it has none. Lets the host rejoin the
    /// hit against its own data without matching on name and position.
    pub feature_id: u64,
    /// The tagged relation id of the admin boundary this label names, or
    /// [`REGION_NONE`](tilecodec::mamaps::body::REGION_NONE). Lets a tapped label mask that exact
    /// region without the host re-deriving it from point + level.
    pub region_id: u64,
    /// Anchor lon/lat in degrees.
    pub lon: f64,
    pub lat: f64,
}

/// The interned `kind`'s name, or empty when it is [`dict::NONE`] or past the table.
///
/// The archive stores kinds as ids into a frozen dictionary; the pick path reports names,
/// because that is what the host filters and switches on.
fn kind_name(kind: u16) -> String {
    use tilecodec::mamaps::dict;
    usize::from(kind)
        .checked_sub(1)
        .and_then(|at| dict::KINDS.get(at))
        .map(|name| (*name).to_string())
        .unwrap_or_default()
}

/// The anchors a layer's labels may be drawn at: its first choice, and the one to fall
/// back to when that box collides.
///
/// A layer with no `text-variable-anchor` is centred and cannot move, which is every place
/// layer. Only the first two are used: the reference declares exactly two, and a
/// [`crate::tile::placement::Candidate`] carries exactly two boxes.
fn anchors_for(layer: &Layer) -> (Anchor, Option<Anchor>) {
    match layer.variable_anchor.as_slice() {
        [] => (Anchor::Center, None),
        [only] => (*only, None),
        [first, second, ..] => (*first, Some(*second)),
    }
}

/// Everything one label's collision box depends on besides its anchor.
///
/// Built once per label and reused for each candidate anchor, so the two boxes a POI is
/// tried at can only differ in where they sit — not in how big they are.
///
/// Lives in [`placement_reuse`] (file-length split) alongside the candidate loop
/// that reads it; import it from there (`super::placement_reuse::{...}`).

/// Multiply a colour's alpha by `opacity`, for the style's fill-opacity ramps.
///
/// The ramp is applied here rather than folded into the layer table because it is a function of
/// the camera's zoom: a fill has to fade across a zoom, not switch at one.
fn scale_alpha(argb: u32, opacity: f32) -> u32 {
    if opacity >= 1.0 {
        return argb;
    }
    let alpha = (((argb >> 24) & 0xFF) as f32 * opacity.clamp(0.0, 1.0)).round() as u32;
    (alpha << 24) | (argb & 0x00FF_FFFF)
}

/// ARGB to the linear RGBA the shaders and clear values take.
fn argb_to_rgba(argb: u32) -> [f32; 4] {
    [
        ((argb >> 16) & 0xFF) as f32 / 255.0,
        ((argb >> 8) & 0xFF) as f32 / 255.0,
        (argb & 0xFF) as f32 / 255.0,
        ((argb >> 24) & 0xFF) as f32 / 255.0,
    ]
}
