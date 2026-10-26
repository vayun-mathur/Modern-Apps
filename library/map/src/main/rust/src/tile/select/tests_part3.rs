//! P5.4 stale-tile cap tuning: measure the structural tile demand first.
//!
//! Split from `tests.rs` (file-length limit). The resident cap ([`RESIDENT_TILE_CAP`])
//! bounds the whole resident set, but what it must never do is evict a *visible*
//! tile: `retain` drops rank-3 (unrelated) tiles first, then the lowest-ranked
//! stand-ins past the limit, so the keep set that matters is the visible tiles plus
//! their ancestors (ranks 0–1). What the cap is really for is the stale stand-ins —
//! resident descendants from a deeper zoom (rank 2) sheltered for zoom-out smoothness
//! (P2) — so this pins the worst keep set over representative viewports, zooms,
//! tilts, rotations and the globe, with headroom left for those stand-ins.
//!
//! Viewports: a dense-city phone (411×891dp, the z14 case from the cap's sizing note),
//! a landscape tablet (1280×800dp), zooms 0–16 (past the archive top, i.e. overzoom),
//! pitch to the host's 65° max, bearing 45°, and the globe below its threshold.
//! Density is 1 throughout: coverage works in Dp, so density changes pixels, not
//! tile counts.
//!
//! The globe half needs care: `globe_visible` enumerates the whole hemisphere cap at
//! the *archive* zooms — thousands of tiles at z7 — but the fetch loop never asks for
//! them, because `has_tile`/absent/retry/in-flight gate every send and the workers
//! drain a bounded upload budget per frame. The globe keep set is aspirational in a
//! way the flat one is not, so it is measured separately (and pinned loosely): the
//! tuning bound below covers the flat path, where every wanted tile is actually
//! fetched and resident.
use super::*;
use crate::camera::Camera;

fn camera(lon: f64, lat: f64, zoom: f64, w: f32, h: f32) -> Camera {
    Camera {
        center_lon: lon,
        center_lat: lat,
        zoom,
        width_dp: w,
        height_dp: h,
        density: 1.0,
        bearing_deg: 0.0,
        pitch_deg: 0.0,
        time_seconds: 0.0,
        globe: false,
        moon: false,
    }
}

#[test]
fn resident_demand_fits_the_cap_with_headroom() {
    let mut worst = (0usize, String::new());
    let mut worst_visible = (0usize, String::new());
    let mut worst_globe = (0usize, String::new());
    // The unfogged worst: pitch 0 only. Fogged far tiles (see `FOG_FAR_VIEWPORTS`)
    // contribute haze, not detail, so they are measured above but budgeted
    // separately — the cap shelters the unfogged set plus descendant stand-ins.
    let mut worst_flat = (0usize, String::new());
    for &(w, h, name) in &[(411.0f32, 891.0, "phone"), (1280.0, 800.0, "tablet")] {
        for zoom in 0..=16u8 {
            // Pitch to the host's 65° max (`MAX_PITCH` in `CameraState.kt`, clamped
            // again at the JNI boundary): the far trapezoid is what drives the worst
            // case, so the measurement must include the steepest supported tilt.
            // Tracked separately: fogged far tiles vs the unfogged set the cap
            // shelters (see the bound below).
            for pitch in [0.0, 45.0, 65.0] {
                for bearing in [0.0, 45.0] {
                    // Flat and globe separately: the globe path engages below its own
                    // threshold, and the flat path must be measured past it too (a
                    // host with the globe off zooms through the same levels flat).
                    for globe in [false, true] {
                        let c = Camera {
                            pitch_deg: pitch,
                            bearing_deg: bearing,
                            globe,
                            ..camera(-122.4194, 37.7749, zoom as f64, w, h)
                        };
                        // Skip the incoherent corner: globe flag on but past the
                        // threshold draws flat, so it would double-count the flat
                        // case above rather than measure the globe path.
                        if globe && !crate::camera::globe_active(&c) {
                            continue;
                        }
                        let v = visible(&c, 0, 15).len();
                        let r = resident_set(&c, 0, 15).len();
                        let ctx = format!("{name} z{zoom} pitch{pitch} bearing{bearing}");
                        if globe {
                            if r > worst_globe.0 {
                                worst_globe = (r, ctx);
                            }
                            continue;
                        }
                        if v > worst_visible.0 {
                            worst_visible = (v, ctx.clone());
                        }
                        if r > worst.0 {
                            worst = (r, ctx.clone());
                        }
                        if pitch == 0.0 && r > worst_flat.0 {
                            worst_flat = (r, ctx);
                        }
                    }
                }
            }
        }
    }
    // The measurement, printed for the tuning note below. Run with `--nocapture` to read it.
    println!("worst visible: {} ({})", worst_visible.0, worst_visible.1);
    println!("worst flat keep set: {} ({})", worst.0, worst.1);
    println!(
        "worst unfogged keep set: {} ({})",
        worst_flat.0, worst_flat.1
    );
    println!("worst globe keep set: {} ({})", worst_globe.0, worst_globe.1);
    // Flat-path bound. A keep set at the cap would evict visible tiles (rank is no
    // defence past the limit — see `retain`), so this must hold with headroom for
    // descendant stand-ins behind it.
    //
    // Measured worst: 162 (tablet z8, 65° pitch, 45° bearing) — the far trapezoid at
    // full tilt, where tiles past ~6 viewport heights are fogged to the land colour
    // (`FOG_FAR_VIEWPORTS`) and contribute haze rather than detail. That is the case
    // for tightening the fetch (a pitch-aware far cap in `coverage`, matching the
    // existing `FAR_REACH_VIEWPORTS` shape), not for raising the residency cap:
    // raising it keeps more fogged tiles resident at single-digit-MB each, while the
    // unfogged path — every pitch-0 viewport, the case the P2 metrics actually
    // cover — peaks far lower (see the pitch-0 print above).
    //
    // So the bound pins today's demand (200, the measured 162 with room for viewport
    // growth), and the cap stays 64: the keep set that must never be evicted is the
    // unfogged one, and 64 shelters it plus a full screenful of descendant
    // stand-ins. If device logs (`stale-dropped`, resident counts) ever show tilt
    // frames churning fetches, the fix is the far-fetch cap, recorded here rather
    // than guessed at now.
    assert!(
        worst.0 <= 200,
        "keep set {} ({}) exceeds the measured budget — see the tuning note",
        worst.0,
        worst.1,
    );
    // The cap shelters the unfogged set: every pitch-0 viewport plus ancestors must
    // fit with room for a full screenful of descendant stand-ins (rank 2) behind it.
    // A keep set at the cap would evict visible tiles (rank is no defence past the
    // limit — see `retain`), so this is the load-bearing half of the pin.
    assert!(
        worst_flat.0 + 24 <= RESIDENT_TILE_CAP,
        "unfogged keep set {} ({}) leaves no room for descendant stand-ins under the {} cap",
        worst_flat.0,
        worst_flat.1,
        RESIDENT_TILE_CAP,
    );
    // The globe enumeration is aspirational (see above): pinned two orders of
    // magnitude above the flat bound so a coverage change that doubles it still
    // passes, while a runaway (whole-pyramid enumeration) fails loudly.
    assert!(
        worst_globe.0 <= 200_000,
        "globe keep set {} ({}) looks like whole-pyramid enumeration",
        worst_globe.0,
        worst_globe.1,
    );
}

const MIB: u64 = 1024 * 1024;

#[test]
fn eviction_drops_unrelated_and_never_visible() {
    let mut ranked = vec![(0, 1, 500 * MIB), (0, 2, 500 * MIB), (3, 9, 1)];
    assert_eq!(evict(&mut ranked, 1, 0), vec![9]);
}

#[test]
fn eviction_spends_the_byte_budget_on_descendants_last() {
    // Visible + ancestors already fill the budget: descendants go, ancestors stay.
    let mut ranked = vec![
        (2, 30, 8 * MIB),
        (1, 20, 4 * MIB),
        (0, 10, 40 * MIB),
        (2, 31, 1 * MIB),
    ];
    let doomed = evict(&mut ranked, 64, 48 * MIB);
    assert_eq!(doomed, vec![30]);
}

#[test]
fn eviction_counts_the_cap_over_kept_tiles_only() {
    let mut ranked = vec![(0, 1, 0), (2, 2, 100 * MIB), (2, 3, 0), (1, 4, 0)];
    // Tile 2 is over the byte budget; it must not use up a cap slot that tile 3 needs.
    assert_eq!(evict(&mut ranked, 3, 50 * MIB), vec![2]);
}
