//! `boundaries`: the one layer whose detail is a number.
//!
//! An administrative boundary's whole meaning is its **level** — 2 is a country, 4 a state, 6 a
//! county, 8 a city — and the style compares it with `<=` rather than matching a name: one layer for
//! country borders and another for everything below. So the level is carried as a plain integer in
//! the `kind_detail` field under [`FLAG_DETAIL_NUMERIC`], rather than interned. Interning it would
//! mean interning every integer, and comparing `<=` against an id that is not ordered like the value
//! would be worse than useless.
//!
//! A `kind` is carried too, for the differential harness and for a future style that wants a county
//! line dashed differently from a state line. It is derivable from the level, which is exactly why
//! the level is what the style reads.
//!
//! # A boundary is a line, and cannot be an area
//!
//! Carrying the region as an area was tried, so that tapping a city could dim everything outside
//! it, and it has to be reverted: **clipping a polygon to a tile adds segments along the tile
//! edge** to close the ring, and the style's boundary layers are `line`, which strokes a polygon's
//! outline. Those synthetic edges are then drawn, and the map is covered in a grid of tile borders.
//! Clipping a *line* merely truncates it, which is why the line form has never had this problem.
//!
//! There is no way to opt out from the style side either: the `boundaries` entry declares no
//! `kinds`, so it matches every feature in the layer and would stroke a polygon whatever kind it
//! carried.
//!
//! Whatever eventually feeds a region mask therefore needs somewhere the boundary style does not
//! reach — a kind the layer excludes, or a layer of its own — decided together with the mask
//! rather than guessed at here.
//!
//! # The marking convention rides on this layer too
//!
//! Which side traffic keeps and what colour separates the directions are properties of the
//! *country*, and the only country geometry this build has is the `admin_level=2` relations it
//! already streams for the border lines. [`Conventions`] turns those into a coarse grid the tiler
//! stamps onto each tile's body, so the renderer never has to carry a polygon set to answer a
//! question that never changes for a given tile.

use std::collections::HashMap;

use tilecodec::mamaps::body::{MarkingConvention, FLAG_DETAIL_NUMERIC};
use tilecodec::mamaps::dict::LAYER_BOUNDARIES;

use super::{kind, Class, TagSource};

pub const FILTERS: &[&str] = &["boundary", "admin_level", "maritime"];

/// Every `kind` this module can emit.
#[cfg_attr(not(test), allow(dead_code))]
pub const KINDS: &[&str] = &["country", "region", "county", "locality", "region_area"];

/// The deepest administrative level worth drawing.
///
/// Below 8 is a ward or a neighbourhood: real data, and a line nobody has ever wanted on a basemap.
const MAX_LEVEL: u16 = 8;

/// The region's *shape*, for a relation that has one.
///
/// Emitted **in addition to** the border line from [`classify`], never instead of it: the line is
/// what draws the border, and this is only read by the region mask. Both are needed, and they
/// cannot be the same feature — see the module docs for the tile-edge grid that results from
/// trying.
///
/// `None` for a way, because a single way is one segment of a border and encloses nothing. A
/// relation whose rings will not close simply yields no area, and the border is unaffected.
pub fn region_area(tags: &(impl TagSource + ?Sized), is_way: bool) -> Option<Class> {
    if is_way {
        return None;
    }
    let line = classify(tags)?;
    Some(Class {
        layer: LAYER_BOUNDARIES,
        kind: kind("region_area"),
        // The same level the border carries, so the mask can tell a country from a city.
        kind_detail: line.kind_detail,
        flags: FLAG_DETAIL_NUMERIC,
        area: true,
        // One level shallower than the border line. A mask is drawn over a whole region, so it is
        // wanted at the zoom where the region *fits on screen* — which is about where its label
        // appears, not where its border becomes legible.
        min_zoom: line.min_zoom.saturating_sub(1),
        min_area_px: 0.0,
    })
}

pub fn classify(tags: &(impl TagSource + ?Sized)) -> Option<Class> {
    if tags.get("boundary") != Some("administrative") {
        return None;
    }
    // A maritime boundary (an EEZ or territorial-water limit drawn across open sea) is
    // `boundary=administrative` over water, not inland/coastline admin, and the reference
    // style's boundary layers do not show it. Dropped here, in the tiler, so the layer
    // carries what the style draws rather than lines across the ocean.
    if tags.get("maritime") == Some("yes") {
        return None;
    }
    let level: u16 = tags.get("admin_level")?.trim().parse().ok()?;
    if level == 0 || level > MAX_LEVEL {
        return None;
    }
    Some(Class {
        layer: LAYER_BOUNDARIES,
        kind: kind(kind_for(level)),
        // The level itself, not an id. This is the only field in the format that is a number.
        kind_detail: level,
        flags: FLAG_DETAIL_NUMERIC,
        // A border is a line even when it closes. Filling it would paint over every layer inside
        // the country, and — the reason an attempt to make it an area had to be reverted — a
        // polygon clipped to a tile grows edges along the tile boundary, which the style's `line`
        // layers then stroke as a grid across the whole map. See the module docs.
        area: false,
        min_zoom: min_zoom_for(level),
        min_area_px: 0.0,
    })
}

/// The name for an administrative level, as upstream spells it.
fn kind_for(level: u16) -> &'static str {
    match level {
        0..=2 => "country",
        3..=4 => "region",
        5..=6 => "county",
        _ => "locality",
    }
}

/// How shallow a level is worth drawing.
///
/// A country border carries a world tile. A city limit at z4 is noise — and there are a hundred
/// thousand of them. Levels 5+ start at z12, and the layer itself caps at z12
/// (`MAX_ZOOM_PER_LAYER`): county/city lines tile exactly one zoom, so the
/// z13 copies the old cap carried are gone and z13+ overzooms from z12.
fn min_zoom_for(level: u16) -> u8 {
    match level {
        0..=2 => 0,
        3..=4 => 3,
        5..=6 => 12,
        _ => 12,
    }
}

/// The ISO 3166-1 alpha-2 code of a country relation, or `None` for anything that is not one.
///
/// `admin_level=2` and nothing else: level 4 is a state, and a state has no traffic convention of
/// its own anywhere this build covers. The code is read verbatim rather than matched against a
/// list, because [`convention_for`] is the only thing that looks at it and an unknown code there
/// is already the default rather than an error.
pub fn country_code(tags: &(impl TagSource + ?Sized)) -> Option<&str> {
    if tags.get("boundary") != Some("administrative") || tags.get("admin_level")?.trim() != "2" {
        return None;
    }
    tags.get("ISO3166-1").map(str::trim).filter(|code| code.len() == 2)
}

/// Countries where traffic keeps left.
///
/// The list is what it is: there is no rule to derive it from, and the ones missing from it get
/// right-hand traffic, which is most of the world by land area and the safer thing to be wrong
/// about. British overseas territories are here individually because OSM gives each its own
/// `admin_level=2` relation and its own code.
const LEFT_HAND: &[&str] = &[
    "AG", "AI", "AU", "BB", "BD", "BM", "BN", "BS", "BT", "BW", "CC", "CK", "CX", "CY", "DM", "FJ",
    "FK", "GB", "GD", "GG", "GY", "HK", "ID", "IE", "IM", "IN", "JE", "JM", "JP", "KE", "KI", "KN",
    "KY", "LC", "LK", "LS", "MO", "MS", "MT", "MU", "MV", "MW", "MY", "MZ", "NA", "NF", "NP", "NR",
    "NU", "NZ", "PG", "PK", "PN", "SB", "SC", "SG", "SH", "SR", "SZ", "TC", "TH", "TL", "TO", "TT",
    "TV", "TZ", "UG", "VC", "VG", "VI", "WS", "ZA", "ZM", "ZW",
];

/// Countries where a **yellow** line separates opposing directions.
///
/// The Americas, plus Japan and South Korea. Everywhere else the centre line is white and only its
/// style distinguishes it from a lane divider, which is what [`MarkingConvention`]'s default says.
const YELLOW_CENTRE: &[&str] = &[
    "AR", "BO", "BR", "BS", "BZ", "CA", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "GY", "HN", "HT",
    "JM", "JP", "KR", "MX", "NI", "PA", "PE", "PR", "PY", "SR", "SV", "TT", "US", "UY", "VE",
];

/// The convention a country's roads are marked to.
///
/// An unlisted code is right-hand traffic with a white centre line, which is
/// [`MarkingConvention`]'s own default and what the renderer falls back to when a tile carries no
/// convention at all — so an unrecognised country and an unresolved tile draw the same, rather
/// than differently for no reason a reader could see.
pub fn convention_for(code: &str) -> MarkingConvention {
    let listed = |list: &[&str]| list.iter().any(|entry| entry.eq_ignore_ascii_case(code));
    MarkingConvention { left_hand: listed(LEFT_HAND), yellow_centre: listed(YELLOW_CENTRE) }
}

/// The zoom the country under a tile is resolved at.
///
/// **This is the whole affordability argument.** A California build is 12.7 M z14 tiles and a
/// planet build is billions, and a point-in-polygon test against every country for each of them is
/// not a cost this build can carry — while the answer is identical across enormous stretches of
/// them, because a convention is a property of a country rather than of a street. So it is
/// resolved once per z6 tile (4096 of them for the whole world, a few hundred kilometres each) and
/// every tile below inherits its ancestor's.
///
/// The error that buys is confined to z6 tiles a border runs through, and only where the two
/// countries either side disagree — the United States and Mexico agree, so do France and Germany;
/// Thailand and Cambodia do not. A handful of tiles drawing a centre line on the wrong side is the
/// price of not projecting a polygon set per tile.
pub const COARSE_ZOOM: u8 = 6;

/// Which convention applies where, as a sparse grid of [`COARSE_ZOOM`] tiles.
///
/// Sparse because most of the grid is ocean, and because a build of one state has no opinion about
/// the rest of the world: an unclaimed tile reads back as [`MarkingConvention::default`], which is
/// exactly what a tile with no convention at all means to the renderer.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Conventions {
    /// `(convention, was claimed by an interior tile)`, keyed by `y * 2^COARSE_ZOOM + x`.
    ///
    /// The flag is what settles a coastal or border tile. A country claims a tile twice over: once
    /// for covering its centre, which is unambiguous, and once for merely having a boundary vertex
    /// in it, which several countries can do at once. An interior claim therefore overrides a
    /// border claim, and between two claims of the same standing the first wins — which is
    /// relation order, which is the PBF's order, which is what keeps the archive reproducible.
    at: HashMap<u32, (MarkingConvention, bool)>,
}

impl Conventions {
    /// Stamp one country's shape onto the grid.
    ///
    /// `polygons` is the relation's assembled rings in lon/lat, exactly as the `boundaries` region
    /// shape carries them — outer first, then holes, which is what makes the even-odd fill below
    /// treat an enclave as a hole rather than as more of the country.
    pub fn add(&mut self, code: &str, polygons: &[Vec<Vec<(f64, f64)>>]) {
        let convention = convention_for(code);
        let side = (1u32 << COARSE_ZOOM) as f64;
        for rings in polygons {
            // Projected once per ring rather than per scanline: this is the same
            // [`tile_build::geom::project`] the tiler places every feature with, so a country's
            // edge lands where its border line does.
            let projected: Vec<Vec<(f64, f64)>> = rings
                .iter()
                .map(|ring| {
                    ring.iter()
                        .map(|&(lon, lat)| tile_build::geom::project(lon, lat, COARSE_ZOOM))
                        .collect()
                })
                .collect();
            for ring in &projected {
                for &(x, y) in ring {
                    self.claim(x, y, convention, false);
                }
            }
            self.fill(&projected, convention, side);
        }
    }

    /// Claim every coarse tile whose centre the polygon covers, one row at a time.
    ///
    /// A scanline rather than a test per tile: the crossings of one horizontal line against every
    /// ring cost one pass over the vertices, and there are only 64 rows in the whole world. Testing
    /// each candidate tile instead would walk a country's rings once per tile, and a country's
    /// rings run to hundreds of thousands of points.
    fn fill(&mut self, rings: &[Vec<(f64, f64)>], convention: MarkingConvention, side: f64) {
        let (mut top, mut bottom) = (f64::MAX, f64::MIN);
        for ring in rings {
            for &(_, y) in ring {
                top = top.min(y);
                bottom = bottom.max(y);
            }
        }
        if top > bottom {
            return;
        }
        let first = top.floor().max(0.0) as u32;
        let last = bottom.floor().min(side - 1.0) as u32;
        let mut crossings: Vec<f64> = Vec::new();
        for row in first..=last {
            let at = row as f64 + 0.5;
            crossings.clear();
            for ring in rings {
                // Wrapping rather than `windows(2)`, so a ring that arrived unclosed still gets its
                // closing edge. A closed one's wrap edge is degenerate and crosses nothing.
                for i in 0..ring.len() {
                    let (ax, ay) = ring[i];
                    let (bx, by) = ring[(i + 1) % ring.len()];
                    // Half-open in `y`, so a vertex exactly on the scanline is counted once rather
                    // than zero or twice — the usual even-odd rule.
                    if (ay <= at) == (by <= at) {
                        continue;
                    }
                    crossings.push(ax + (at - ay) * (bx - ax) / (by - ay));
                }
            }
            crossings.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
            for span in crossings.chunks(2) {
                let [from, to] = span else { continue };
                // A tile is inside when its *centre* is, which is `tx + 0.5` in `from..=to`.
                let low = (from - 0.5).ceil().max(0.0);
                let high = (to - 0.5).floor().min(side - 1.0);
                let mut tx = low;
                while tx <= high {
                    self.claim(tx, row as f64, convention, true);
                    tx += 1.0;
                }
            }
        }
    }

    fn claim(&mut self, x: f64, y: f64, convention: MarkingConvention, interior: bool) {
        let side = 1u32 << COARSE_ZOOM;
        if !(x.is_finite() && y.is_finite()) {
            return;
        }
        let (tx, ty) = (
            (x.floor().max(0.0) as u32).min(side - 1),
            (y.floor().max(0.0) as u32).min(side - 1),
        );
        let held = self.at.entry(ty * side + tx).or_insert((convention, interior));
        // An interior claim outranks the border claim of whichever country reached the tile first.
        if interior && !held.1 {
            *held = (convention, true);
        }
    }

    /// The convention for a tile, or the default where nothing claimed its coarse ancestor.
    ///
    /// A tile at or below [`COARSE_ZOOM`] inherits the grid cell it sits in; one above it takes
    /// the cell at its own centre, which is the only sensible answer when a tile spans several
    /// countries and is drawn at a zoom where no lane marking is visible anyway.
    pub fn at_tile(&self, z: u8, x: u64, y: u64) -> MarkingConvention {
        let side = 1u64 << COARSE_ZOOM;
        let (cx, cy) = if z >= COARSE_ZOOM {
            let shift = z - COARSE_ZOOM;
            (x >> shift, y >> shift)
        } else {
            let step = 1u64 << (COARSE_ZOOM - z);
            ((x * step) + step / 2, (y * step) + step / 2)
        };
        let key = (cy.min(side - 1) * side + cx.min(side - 1)) as u32;
        self.at.get(&key).map(|(convention, _)| *convention).unwrap_or_default()
    }

    /// Does the grid know anything at all? Empty for a build with the `boundaries` layer switched
    /// off, or one whose extract holds no country relation.
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.at.is_empty()
    }

    /// The grid as the store index carries it: a `u32` count, then a `u32` cell key and a
    /// [`MarkingConvention`] byte each, ascending by key so a reused store reproduces the run that
    /// built it byte for byte.
    #[cfg(test)]
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut keys: Vec<&u32> = self.at.keys().collect();
        keys.sort_unstable();
        let mut out = Vec::with_capacity(4 + keys.len() * 5);
        out.extend_from_slice(&(keys.len() as u32).to_le_bytes());
        for key in keys {
            out.extend_from_slice(&key.to_le_bytes());
            out.push(self.at[key].0.to_byte());
        }
        out
    }

    /// The inverse of [`Conventions::to_bytes`], returning the grid and the bytes it consumed.
    #[cfg(test)]
    pub fn from_bytes(raw: &[u8]) -> osm_ingest::proto::Result<(Conventions, usize)> {
        if raw.len() < 4 {
            return osm_ingest::proto::err(
                "a store index's convention grid is truncated".to_string(),
            );
        }
        let count = u32::from_le_bytes(raw[..4].try_into().expect("four bytes")) as usize;
        let end = 4 + count * 5;
        if raw.len() < end {
            return osm_ingest::proto::err(format!(
                "a store index claims {count} convention cell(s) and holds {}",
                (raw.len() - 4) / 5,
            ));
        }
        let mut at = HashMap::with_capacity(count);
        for cell in raw[4..end].chunks_exact(5) {
            let key = u32::from_le_bytes(cell[..4].try_into().expect("four bytes"));
            let convention = MarkingConvention::from_byte(cell[4])
                .map_err(|e| osm_ingest::proto::Error(e.to_string()))?;
            // Resolved already, so every reloaded cell counts as an interior claim — nothing adds
            // to a grid that came off disk.
            at.insert(key, (convention, true));
        }
        Ok((Conventions { at }, end))
    }
}

include!("boundaries_part1.rs");