//! Fixed 3x3 row-major algebra: `Matrix3` construction, inverse, SVD and
//! symmetric eigen-decomposition, plus the operator impls.
use std::marker::PhantomData;
use std::ops::{AddAssign, DivAssign, Index, IndexMut, Mul};

use super::jacobi::jacobi_sym;
use super::vector::{RowVector3, Vector3};

// ===========================================================================
// Matrix3 (row-major, 3×3)
// ===========================================================================

#[derive(Clone, Copy, Debug)]
pub struct Matrix3<T = f64> {
    /// Row-major: `d[r * 3 + c]`.
    d: [f64; 9],
    _p: PhantomData<T>,
}

impl Matrix3<f64> {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        m00: f64, m01: f64, m02: f64,
        m10: f64, m11: f64, m12: f64,
        m20: f64, m21: f64, m22: f64,
    ) -> Self {
        Matrix3 {
            d: [m00, m01, m02, m10, m11, m12, m20, m21, m22],
            _p: PhantomData,
        }
    }

    pub fn zeros() -> Self {
        Matrix3 { d: [0.0; 9], _p: PhantomData }
    }

    pub fn identity() -> Self {
        Matrix3::new(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }

    /// Build from three row vectors (mirrors `nalgebra::Matrix3::from_rows`).
    pub fn from_rows(rows: &[RowVector3<f64>]) -> Self {
        let mut d = [0.0; 9];
        for (r, row) in rows.iter().take(3).enumerate() {
            d[r * 3] = row.d[0];
            d[r * 3 + 1] = row.d[1];
            d[r * 3 + 2] = row.d[2];
        }
        Matrix3 { d, _p: PhantomData }
    }

    #[inline]
    fn at(&self, r: usize, c: usize) -> f64 {
        self.d[r * 3 + c]
    }

    pub fn transpose(&self) -> Self {
        Matrix3::new(
            self.d[0], self.d[3], self.d[6],
            self.d[1], self.d[4], self.d[7],
            self.d[2], self.d[5], self.d[8],
        )
    }

    /// Column `c` as an owned vector.
    pub fn column(&self, c: usize) -> Vector3<f64> {
        Vector3::new(self.d[c], self.d[3 + c], self.d[6 + c])
    }

    fn set_column(&mut self, c: usize, v: Vector3<f64>) {
        self.d[c] = v.x;
        self.d[3 + c] = v.y;
        self.d[6 + c] = v.z;
    }

    pub fn determinant(&self) -> f64 {
        let m = &self.d;
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
            + m[2] * (m[3] * m[7] - m[4] * m[6])
    }

    /// Closed-form 3×3 inverse via the adjugate; `None` if singular.
    pub fn try_inverse(&self) -> Option<Self> {
        let m = &self.d;
        let (a, b, c) = (m[0], m[1], m[2]);
        let (d, e, f) = (m[3], m[4], m[5]);
        let (g, h, i) = (m[6], m[7], m[8]);

        // Cofactors.
        let a00 = e * i - f * h;
        let a01 = -(d * i - f * g);
        let a02 = d * h - e * g;
        let a10 = -(b * i - c * h);
        let a11 = a * i - c * g;
        let a12 = -(a * h - b * g);
        let a20 = b * f - c * e;
        let a21 = -(a * f - c * d);
        let a22 = a * e - b * d;

        let det = a * a00 + b * a01 + c * a02;
        if !det.is_finite() || det == 0.0 {
            return None;
        }
        let inv = 1.0 / det;
        // Adjugate = transpose of the cofactor matrix.
        Some(Matrix3::new(
            a00 * inv, a10 * inv, a20 * inv,
            a01 * inv, a11 * inv, a21 * inv,
            a02 * inv, a12 * inv, a22 * inv,
        ))
    }

    /// Eigen-decomposition of a **symmetric** matrix via cyclic Jacobi.
    /// `eigenvectors`' columns are the (unit) eigenvectors; eigenvalues are
    /// unordered (matching `nalgebra::SymmetricEigen`).
    /// As [`Matrix3::svd`], but with singular values in **descending** order and
    /// `u` / `v_t` permuted to match.
    ///
    /// Plain `svd()` inherits the Jacobi eigensolver's arbitrary ordering, which is
    /// harmless for callers that only form `U·Vᵀ` (orthonormalization) but wrong for
    /// anyone indexing a specific singular direction — e.g. taking the null vector as
    /// `u.column(2)`, or forcing the smallest singular value to zero. `DMatrix::svd`
    /// already sorts; this makes the 3×3 path consistent with it.
    pub fn svd_sorted(&self) -> Svd {
        let s = self.svd(true, true);
        let (u, vt) = match (s.u, s.v_t) {
            (Some(u), Some(vt)) => (u, vt),
            _ => return s,
        };
        let sigma = s.singular_values;
        let mut order = [0usize, 1, 2];
        order.sort_by(|&a, &b| sigma[b].total_cmp(&sigma[a]));

        let mut nu = Matrix3::zeros();
        let mut nv = Matrix3::zeros();
        for (rank, &src) in order.iter().enumerate() {
            nu.set_column(rank, u.column(src));
            // Row `src` of v_t is the corresponding right singular vector.
            let r = Vector3::new(vt[(src, 0)], vt[(src, 1)], vt[(src, 2)]);
            nv.set_column(rank, r);
        }
        Svd {
            u: Some(nu),
            v_t: Some(nv.transpose()),
            singular_values: Vector3::new(
                sigma[order[0]],
                sigma[order[1]],
                sigma[order[2]],
            ),
        }
    }

    pub fn symmetric_eigen(&self) -> SymmetricEigen {
        let (evals, evecs) = jacobi_sym(&self.d, 3);
        SymmetricEigen {
            eigenvalues: Vector3::new(evals[0], evals[1], evals[2]),
            eigenvectors: Matrix3 {
                d: [
                    evecs[0], evecs[1], evecs[2],
                    evecs[3], evecs[4], evecs[5],
                    evecs[6], evecs[7], evecs[8],
                ],
                _p: PhantomData,
            },
        }
    }

    /// Singular value decomposition. Computed from the symmetric eigen of MᵀM:
    /// `M = U Σ Vᵀ`, with `V` the eigenvectors of MᵀM and `u_i = M v_i / σ_i`.
    /// The booleans mirror `nalgebra`'s API; both factors are always produced.
    pub fn svd(&self, _compute_u: bool, _compute_v: bool) -> Svd {
        let ata = self.transpose() * *self;
        let se = ata.symmetric_eigen();
        let mut sigma = [0.0f64; 3];
        for (i, s) in sigma.iter_mut().enumerate() {
            *s = se.eigenvalues[i].max(0.0).sqrt();
        }
        let vmat = se.eigenvectors;

        let mut u = Matrix3::zeros();
        let mut degenerate = [false; 3];
        for i in 0..3 {
            let vi = vmat.column(i);
            let mvi = *self * vi;
            if sigma[i] <= 1e-12 {
                degenerate[i] = true;
                continue;
            }
            // u_i = M v_i / σ_i is only as good as v_i, and for a near-zero
            // σ_i the Jacobi eigenvector is numerically in M's null space:
            // the quotient comes out near zero instead of unit (an essential
            // matrix's σ = (1, 1, 1e-9) did exactly this, and every consumer
            // indexing u.column(2) — the translation direction — read noise).
            // Orthogonalise against the accepted columns and let the fixup
            // below complete any column that does not survive as unit.
            let mut cand = mvi / sigma[i];
            for (j, _) in degenerate.iter().enumerate().take(i) {
                if !degenerate[j] {
                    let uj = u.column(j);
                    cand = cand - uj * uj.dot(&cand);
                }
            }
            let n = cand.norm();
            if n > 0.5 {
                u.set_column(i, cand / n);
            } else {
                degenerate[i] = true;
            }
        }
        // Rebuild any degenerate (near-zero σ) columns to keep U orthonormal.
        // (Only reached for (near-)singular inputs; the stitcher feeds
        // near-rotation matrices, so σ ≈ 1 and this is a no-op there.)
        fixup_orthonormal(&mut u, &degenerate);

        Svd {
            u: Some(u),
            v_t: Some(vmat.transpose()),
            singular_values: Vector3::new(sigma[0], sigma[1], sigma[2]),
        }
    }
}

pub struct Svd {
    pub u: Option<Matrix3<f64>>,
    pub v_t: Option<Matrix3<f64>>,
    #[allow(dead_code)]
    pub singular_values: Vector3<f64>,
}

pub struct SymmetricEigen {
    pub eigenvalues: Vector3<f64>,
    pub eigenvectors: Matrix3<f64>,
}

/// Fill columns flagged as degenerate with an orthonormal completion of the
/// remaining columns, so `U` stays a valid orthogonal matrix.
fn fixup_orthonormal(u: &mut Matrix3<f64>, degenerate: &[bool; 3]) {
    let n_bad = degenerate.iter().filter(|b| **b).count();
    if n_bad == 0 {
        return;
    }
    let good: Vec<usize> = (0..3).filter(|i| !degenerate[*i]).collect();
    let bad: Vec<usize> = (0..3).filter(|i| degenerate[*i]).collect();

    if n_bad == 1 && good.len() == 2 {
        // Third column = ± cross product of the two good ones.
        let c = u.column(good[0]).cross(&u.column(good[1]));
        let n = c.norm();
        u.set_column(bad[0], if n > 1e-12 { c / n } else { basis(bad[0]) });
    } else {
        // Two or three degenerate columns: fall back to filling with the
        // standard basis, then Gram-Schmidt against existing good columns.
        for &bi in &bad {
            let mut e = basis(bi);
            for &gi in &good {
                let g = u.column(gi);
                let proj = g.dot(&e);
                e = e - g * proj;
            }
            let n = e.norm();
            u.set_column(bi, if n > 1e-12 { e / n } else { basis(bi) });
        }
    }
}

fn basis(i: usize) -> Vector3<f64> {
    match i {
        0 => Vector3::new(1.0, 0.0, 0.0),
        1 => Vector3::new(0.0, 1.0, 0.0),
        _ => Vector3::new(0.0, 0.0, 1.0),
    }
}

impl Index<(usize, usize)> for Matrix3<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.d[r * 3 + c]
    }
}

impl IndexMut<(usize, usize)> for Matrix3<f64> {
    #[inline]
    fn index_mut(&mut self, (r, c): (usize, usize)) -> &mut f64 {
        &mut self.d[r * 3 + c]
    }
}

impl Mul<Matrix3<f64>> for Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: Matrix3<f64>) -> Matrix3<f64> {
        let mut out = [0.0f64; 9];
        for r in 0..3 {
            for c in 0..3 {
                let mut s = 0.0;
                for k in 0..3 {
                    s += self.at(r, k) * rhs.at(k, c);
                }
                out[r * 3 + c] = s;
            }
        }
        Matrix3 { d: out, _p: PhantomData }
    }
}

impl Mul<Vector3<f64>> for Matrix3<f64> {
    type Output = Vector3<f64>;
    fn mul(self, v: Vector3<f64>) -> Vector3<f64> {
        Vector3::new(
            self.d[0] * v.x + self.d[1] * v.y + self.d[2] * v.z,
            self.d[3] * v.x + self.d[4] * v.y + self.d[5] * v.z,
            self.d[6] * v.x + self.d[7] * v.y + self.d[8] * v.z,
        )
    }
}

// Reference operand variants (nalgebra implements these too). `Matrix3`/`Vector3`
// are `Copy`, so each just dereferences and delegates to the owned impls.
impl Mul<&Matrix3<f64>> for Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: &Matrix3<f64>) -> Matrix3<f64> {
        self * *rhs
    }
}

impl Mul<Matrix3<f64>> for &Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: Matrix3<f64>) -> Matrix3<f64> {
        *self * rhs
    }
}

impl Mul<&Matrix3<f64>> for &Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: &Matrix3<f64>) -> Matrix3<f64> {
        *self * *rhs
    }
}

impl Mul<Vector3<f64>> for &Matrix3<f64> {
    type Output = Vector3<f64>;
    fn mul(self, v: Vector3<f64>) -> Vector3<f64> {
        *self * v
    }
}

impl AddAssign<Matrix3<f64>> for Matrix3<f64> {
    fn add_assign(&mut self, rhs: Matrix3<f64>) {
        for i in 0..9 {
            self.d[i] += rhs.d[i];
        }
    }
}

impl DivAssign<f64> for Matrix3<f64> {
    fn div_assign(&mut self, s: f64) {
        for i in 0..9 {
            self.d[i] /= s;
        }
    }
}
