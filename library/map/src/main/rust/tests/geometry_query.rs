#![allow(dead_code)]
//! The geometry query against a synthetic `.mamaps` archive, through
//! the same file reader + codec the device uses: bbox filtering, caps
//! and empty-region honesty at the archive level (the pure extraction
//! maths is unit-tested in `tile::geometry_query`).

use std::path::PathBuf;
use std::sync::Arc;

use map_renderer::tile::geometry_query::{
    query_geometry, GeometryQuery, KIND_ALL, KIND_BUILDING, KIND_ROAD, KIND_WATER,
};
use map_renderer::tile::source::FileRangeReader;
use tilecodec::mamaps::read::{LeafCache, MamapsArchive};

#[path = "real_archive/fixture.rs"]
mod fixture;

fn open(path: &std::path::Path) -> MamapsArchive<FileRangeReader> {
    MamapsArchive::open_with_cache(
        FileRangeReader::open(path).expect("FileRangeReader::open"),
        Arc::new(LeafCache::new()),
    )
    .expect("archive opens")
}

/// A z14 archive over San Francisco holding the rich fixture body
/// (road, lake, building) in the tile containing the city centre.
fn sf_archive(tag: &str) -> (PathBuf, (u32, u32)) {
    let (x, y) = fixture::tile_of(37.7749, -122.4194, 14);
    let path = fixture::temp_path(tag);
    fixture::write_archive(&path, &[(14, x, y, fixture::rich_body(0))]);
    (path, (x, y))
}

fn sf_query() -> GeometryQuery {
    GeometryQuery {
        west: -122.43,
        south: 37.76,
        east: -122.41,
        north: 37.79,
        kinds_mask: KIND_ALL,
        max_features: 256,
    }
}

#[test]
fn query_reads_roads_water_and_buildings_from_a_real_archive() {
    let (path, _) = sf_archive("geometry-sf");
    let archive = open(&path);
    assert_eq!(archive.header.max_zoom, 14);
    let out = query_geometry(&archive, &sf_query());
    assert!(out.covered_tiles >= 1, "the SF tile was decoded");
    assert_eq!(out.failed_tiles, 0);
    let kinds: Vec<u32> = out.features.iter().map(|f| f.kind).collect();
    assert!(kinds.contains(&KIND_ROAD), "roads came back: {kinds:?}");
    assert!(kinds.contains(&KIND_WATER), "the lake came back: {kinds:?}");
    assert!(kinds.contains(&KIND_BUILDING), "buildings came back: {kinds:?}");
    for f in &out.features {
        for &(lon, lat) in &f.points {
            assert!((-123.0..-122.0).contains(&lon), "lon {lon}");
            assert!((37.0..38.5).contains(&lat), "lat {lat}");
        }
    }
    let _ = std::fs::remove_file(&path);
}

#[test]
fn query_over_empty_ground_is_covered_by_nothing_and_honest() {
    let (path, _) = sf_archive("geometry-empty");
    let archive = open(&path);
    // The Pacific, far from the one stored tile: the archive holds no
    // tile there, so nothing is covered and nothing is invented — and
    // that is not a failure.
    let out = query_geometry(
        &archive,
        &GeometryQuery { west: -150.0, south: 30.0, east: -149.0, north: 31.0, kinds_mask: KIND_ALL, max_features: 256 },
    );
    assert!(out.features.is_empty());
    assert_eq!(out.covered_tiles, 0);
    assert_eq!(out.failed_tiles, 0);
    assert!(out.total_tiles >= 1);
    let _ = std::fs::remove_file(&path);
}

#[test]
fn kind_mask_and_feature_cap_are_respected() {
    let (path, _) = sf_archive("geometry-caps");
    let archive = open(&path);
    let water_only = query_geometry(
        &archive,
        &GeometryQuery { kinds_mask: KIND_WATER, ..sf_query() },
    );
    assert!(!water_only.features.is_empty());
    assert!(water_only.features.iter().all(|f| f.kind == KIND_WATER));
    let capped = query_geometry(
        &archive,
        &GeometryQuery { max_features: 1, ..sf_query() },
    );
    assert_eq!(capped.features.len(), 1);
    assert!(capped.truncated);
    let _ = std::fs::remove_file(&path);
}
