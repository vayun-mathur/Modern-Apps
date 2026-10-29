//! POI extraction: `.osm.pbf` -> a geojsonseq for tippecanoe plus
//! `poi_names.bin`, `poi_index.bin` and `poi_attrs.bin`.
//!
//! A port of the former `scripts/maps/poi_extract.cpp`. The four outputs are
//! mutually consistent — same POI set, same order, same coordinates:
//!
//! 1. **geojsonseq** newline-delimited GeoJSON `Point` features, fed to
//!    tippecanoe to build the `ma_pois` source layer. Properties: `name`
//!    (string), `type` (number), `osm_id` (number).
//! 2. **`poi_names.bin`** deduped NUL-terminated UTF-8 name table; a "name start
//!    index" is the byte offset of a name's first byte (the same convention as
//!    `road_names.bin`).
//! 3. **`poi_index.bin`** flat 14-byte little-endian records —
//!    `i32 lat_e7, i32 lon_e7, u32 name_off, u16 type` — sorted by the 64-bit
//!    Morton key of `(lat, lon)`, so the app can mmap and binary-scan a spatial
//!    range. The record contract lives in
//!    `maps/src/main/java/com/vayunmathur/maps/util/PoiIndex.kt`.
//! 4. **`poi_attrs.bin`** the attribute sidecar: opening hours, phone, website,
//!    address, cuisine and wheelchair access, indexed by the ORDINAL of the
//!    matching `poi_index.bin` record. See [`crate::poi_attrs`] for the layout.
//!    Kept separate because the 14-byte record is full and its width is load
//!    bearing in three places.
//!
//! A POI is any node, or closed-way / multipolygon area, carrying BOTH a `name`
//! and one of the recognised POI keys (see [`crate::tags::classify`]). Area
//! geometry is reduced to a representative centroid because POIs render as
//! points.
//!
//! ## Where this differs from the libosmium version
//!
//! libosmium assembled true polygon rings (`MultipolygonManager` + `Assembler`).
//! Porting a ring assembler is a large piece of work on its own, so a relation's
//! outer ring is approximated by the deduplicated node ids of its `outer`-role
//! member ways. That is exact when the ring is a single closed way; when it is
//! split across several, the ring's true vertex order is lost and the centroid
//! shifts by a few metres at most. Node- and closed-way-derived POIs match the
//! old tool exactly.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::geojson::json_escape;
use crate::names::NamePool;
use crate::osm::{self, visit_block, Element, MEMBER_WAY};
use crate::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use crate::poi_attrs::AttrPool;
use crate::poi_reviews::{ReviewTable, ELEMENT_NODE, ELEMENT_RELATION, ELEMENT_WAY};
use crate::poi_side;
use crate::proto::{Error, Result};
use crate::spatial::spatial_from_e7;
use crate::tags::{self, PoiTags};

/// Relation types libosmium's `MultipolygonManager` accepts as areas.
const AREA_RELATION_TYPES: [&str; 2] = ["multipolygon", "boundary"];

/// Sentinel latitude for "this node's location was never seen".
const NO_LOC: i32 = i32::MIN;

pub struct Stats {
    pub records: usize,
    pub unique_names: usize,
    pub name_bytes: u32,
    pub from_nodes: usize,
    pub from_ways: usize,
    pub from_relations: usize,
    /// POIs carrying at least one sidecar attribute.
    pub with_attrs: usize,
    pub unique_attrs: usize,
    pub attr_bytes: usize,
    /// Populated cells in `poi_spatial.bin`.
    pub spatial_cells: usize,
    /// `(record, word)` entries in `poi_name_index.bin`.
    pub name_entries: usize,
    /// POIs that matched a row of the `--reviews` table.
    pub with_reviews: usize,
}

struct Poi {
    lat: f64,
    lon: f64,
    lat_e7: i32,
    lon_e7: i32,
    morton: u64,
    type_: u16,
    osm_id: i64,
    name: Vec<u8>,
    /// Encoded sidecar record body, empty when the POI has no attributes.
    attrs: Vec<u8>,
}

/// A `type=multipolygon`/`boundary` relation that is itself a POI.
struct RelArea {
    id: i64,
    type_: u16,
    name: Vec<u8>,
    attrs: Vec<u8>,
    outer_ways: Vec<i64>,
}

#[derive(Default)]
struct RelPass {
    areas: Vec<RelArea>,
}

/// A closed way that is a POI in its own right.
struct WayArea {
    id: i64,
    type_: u16,
    name: Vec<u8>,
    attrs: Vec<u8>,
    refs: Vec<i64>,
}

#[derive(Default)]
struct WayPass {
    areas: Vec<WayArea>,
    /// Node refs of ways that are outer members of a POI relation.
    outer_refs: Vec<(i64, Vec<i64>)>,
}

#[derive(Default)]
struct NodePass {
    pois: Vec<Poi>,
    /// `(index into the needed-node table, lat_e7, lon_e7)`.
    locs: Vec<(u32, i32, i32)>,
}

pub fn build(
    input: &Path,
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
) -> Result<Stats> {
    build_with(input, geojson, names, index, attrs, spatial, name_index, None)
}

/// [`build`] with a region bbox filter: `Some` keeps only POIs touching the
/// box, `None` (world) keeps everything. Ways and relations are kept whole
/// when any vertex touches — the `complete_ways` rule — so a POI straddling
/// the border keeps its true centroid rather than one computed from a clip.
pub fn build_with(
    input: &Path,
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
    bbox: Option<crate::bbox::BBox>,
) -> Result<Stats> {
    build_with_reviews(input, geojson, names, index, attrs, spatial, name_index, bbox, None)
}

/// [`build_with`] plus an external review table whose rows are appended to the
/// matching POIs' attribute records (see [`crate::poi_reviews`]).
#[allow(clippy::too_many_arguments)]
pub fn build_with_reviews(
    input: &Path,
    geojson: &Path,
    names: &Path,
    index: &Path,
    attrs: &Path,
    spatial: &Path,
    name_index: &Path,
    bbox: Option<crate::bbox::BBox>,
    reviews: Option<&ReviewTable>,
) -> Result<Stats> {
    // Fail before the three passes rather than after them if an output path is
    // unwritable.
    for path in [geojson, names, index, attrs, spatial, name_index] {
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            std::fs::create_dir_all(parent)
                .map_err(|e| Error(format!("cannot create {}: {e}", parent.display())))?;
        }
    }

    let mut timer = crate::timing::PhaseTimer::start("poi");
    let blobs = pbf::scan_blobs(input)?;
    println!("[poi] Scanned {} data blob(s) in {}", blobs.len(), input.display());

    timer.switch("relations");
    // --- Pass 1: area relations ---------------------------------------------
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        &blobs,
        None,
        KIND_RELATIONS,
        "Pass 1: relations",
        RelPass::default,
        relation_blob,
    )?;
    let mut rel_areas: Vec<RelArea> = Vec::new();
    for chunk in chunks {
        rel_areas.extend(chunk.areas);
    }
    let mut outer_wanted: Vec<i64> = rel_areas
        .iter()
        .flat_map(|a| a.outer_ways.iter().copied())
        .collect();
    outer_wanted.sort_unstable();
    outer_wanted.dedup();
    println!("{} POI relation(s)", rel_areas.len());

    timer.switch("ways");
    // --- Pass 2: closed-way areas + the relations' outer ways ----------------
    let (chunks, _) = pbf::run_pass(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_WAYS,
        "Pass 2: ways",
        WayPass::default,
        |state, block| way_blob(state, block, &outer_wanted),
    )?;
    let mut way_areas: Vec<WayArea> = Vec::new();
    let mut outer_refs: HashMap<i64, Vec<i64>> = HashMap::new();
    for chunk in chunks {
        way_areas.extend(chunk.areas);
        outer_refs.extend(chunk.outer_refs);
    }

    // Every node whose location an area centroid needs.
    let mut needed: Vec<i64> = way_areas
        .iter()
        .flat_map(|a| a.refs.iter().copied())
        .chain(outer_refs.values().flat_map(|r| r.iter().copied()))
        .collect();
    needed.sort_unstable();
    needed.dedup();
    println!(
        "{} closed-way area(s), {} node location(s) needed",
        way_areas.len(),
        needed.len()
    );

    timer.switch("nodes");
    // --- Pass 3: node POIs + the node locations areas need -------------------
    let (chunks, _) = pbf::run_pass(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_NODES,
        "Pass 3: nodes",
        NodePass::default,
        |state, block| node_blob(state, block, &needed, bbox.as_ref()),
    )?;
    let mut pois: Vec<Poi> = Vec::new();
    let mut locs: Vec<(i32, i32)> = vec![(NO_LOC, NO_LOC); needed.len()];
    for chunk in chunks {
        pois.extend(chunk.pois);
        for (idx, lat, lon) in chunk.locs {
            locs[idx as usize] = (lat, lon);
        }
    }
    let from_nodes = pois.len();

    let location = |id: i64| -> Option<(i32, i32)> {
        let idx = needed.binary_search(&id).ok()?;
        let (lat, lon) = locs[idx];
        (lat != NO_LOC).then_some((lat, lon))
    };

    // A POI touches the region when any of its vertices does — same
    // `complete_ways` rule as the graph. The centroid is computed from the
    // whole ring regardless, so a border-straddling POI keeps its true
    // location.
    let touches = |ids: &mut dyn Iterator<Item = i64>| -> bool {
        let Some(b) = bbox.as_ref() else {
            return true;
        };
        ids.filter_map(&location).any(|(lat, lon)| b.contains_e7(lat, lon))
    };

    timer.switch("centroids");
    // --- Area centroids ------------------------------------------------------
    for area in &way_areas {
        if !touches(&mut area.refs.iter().copied()) {
            continue;
        }
        // A closed way's ring is its own node list minus the repeated closing
        // node.
        if let Some(poi) = ring_centroid(area.refs[..area.refs.len() - 1].iter().copied(), &location)
        {
            pois.push(make_poi(poi, area.type_, area.id, &area.name, area.attrs.clone()));
        }
    }
    let from_ways = pois.len() - from_nodes;

    for area in &rel_areas {
        // libosmium assembled a true ring from the member ways; we cannot, so
        // deduplicate the member node ids instead. That is exact when the outer
        // ring is a single closed way, and drops the endpoints shared between
        // consecutive ways when it is split across several.
        let mut refs: Vec<i64> = area
            .outer_ways
            .iter()
            .filter_map(|w| outer_refs.get(w))
            .flat_map(|r| r.iter().copied())
            .collect();
        refs.sort_unstable();
        refs.dedup();
        if !touches(&mut refs.iter().copied()) {
            continue;
        }
        if let Some(poi) = ring_centroid(refs.iter().copied(), &location) {
            pois.push(make_poi(poi, area.type_, -area.id, &area.name, area.attrs.clone()));
        }
    }
    let from_relations = pois.len() - from_nodes - from_ways;
    println!(
        "Extracted {} POI(s): {from_nodes} node, {from_ways} closed-way, {from_relations} relation",
        pois.len()
    );

    // Before the sort: `pois` is still nodes, then ways, then relations, which is
    // the only place the OSM element type is known.
    let with_reviews = match reviews {
        Some(table) => {
            let n = apply_reviews(&mut pois, from_nodes, from_ways, table);
            println!("{n} POI(s) with review data ({} review row(s) loaded)", table.len());
            n
        }
        None => 0,
    };

    // Morton order, ties broken by osm_id, so both poi_index.bin and the geojson
    // are stable across runs.
    timer.switch("sort");
    pois.sort_by_key(|p| (p.morton, p.osm_id));

    timer.switch("write");
    let written = write_outputs(&pois, geojson, names, index, attrs, spatial, name_index)?;
    timer.finish();
    Ok(Stats {
        records: written.records,
        unique_names: written.unique_names,
        name_bytes: written.name_bytes,
        from_nodes,
        from_ways,
        from_relations,
        with_attrs: written.with_attrs,
        unique_attrs: written.unique_attrs,
        attr_bytes: written.attr_bytes,
        spatial_cells: written.spatial_cells,
        name_entries: written.name_entries,
        with_reviews,
    })
}

/// Append each matching review row to its POI's attribute body; returns the match
/// count. `pois[..from_nodes]` are nodes, the next `from_ways` closed ways, the
/// rest relations (whose `osm_id` is stored negated).
fn apply_reviews(pois: &mut [Poi], from_nodes: usize, from_ways: usize, table: &ReviewTable) -> usize {
    let mut matched = 0;
    for (i, p) in pois.iter_mut().enumerate() {
        let (element, id) = if i < from_nodes {
            (ELEMENT_NODE, p.osm_id)
        } else if i < from_nodes + from_ways {
            (ELEMENT_WAY, p.osm_id)
        } else {
            (ELEMENT_RELATION, -p.osm_id)
        };
        if let Some(review) = table.get(element, id) {
            p.attrs = crate::poi_attrs::append(std::mem::take(&mut p.attrs), &review.fields());
            matched += 1;
        }
    }
    matched
}

fn poi_tags<'a>(t: &osm::Tags<'_, 'a>) -> PoiTags<'a> {
    PoiTags {
        railway: t.get_str("railway"),
        public_transport: t.get_str("public_transport"),
        amenity: t.get_str("amenity"),
        shop: t.get_str("shop"),
        tourism: t.get_str("tourism"),
        leisure: t.get_str("leisure"),
        healthcare: t.get_str("healthcare"),
        office: t.get_str("office"),
    }
}

fn relation_blob(state: &mut RelPass, block: &pbf::PrimitiveBlock) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_RELATIONS, &mut kinds, &mut |el: Element| {
        if let Element::Relation(r) = el {
            let is_area = r
                .tags
                .get_str("type")
                .is_some_and(|t| AREA_RELATION_TYPES.contains(&t));
            if !is_area {
                return Ok(());
            }
            let name = match r.tags.get("name") {
                Some(n) if !n.is_empty() => n,
                _ => return Ok(()),
            };
            if let Some(type_) = tags::classify(&poi_tags(&r.tags)) {
                state.areas.push(RelArea {
                    id: r.id,
                    type_,
                    name: name.to_vec(),
                    attrs: crate::poi_attrs::encode(|k| r.tags.get(k)),
                    outer_ways: r
                        .members
                        .iter()
                        .filter(|m| m.kind == MEMBER_WAY && (m.role == b"outer" || m.role.is_empty()))
                        .map(|m| m.id)
                        .collect(),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn way_blob(
    state: &mut WayPass,
    block: &pbf::PrimitiveBlock,
    outer_wanted: &[i64],
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if outer_wanted.binary_search(&w.id).is_ok() {
                state.outer_refs.push((w.id, w.refs.to_vec()));
            }
            // libosmium's `MultipolygonManager::after_way` builds a standalone
            // area from every closed way with more than 3 nodes that is not
            // tagged `area=no` — including ways that are also members of a
            // multipolygon relation, so a campus tagged on both the relation and
            // its rings yields a POI either way. It compares end *locations*;
            // comparing the end refs is equivalent for real OSM data and needs no
            // location index here.
            let closed = w.refs.len() > 3 && w.refs.first() == w.refs.last();
            if !closed || w.tags.get_str("area") == Some("no") {
                return Ok(());
            }
            let name = match w.tags.get("name") {
                Some(n) if !n.is_empty() => n,
                _ => return Ok(()),
            };
            if let Some(type_) = tags::classify(&poi_tags(&w.tags)) {
                state.areas.push(WayArea {
                    id: w.id,
                    type_,
                    name: name.to_vec(),
                    attrs: crate::poi_attrs::encode(|k| w.tags.get(k)),
                    refs: w.refs.to_vec(),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

fn node_blob(
    state: &mut NodePass,
    block: &pbf::PrimitiveBlock,
    needed: &[i64],
    bbox: Option<&crate::bbox::BBox>,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if let Ok(idx) = needed.binary_search(&n.id) {
                state.locs.push((idx as u32, n.lat_e7, n.lon_e7));
            }
            if n.tags.is_empty() {
                return Ok(());
            }
            // A node POI outside the region is dropped here; its own location
            // arrives with it, so no location table is needed.
            if !crate::bbox::keep_e7(bbox, n.lat_e7, n.lon_e7) {
                return Ok(());
            }
            let name = match n.tags.get("name") {
                Some(v) if !v.is_empty() => v,
                _ => return Ok(()),
            };
            if let Some(type_) = tags::classify(&poi_tags(&n.tags)) {
                state.pois.push(make_poi(
                    (n.lat_e7 as f64 * 1e-7, n.lon_e7 as f64 * 1e-7),
                    type_,
                    n.id,
                    name,
                    crate::poi_attrs::encode(|k| n.tags.get(k)),
                ));
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

/// Average of an area ring's vertices, reproducing libosmium's representative
/// point.
///
/// `refs` is the ring's *distinct* vertices. libosmium stored a ring closed — the
/// start vertex appears again at the end — and its Assembler sorted the ring's
/// segments before walking them, so the ring always starts at the vertex with the
/// smallest `(lon, lat)`. That vertex is therefore the one counted twice, which is
/// why it is added again here: averaging the way's own node list instead would
/// double the way's *first* node and land metres off on a small building.
fn ring_centroid<I, L>(refs: I, location: &L) -> Option<(f64, f64)>
where
    I: Iterator<Item = i64>,
    L: Fn(i64) -> Option<(i32, i32)>,
{
    let mut sum_lat = 0.0f64;
    let mut sum_lon = 0.0f64;
    let mut n = 0u64;
    let mut start: Option<(i32, i32)> = None;
    for id in refs {
        if let Some((lat_e7, lon_e7)) = location(id) {
            sum_lat += lat_e7 as f64 * 1e-7;
            sum_lon += lon_e7 as f64 * 1e-7;
            n += 1;
            // libosmium's Location orders by x (lon) then y (lat).
            if start.is_none_or(|(slat, slon)| (lon_e7, lat_e7) < (slon, slat)) {
                start = Some((lat_e7, lon_e7));
            }
        }
    }
    let (slat, slon) = start?;
    sum_lat += slat as f64 * 1e-7;
    sum_lon += slon as f64 * 1e-7;
    Some((sum_lat / (n + 1) as f64, sum_lon / (n + 1) as f64))
}

include!("poi_build_part1.rs");
include!("poi_build_part2.rs");