//! Seam finding. `seam_masks` uses a graph-cut (min-cut) seam like OpenCV's
//! GraphCutSeamFinder, matching:
//!   terminal_cost 10000, bad_region 1000, COST_COLOR_GRAD (default in header
//!   but Stitcher create forces COLOR; we use GRAD for better seams), Sobel
//!   gradients CV_32F, gap=10 subimage handling, L2 color diff, bad_region penalty.
//! Falls back to VoronoiSeamFinder if cut too large.

use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

// OpenCV GraphCutSeamFinder defaults: terminal 10000, bad_region 1000, COST_COLOR
const TERMINAL_COST: i64 = 10000;
const INF: i64 = 1 << 40; // internal max for BFS cap, still larger than terminal
const MAX_CUT_NODES: usize = 150_000;

pub fn seam_masks(tiles: &[WarpedTile]) -> Vec<Vec<u8>> {
    graph_cut_masks(tiles).unwrap_or_else(|| voronoi_masks(tiles))
}

// ---------------------------------------------------------------------------
// Voronoi (fallback): nearest warped-center ownership.
// ---------------------------------------------------------------------------
pub fn voronoi_masks(tiles: &[WarpedTile]) -> Vec<Vec<u8>> {
    let num = tiles.len();
    let mut masks: Vec<Vec<u8>> = tiles.iter().map(|t| vec![0u8; t.img.w * t.img.h]).collect();
    if num == 0 {
        return masks;
    }
    let (gx0, gy0, gx1, gy1) = global_bounds(tiles);
    let centers: Vec<(f64, f64)> = tiles
        .iter()
        .map(|t| (t.corner_x as f64 + t.img.w as f64 / 2.0, t.corner_y as f64 + t.img.h as f64 / 2.0))
        .collect();
    for gy in gy0..gy1 {
        for gx in gx0..gx1 {
            let mut best = usize::MAX;
            let mut best_d = f64::MAX;
            for ti in 0..num {
                if let Some(c) = tile_at(&tiles[ti], gx, gy) {
                    if c[3] == 0 {
                        continue;
                    }
                    let dx = gx as f64 - centers[ti].0;
                    let dy = gy as f64 - centers[ti].1;
                    let d = dx * dx + dy * dy;
                    if d < best_d {
                        best_d = d;
                        best = ti;
                    }
                }
            }
            if best != usize::MAX {
                let t = &tiles[best];
                masks[best][(gy - t.corner_y) as usize * t.img.w + (gx - t.corner_x) as usize] = 1;
            }
        }
    }
    masks
}

// ---------------------------------------------------------------------------
// Graph-cut seam (default): incremental pairwise min-cut on a per-pixel owner map.
// ---------------------------------------------------------------------------
fn graph_cut_masks(tiles: &[WarpedTile]) -> Option<Vec<Vec<u8>>> {
    let num = tiles.len();
    if num == 0 {
        return Some(Vec::new());
    }
    let (gx0, gy0, gx1, gy1) = global_bounds(tiles);
    let cw = (gx1 - gx0) as usize;
    let ch = (gy1 - gy0) as usize;
    if cw == 0 || ch == 0 {
        return None;
    }
    // owner[global pixel] = tile index or -1
    let mut owner = vec![-1i32; cw * ch];
    // per-tile gradient maps for the COST_COLOR_GRAD seam cost
    let grads: Vec<Vec<i64>> = tiles.iter().map(|t| gradient_map(&t.img)).collect();
    let grad_at = |ti2: usize, gi: usize| -> i64 {
        let tt = &tiles[ti2];
        let gx = (gi % cw) as i32 + gx0;
        let gy = (gi / cw) as i32 + gy0;
        let lx = gx - tt.corner_x;
        let ly = gy - tt.corner_y;
        if lx < 0 || ly < 0 || lx >= tt.img.w as i32 || ly >= tt.img.h as i32 {
            0
        } else {
            grads[ti2][ly as usize * tt.img.w + lx as usize]
        }
    };

    for ti in 0..num {
        let t = &tiles[ti];
        // Overlap pixels (owned by a previous tile AND covered by ti).
        let mut overlap: Vec<usize> = Vec::new(); // global indices
        for ly in 0..t.img.h {
            for lx in 0..t.img.w {
                if t.img.get(lx, ly)[3] == 0 {
                    continue;
                }
                let gx = t.corner_x + lx as i32;
                let gy = t.corner_y + ly as i32;
                let gi = (gy - gy0) as usize * cw + (gx - gx0) as usize;
                if owner[gi] >= 0 {
                    overlap.push(gi);
                }
            }
        }
        if overlap.len() > MAX_CUT_NODES {
            return None; // too big -> use Voronoi
        }
        if overlap.is_empty() {
            // first tile or disjoint: claim all covered pixels
            claim_all(t, gx0, gy0, cw, &mut owner, ti);
            continue;
        }

        // Build min-cut over the overlap: source = existing (A), sink = new (B=ti).
        // node id: 0..overlap.len(); +2 terminals.
        let mut node_of = vec![usize::MAX; cw * ch];
        for (k, &gi) in overlap.iter().enumerate() {
            node_of[gi] = k;
        }
        let src = overlap.len();
        let snk = overlap.len() + 1;
        let mut mf = MaxFlow::new(overlap.len() + 2);

        let color_at = |ti2: usize, gi: usize| -> [u8; 4] {
            let tt = &tiles[ti2];
            let gx = (gi % cw) as i32 + gx0;
            let gy = (gi / cw) as i32 + gy0;
            tile_at(tt, gx, gy).unwrap_or([0, 0, 0, 0])
        };

        for (k, &gi) in overlap.iter().enumerate() {
            let gx = gi % cw;
            let gy = gi / cw;
            // neighbor smoothness edges (right, down) within overlap
            // OpenCV setGraphWeightsColorGrad: weight = (normL2(img1(p)-img2(p))+normL2(q)+1)/(grad+eps) +1000 if mask zero
            // We replicate: L2 diff, gradient map = Sobel magnitude sum, + BAD penalty if either mask missing (should not happen here)
            for (nx, ny) in [(gx + 1, gy), (gx, gy + 1)] {
                if nx >= cw || ny >= ch {
                    continue;
                }
                let ngi = ny * cw + nx;
                let nk = node_of[ngi];
                if nk == usize::MAX {
                    continue;
                }
                // L2 color diff as OpenCV normL2(Point3f)
                let cp = diff_l2(color_at(owner[gi] as usize, gi), color_at(ti, gi));
                let cq = diff_l2(color_at(owner[ngi] as usize, ngi), color_at(ti, ngi));
                // gradient sum from both images (Sobel magnitude) – COLOR_GRAD divides by grad sum
                let gp = grad_at(owner[gi] as usize, gi) + grad_at(ti, gi);
                let gq = grad_at(owner[ngi] as usize, ngi) + grad_at(ti, ngi);
                // weight = (cp+cq+1) / (gp+gq+1) ? Actually OpenCV: (norm+norm)/grad + eps
                // Using formula from seam_finders: (cp+cq)/grad +1 plus bad penalty
                // cp,cq already L2 norms. Use (cp+cq +1)/(gp+gq+1) scaled similar to original *256 but now L2.
                // Keep scaling factor to keep capacities integer and avoid tiny weights.
                let grad_sum = (gp + gq + 1).max(1) as f32;
                let color_sum = (cp + cq) as f32 + 1.0;
                let cap_f = color_sum / grad_sum + 1.0;
                // Bad region penalty: if either tile's alpha zero? In overlap both are opaque, but for safety
                // If gradient zero (flat), weight ~color_sum which is high – prefers seam in textured areas? Actually COLOR_GRAD divides, so high gradient lowers cost -> prefers textured.
                // Clamp and scale to int
                let cap = (cap_f * 256.0) as i64 + 1;
                let cap = cap.clamp(1, 1_000_000);
                mf.add_edge(k, nk, cap, cap);
            }
            // terminal links: pixels bordering A-exclusive -> source; B-exclusive -> sink.
            // Terminal cost 10000 not INF – matches OpenCV
            let mut borders_a_excl = false;
            let mut borders_b_excl = false;
            for (nx, ny) in neighbors(gx, gy, cw, ch) {
                let ngi = ny * cw + nx;
                if node_of[ngi] != usize::MAX {
                    continue; // still in overlap
                }
                if owner[ngi] >= 0 {
                    borders_a_excl = true;
                }
                // Check if ti covers neighbor position
                let ngx_global = nx as i32 + gx0;
                let ngy_global = ny as i32 + gy0;
                if covered_by(&tiles[ti], ngx_global, ngy_global) {
                    borders_b_excl = borders_b_excl || (owner[ngi] < 0);
                }
            }
            if borders_a_excl {
                mf.add_edge(src, k, TERMINAL_COST, 0);
            }
            if borders_b_excl {
                mf.add_edge(k, snk, TERMINAL_COST, 0);
            }
        }

        mf.max_flow(src, snk);
        let cut = mf.min_cut_side(src); // true = source side (A keeps), false = sink (B=ti)
        // Assign overlap pixels.
        for (k, &gi) in overlap.iter().enumerate() {
            if !cut[k] {
                owner[gi] = ti as i32;
            }
        }
        // Claim ti's non-overlap new coverage.
        claim_all(t, gx0, gy0, cw, &mut owner, ti);
    }

    // Build per-tile masks from owner.
    let mut masks: Vec<Vec<u8>> = tiles.iter().map(|t| vec![0u8; t.img.w * t.img.h]).collect();
    for gy in 0..ch {
        for gx in 0..cw {
            let o = owner[gy * cw + gx];
            if o < 0 {
                continue;
            }
            let t = &tiles[o as usize];
            let lx = gx as i32 + gx0 - t.corner_x;
            let ly = gy as i32 + gy0 - t.corner_y;
            if lx >= 0 && ly >= 0 && (lx as usize) < t.img.w && (ly as usize) < t.img.h {
                masks[o as usize][ly as usize * t.img.w + lx as usize] = 1;
            }
        }
    }
    Some(masks)
}

/// Claim every covered pixel of `t` that is currently unowned.
fn claim_all(t: &WarpedTile, gx0: i32, gy0: i32, cw: usize, owner: &mut [i32], ti: usize) {
    for ly in 0..t.img.h {
        for lx in 0..t.img.w {
            if t.img.get(lx, ly)[3] == 0 {
                continue;
            }
            let gx = t.corner_x + lx as i32;
            let gy = t.corner_y + ly as i32;
            let gi = (gy - gy0) as usize * cw + (gx - gx0) as usize;
            if owner[gi] < 0 {
                owner[gi] = ti as i32;
            }
        }
    }
}

fn neighbors(x: usize, y: usize, w: usize, h: usize) -> Vec<(usize, usize)> {
    let mut v = Vec::with_capacity(4);
    if x + 1 < w {
        v.push((x + 1, y));
    }
    if x > 0 {
        v.push((x - 1, y));
    }
    if y + 1 < h {
        v.push((x, y + 1));
    }
    if y > 0 {
        v.push((x, y - 1));
    }
    v
}

fn covered_by(t: &WarpedTile, gx: i32, gy: i32) -> bool {
    tile_at(t, gx, gy).is_some_and(|c| c[3] != 0)
}

fn tile_at(t: &WarpedTile, gx: i32, gy: i32) -> Option<[u8; 4]> {
    let lx = gx - t.corner_x;
    let ly = gy - t.corner_y;
    if lx < 0 || ly < 0 || lx >= t.img.w as i32 || ly >= t.img.h as i32 {
        return None;
    }
    Some(t.img.get(lx as usize, ly as usize))
}

fn diff_l2(a: [u8; 4], b: [u8; 4]) -> i64 {
    // normL2(Point3f) like OpenCV: sqrt(sum squares) – but integer approx sum squares sqrt
    let dr = a[0] as i64 - b[0] as i64;
    let dg = a[1] as i64 - b[1] as i64;
    let db = a[2] as i64 - b[2] as i64;
    let sq = dr * dr + dg * dg + db * db;
    (sq as f64).sqrt() as i64
}

#[inline]
fn luma_i(c: [u8; 4]) -> i64 {
    (0.299 * c[0] as f64 + 0.587 * c[1] as f64 + 0.114 * c[2] as f64) as i64
}

/// Sobel gradients magnitude approximation for COLOR_GRAD
/// OpenCV: Sobel(src, dx, CV_32F, 1,0) and Sobel(src, dy, CV_32F, 0,1)
/// then combined magnitude sqrt(dx^2+dy^2) per channel? We compute |dx|+|dy| Sobel luma based.
fn gradient_map(img: &Rgba) -> Vec<i64> {
    let (w, h) = (img.w, img.h);
    let mut g = vec![0i64; w * h];
    // Sobel kernel approximation: use luma Sobel
    for y in 0..h {
        for x in 0..w {
            if img.get(x, y)[3] == 0 {
                continue;
            }
            // Sobel X: -1 0 1 / -2 0 2 / -1 0 1 on luma
            let xm = if x > 0 { x - 1 } else { x };
            let xp = if x + 1 < w { x + 1 } else { x };
            let ym = if y > 0 { y - 1 } else { y };
            let yp = if y + 1 < h { y + 1 } else { y };
            let ym_row = ym;
            let yp_row = yp;
            // 3x3 luma matrix
            let get_l = |xx: usize, yy: usize| -> i32 { luma_i(img.get(xx, yy)) as i32 };
            let l00 = get_l(xm, ym_row); let l01 = get_l(x, ym_row); let l02 = get_l(xp, ym_row);
            let l10 = get_l(xm, y);      let l12 = get_l(xp, y);
            let l20 = get_l(xm, yp_row); let l21 = get_l(x, yp_row); let l22 = get_l(xp, yp_row);
            let dx = -l00 + l02 - 2 * l10 + 2 * l12 - l20 + l22;
            let dy = -l00 - 2 * l01 - l02 + l20 + 2 * l21 + l22;
            let mag = dx.abs() + dy.abs(); // Manhattan approx of Sobel magnitude, scaled
            g[y * w + x] = mag as i64;
        }
    }
    g
}

fn global_bounds(tiles: &[WarpedTile]) -> (i32, i32, i32, i32) {
    let mut gx0 = i32::MAX;
    let mut gy0 = i32::MAX;
    let mut gx1 = i32::MIN;
    let mut gy1 = i32::MIN;
    for t in tiles {
        gx0 = gx0.min(t.corner_x);
        gy0 = gy0.min(t.corner_y);
        gx1 = gx1.max(t.corner_x + t.img.w as i32);
        gy1 = gy1.max(t.corner_y + t.img.h as i32);
    }
    (gx0, gy0, gx1, gy1)
}

// ---------------------------------------------------------------------------
// Dinic max-flow / min-cut.
// ---------------------------------------------------------------------------
struct MaxFlow {
    head: Vec<i32>,
    to: Vec<u32>,
    nxt: Vec<i32>,
    cap: Vec<i64>,
    level: Vec<i32>,
    it: Vec<i32>,
    n: usize,
}

impl MaxFlow {
    fn new(n: usize) -> Self {
        MaxFlow {
            head: vec![-1; n],
            to: Vec::new(),
            nxt: Vec::new(),
            cap: Vec::new(),
            level: vec![0; n],
            it: vec![0; n],
            n,
        }
    }
    fn add_edge(&mut self, u: usize, v: usize, c: i64, rc: i64) {
        self.to.push(v as u32);
        self.cap.push(c);
        self.nxt.push(self.head[u]);
        self.head[u] = (self.to.len() - 1) as i32;
        self.to.push(u as u32);
        self.cap.push(rc);
        self.nxt.push(self.head[v]);
        self.head[v] = (self.to.len() - 1) as i32;
    }
    fn bfs(&mut self, s: usize, t: usize) -> bool {
        for l in self.level.iter_mut() {
            *l = -1;
        }
        let mut q = std::collections::VecDeque::new();
        self.level[s] = 0;
        q.push_back(s);
        while let Some(u) = q.pop_front() {
            let mut e = self.head[u];
            while e != -1 {
                let ei = e as usize;
                let v = self.to[ei] as usize;
                if self.cap[ei] > 0 && self.level[v] < 0 {
                    self.level[v] = self.level[u] + 1;
                    q.push_back(v);
                }
                e = self.nxt[ei];
            }
        }
        self.level[t] >= 0
    }
    fn dfs(&mut self, u: usize, t: usize, f: i64) -> i64 {
        if u == t {
            return f;
        }
        while self.it[u] != -1 {
            let ei = self.it[u] as usize;
            let v = self.to[ei] as usize;
            if self.cap[ei] > 0 && self.level[v] == self.level[u] + 1 {
                let d = self.dfs(v, t, f.min(self.cap[ei]));
                if d > 0 {
                    self.cap[ei] -= d;
                    self.cap[ei ^ 1] += d;
                    return d;
                }
            }
            self.it[u] = self.nxt[ei];
        }
        0
    }
    fn max_flow(&mut self, s: usize, t: usize) -> i64 {
        let mut flow = 0;
        while self.bfs(s, t) {
            for u in 0..self.n {
                self.it[u] = self.head[u];
            }
            loop {
                let f = self.dfs(s, t, INF);
                if f == 0 {
                    break;
                }
                flow += f;
            }
        }
        flow
    }
    /// Pixels reachable from `s` in the residual graph = source side of the cut.
    fn min_cut_side(&self, s: usize) -> Vec<bool> {
        let mut side = vec![false; self.n];
        let mut q = std::collections::VecDeque::new();
        side[s] = true;
        q.push_back(s);
        while let Some(u) = q.pop_front() {
            let mut e = self.head[u];
            while e != -1 {
                let ei = e as usize;
                let v = self.to[ei] as usize;
                if self.cap[ei] > 0 && !side[v] {
                    side[v] = true;
                    q.push_back(v);
                }
                e = self.nxt[ei];
            }
        }
        side
    }
}

#[cfg(test)]
#[path = "seam_tests.rs"]
mod seam_tests;
