//! Host timings for the work the map used to do on the Android main thread.
//!
//! Not a correctness test: ignored by default and run on demand to put numbers on the
//! render-thread move and on the hot paths it carries. Everything here is the CPU half of the
//! native renderer (the Vulkan half has no host build), timed on the host with the same code
//! the device runs. A phone core is roughly 2-4x slower than a desktop core, so read these as
//! lower bounds for the device.
//!
//! ```text
//! cargo test -p map_renderer --release --test frame_cpu_bench -- --ignored --nocapture --test-threads=1
//! MAMAPS_REAL_ARCHIVE=/path/to/world.mamaps cargo test ... (adds the archive half)
//! ```

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use map_renderer::camera::Camera;
use map_renderer::style::{self, KindFilter, LayerToggles};
use map_renderer::tile::geometry;
use map_renderer::tile::glyph::atlas::GlyphAtlas;
use map_renderer::tile::select;
use map_renderer::tile::source::FileRangeReader;
use map_renderer::tile::sprite::SpriteAtlas;
use tilecodec::mamaps::read::{LeafCache, MamapsArchive};

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1e3
}

fn camera(lon: f64, lat: f64, zoom: f64) -> Camera {
    Camera {
        center_lon: lon,
        center_lat: lat,
        zoom,
        // A Pixel-class portrait viewport.
        width_dp: 412.0,
        height_dp: 915.0,
        density: 2.625,
        bearing_deg: 0.0,
        pitch_deg: 0.0,
        pivot_dp: 0.0,
        globe: false,
        moon: false,
        time_seconds: 0.0,
    }
}

/// What `MapNative.create` computed on the calling thread before any Vulkan object existed:
/// the parsed style, the SDF glyph atlas and the decoded POI sprite sheet. All three are
/// process-wide lazies, so this is a first-surface cost (each process pays it once).
#[test]
#[ignore = "timing harness; run with --ignored --nocapture"]
fn create_time_cpu_work() {
    let t = Instant::now();
    let layers = style::layers().len();
    let style_ms = ms(t.elapsed());
    let t = Instant::now();
    let glyphs = GlyphAtlas::build();
    let atlas_ms = ms(t.elapsed());
    let t = Instant::now();
    let sprites = SpriteAtlas::build().expect("sprite sheet");
    let sprite_ms = ms(t.elapsed());
    std::hint::black_box((&glyphs, &sprites));
    println!(
        "create CPU work: style parse {style_ms:.1} ms ({layers} layers), glyph atlas {atlas_ms:.1} ms, \
         sprite decode {sprite_ms:.1} ms, total {:.1} ms",
        style_ms + atlas_ms + sprite_ms
    );
}

/// Per-frame tile selection (`select::visible` + `select::resident_set`), which `render` runs
/// on every frame while the camera moves.
#[test]
#[ignore = "timing harness; run with --ignored --nocapture"]
fn per_frame_selection() {
    for zoom in [4.0, 10.0, 14.0, 17.0] {
        let mut cam = camera(-122.4194, 37.7749, zoom);
        let frames = 2_000;
        let t = Instant::now();
        let mut n = 0usize;
        for i in 0..frames {
            // A pan: a little east every frame, as a drag does.
            cam.center_lon = -122.4194 + i as f64 * 1e-5 * 2f64.powf(14.0 - zoom);
            n += select::visible(&cam, 0, 15).len();
            n += select::resident_set(&cam, 0, 15).len();
        }
        println!(
            "selection z{zoom}: {:.3} ms/frame ({} tiles/frame)",
            ms(t.elapsed()) / frames as f64,
            n / frames
        );
    }
}

fn archive_path() -> Option<PathBuf> {
    match std::env::var("MAMAPS_REAL_ARCHIVE") {
        Ok(p) if Path::new(&p).is_file() => Some(PathBuf::from(p)),
        _ => {
            eprintln!("MAMAPS_REAL_ARCHIVE not set: archive half skipped");
            None
        }
    }
}

/// Worker-side cost of one viewport: archive read (memory-mapped, cold pages are random
/// reads) plus decode and tessellation per tile. Runs on the tile workers, never on the
/// render or main thread; reported so the frame numbers have their background counterpart.
#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE; timing harness"]
fn viewport_tile_build() {
    let Some(path) = archive_path() else { return };
    let archive = MamapsArchive::open_with_cache(
        FileRangeReader::open(&path).expect("open"),
        Arc::new(LeafCache::new()),
    )
    .expect("archive");
    let layers = style::layers();
    let toggles = LayerToggles { poi: true, transit: true, traffic: true, safety: true };
    for (name, lon, lat, zoom) in [("SF", -122.4194, 37.7749, 14.0), ("London", -0.1278, 51.5074, 11.0)] {
        let cam = camera(lon, lat, zoom);
        let tiles = select::visible(&cam, archive.header.min_zoom, archive.header.max_zoom);
        let (mut read, mut build, mut labels) = (Duration::ZERO, Duration::ZERO, 0usize);
        for tile in &tiles {
            let t = Instant::now();
            let Ok(Some(body)) = archive.tile(tile.z, tile.x, tile.y) else { continue };
            read += t.elapsed();
            let t = Instant::now();
            let mesh = geometry::build_toggled(
                &body, layers, tile.z, tile.x, tile.y, false, toggles, &KindFilter::all(), 1,
            );
            build += t.elapsed();
            labels += mesh.labels.len();
        }
        println!(
            "{name} z{zoom}: {} tiles, read {:.1} ms, decode+tessellate {:.1} ms ({:.1} ms/tile), {labels} labels",
            tiles.len(),
            ms(read),
            ms(build),
            ms(build) / tiles.len().max(1) as f64
        );
    }
}

/// The per-frame label bookkeeping in `record_symbol`: for every symbol layer, every tile's
/// whole label list is scanned for that layer's labels (once to count, once to emit), and each
/// of those pays a `candidate_id` + accept-set lookup before any glyph is emitted.
#[test]
#[ignore = "needs MAMAPS_REAL_ARCHIVE; timing harness"]
fn per_frame_label_scan() {
    use map_renderer::style::LayerKind;
    use map_renderer::tile::placement::candidate_id;
    use std::collections::HashMap;
    let Some(path) = archive_path() else { return };
    let archive = MamapsArchive::open_with_cache(
        FileRangeReader::open(&path).expect("open"),
        Arc::new(LeafCache::new()),
    )
    .expect("archive");
    let layers = style::layers();
    let symbol_layers: Vec<usize> =
        (0..layers.len()).filter(|&i| layers[i].kind == LayerKind::Symbol).collect();
    let toggles = LayerToggles { poi: true, transit: true, traffic: true, safety: true };
    for (name, lon, lat, zoom) in [("SF", -122.4194, 37.7749, 14.0), ("London", -0.1278, 51.5074, 11.0)] {
        let cam = camera(lon, lat, zoom);
        let tiles: Vec<_> = select::visible(&cam, archive.header.min_zoom, archive.header.max_zoom)
            .into_iter()
            .filter_map(|t| {
                let body = archive.tile(t.z, t.x, t.y).ok()??;
                Some(geometry::build_toggled(&body, layers, t.z, t.x, t.y, false, toggles, &KindFilter::all(), 1))
            })
            .collect();
        let total: usize = tiles.iter().map(|m| m.labels.len()).sum();
        // ~1 label in 20 survives placement at these zooms; the accept set is what is looked up.
        let mut accepted: HashMap<u64, (bool, u32)> = HashMap::new();
        for m in &tiles {
            for (i, l) in m.labels.iter().enumerate() {
                if i % 20 == 0 {
                    accepted.insert(candidate_id(m.z, m.x, m.y, l.layer_index, i), (false, 0));
                }
            }
        }
        let frames = 200;
        let t = Instant::now();
        let mut hits = 0usize;
        for _ in 0..frames {
            for &layer_index in &symbol_layers {
                for m in &tiles {
                    let count = m.labels.iter().filter(|l| l.layer_index == layer_index).count();
                    std::hint::black_box(count);
                    for (i, l) in m.labels.iter().enumerate().filter(|(_, l)| l.layer_index == layer_index) {
                        if accepted.contains_key(&candidate_id(m.z, m.x, m.y, layer_index, i)) {
                            hits += 1;
                        }
                        std::hint::black_box(l);
                    }
                }
            }
        }
        let scan_ms = ms(t.elapsed()) / frames as f64;
        // The same walk through the per-tile layer index the renderer now builds at upload,
        // against the accept-set as the renderer now keys it (ids pass straight through).
        let indexed: Vec<_> = tiles.iter().map(|m| geometry::labels_by_layer(&m.labels)).collect();
        let accepted: map_renderer::tile::placement::CandidateMap<(bool, u32)> =
            accepted.iter().map(|(k, v)| (*k, *v)).collect();
        let t = Instant::now();
        let mut hits_indexed = 0usize;
        for _ in 0..frames {
            for &layer_index in &symbol_layers {
                for (m, index) in tiles.iter().zip(&indexed) {
                    let own = index.get(&layer_index).map_or(&[][..], Vec::as_slice);
                    std::hint::black_box(own.len());
                    for &i in own {
                        let l = &m.labels[i as usize];
                        if accepted.contains_key(&candidate_id(m.z, m.x, m.y, layer_index, i as usize)) {
                            hits_indexed += 1;
                        }
                        std::hint::black_box(l);
                    }
                }
            }
        }
        let indexed_ms = ms(t.elapsed()) / frames as f64;
        assert_eq!(hits, hits_indexed, "the index must visit exactly the same labels");
        println!(
            "{name} z{zoom}: {total} labels x {} symbol layers: filter scan {scan_ms:.3} ms/frame, \
             layer index + id-keyed set {indexed_ms:.3} ms/frame ({} accepted)",
            symbol_layers.len(),
            hits / frames
        );
    }
}

/// What the tile workers' nice value does to a frame thread under a tile burst.
///
/// Eight busy "workers" (the native tile-worker count) and one frame thread that needs 4 ms of
/// CPU every 16.7 ms, squeezed onto two cores so they contend the way a pan's tile burst
/// contends a phone's cores. Run under `taskset -c 0-1`. Reports the frame thread's wall time
/// per frame with the workers at nice 0 (before) and nice 10 (after,
/// `bridge::priority::WORKER_NICE`).
#[test]
#[ignore = "scheduler harness; run under `taskset -c 0-1` with --ignored --nocapture"]
fn worker_priority_vs_frame_time() {
    use std::sync::atomic::{AtomicBool, Ordering};
    extern "C" {
        fn setpriority(which: i32, who: u32, prio: i32) -> i32;
    }
    fn spin(d: Duration) {
        let t = Instant::now();
        while t.elapsed() < d {
            std::hint::spin_loop();
        }
    }
    for nice in [0, 10] {
        let stop = Arc::new(AtomicBool::new(false));
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let stop = Arc::clone(&stop);
                std::thread::spawn(move || {
                    unsafe { setpriority(0, 0, nice) };
                    while !stop.load(Ordering::Relaxed) {
                        spin(Duration::from_millis(1));
                    }
                })
            })
            .collect();
        std::thread::sleep(Duration::from_millis(100));
        let mut walls = Vec::new();
        let period = Duration::from_micros(16_667);
        let start = Instant::now();
        for frame in 0..120u32 {
            let due = start + period * frame;
            if let Some(wait) = due.checked_duration_since(Instant::now()) {
                std::thread::sleep(wait);
            }
            let t = Instant::now();
            // 4 ms of frame work (recording, placement, emission).
            spin(Duration::from_millis(4));
            walls.push(t.elapsed());
        }
        stop.store(true, Ordering::Relaxed);
        for w in workers {
            w.join().unwrap();
        }
        walls.sort();
        let missed = walls.iter().filter(|w| **w > period).count();
        println!(
            "workers at nice {nice}: frame work p50 {:.1} ms, p95 {:.1} ms, max {:.1} ms, {missed}/120 over 16.7 ms",
            ms(walls[60]),
            ms(walls[114]),
            ms(walls[119])
        );
    }
}
