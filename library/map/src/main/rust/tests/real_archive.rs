//! The renderer's reader against a REAL planet archive (`planet4.mamaps` / `planet5.mamaps`, or the
//! byte-identical local build `scripts/maps/inputs/world.mamaps`).
//!
//! Ignored by default and gated on `MAMAPS_REAL_ARCHIVE`, so a normal `cargo test` never
//! needs the 80 GB input:
//!
//! ```text
//! MAMAPS_REAL_ARCHIVE=/path/to/world.mamaps \
//!   cargo test -p map_renderer --test real_archive -- --ignored --nocapture --test-threads=1
//! ```
//!
//! Everything goes through the code the app runs: `FileRangeReader` (the device's
//! `TileSource.LocalFile` path), tilecodec's `MamapsArchive` (the shared decoder the
//! writer tools also use), the app's style table, `geometry::build_toggled` (the worker's
//! tessellation entry point) and `source::discover` (overlay segments beside the base).
//! Graph/transit (the `offlinerouter` crate) and POI (Kotlin) have their own real-archive
//! tests; here the section directory is checked so every kind they read is present.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use map_renderer::style::{self, KindFilter, LayerToggles};
use map_renderer::tile::geometry;
use map_renderer::tile::source::{discover, FileRangeReader};
use tilecodec::mamaps::archive::{self as sidecar, ArchiveView};
use tilecodec::mamaps::body::Body;
use tilecodec::mamaps::header::Header;
use tilecodec::mamaps::read::{LeafCache, MamapsArchive, TileSource};

const ENV: &str = "MAMAPS_REAL_ARCHIVE";

// planet4 claims FLAG_RINGS_VALIDATED without earning it; the renderer must keep repairing.
const _: () = assert!(!geometry::TRUST_RINGS_VALIDATED);

/// Cities the task names, plus a mountain for the DEM.
const CITIES: &[(&str, f64, f64)] = &[
    ("SF", 37.7749, -122.4194),
    ("NYC", 40.7580, -73.9855),
    ("London", 51.5074, -0.1278),
    ("Tokyo", 35.6812, 139.7671),
];
const ZOOMS: &[u8] = &[0, 4, 8, 10, 12, 13, 14];

fn archive_path() -> Option<PathBuf> {
    match std::env::var(ENV) {
        Ok(p) if !p.trim().is_empty() && Path::new(&p).is_file() => Some(PathBuf::from(p)),
        _ => {
            eprintln!("{ENV} not set (or not a file): real-archive test skipped");
            None
        }
    }
}

fn open(path: &Path) -> MamapsArchive<FileRangeReader> {
    MamapsArchive::open_with_cache(
        FileRangeReader::open(path).expect("FileRangeReader::open"),
        Arc::new(LeafCache::new()),
    )
    .expect("the app's reader refused the archive")
}

/// Slippy-map tile containing (lat, lon) at zoom `z`.
fn tile_of(lat: f64, lon: f64, z: u8) -> (u32, u32) {
    let n = (1u64 << z) as f64;
    let x = ((lon + 180.0) / 360.0 * n).floor();
    let lat_r = lat.to_radians();
    let y = ((1.0 - (lat_r.tan() + 1.0 / lat_r.cos()).ln() / std::f64::consts::PI) / 2.0 * n).floor();
    let max = n as u32 - 1;
    ((x as u32).min(max), (y as u32).min(max))
}

fn all_on() -> LayerToggles {
    LayerToggles { poi: true, transit: true, traffic: true, safety: true }
}

fn layer_name(id: u8) -> &'static str {
    tilecodec::mamaps::dict::LAYERS.get(id as usize).copied().unwrap_or("?")
}

#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE (80 GB planet archive)"]
fn header_version_and_dictionary_are_accepted() {
    let Some(path) = archive_path() else { return };
    let mut head = vec![0u8; tilecodec::mamaps::header::HEADER_LEN];
    {
        use std::io::Read as _;
        std::fs::File::open(&path).unwrap().read_exact(&mut head).unwrap();
    }
    let header = Header::parse(&head).expect("header parse (version gate)");
    println!(
        "header: version byte {} flags {:#06x} compression {} layers {} z{}-{} build {:#018x} len {}",
        head[7], header.flags, header.compression, header.layer_count, header.min_zoom,
        header.max_zoom, header.build_id, header.file_len
    );
    // planet4 is a v9 archive; planet5 a v10 one (dictionary-zstd bodies). Both must open.
    match head[7] {
        tilecodec::mamaps::header::FORMAT_VERSION_V9 => {}
        tilecodec::mamaps::header::FORMAT_VERSION_HEADER_V10 => {
            assert_eq!(header.compression, tilecodec::mamaps::header::COMPRESSION_ZSTD_DICT);
        }
        v => panic!("planet4 is v9 and planet5 v10, got v{v}"),
    }
    assert_eq!(header.file_len, std::fs::metadata(&path).unwrap().len());
    assert!(header.compressed() && header.rings_validated() && header.leaves_compressed());
    // The header claims validated rings, but the renderer does not trust it (see
    // geometry::TRUST_RINGS_VALIDATED, pinned off below); this test runs the same policy.
    assert_eq!(header.min_zoom, 0);
    assert_eq!(header.max_zoom, 14);

    let archive = open(&path);
    // `MamapsArchive::open` already validated the dictionary against the compiled-in
    // schema; pin the layer list the style is written against.
    assert_eq!(
        archive.dictionary.layers,
        [
            "landtype", "roads", "boundaries", "buildings", "places", "poi", "transit",
            "traffic", "junction", "safety",
        ]
    );
    assert_eq!(header.layer_count as usize, archive.dictionary.layers.len());
    println!("dictionary: {} layers, {} kinds, {} details",
        archive.dictionary.layers.len(), archive.dictionary.kinds.len(), archive.dictionary.details.len());
}

#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE (80 GB planet archive)"]
fn section_directory_carries_every_kind_the_app_reads() {
    let Some(path) = archive_path() else { return };
    let file = std::fs::File::open(&path).unwrap();
    // SAFETY: read-only mapping of a file nobody writes while the test runs.
    let bytes = unsafe { memmap2::Mmap::map(&file) }.expect("mmap");
    let header = Header::parse(&bytes[..tilecodec::mamaps::header::HEADER_LEN]).unwrap();
    let view = ArchiveView::parse(&bytes, &header).expect("ArchiveView::parse (container checks)");
    let required = [
        (sidecar::ARCHIVE_KIND_GRAPH_META, "graph meta"),
        (sidecar::ARCHIVE_KIND_GRAPH_NODES, "graph nodes"),
        (sidecar::ARCHIVE_KIND_GRAPH_EDGES, "graph edges"),
        (sidecar::ARCHIVE_KIND_GRAPH_INTERMEDIATE, "graph intermediate"),
        (sidecar::ARCHIVE_KIND_GRAPH_NAMES, "road names"),
        (sidecar::ARCHIVE_KIND_GRAPH_LANES, "lanes"),
        (sidecar::ARCHIVE_KIND_POI_INDEX, "poi index"),
        (sidecar::ARCHIVE_KIND_POI_NAMES, "poi names"),
        (sidecar::ARCHIVE_KIND_POI_ATTRS, "poi attrs"),
        (sidecar::ARCHIVE_KIND_POI_SPATIAL, "poi spatial"),
        (sidecar::ARCHIVE_KIND_POI_WORDS, "poi words"),
        (sidecar::ARCHIVE_KIND_TRANSIT, "transit"),
        (sidecar::ARCHIVE_KIND_GRAPH_OVERLAY, "highway overlay"),
        (sidecar::ARCHIVE_KIND_POI_REGION, "poi region"),
    ];
    for (kind, name) in required {
        let (off, len) = view.location(kind).unwrap_or_else(|| panic!("kind {kind} ({name}) missing"));
        let s = view.section(&bytes, kind).unwrap();
        println!("kind {kind:2} {name:<18} @{off} len {len} magic {:02x?}", &s[..4.min(s.len())]);
    }
    // Optional, and absent from planet4 (road_graph ran without --dem): the router reads
    // a missing elevation section as "no profile".
    println!("kind  7 elevation present: {}", view.location(sidecar::ARCHIVE_KIND_GRAPH_ELEVATION).is_some());
}

#[derive(Default)]
struct LayerTally {
    tiles: usize,
    features: usize,
}

#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE (80 GB planet archive)"]
fn every_layer_decodes_and_tessellates_in_four_cities() {
    let Some(path) = archive_path() else { return };
    let archive = open(&path);
    let layers = style::layers();
    let rings_validated = archive.header.rings_validated() && geometry::TRUST_RINGS_VALIDATED;
    let mut tally: BTreeMap<&'static str, LayerTally> = BTreeMap::new();
    let mut versions: BTreeMap<u8, usize> = BTreeMap::new();
    let (mut tiles, mut meshes, mut labels, mut buildings, mut carriageways, mut traffic, mut regions) =
        (0, 0, 0, 0, 0, 0, 0);
    let mut extra: BTreeMap<&'static str, usize> = BTreeMap::new();
    for &(city, lat, lon) in CITIES {
        for &z in ZOOMS {
            let (x, y) = tile_of(lat, lon, z);
            let raw = archive.tile_bytes(z, x, y).expect("tile_bytes");
            let body: Body = archive
                .tile(z, x, y)
                .unwrap_or_else(|e| panic!("{city} {z}/{x}/{y}: decode failed: {e}"))
                .unwrap_or_else(|| panic!("{city} {z}/{x}/{y}: absent"));
            // Byte 3 of the stored body header is the body version (v10 = narrowed records).
            if let Some(raw) = raw {
                *versions.entry(raw[3]).or_default() += 1;
            }
            tiles += 1;
            let mut line = format!("{city:<6} {z:2}/{x}/{y}:");
            for layer in &body.layers {
                let t = tally.entry(layer_name(layer.layer_id)).or_default();
                t.tiles += 1;
                t.features += layer.features.len();
                line += &format!(" {}={}", layer_name(layer.layer_id), layer.features.len());
            }
            for (name, present) in [
                ("names", !body.names.is_empty()),
                ("ids", !body.ids.is_empty()),
                ("turn_lanes", !body.turn_lanes.is_empty()),
                ("buildings_table", !body.buildings.is_empty()),
                ("heightmap", body.heightmap.is_some()),
                ("carriageways", !body.carriageways.is_empty()),
                ("convention", body.convention.is_some()),
                ("region_links", !body.region_links.is_empty()),
            ] {
                if present {
                    *extra.entry(name).or_default() += 1;
                }
            }
            let mesh = geometry::build_toggled(
                &body, layers, z, x, y, rings_validated, all_on(), &KindFilter::all(), 1,
            );
            assert_eq!((mesh.z, mesh.x, mesh.y), (z, x, y));
            meshes += mesh.meshes.iter().filter(|m| !m.indices.is_empty()).count();
            labels += mesh.labels.len();
            buildings += usize::from(!mesh.buildings.indices.is_empty());
            carriageways += mesh.carriageways.len();
            traffic += mesh.traffic.len();
            regions += mesh.regions.len();
            assert!(
                mesh.meshes.iter().any(|m| !m.indices.is_empty()),
                "{city} {z}/{x}/{y}: nothing tessellated"
            );
            println!("{line} | meshes {} labels {}", mesh.meshes.len(), mesh.labels.len());
        }
    }
    println!("body versions: {versions:?}");
    println!("optional body sections (tiles carrying): {extra:?}");
    println!(
        "tiles {tiles} non-empty meshes {meshes} labels {labels} building tiles {buildings} carriageways {carriageways} traffic {traffic} regions {regions}"
    );
    for (name, t) in &tally {
        println!("layer {name:<10} tiles {:3} features {}", t.tiles, t.features);
    }
    for name in tilecodec::mamaps::dict::LAYERS {
        if *name == "traffic" {
            // The writer stopped emitting `traffic` features (mamaps_build extract_part3.rs:
            // "dropped as of the NA diet"); the layer stays in the dictionary with zero
            // features. Pinned so a rebuild that brings it back is noticed.
            assert!(!tally.contains_key(name), "traffic layer is back: wire up and test it");
            continue;
        }
        assert!(
            tally.get(name).is_some_and(|t| t.features > 0),
            "layer {name} never appeared in {tiles} city tiles"
        );
    }
    assert!(buildings > 0, "no 3D buildings tessellated");
    // Lane rendering is switched off in the style (style::LANE_RENDERING, z16+ overzoom), so
    // carriageway meshes stay empty by design; the tables themselves must still decode.
    assert!(extra.contains_key("carriageways"), "no carriageway tables decoded");
    assert!(extra.contains_key("turn_lanes"), "no turn-lane tables decoded");
    assert_eq!(traffic, 0);
    assert!(labels > 0, "no labels shaped");
    assert!(regions > 0, "no region-mask shapes (boundaries)");
    assert!(extra.contains_key("heightmap"), "no heightmap on any city tile");
    assert!(extra.contains_key("region_links"), "no region-link tables decoded");
    // planet4 carries v10 bodies, planet5 v11 (MED-residual heightmaps); never a mix.
    assert!(
        versions.len() == 1 && versions.keys().all(|&v| v == 10 || v == 11),
        "expected v10 (planet4) or v11 (planet5) bodies only, got {versions:?}"
    );
}

#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE (80 GB planet archive)"]
fn dem_heightmaps_sample_and_drape() {
    let Some(path) = archive_path() else { return };
    let archive = open(&path);
    let layers = style::layers();
    // Mount Fuji (3776 m) and the SF hills: relief must come through with real metres.
    for &(name, lat, lon, z, min_peak) in &[
        ("Fuji", 35.3606, 138.7274, 12u8, 2500i32),
        ("Fuji", 35.3606, 138.7274, 10u8, 2000i32),
        ("SF Twin Peaks", 37.7544, -122.4477, 12u8, 150i32),
        ("Denver flat", 39.7392, -104.9903, 12u8, 1400i32),
    ] {
        let (x, y) = tile_of(lat, lon, z);
        let body = archive.tile(z, x, y).unwrap().expect("tile present");
        let hm = body.heightmap.as_ref().unwrap_or_else(|| panic!("{name} {z}/{x}/{y}: no heightmap"));
        assert_eq!(hm.samples.len(), hm.dim as usize * hm.dim as usize);
        let metres: Vec<i32> = hm.samples.iter().map(|&s| tilecodec::mamaps::body::Heightmap::metres(s)).collect();
        let (lo, hi) = (*metres.iter().min().unwrap(), *metres.iter().max().unwrap());
        let mid = hm.sample(hm.dim / 2, hm.dim / 2).map(tilecodec::mamaps::body::Heightmap::metres);
        println!("{name:<14} {z}/{x}/{y} dim {} min {lo} m max {hi} m centre {mid:?}", hm.dim);
        assert!(hi >= min_peak, "{name}: max {hi} m below {min_peak} m");
        assert!(lo > -500 && hi < 9000, "{name}: implausible range {lo}..{hi}");
        let mesh = geometry::build(&body, layers, z, x, y, archive.header.rings_validated() && geometry::TRUST_RINGS_VALIDATED);
        assert!(!mesh.terrain.indices.is_empty(), "{name}: heightmap present but no terrain mesh");
    }
}

#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE (80 GB planet archive)"]
fn an_overlay_segment_is_discovered_and_served_patch_first() {
    use tilecodec::mamaps::write::{Options, StreamWriter};
    let Some(path) = archive_path() else { return };
    let base = open(&path);
    let build = base.header.build_id;
    let len = base.header.file_len;

    // Patch one SF z14 tile (drop its buildings layer, so the patch is visible) and
    // tombstone one London z14 tile; everything else must still come from the base.
    let (px, py) = tile_of(37.7749, -122.4194, 14);
    let (tx, ty) = tile_of(51.5074, -0.1278, 14);
    let (ux, uy) = tile_of(40.7580, -73.9855, 14);
    let mut patched = base.tile(14, px, py).unwrap().expect("SF z14");
    let before = patched.layers.len();
    patched.layers.retain(|l| layer_name(l.layer_id) != "buildings");
    patched.buildings.clear();
    assert!(patched.layers.len() < before, "SF z14 had no buildings layer to drop");

    let dir = std::env::temp_dir().join(format!("real-archive-overlay-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("overlays/0001")).unwrap();
    let base_link = dir.join("basemap.mamaps");
    std::os::unix::fs::symlink(&path, &base_link).unwrap();
    let mut writer = StreamWriter::new(Options {
        build_id: 0xabcd,
        rings_validated: true,
        ..Options::default()
    })
    .unwrap();
    writer.append(tilecodec::pmtiles::tile_id(14, px as u64, py as u64), &patched).unwrap();
    let seg = writer.finish().unwrap();
    std::fs::write(dir.join("overlays/0001/overlay-0000.mamaps"), &seg).unwrap();
    let manifest = format!(
        r#"{{"version":1,"base":{{"url":"https://data.maproj.org/planet4.mamaps","build_id":"{build:#018x}","len":{len}}},"overlay_seq":1,"overlay_build_id":"0xabcd","segments":[{{"url":"https://data.maproj.org/overlays/0001/overlay-0000.mamaps","len":{},"sha256":"x","seq":0}}],"sections":[],"tombstones":[{}],"min_client":1}}"#,
        seg.len(),
        tilecodec::pmtiles::tile_id(14, tx as u64, ty as u64),
    );
    std::fs::write(dir.join("overlays/manifest.json"), manifest).unwrap();

    let found = discover(&base_link, Arc::new(LeafCache::new())).expect("discover refused a valid overlay over the real base");
    assert_eq!(found.seq, 1);
    let overlay = found.overlay;
    assert_eq!(overlay.header().build_id, build, "the overlay keeps the base identity");
    let served = overlay.tile(14, px, py).unwrap().expect("patched tile");
    assert!(served.layers.iter().all(|l| layer_name(l.layer_id) != "buildings"), "segment not served first");
    assert!(overlay.tile(14, tx, ty).unwrap().is_none(), "tombstoned tile still served");
    let untouched = overlay.tile(14, ux, uy).unwrap().expect("base tile");
    assert_eq!(untouched, base.tile(14, ux, uy).unwrap().unwrap(), "base tile changed under the overlay");
    let batch = overlay.tiles(&[(14, px, py), (14, tx, ty), (14, ux, uy)]);
    assert!(batch[0].as_ref().unwrap().is_some() && batch[1].as_ref().unwrap().is_none());
    println!("overlay: seq 1 segment {} B, patch-first + tombstone + base fall-through OK", seg.len());

    // A manifest pinned to another base (planet3) must be refused: base alone.
    let stale = std::fs::read_to_string(dir.join("overlays/manifest.json"))
        .unwrap()
        .replace(&format!("{build:#018x}"), "0x0a90e5e4ab91159e");
    std::fs::write(dir.join("overlays/manifest.json"), stale).unwrap();
    assert!(discover(&base_link, Arc::new(LeafCache::new())).is_none(), "an overlay for another base was accepted");
    let _ = std::fs::remove_dir_all(&dir);
}

/// A [`RangeFetcher`] over the `curl` binary, standing in for the device's
/// `JniRangeFetcher` (`:library:network`) on the host. Honours `https_proxy`.
struct CurlFetcher;

impl map_renderer::tile::source::RangeFetcher for CurlFetcher {
    fn fetch(
        &self,
        url: &str,
        range: &str,
    ) -> tilecodec::proto::Result<map_renderer::tile::source::RangeResponse> {
        let bytes = range.strip_prefix("bytes=").unwrap_or(range);
        let out = std::env::temp_dir().join(format!("curl-range-{}-{bytes}", std::process::id()));
        let status = std::process::Command::new("curl")
            .args(["-s", "-m", "60", "-r", bytes, "-o"])
            .arg(&out)
            .args(["-w", "%{http_code}", url])
            .output()
            .map_err(|e| tilecodec::proto::Error(format!("curl: {e}")))?;
        let code: u16 = String::from_utf8_lossy(&status.stdout).trim().parse().unwrap_or(0);
        let body = std::fs::read(&out).unwrap_or_default();
        let _ = std::fs::remove_file(&out);
        Ok(map_renderer::tile::source::RangeResponse { status: code, body })
    }
}

/// The LIVE planet4 through the renderer's HTTP path: the 128-byte header range the workers
/// fetch first, the origin marker it keys the disk cache on, then a cold tile read through
/// `CachingRangeReader` + `RangeCache` + the shared decoder — compared byte-for-byte with the
/// local copy when `MAMAPS_REAL_ARCHIVE` is set too. Gated on `MAMAPS_LIVE=1` (network).
#[test]
#[ignore = "needs MAMAPS_LIVE=1 and network to data.maproj.org"]
fn live_planet4_reads_through_the_range_cache() {
    use map_renderer::tile::cache::{RangeCache, DEFAULT_MAX_BYTES};
    use map_renderer::tile::source::{
        basemap_origin, CachingRangeReader, RangeFetcher, BASEMAP_ARCHIVE_URL,
    };
    if std::env::var("MAMAPS_LIVE").as_deref() != Ok("1") {
        eprintln!("MAMAPS_LIVE not set: live test skipped");
        return;
    }
    assert_eq!(BASEMAP_ARCHIVE_URL, "https://data.maproj.org/planet4.mamaps");
    let head = CurlFetcher.fetch(BASEMAP_ARCHIVE_URL, "bytes=0-127").expect("header fetch");
    assert_eq!(head.status, 206, "the CDN must answer ranges");
    assert_eq!(head.body.len(), tilecodec::mamaps::header::HEADER_LEN);
    let header = Header::parse(&head.body).expect("live header parses");
    println!(
        "live: build {:#018x} len {} version {} layers {}",
        header.build_id, header.file_len, head.body[7], header.layer_count
    );
    assert_eq!(header.build_id, 0x2ab5_ba58_33e5_4438);
    assert_eq!(header.file_len, 81_331_811_296);

    let dir = std::env::temp_dir().join(format!("live-range-cache-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let origin = basemap_origin(BASEMAP_ARCHIVE_URL, header.build_id);
    let cache = RangeCache::open(&dir, &origin, DEFAULT_MAX_BYTES);
    let reader = CachingRangeReader::new(BASEMAP_ARCHIVE_URL, cache, CurlFetcher);
    let live = MamapsArchive::open_with_cache(reader, Arc::new(LeafCache::new())).expect("live open");
    assert_eq!(live.header, header);
    let (x, y) = tile_of(37.7749, -122.4194, 14);
    let body = live.tile(14, x, y).expect("live tile").expect("SF z14 present");
    let cached = std::fs::read_dir(&dir).unwrap().flatten().filter(|e| {
        e.path().extension().is_some_and(|x| x == "data")
    }).count();
    println!("live: SF z14 has {} layers; {cached} ranges cached under {origin}", body.layers.len());
    assert!(cached > 0, "nothing landed in the range cache");
    assert_eq!(std::fs::read_to_string(dir.join(".origin")).unwrap().trim(), origin);
    if let Some(path) = archive_path() {
        let local = open(&path);
        assert_eq!(local.header, header, "local world.mamaps is not the published planet4");
        assert_eq!(local.tile(14, x, y).unwrap().unwrap(), body, "live and local bodies differ");
        println!("live: header and SF z14 body identical to the local archive");
    }
    let _ = std::fs::remove_dir_all(&dir);
}
