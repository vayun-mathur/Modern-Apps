//! Oriented-FAST + rotated-BRIEF (ORB-style) feature detection and matching.

use crate::imgbuf::Gray;

pub struct KeyPoint {
    pub x: f32,
    pub y: f32,
    pub angle: f32,
    pub score: f32,
}

pub struct Features {
    pub kps: Vec<KeyPoint>,
    pub desc: Vec<[u8; 32]>, // 256-bit BRIEF
}

// Bresenham circle of radius 3 (16 pixels), clockwise from top.
const CIRCLE: [(i32, i32); 16] = [
    (0, -3), (1, -3), (2, -2), (3, -1), (3, 0), (3, 1), (2, 2), (1, 3),
    (0, 3), (-1, 3), (-2, 2), (-3, 1), (-3, 0), (-3, -1), (-2, -2), (-1, -3),
];

const PATCH_R: i32 = 15;

/// Deterministic BRIEF sampling pattern: 256 point pairs within the patch.
fn brief_pattern() -> Vec<((i32, i32), (i32, i32))> {
    // Simple LCG for reproducible pseudo-random pairs.
    let mut state: u64 = 0x9E3779B97F4A7C15;
    let mut next = || {
        state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((state >> 33) as i64) as i32
    };
    let span = PATCH_R - 2; // keep pairs inside patch after rotation
    let mut pairs = Vec::with_capacity(256);
    for _ in 0..256 {
        let ax = (next().rem_euclid(2 * span + 1)) - span;
        let ay = (next().rem_euclid(2 * span + 1)) - span;
        let bx = (next().rem_euclid(2 * span + 1)) - span;
        let by = (next().rem_euclid(2 * span + 1)) - span;
        pairs.push(((ax, ay), (bx, by)));
    }
    pairs
}

#[inline]
fn fast_score(g: &Gray, x: i32, y: i32) -> i32 {
    let c = g.at(x as usize, y as usize) as i32;
    let mut s = 0;
    for &(dx, dy) in CIRCLE.iter() {
        let p = g.at((x + dx) as usize, (y + dy) as usize) as i32;
        s += (p - c).abs();
    }
    s
}

#[inline]
fn is_corner(g: &Gray, x: i32, y: i32, t: i32) -> bool {
    let c = g.at(x as usize, y as usize) as i32;
    // High-speed rejection test on pixels 1,5,9,13 (indices 0,4,8,12).
    let p0 = g.at((x + CIRCLE[0].0) as usize, (y + CIRCLE[0].1) as usize) as i32;
    let p8 = g.at((x + CIRCLE[8].0) as usize, (y + CIRCLE[8].1) as usize) as i32;
    let p4 = g.at((x + CIRCLE[4].0) as usize, (y + CIRCLE[4].1) as usize) as i32;
    let p12 = g.at((x + CIRCLE[12].0) as usize, (y + CIRCLE[12].1) as usize) as i32;
    let brighter = |p: i32| p > c + t;
    let darker = |p: i32| p < c - t;
    let nb = brighter(p0) as i32 + brighter(p4) as i32 + brighter(p8) as i32 + brighter(p12) as i32;
    let nd = darker(p0) as i32 + darker(p4) as i32 + darker(p8) as i32 + darker(p12) as i32;
    if nb < 3 && nd < 3 {
        return false;
    }
    // Full contiguous-9 test around the ring (wrap-around).
    let mut vals = [0i32; 16];
    for k in 0..16 {
        vals[k] = g.at((x + CIRCLE[k].0) as usize, (y + CIRCLE[k].1) as usize) as i32;
    }
    for start in 0..16 {
        let mut all_b = true;
        let mut all_d = true;
        for j in 0..9 {
            let v = vals[(start + j) % 16];
            if !(v > c + t) { all_b = false; }
            if !(v < c - t) { all_d = false; }
        }
        if all_b || all_d {
            return true;
        }
    }
    false
}

fn orientation(g: &Gray, x: i32, y: i32) -> f32 {
    let mut m01 = 0i64;
    let mut m10 = 0i64;
    for dy in -PATCH_R..=PATCH_R {
        for dx in -PATCH_R..=PATCH_R {
            if dx * dx + dy * dy > PATCH_R * PATCH_R {
                continue;
            }
            let v = g.at((x + dx) as usize, (y + dy) as usize) as i64;
            m10 += dx as i64 * v;
            m01 += dy as i64 * v;
        }
    }
    (m01 as f32).atan2(m10 as f32)
}

pub fn detect_and_describe(g: &Gray, max_features: usize, threshold: i32) -> Features {
    let border = PATCH_R + 1;
    if g.w as i32 <= 2 * border || g.h as i32 <= 2 * border {
        return Features { kps: Vec::new(), desc: Vec::new() };
    }
    // 1) detect corners with scores
    let mut cand: Vec<(i32, i32, i32)> = Vec::new();
    for y in border..(g.h as i32 - border) {
        for x in border..(g.w as i32 - border) {
            if is_corner(g, x, y, threshold) {
                cand.push((x, y, fast_score(g, x, y)));
            }
        }
    }
    // 2) grid-bucketed non-max suppression, then cap by score
    cand.sort_by(|a, b| b.2.cmp(&a.2));
    let cell = 8i32;
    let gw = (g.w as i32 / cell) + 1;
    let gh = (g.h as i32 / cell) + 1;
    let mut occupied = vec![false; (gw * gh) as usize];
    let mut kept: Vec<(i32, i32)> = Vec::new();
    for (x, y, _s) in cand {
        let cx = x / cell;
        let cy = y / cell;
        let idx = (cy * gw + cx) as usize;
        if occupied[idx] {
            continue;
        }
        occupied[idx] = true;
        kept.push((x, y));
        if kept.len() >= max_features {
            break;
        }
    }
    // 3) describe
    let pattern = brief_pattern();
    let mut kps = Vec::with_capacity(kept.len());
    let mut desc = Vec::with_capacity(kept.len());
    for (x, y) in kept {
        let angle = orientation(g, x, y);
        let (sin, cos) = angle.sin_cos();
        let mut d = [0u8; 32];
        for (bit, &((ax, ay), (bx, by))) in pattern.iter().enumerate() {
            let rax = (ax as f32 * cos - ay as f32 * sin).round() as i32;
            let ray = (ax as f32 * sin + ay as f32 * cos).round() as i32;
            let rbx = (bx as f32 * cos - by as f32 * sin).round() as i32;
            let rby = (bx as f32 * sin + by as f32 * cos).round() as i32;
            let pa = sample_clamped(g, x + rax, y + ray);
            let pb = sample_clamped(g, x + rbx, y + rby);
            if pa < pb {
                d[bit / 8] |= 1 << (bit % 8);
            }
        }
        kps.push(KeyPoint { x: x as f32, y: y as f32, angle, score: 0.0 });
        desc.push(d);
    }
    Features { kps, desc }
}

#[inline]
fn sample_clamped(g: &Gray, x: i32, y: i32) -> i32 {
    let xx = x.clamp(0, g.w as i32 - 1) as usize;
    let yy = y.clamp(0, g.h as i32 - 1) as usize;
    g.at(xx, yy) as i32
}

#[inline]
fn hamming(a: &[u8; 32], b: &[u8; 32]) -> u32 {
    let mut d = 0u32;
    for i in 0..32 {
        d += (a[i] ^ b[i]).count_ones();
    }
    d
}

/// Brute-force match a->b with Lowe ratio test. Returns (index_in_a, index_in_b).
pub fn match_features(a: &Features, b: &Features, ratio: f32) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    if a.desc.is_empty() || b.desc.is_empty() {
        return out;
    }
    for (i, da) in a.desc.iter().enumerate() {
        let mut best = u32::MAX;
        let mut second = u32::MAX;
        let mut best_j = usize::MAX;
        for (j, db) in b.desc.iter().enumerate() {
            let d = hamming(da, db);
            if d < best {
                second = best;
                best = d;
                best_j = j;
            } else if d < second {
                second = d;
            }
        }
        if best_j != usize::MAX && (best as f32) < ratio * (second as f32) {
            out.push((i, best_j));
        }
    }
    out
}
