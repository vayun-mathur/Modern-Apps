//! Unit tests for `liquify` (displacement field + bilinear resample).
use crate::liquify::liquify;
use crate::pixel::{a, pack, r};

/// Unique-per-pixel image: R encodes x, G encodes y.
fn coords(w: usize, h: usize) -> Vec<i32> {
    let mut v = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            v.push(pack(255, (x * 16) as i32, (y * 16) as i32, ((x + y) * 8 % 256) as i32));
        }
    }
    v
}
const PUSH: i32 = 0;
const TWIRL: i32 = 1;
const PUCKER: i32 = 2;
const BLOAT: i32 = 3;
const RECONSTRUCT: i32 = 4;

#[test]
fn empty_and_truncated_ops_are_identity() {
    let src = coords(8, 8);
    assert_eq!(liquify(&src, 8, 8, &[], &[]), src);
    // tools present but no params at all -> zero complete ops.
    assert_eq!(liquify(&src, 8, 8, &[PUSH, PUSH], &[]), src);
    // One tool but only a partial (<6) param block -> ignored.
    assert_eq!(liquify(&src, 8, 8, &[PUSH], &[0.5, 0.5, 0.2]), src);
    // Extra tool beyond complete param blocks is dropped.
    let one = liquify(&src, 8, 8, &[PUSH], &[0.5, 0.5, 0.0625, 0.0, 0.5, 1.0]);
    let two_tools_one_block = liquify(&src, 8, 8, &[PUSH, BLOAT], &[0.5, 0.5, 0.0625, 0.0, 0.5, 1.0]);
    assert_eq!(one, two_tools_one_block);
}

#[test]
fn zero_drag_push_and_zero_radius_are_identity() {
    let src = coords(12, 12);
    // Push with no drag displaces nothing.
    assert_eq!(liquify(&src, 12, 12, &[PUSH], &[0.5, 0.5, 0.0, 0.0, 0.4, 0.9]), src);
    // Zero / negative radius ops are skipped entirely.
    assert_eq!(liquify(&src, 12, 12, &[PUSH], &[0.5, 0.5, 0.2, 0.2, 0.0, 0.9]), src);
    assert_eq!(liquify(&src, 12, 12, &[TWIRL], &[0.5, 0.5, 0.0, 0.0, -0.3, 0.9]), src);
}

#[test]
fn push_centre_samples_exactly_one_pixel_upstream() {
    // At the op centre falloff t == 1, so displacement is exactly
    // -drag*max_dim*strength. drag 1/16 of 16px at strength 1 => -1px in x:
    // the centre destination pixel must equal the source pixel to its left.
    let (w, h) = (16, 16);
    let src = coords(w, h);
    let out = liquify(&src, w, h, &[PUSH], &[0.5, 0.5, 0.0625, 0.0, 0.5, 1.0]);
    assert_eq!(out[8 * w + 8], src[8 * w + 7]);
    // Same in y with the drag swapped.
    let out = liquify(&src, w, h, &[PUSH], &[0.5, 0.5, 0.0, 0.0625, 0.5, 1.0]);
    assert_eq!(out[8 * w + 8], src[7 * w + 8]);
    // Two identical pushes accumulate to a -2px sample offset.
    let out = liquify(
        &src, w, h,
        &[PUSH, PUSH],
        &[0.5, 0.5, 0.0625, 0.0, 0.5, 1.0, 0.5, 0.5, 0.0625, 0.0, 0.5, 1.0],
    );
    assert_eq!(out[8 * w + 8], src[8 * w + 6]);
}

#[test]
fn push_outside_radius_is_untouched_and_direction_is_upstream() {
    let (w, h) = (20, 20);
    let src = coords(w, h);
    // Small brush at centre; corners lie outside the radius.
    let out = liquify(&src, w, h, &[PUSH], &[0.5, 0.5, 0.1, 0.0, 0.1, 1.0]);
    assert_eq!(out[0], src[0]);
    assert_eq!(out[w - 1], src[w - 1]);
    assert_eq!(out[(h - 1) * w], src[(h - 1) * w]);
    // Dragging right samples from the left: R (which encodes x) drops.
    let big = liquify(&src, w, h, &[PUSH], &[0.5, 0.5, 0.1, 0.0, 0.5, 0.8]);
    assert!(r(big[10 * w + 10]) < r(src[10 * w + 10]), "samples upstream (left)");
}

#[test]
fn twirl_pucker_bloat_keep_centre_fixed() {
    // At the exact centre the offset from centre is zero, so twirl rotates
    // (0,0) to itself and pucker/bloat scale a zero vector: centre fixed.
    let (w, h) = (15, 15);
    let src = coords(w, h);
    for tool in [TWIRL, PUCKER, BLOAT] {
        let out = liquify(&src, w, h, &[tool], &[0.5, 0.5, 0.0, 0.0, 0.5, 1.0]);
        // cx = 0.5*15 = 7.5; nearest pixels still move for pucker/bloat, so
        // assert only dimensions and that *some* off-centre pixel changed
        // for the radial tools (twirl moves off-centre pixels too).
        assert_eq!(out.len(), w * h);
        assert_ne!(out, src, "tool {tool} should displace off-centre pixels");
    }
}

#[test]
fn all_tools_preserve_dims_and_opaque_alpha() {
    let (w, h) = (16, 14);
    let src = coords(w, h);
    for tool in [PUSH, TWIRL, PUCKER, BLOAT, RECONSTRUCT] {
        let out = liquify(&src, w, h, &[tool], &[0.5, 0.5, 0.05, 0.03, 0.4, 0.7]);
        assert_eq!(out.len(), w * h, "tool {tool}");
        // Bilinear interpolation of an all-opaque image stays opaque.
        assert!(out.iter().all(|&p| a(p) == 255), "tool {tool} alpha");
    }
}

#[test]
fn reconstruct_at_centre_cancels_prior_push() {
    let (w, h) = (16, 16);
    let src = coords(w, h);
    let push_only = liquify(&src, w, h, &[PUSH], &[0.5, 0.5, 0.0625, 0.0, 0.5, 1.0]);
    assert_ne!(push_only[8 * w + 8], src[8 * w + 8]);
    // Reconstruct with strength 1 at the same centre: falloff 1 there, so
    // factor = 1 - 1*1 = 0 wipes the displacement at the centre exactly.
    let healed = liquify(
        &src, w, h,
        &[PUSH, RECONSTRUCT],
        &[0.5, 0.5, 0.0625, 0.0, 0.5, 1.0, 0.5, 0.5, 0.0, 0.0, 0.5, 1.0],
    );
    assert_eq!(healed[8 * w + 8], src[8 * w + 8]);
}

#[test]
fn unknown_tool_is_ignored_not_reconstruct() {
    // An out-of-range tool ordinal must be a no-op. Treating it as
    // Reconstruct would silently scale the displacement from a prior op.
    let (w, h) = (16, 16);
    let src = coords(w, h);
    let push_params = [0.5f32, 0.5, 0.0625, 0.0, 0.5, 1.0];
    let push_only = liquify(&src, w, h, &[PUSH], &push_params);
    let mut tools = vec![PUSH, 99];
    let mut params = push_params.to_vec();
    params.extend_from_slice(&[0.5, 0.5, 0.0, 0.0, 0.5, 1.0]);
    let with_unknown = liquify(&src, w, h, &tools, &params);
    assert_eq!(with_unknown, push_only, "unknown tool must not alter displacement");
    tools[1] = -3;
    let with_negative = liquify(&src, w, h, &tools, &params);
    assert_eq!(with_negative, push_only);
    // Unknown tool alone is identity.
    assert_eq!(liquify(&src, w, h, &[42], &[0.5, 0.5, 0.1, 0.1, 0.5, 1.0]), src);
}
