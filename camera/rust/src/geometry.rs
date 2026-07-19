//! Homography estimation (normalized DLT) with RANSAC.

use nalgebra::{DMatrix, Matrix3, Vector3};

pub type Pt = (f32, f32);

struct Lcg(u64);
impl Lcg {
    fn new(seed: u64) -> Self {
        Lcg(seed | 1)
    }
    fn next_usize(&mut self, n: usize) -> usize {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 33) as usize) % n.max(1)
    }
}

/// Isotropic normalization (Hartley): returns (T, normalized points).
fn normalize(pts: &[Pt]) -> (Matrix3<f64>, Vec<(f64, f64)>) {
    let n = pts.len().max(1) as f64;
    let (mut mx, mut my) = (0.0, 0.0);
    for &(x, y) in pts {
        mx += x as f64;
        my += y as f64;
    }
    mx /= n;
    my /= n;
    let mut mean_dist = 0.0;
    for &(x, y) in pts {
        let dx = x as f64 - mx;
        let dy = y as f64 - my;
        mean_dist += (dx * dx + dy * dy).sqrt();
    }
    mean_dist /= n;
    let s = if mean_dist > 1e-8 { (2.0f64).sqrt() / mean_dist } else { 1.0 };
    let t = Matrix3::new(s, 0.0, -s * mx, 0.0, s, -s * my, 0.0, 0.0, 1.0);
    let out = pts.iter().map(|&(x, y)| (s * (x as f64 - mx), s * (y as f64 - my))).collect();
    (t, out)
}

/// Homography mapping a -> b from >=4 correspondences via normalized DLT.
fn dlt(a: &[Pt], b: &[Pt]) -> Option<Matrix3<f64>> {
    if a.len() < 4 || a.len() != b.len() {
        return None;
    }
    let (ta, na) = normalize(a);
    let (tb, nb) = normalize(b);
    let mut m = DMatrix::<f64>::zeros(2 * a.len(), 9);
    for i in 0..a.len() {
        let (x, y) = na[i];
        let (u, v) = nb[i];
        m[(2 * i, 0)] = -x;
        m[(2 * i, 1)] = -y;
        m[(2 * i, 2)] = -1.0;
        m[(2 * i, 6)] = u * x;
        m[(2 * i, 7)] = u * y;
        m[(2 * i, 8)] = u;
        m[(2 * i + 1, 3)] = -x;
        m[(2 * i + 1, 4)] = -y;
        m[(2 * i + 1, 5)] = -1.0;
        m[(2 * i + 1, 6)] = v * x;
        m[(2 * i + 1, 7)] = v * y;
        m[(2 * i + 1, 8)] = v;
    }
    let svd = m.svd(true, true);
    let vt = svd.v_t?;
    let h = vt.row(vt.nrows() - 1);
    let hn = Matrix3::new(
        h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], h[8],
    );
    // Denormalize: H = Tb^-1 * Hn * Ta
    let tb_inv = tb.try_inverse()?;
    let mut hd = tb_inv * hn * ta;
    if hd[(2, 2)].abs() > 1e-12 {
        hd /= hd[(2, 2)];
    }
    Some(hd)
}

#[inline]
fn apply(h: &Matrix3<f64>, p: Pt) -> (f64, f64) {
    let v = h * Vector3::new(p.0 as f64, p.1 as f64, 1.0);
    if v.z.abs() < 1e-12 {
        (v.x, v.y)
    } else {
        (v.x / v.z, v.y / v.z)
    }
}

/// Indices of correspondences whose transfer error under `h` is below `thresh`.
pub fn transfer_inliers(h: &Matrix3<f64>, a: &[Pt], b: &[Pt], thresh: f32) -> Vec<usize> {
    let thr2 = (thresh * thresh) as f64;
    let mut v = Vec::new();
    for i in 0..a.len().min(b.len()) {
        let (px, py) = apply(h, a[i]);
        let dx = px - b[i].0 as f64;
        let dy = py - b[i].1 as f64;
        if dx * dx + dy * dy < thr2 {
            v.push(i);
        }
    }
    v
}

/// RANSAC homography a->b. Returns (H, inlier_count).
pub fn find_homography_ransac(a: &[Pt], b: &[Pt], iters: usize, thresh: f32) -> Option<(Matrix3<f64>, usize)> {
    let n = a.len();
    if n < 4 || n != b.len() {
        return None;
    }
    let thr2 = (thresh * thresh) as f64;
    let mut rng = Lcg::new(0xC0FFEE ^ n as u64);
    let mut best_h: Option<Matrix3<f64>> = None;
    let mut best_inliers: Vec<usize> = Vec::new();

    for _ in 0..iters {
        // sample 4 distinct indices
        let mut idx = [0usize; 4];
        let mut ok = true;
        for k in 0..4 {
            idx[k] = rng.next_usize(n);
            for j in 0..k {
                if idx[j] == idx[k] {
                    ok = false;
                }
            }
        }
        if !ok {
            continue;
        }
        let sa: Vec<Pt> = idx.iter().map(|&i| a[i]).collect();
        let sb: Vec<Pt> = idx.iter().map(|&i| b[i]).collect();
        let h = match dlt(&sa, &sb) {
            Some(h) => h,
            None => continue,
        };
        let mut inliers = Vec::new();
        for i in 0..n {
            let (px, py) = apply(&h, a[i]);
            let dx = px - b[i].0 as f64;
            let dy = py - b[i].1 as f64;
            if dx * dx + dy * dy < thr2 {
                inliers.push(i);
            }
        }
        if inliers.len() > best_inliers.len() {
            best_inliers = inliers;
            best_h = Some(h);
        }
    }

    let inliers = best_inliers;
    if inliers.len() < 8 {
        return best_h.map(|h| (h, inliers.len()));
    }
    // Refit on all inliers.
    let ia: Vec<Pt> = inliers.iter().map(|&i| a[i]).collect();
    let ib: Vec<Pt> = inliers.iter().map(|&i| b[i]).collect();
    let refined = dlt(&ia, &ib).or(best_h)?;
    Some((refined, inliers.len()))
}
