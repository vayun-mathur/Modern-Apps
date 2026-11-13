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
