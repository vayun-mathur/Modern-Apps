//! What gets indexed, and how it is pulled out of a `.osm.pbf`.
//!
//! The database this replaces held **only** objects carrying both `addr:housenumber` and
//! `addr:street`. That left three large gaps, all of which show up as a geocoder that says
//! nothing useful:
//!
//! - **No streets.** Reverse geocoding could return a nearby house number or nothing at all;
//!   it could never say "you are on Foo Street".
//! - **No POIs or places.** A named building, shop or neighbourhood was invisible in both
//!   directions.
//! - **`addr:place` addresses dropped.** Many countries — much of Germany and Austria, and
//!   most of Japan and Korea — address buildings against a *place* rather than a street.
//!   Requiring `addr:street` silently discarded all of them.
//!
//! ## Pass ordering
//!
//! A PBF stores nodes before the ways that reference them and ways before the relations, so
//! anything needing way or relation geometry has to be read backwards, in three passes:
//! relations decide which ways matter, ways decide which node coordinates matter, and nodes
//! supply them. `osm_ingest::extract` documents this; the same ordering is reproduced here
//! because this crate drives the scan itself rather than emitting a layer.

use std::collections::HashMap;

use osm_ingest::bbox::{self, BBox};
use osm_ingest::nodeloc::{resolve_nodes, NodeLocations};
use osm_ingest::osm::{visit_block, Element, Tags, MEMBER_WAY};
use osm_ingest::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use osm_ingest::proto::{Error, Result};
use rayon::prelude::*;

use crate::codec::Interner;
use crate::extract_extra::STREET_SAMPLE_E7;
use crate::progress::Progress;
use crate::format::{e7_to_e5, in_range, DICTS, D_CITY, D_COUNTRY, D_HOUSE, D_NAME, D_POSTCODE,
    D_STATE, D_STREET, K_ADDRESS, K_PLACE, K_POI, K_STREET};

/// Cap on samples per street way, so one enormous way cannot dominate the database.
const STREET_MAX_SAMPLES: usize = 32;

/// Tag keys that make an object a POI when it also has a name.
const POI_KEYS: [&str; 6] = ["amenity", "shop", "tourism", "leisure", "office", "healthcare"];

/// `highway=*` values that are streets someone could be standing on or addressed against.
/// Named footways, cycleways, tracks, service aisles, links and bus stops used to be sampled
/// too; they were a large share of the street rows and a nearest-street answer of "Parking
/// Aisle B" or a bus stop name is worse than the road beside it.
const ROAD_VALUES: [&str; 10] = [
    "motorway",
    "trunk",
    "primary",
    "secondary",
    "tertiary",
    "unclassified",
    "residential",
    "living_street",
    "pedestrian",
    "road",
];

fn is_road(highway: &str) -> bool {
    ROAD_VALUES.contains(&highway)
}

/// `place=*` values worth indexing: inhabited places people search for and expect to be told
/// they are in. Deliberately excludes `place=locality` (often uninhabited), `region`, `ocean`
/// and the administrative-only values.
const PLACE_VALUES: [&str; 9] = [
    "city",
    "town",
    "village",
    "hamlet",
    "suburb",
    "neighbourhood",
    "quarter",
    "borough",
    "island",
];

/// One indexed feature, with its strings already interned.
///
/// Holding `String`s here instead would be the single thing that stops a planet build from
/// running: at roughly 640 million rows, seven `String`s each is about 250 GB of allocator
/// headers and heap. Interned, a row is 40 bytes and the whole set is 25 GB, with the distinct
/// strings stored once in [`Strings`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Row {
    /// Latitude, e5.
    pub lat: i32,
    /// Longitude, e5.
    pub lon: i32,
    /// One of the `K_*` constants.
    pub kind: u8,
    /// Dictionary ids, indexed by the `D_*` constants in [`crate::format`].
    pub ids: [u32; DICTS],
}

/// The string dictionaries, filled as extraction runs.
pub struct Strings {
    /// One interner per dictionary slot, in `D_*` order.
    pub dicts: Vec<Interner>,
}

impl Default for Strings {
    fn default() -> Strings {
        Strings { dicts: (0..DICTS).map(|_| Interner::new()).collect() }
    }
}

impl Strings {
    pub(crate) fn intern(&mut self, a: &Attrs) -> [u32; DICTS] {
        [
            self.dicts[D_NAME].intern(&a.name),
            self.dicts[D_HOUSE].intern(&a.house),
            self.dicts[D_STREET].intern(&a.street),
            self.dicts[D_CITY].intern(&a.city),
            self.dicts[D_STATE].intern(&a.state),
            self.dicts[D_COUNTRY].intern(&a.country),
            self.dicts[D_POSTCODE].intern(&a.postcode),
        ]
    }
}

/// Whether a classified feature's **tags** carry what its kind requires. Checked before
/// interning, so a useless feature never reaches the dictionaries.
fn attrs_ok(kind: u8, a: &Attrs) -> bool {
    match kind {
        // An address with neither a street nor a place cannot be written down, and a house
        // number on its own is not findable.
        K_ADDRESS => !a.house.is_empty() && !a.street.is_empty(),
        _ => !a.name.is_empty(),
    }
}

/// Whether a classified feature is worth storing at all.
fn is_useful(kind: u8, a: &Attrs, lat_e7: i32, lon_e7: i32) -> bool {
    in_range(e7_to_e5(lat_e7), e7_to_e5(lon_e7)) && attrs_ok(kind, a)
}

/// What a pass decided about one element.
#[derive(Default, Clone)]
pub(crate) struct Attrs {
    name: String,
    house: String,
    street: String,
    city: String,
    state: String,
    country: String,
    postcode: String,
}

fn tag(tags: &Tags, key: &str) -> String {
    tags.get_str(key).unwrap_or("").trim().to_string()
}

fn read_attrs(tags: &Tags) -> Attrs {
    let street = {
        let s = tag(tags, "addr:street");
        // `addr:place` is how much of DE/AT/JP/KR addresses buildings; treating it as the
        // street is what makes those addresses representable at all.
        if s.is_empty() {
            tag(tags, "addr:place")
        } else {
            s
        }
    };
    let state = {
        let s = tag(tags, "addr:state");
        if s.is_empty() {
            tag(tags, "addr:province")
        } else {
            s
        }
    };
    Attrs {
        name: tag(tags, "name"),
        house: tag(tags, "addr:housenumber"),
        street,
        city: tag(tags, "addr:city"),
        state,
        country: tag(tags, "addr:country"),
        postcode: tag(tags, "addr:postcode"),
    }
}

/// Decide what an element is, from its tags alone. `None` means "not indexed".
fn classify(tags: &Tags) -> Option<u8> {
    if tags.get("addr:housenumber").is_some() {
        return Some(K_ADDRESS);
    }
    let named = tags.get("name").is_some();
    if !named {
        return None;
    }
    if tags.get_str("highway").is_some_and(is_road) {
        return Some(K_STREET);
    }
    if let Some(place) = tags.get_str("place") {
        if PLACE_VALUES.contains(&place) {
            return Some(K_PLACE);
        }
    }
    if POI_KEYS.iter().any(|k| tags.get(k).is_some()) {
        return Some(K_POI);
    }
    None
}

/// OSM coordinates arrive as e7 and are rounded to the stored e5 here, once.
fn row_from(kind: u8, ids: [u32; DICTS], lat_e7: i32, lon_e7: i32) -> Row {
    Row { lat: e7_to_e5(lat_e7), lon: e7_to_e5(lon_e7), kind, ids }
}

// --------------------------------------------------------------------------- way handling
/// A way held between passes, with everything it needs except coordinates.
///
/// Attributes are already interned: a planet run holds a quarter of a billion of these, and
/// keeping seven `String`s per way is what would put the build over the machine's memory.
pub(crate) struct PendingWay {
    kind: u8,
    ids: [u32; DICTS],
    refs: Vec<i64>,
}

/// Vertex average of a closed or open way.
///
/// A closed ring repeats its first vertex; counting it twice biases the point, so it is
/// dropped. This is a vertex average and not a true polygon centroid, which for an outline
/// with unevenly spaced nodes differs by metres — acceptable for a search result's anchor.
fn average(coords: &[(i32, i32)]) -> Option<(i32, i32)> {
    let pts = if coords.len() > 2 && coords.first() == coords.last() {
        &coords[..coords.len() - 1]
    } else {
        coords
    };
    if pts.is_empty() {
        return None;
    }
    let mut lat = 0i64;
    let mut lon = 0i64;
    for &(a, o) in pts {
        lat += a as i64;
        lon += o as i64;
    }
    Some(((lat / pts.len() as i64) as i32, (lon / pts.len() as i64) as i32))
}

/// Sample points along a street.
///
/// One record per way would put a long road's only entry at its midpoint, so standing at one
/// end you would be told the nearest street is something else entirely. Samples are spread
/// **evenly along the way**, roughly [`crate::extract_extra::STREET_SAMPLE_E7`] apart, always including both ends,
/// and capped at [`STREET_MAX_SAMPLES`].
///
/// Spreading evenly rather than walking until the cap is reached is what keeps a motorway
/// represented over its whole length: taking the first 32 samples at a fixed spacing would
/// cover the first few kilometres and leave the rest of the way invisible to reverse lookup.
fn sample_line(coords: &[(i32, i32)]) -> Vec<(i32, i32)> {
    let Some(&first) = coords.first() else { return Vec::new() };
    let Some(&last) = coords.last() else { return Vec::new() };
    if coords.len() == 1 {
        return vec![first];
    }

    // Manhattan distance in e7 units, longitude unscaled. Cheap, and only ever used to space
    // samples, never as a real distance.
    let seg = |a: (i32, i32), b: (i32, i32)| -> i64 {
        (a.0 as i64 - b.0 as i64).abs() + (a.1 as i64 - b.1 as i64).abs()
    };
    let total: i64 = coords.windows(2).map(|w| seg(w[0], w[1])).sum();
    if total == 0 {
        return vec![first];
    }

    let want = ((total / STREET_SAMPLE_E7) + 1).clamp(2, STREET_MAX_SAMPLES as i64) as usize;
    let step = total as f64 / (want - 1) as f64;

    let mut out = Vec::with_capacity(want);
    out.push(first);
    let mut acc = 0i64;
    for w in coords.windows(2) {
        acc += seg(w[0], w[1]);
        while out.len() < want - 1 && acc as f64 >= step * out.len() as f64 {
            out.push(w[1]);
        }
    }
    out.push(last);
    out.dedup();
    out
}

// --------------------------------------------------------------------------- driver
/// Counts describing what a run indexed, for the build report.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    /// Rows from node elements.
    pub from_nodes: usize,
    /// Rows from way elements.
    pub from_ways: usize,
    /// Rows from relation elements.
    pub from_relations: usize,
    /// Addresses.
    pub addresses: usize,
    /// Street samples.
    pub streets: usize,
    /// Points of interest.
    pub pois: usize,
    /// Populated places.
    pub places: usize,
    /// Classified but outside `--bbox`.
    pub outside_bbox: usize,
    /// Classified but missing what its kind requires.
    pub incomplete: usize,
}

impl Stats {
    /// Rows produced.
    pub fn rows(&self) -> usize {
        self.addresses + self.streets + self.pois + self.places
    }

    fn add(&mut self, o: &Stats) {
        self.from_nodes += o.from_nodes;
        self.from_ways += o.from_ways;
        self.from_relations += o.from_relations;
        self.addresses += o.addresses;
        self.streets += o.streets;
        self.pois += o.pois;
        self.places += o.places;
        self.outside_bbox += o.outside_bbox;
        self.incomplete += o.incomplete;
    }
}

include!("extract_part1.rs");