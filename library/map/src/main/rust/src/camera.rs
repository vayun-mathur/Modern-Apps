//! The camera: a snapshot from Kotlin to a per-tile clip-space matrix.
//!
//! Web Mercator on a 512-logical-px tile grid (world = 512 * 2^zoom), matching
//! `:library:map`'s `Mercator.kt` — the Kotlin side owns the public `Projection`
//! in `Dp`, this side has to agree with it or every overlay drifts from the
//! basemap under it.
//!
//! 512 is also what gives MapLibre parity: the vector archives are authored on
//! that convention, so at the same zoom float a tile covers the same ground and
//! renders at the same size. This used to be a 256 grid with a compensating +1
//! zoom offset applied at the JNI boundary, which produced the right ground scale
//! by a different route but had two costs: tile addressing took the floor of the
//! *offset* zoom, so a screenful fetched four times as many tiles as MapLibre
//! does, and every style ramp — widths, `text_size`, opacity and the `min_zoom`
//! layer gating — was evaluated one level away from the zoom the authored
//! `basemap.json` those values were transcribed from meant by it.
//!
//! Only the camera crosses JNI, once per frame. Everything per-tile is derived here.

/// Logical pixels across one tile: 512, the convention the archives are authored on.
///
/// World scale is 512 * 2^zoom, which is MapLibre's, so tile addressing is the plain
/// floor of the camera zoom and every ramp is evaluated at the zoom the style means.
pub const TILE_SIZE: f64 = 512.0;

/// Largest camera tilt we allow, in degrees.
///
/// Not cosmetic: at this cap the horizon still sits above the top of the screen (the
/// perspective's far edge is a finite ground distance), so [`Camera::screen_to_world`]
/// always meets the ground plane and tile selection never has to cover an infinite
/// trapezoid. `fy = d/half_h = 3` and the horizon enters the screen only past
/// `atan(fy) ≈ 71°`, so 65 leaves a margin. See [`Camera::pitch_deg`].
pub const PITCH_MAX_DEG: f64 = 65.0;

/// The camera as Kotlin measured it.
#[derive(Clone, Copy, Debug)]
pub struct Camera {
    pub center_lon: f64,
    pub center_lat: f64,
    pub zoom: f64,
    /// Viewport in logical pixels (Dp), as the Compose host measured it.
    pub width_dp: f32,
    pub height_dp: f32,
    /// Device pixels per Dp. The only place a physical pixel enters.
    pub density: f32,
    /// Whether this frame draws the Moon instead of the Earth vector basemap.
    ///
    /// Only read while [`globe_active`] holds: the Moon path skips tile
    /// selection/fetch entirely (the raster pair is one uploaded texture, not
    /// tiles) and draws the textured sphere instead. False on every path but
    /// the maps body switch.
    pub moon: bool,
    /// Whether to draw the orthographic-sphere globe rather than the flat map.
    ///
    /// False on every path but the phone globe toggle (and always false past
    /// [`GLOBE_FLAT_THRESHOLD`], where the sphere is sub-pixel from flat): the
    /// flat derivation below is then taken bit-for-bit as before. When true,
    /// [`tile_to_clip`](Self::tile_to_clip) bends each tile onto the sphere and
    /// [`visible`](crate::tile::select::visible) selects the disc the viewport
    /// sees.
    pub globe: bool,
    /// Which compass direction points **up** the screen, in degrees clockwise from
    /// north. Zero is north-up, which is every path but heading-up car navigation.
    ///
    /// A rotation, not a tilt: it composes into the clip matrices as a plain 2x2. Tilt is
    /// [`pitch_deg`](Self::pitch_deg), which is the perspective term and composes separately.
    pub bearing_deg: f64,
    /// Camera tilt away from straight-down, in degrees, expected in `0..=`[`PITCH_MAX_DEG`]
    /// (the JNI boundary clamps it; the matrices below assume nothing).
    ///
    /// Zero is the classic top-down orthographic map — every path but the tilt gesture — and
    /// is short-circuited in every matrix builder so the ortho fast-path is byte-for-byte what
    /// it always was. Above zero [`world_quad_to_clip`](Self::world_quad_to_clip) produces a
    /// true perspective (real z, w-divide) and [`screen_quad_to_clip`](Self::screen_quad_to_clip)
    /// billboards its quad upright.
    pub pitch_deg: f64,
    /// Seconds since an arbitrary epoch, forwarded from the host's per-frame `frameTimeNanos`.
    ///
    /// **Bounded to `[0, `[`CLOCK_WRAP_SECONDS`]`)` and wraps** — see [`CLOCK_WRAP_NANOS`] for
    /// why. Anything measuring an elapsed time against it has to take the difference modulo
    /// the period, or a stamp taken just before a wrap reads as an hour in the future.
    ///
    /// Not part of the projection — it never enters a matrix, so it changes no camera test —
    /// but it rides on the camera because it is the other thing that arrives exactly once per
    /// frame. The renderer forwards it to shaders through the `Push.misc.w` slot; the animated
    /// workstreams (dash phase, LOD morph, vehicles) read it there.
    pub time_seconds: f32,
}

/// The period [`Camera::time_seconds`] is reduced modulo, in nanoseconds.
///
/// The host's `frameTimeNanos` is a boot-relative clock that grows without bound, and
/// `time_seconds` is an `f32` with ~7 significant digits — so after a long uptime the raw
/// value would quantise to tens of milliseconds and coarsen every animation that reads it.
/// Reducing it first keeps the resolution; the cost is that the clock wraps, which every
/// reader has to allow for.
pub const CLOCK_WRAP_NANOS: i64 = 3_600_000_000_000;

/// [`CLOCK_WRAP_NANOS`] in seconds: the period of [`Camera::time_seconds`].
///
/// An elapsed time against that clock is `(now - then).rem_euclid(CLOCK_WRAP_SECONDS)`. A
/// plain subtraction is wrong across a wrap, and wrong by an entire period rather than
/// slightly — which reads as a stamp an hour in the future rather than a moment in the past.
pub const CLOCK_WRAP_SECONDS: f32 = 3600.0;

/// A point in Web Mercator world pixels at some zoom.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WorldPx {
    pub x: f64,
    pub y: f64,
}

/// The tilt-dependent constants of the perspective projection, computed once per matrix.
///
/// Only built on the pitched path; the ortho fast-path never touches it. `d` is the
/// camera-to-centre distance and cancels at pitch 0, so its only job is setting how strong
/// the foreshortening is; `fx`/`fy` are focal terms; `depth_a`/`depth_b` map view distance to
/// Vulkan's `[0, 1]` clip depth (`ndc_z = depth_a - depth_b/w`).
#[derive(Clone, Copy)]
pub(crate) struct Perspective {
    pub(crate) fx: f64,
    pub(crate) fy: f64,
    pub(crate) d: f64,
    pub(crate) depth_a: f64,
    pub(crate) depth_b: f64,
}

/// Total map width and height in logical px at `zoom`.
pub fn world_size(zoom: f64) -> f64 {
    TILE_SIZE * 2f64.powf(zoom)
}

/// Project lon/lat degrees to world px at `zoom`.
pub fn project(lon: f64, lat: f64, zoom: f64) -> WorldPx {
    let size = world_size(zoom);
    // Mercator y is undefined at the poles; this is the standard web-mapping clamp
    // and the same constant `Mercator.kt` uses.
    let lat = lat.clamp(-85.051_128_78, 85.051_128_78);
    let x = (lon + 180.0) / 360.0 * size;
    let sin_lat = (lat * std::f64::consts::PI / 180.0).sin();
    let y = (0.5 - ((1.0 + sin_lat) / (1.0 - sin_lat)).ln() / (4.0 * std::f64::consts::PI)) * size;
    WorldPx { x, y }
}

/// Inverse of [`project`].
pub fn unproject(x: f64, y: f64, zoom: f64) -> (f64, f64) {
    let size = world_size(zoom);
    let lon = x / size * 360.0 - 180.0;
    let n = std::f64::consts::PI - 2.0 * std::f64::consts::PI * y / size;
    let lat = n.sinh().atan() * 180.0 / std::f64::consts::PI;
    (lon, lat)
}

/// Past this zoom the sphere is sub-pixel from flat, so [`Camera::tile_to_clip`]
/// and the Kotlin `Projection` both take the flat path bit-identically. Must stay
/// in step with the Kotlin `GLOBE_DETAIL_ZOOM`.
pub const GLOBE_FLAT_THRESHOLD: f64 = 8.0;

/// Zoom at which [`globe_radius`] starts blending from the half-world sphere toward the flat
/// map's scale. Must stay in step with the Kotlin `GLOBE_BLEND_START_ZOOM`.
pub const GLOBE_BLEND_START: f64 = 5.0;

/// Screen radius of the globe in Dp, for a camera at `zoom` centred on `center_lat`.
///
/// Zoomed out (below [`GLOBE_BLEND_START`]) it is half the world width, so the sphere shows
/// half the planet across its diameter and fills the screen. By [`GLOBE_FLAT_THRESHOLD`] it
/// must match the flat Mercator scale at the centre — `world / (2 * pi * cos(lat))` — or the
/// switch to the flat map jumps about 1.65 zoom levels. Between the two the factor blends
/// geometrically. Mirrors the Kotlin `globeRadius`.
pub fn globe_radius(zoom: f64, center_lat: f64) -> f64 {
    let t = ((zoom - GLOBE_BLEND_START) / (GLOBE_FLAT_THRESHOLD - GLOBE_BLEND_START)).clamp(0.0, 1.0);
    let cos = center_lat.to_radians().cos().max(0.05);
    let to_flat = 1.0 / (std::f64::consts::PI * cos);
    world_size(zoom) / 2.0 * to_flat.powf(t)
}

/// Whether this camera draws the globe: the flag on, and close enough out that
/// the sphere reads as a sphere rather than as the flat map.
pub fn globe_active(camera: &Camera) -> bool {
    camera.globe && camera.zoom < GLOBE_FLAT_THRESHOLD
}

/// Whether this frame draws the Moon: Moon selected *and* the globe active.
/// Below the threshold the Moon reads as Earth (flat vector path), so a
/// zoomed-in Moon never shows a stale raster.
pub fn moon_active(camera: &Camera) -> bool {
    camera.moon && globe_active(camera)
}

/// 3D unit-sphere point of (`lon`, `lat`) in the basis facing the camera centre:
/// the centre maps to (0, 0, 1), east is +x, north is +y, the far side is z < 0.
/// Mirrors the Kotlin `globePoint`, which must stay in step.
pub fn globe_point(center_lon: f64, center_lat: f64, lon: f64, lat: f64) -> (f64, f64, f64) {
    use std::f64::consts::PI;
    let lat_r = lat * PI / 180.0;
    let d_lon = (lon - center_lon) * PI / 180.0;
    let c_lat_r = center_lat * PI / 180.0;
    let (sy, cy) = (c_lat_r.sin(), c_lat_r.cos());
    let cos_lat = lat_r.cos();
    let sin_lat = lat_r.sin();
    let x = cos_lat * d_lon.sin();
    let y = cy * sin_lat - sy * cos_lat * d_lon.cos();
    let z = sy * sin_lat + cy * cos_lat * d_lon.cos();
    (x, y, z)
}

/// Inverse of [`globe_point`]: the lon/lat of a unit-sphere point in the
/// centre-facing basis. Mirrors the Kotlin `globeLonLat`.
pub fn globe_lonlat(center_lon: f64, center_lat: f64, x: f64, y: f64, z: f64) -> (f64, f64) {
    use std::f64::consts::PI;
    let c_lat_r = center_lat * PI / 180.0;
    let (sy, cy) = (c_lat_r.sin(), c_lat_r.cos());
    let ex = x;
    let ey = cy * y + sy * z;
    let ez = -sy * y + cy * z;
    let lat = ey.clamp(-1.0, 1.0).asin() * 180.0 / PI;
    let lon = center_lon + ex.atan2(ez) * 180.0 / PI;
    // Wrap to -180..180.
    let lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    (lon, lat.clamp(-90.0, 90.0))
}
