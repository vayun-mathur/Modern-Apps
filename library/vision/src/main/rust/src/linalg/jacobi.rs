//! Cyclic Jacobi eigen-decomposition shared by the 3x3 `symmetric_eigen` and
//! the `DMatrix` SVD (via AᵀA).
/// Cyclic Jacobi eigen-decomposition of a symmetric `n×n` matrix given
/// row-major in `a_in`. Returns `(eigenvalues, eigenvectors)` where
/// `eigenvectors` is row-major `n×n` and column `i` is the unit eigenvector
/// for `eigenvalues[i]` (unordered). Used for both the 3×3 `symmetric_eigen`
/// and the `DMatrix` SVD (via AᵀA).
pub(crate) fn jacobi_sym(a_in: &[f64], n: usize) -> (Vec<f64>, Vec<f64>) {
    let mut a = a_in.to_vec();
    let mut v = vec![0.0f64; n * n];
    for i in 0..n {
        v[i * n + i] = 1.0;
    }
    let idx = |r: usize, c: usize| r * n + c;

    // n·n·(a few) sweeps is ample; 3×3 converges in ~5, 9×9 in well under 100.
    for _ in 0..100 {
        // Sum of squared strict-upper off-diagonals.
        let mut off = 0.0;
        for p in 0..n {
            for q in (p + 1)..n {
                off += a[idx(p, q)] * a[idx(p, q)];
            }
        }
        if off < 1e-300 {
            break;
        }
        for p in 0..n {
            for q in (p + 1)..n {
                let apq = a[idx(p, q)];
                if apq.abs() < 1e-300 {
                    continue;
                }
                let app = a[idx(p, p)];
                let aqq = a[idx(q, q)];
                let theta = (aqq - app) / (2.0 * apq);
                let t = if theta == 0.0 {
                    1.0
                } else {
                    theta.signum() / (theta.abs() + (theta * theta + 1.0).sqrt())
                };
                let cc = 1.0 / (t * t + 1.0).sqrt();
                let ss = t * cc;

                // B = A * G  (rotate columns p, q).
                for k in 0..n {
                    let akp = a[idx(k, p)];
                    let akq = a[idx(k, q)];
                    a[idx(k, p)] = cc * akp - ss * akq;
                    a[idx(k, q)] = ss * akp + cc * akq;
                }
                // A' = Gᵀ * B  (rotate rows p, q).
                for k in 0..n {
                    let bpk = a[idx(p, k)];
                    let bqk = a[idx(q, k)];
                    a[idx(p, k)] = cc * bpk - ss * bqk;
                    a[idx(q, k)] = ss * bpk + cc * bqk;
                }
                // Accumulate eigenvectors: V' = V * G.
                for k in 0..n {
                    let vkp = v[idx(k, p)];
                    let vkq = v[idx(k, q)];
                    v[idx(k, p)] = cc * vkp - ss * vkq;
                    v[idx(k, q)] = ss * vkp + cc * vkq;
                }
            }
        }
    }

    let evals: Vec<f64> = (0..n).map(|i| a[idx(i, i)]).collect();
    (evals, v)
}

#[cfg(test)] #[path = "jacobi_tests.rs"] mod jacobi_tests;
