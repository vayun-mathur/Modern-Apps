//! Dense dynamic matrices/vectors: `DMatrix` / `DVector` with the thin SVD
//! (DLT null-space solve) and partial-pivot LU solve.
use std::marker::PhantomData;
use std::ops::{AddAssign, Index, IndexMut, Neg};

use super::jacobi::jacobi_sym;

// ===========================================================================
// Return structs for svd / symmetric_eigen
// ===========================================================================

/// Result of `DMatrix::svd`. Only `v_t` is populated (see `DMatrix::svd`).
pub struct DSvd {
    #[allow(dead_code)]
    pub u: Option<DMatrix<f64>>,
    pub v_t: Option<DMatrix<f64>>,
}

// ===========================================================================
// DMatrix / DVector (dense, dynamic) + partial-pivot LU solve
// ===========================================================================

#[derive(Clone, Debug)]
pub struct DMatrix<T = f64> {
    rows: usize,
    cols: usize,
    d: Vec<f64>,
    _p: PhantomData<T>,
}

impl DMatrix<f64> {
    pub fn zeros(rows: usize, cols: usize) -> Self {
        DMatrix { rows, cols, d: vec![0.0; rows * cols], _p: PhantomData }
    }

    pub fn nrows(&self) -> usize {
        self.rows
    }

    /// Row `r` as an owned `Vec<f64>` (indexable like a `nalgebra` row view).
    pub fn row(&self, r: usize) -> Vec<f64> {
        self.d[r * self.cols..(r + 1) * self.cols].to_vec()
    }

    /// Thin SVD sufficient for the DLT null-space solve: only `v_t` is produced,
    /// with rows ordered by **descending** singular value (so the last row is
    /// the smallest singular vector — the homography null space). Computed from
    /// the symmetric eigen of `AᵀA`; the DLT points are Hartley-normalized
    /// beforehand, so `AᵀA` is well-conditioned. `u` is not needed by any caller.
    pub fn svd(&self, _compute_u: bool, _compute_v: bool) -> DSvd {
        let (m, n) = (self.rows, self.cols);
        // AᵀA (n×n, symmetric).
        let mut ata = vec![0.0f64; n * n];
        for i in 0..n {
            for j in i..n {
                let mut s = 0.0;
                for k in 0..m {
                    s += self.d[k * n + i] * self.d[k * n + j];
                }
                ata[i * n + j] = s;
                ata[j * n + i] = s;
            }
        }
        let (evals, evecs) = jacobi_sym(&ata, n);
        // Order eigenpairs by descending eigenvalue (== descending singular value).
        let mut order: Vec<usize> = (0..n).collect();
        order.sort_by(|&a, &b| evals[b].total_cmp(&evals[a]));

        // v_t: row `rank` = eigenvector for the rank-th largest eigenvalue.
        let mut vt = DMatrix::zeros(n, n);
        for (rank, &ei) in order.iter().enumerate() {
            for comp in 0..n {
                vt.d[rank * n + comp] = evecs[comp * n + ei];
            }
        }
        DSvd { u: None, v_t: Some(vt) }
    }

    /// Partial-pivot LU factorization (square matrices).
    pub fn lu(&self) -> Lu {
        let n = self.rows;
        debug_assert_eq!(self.rows, self.cols, "lu() requires a square matrix");
        let mut a = self.d.clone();
        let mut piv: Vec<usize> = (0..n).collect();
        let mut singular = false;

        for k in 0..n {
            // Choose the largest-magnitude pivot in column k.
            let mut p = k;
            let mut max = a[k * n + k].abs();
            for i in (k + 1)..n {
                let v = a[i * n + k].abs();
                if v > max {
                    max = v;
                    p = i;
                }
            }
            if max == 0.0 {
                singular = true;
                break;
            }
            if p != k {
                for j in 0..n {
                    a.swap(p * n + j, k * n + j);
                }
                piv.swap(p, k);
            }
            let akk = a[k * n + k];
            for i in (k + 1)..n {
                let f = a[i * n + k] / akk;
                a[i * n + k] = f;
                for j in (k + 1)..n {
                    a[i * n + j] -= f * a[k * n + j];
                }
            }
        }

        Lu { lu: a, n, piv, singular }
    }
}

impl Index<(usize, usize)> for DMatrix<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.d[r * self.cols + c]
    }
}

impl IndexMut<(usize, usize)> for DMatrix<f64> {
    #[inline]
    fn index_mut(&mut self, (r, c): (usize, usize)) -> &mut f64 {
        &mut self.d[r * self.cols + c]
    }
}

impl AddAssign<DMatrix<f64>> for DMatrix<f64> {
    fn add_assign(&mut self, rhs: DMatrix<f64>) {
        debug_assert_eq!(self.d.len(), rhs.d.len());
        for i in 0..self.d.len() {
            self.d[i] += rhs.d[i];
        }
    }
}

#[derive(Clone, Debug)]
pub struct DVector<T = f64> {
    d: Vec<f64>,
    _p: PhantomData<T>,
}

impl DVector<f64> {
    pub fn zeros(n: usize) -> Self {
        DVector { d: vec![0.0; n], _p: PhantomData }
    }
}

impl Index<usize> for DVector<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, i: usize) -> &f64 {
        &self.d[i]
    }
}

impl IndexMut<usize> for DVector<f64> {
    #[inline]
    fn index_mut(&mut self, i: usize) -> &mut f64 {
        &mut self.d[i]
    }
}

impl AddAssign<DVector<f64>> for DVector<f64> {
    fn add_assign(&mut self, rhs: DVector<f64>) {
        debug_assert_eq!(self.d.len(), rhs.d.len());
        for i in 0..self.d.len() {
            self.d[i] += rhs.d[i];
        }
    }
}

impl Neg for &DVector<f64> {
    type Output = DVector<f64>;
    fn neg(self) -> DVector<f64> {
        DVector { d: self.d.iter().map(|v| -v).collect(), _p: PhantomData }
    }
}

/// LU factors with the row permutation, ready to solve `A x = b`.
pub struct Lu {
    lu: Vec<f64>, // combined L (unit diag, below) and U (on/above diag), n×n
    n: usize,
    piv: Vec<usize>,
    singular: bool,
}

impl Lu {
    /// Solve `A x = b`; `None` if the matrix was singular.
    pub fn solve(&self, b: &DVector<f64>) -> Option<DVector<f64>> {
        if self.singular {
            return None;
        }
        let n = self.n;
        // Apply the row permutation to the RHS.
        let mut x = vec![0.0f64; n];
        for (i, xi) in x.iter_mut().enumerate() {
            *xi = b.d[self.piv[i]];
        }
        // Forward substitution (unit-lower L).
        for i in 0..n {
            let mut s = x[i];
            for (j, xj) in x.iter().take(i).enumerate() {
                s -= self.lu[i * n + j] * xj;
            }
            x[i] = s;
        }
        // Back substitution (upper U).
        for i in (0..n).rev() {
            let mut s = x[i];
            for (j, xj) in x.iter().enumerate().skip(i + 1) {
                s -= self.lu[i * n + j] * xj;
            }
            let d = self.lu[i * n + i];
            if d == 0.0 {
                return None;
            }
            x[i] = s / d;
        }
        Some(DVector { d: x, _p: PhantomData })
    }
}

#[cfg(test)] #[path = "dense_tests.rs"] mod dense_tests;
