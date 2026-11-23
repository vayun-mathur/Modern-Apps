use super::resident::ANCESTOR_DEPTH;

use super::tile_id::TileId;

/// How long a finer LOD tile takes to cross-fade in over its coarse ancestor, in seconds (WS-D).
///
/// Short enough to read as an anti-pop rather than an animation; measured against the shared
/// clock (`Camera::time_seconds` / `Push.misc.w`) from the tile's `uploaded_at` stamp.
pub const LOD_FADE_SECONDS: f32 = 0.3;

/// The per-tile opacity of a tile `now - uploaded_at` seconds after its GPU buffers landed: 0 at
/// upload, ramping linearly to 1 over `duration` (WS-D LOD cross-fade). Both times share the
/// `Camera::time_seconds` epoch. A non-positive `duration` disables the fade (opaque at once).
///
/// The elapsed time is taken **modulo [`CLOCK_WRAP_SECONDS`]** like
/// [`fade_in_progress`]: the shared clock wraps hourly, and a plain subtraction across a wrap
/// is wrong by an entire period — a tile stamped at 3599.9 against a clock just wrapped to 0.1
/// would read as −3599.8 s, clamp to 0, and sit transparent for the remaining hour whenever a
/// coarse ancestor is resident. The wrap is exact here (not an approximation): both stamps
/// come from the same bounded clock.
pub fn lod_fade_alpha(now: f32, uploaded_at: f32, duration: f32) -> f32 {
    if duration <= 0.0 {
        return 1.0;
    }
    ((now - uploaded_at).rem_euclid(crate::camera::CLOCK_WRAP_SECONDS) / duration).clamp(0.0, 1.0)
}

/// Whether a tile stamped `uploaded_at` is still inside its cross-fade, and so would draw
/// differently on the next frame even from an identical camera.
///
/// This is what stops the on-demand frame loop idling mid-fade and freezing a tile at
/// half opacity. It is deliberately **not** `lod_fade_alpha(..) < 1.0`: the fade is gated on
/// a resident ancestor (see [`tile_lod_alpha`]) and this is not, so it answers "is this tile
/// young enough that the fade *could* be running" rather than "is it running". Erring that
/// way costs a few frames on a tile that was never going to fade; erring the other way stops
/// the clock on one that was.
///
/// The elapsed time is taken **modulo [`CLOCK_WRAP_SECONDS`]**, because the shared clock wraps
/// hourly (see [`Camera::time_seconds`](crate::camera::Camera::time_seconds)). A plain
/// subtraction is not merely imprecise across a wrap, it is wrong in the worst direction: a
/// tile stamped at 3599.5 against a clock that has just wrapped to 0.0 gives -3599.5, which is
/// below any sane `duration`, so the window would read as open and pin the frame loop awake
/// for the remaining hour — the exact thing on-demand rendering exists to prevent.
pub fn fade_in_progress(now: f32, uploaded_at: f32, duration: f32) -> bool {
    if duration <= 0.0 {
        return false;
    }
    (now - uploaded_at).rem_euclid(crate::camera::CLOCK_WRAP_SECONDS) < duration
}

/// Whether a coarser ancestor of `key` (up to [`ANCESTOR_DEPTH`] levels up) is currently
/// resident, and so is drawn underneath as an opaque stand-in.
///
/// This gates the fade: a finer tile may fade in only when there is an ancestor to show through
/// the gap. A tile with no resident ancestor draws fully opaque immediately, so a freshly
/// fetched area never fades up from the background.
pub fn has_resident_ancestor(key: u64, resident: &std::collections::HashSet<u64>) -> bool {
    let tile = TileId::from_key(key);
    (1..=ANCESTOR_DEPTH).any(|levels| {
        tile.ancestor(levels)
            .is_some_and(|a| resident.contains(&a.key()))
    })
}

/// The per-tile LOD cross-fade opacity the renderer writes into `Push.morph.x` for tile `key`.
///
/// Ramps 0→1 over `duration` from `uploaded_at` when a coarse ancestor is resident to stand in
/// under the gap; otherwise fully opaque, so a tile with nothing beneath it never fades up from
/// the background. `resident` is the set of currently resident tile keys.
pub fn tile_lod_alpha(
    key: u64,
    uploaded_at: f32,
    now: f32,
    duration: f32,
    resident: &std::collections::HashSet<u64>,
) -> f32 {
    tile_lod_alpha_with(key, uploaded_at, now, duration, |ancestor| resident.contains(&ancestor))
}

/// Order-independent fingerprint of `(tile key, upload-stamp bits)` pairs:
/// splitmix64 per pair, summed. Two frames with the same resident set in any
/// iteration order fingerprint identically; any tile arriving, leaving, or
/// being re-stamped changes it with overwhelming probability. The symbol
/// placement key uses this instead of a `Vec` of pairs, so building or
/// cloning the key allocates nothing.
pub fn tiles_fingerprint(tiles: impl Iterator<Item = (u64, u32)>) -> u64 {
    tiles.fold(0u64, |acc, (key, stamp)| {
        let mut h = key ^ u64::from(stamp).wrapping_mul(0x9e37_79b9_7f4a_7c15);
        h = (h ^ (h >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
        h = (h ^ (h >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
        acc.wrapping_add(h ^ (h >> 31))
    })
}

/// Whether a covered-stand-in suppression refresh can be skipped outright:
/// the resident set is the one the current map was computed against and no
/// fade was in flight then. Settled-ness is monotonic in the frame clock, so
/// a fully-settled set with unchanged residency stays fully settled.
pub fn suppression_can_skip(residency: u64, computed_residency: u64, had_unsettled: bool) -> bool {
    residency == computed_residency && !had_unsettled
}

/// [`tile_lod_alpha`] with the resident-ancestor test supplied by the caller,
/// so a caller that already owns a keyed tile map (the record pass) need not
/// build a separate resident `HashSet` per frame just to answer it. Output is
/// identical: same ancestor walk, same fade ramp.
pub fn tile_lod_alpha_with(
    key: u64,
    uploaded_at: f32,
    now: f32,
    duration: f32,
    is_resident: impl Fn(u64) -> bool,
) -> f32 {
    let tile = TileId::from_key(key);
    let has_ancestor = (1..=ANCESTOR_DEPTH).any(|levels| {
        tile.ancestor(levels).is_some_and(|a| is_resident(a.key()))
    });
    if has_ancestor {
        lod_fade_alpha(now, uploaded_at, duration)
    } else {
        1.0
    }
}

#[cfg(test)]
mod fingerprint_tests {
    use super::{suppression_can_skip, tiles_fingerprint};

    #[test]
    fn fingerprint_is_order_independent_and_stamp_sensitive() {
        let a = tiles_fingerprint([(11, 1), (22, 2), (33, 3)].into_iter());
        let b = tiles_fingerprint([(33, 3), (11, 1), (22, 2)].into_iter());
        assert_eq!(a, b, "resident-set identity must not depend on iteration order");
        assert_ne!(a, tiles_fingerprint([(11, 1), (22, 2), (33, 4)].into_iter()), "a re-stamped tile re-places");
        assert_ne!(a, tiles_fingerprint([(11, 1), (22, 2)].into_iter()), "a tile leaving re-places");
        assert_eq!(tiles_fingerprint(std::iter::empty()), 0);
    }

    #[test]
    fn suppression_skips_only_when_same_residency_and_previously_settled() {
        assert!(suppression_can_skip(7, 7, false));
        assert!(!suppression_can_skip(8, 7, false), "an upload/eviction must recompute");
        assert!(!suppression_can_skip(7, 7, true), "a fade in flight may complete and change coverage");
        assert!(!suppression_can_skip(7, 8, false));
    }
}
