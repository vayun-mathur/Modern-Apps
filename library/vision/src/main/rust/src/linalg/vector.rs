//! `Vector3` / `RowVector3` fixed 3-vectors plus the operator impls and the
//! outer-product (`Vector3 * RowVector3 -> Matrix3`).
use std::marker::PhantomData;
use std::ops::{AddAssign, Div, DivAssign, Index, IndexMut, Mul, Neg};

use super::matrix3::Matrix3;

// ===========================================================================
// Vector3 / RowVector3
// ===========================================================================

#[derive(Clone, Copy, Debug)]
pub struct Vector3<T = f64> {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    _p: PhantomData<T>,
}

impl Vector3<f64> {
    pub fn new(x: f64, y: f64, z: f64) -> Self {
        Vector3 { x, y, z, _p: PhantomData }
    }

    pub fn zeros() -> Self {
        Vector3::new(0.0, 0.0, 0.0)
    }

    /// Present for parity with `nalgebra` column-view `.into_owned()`; our
    /// `Matrix3::column` already returns an owned vector, so this is identity.
    pub fn into_owned(self) -> Self {
        self
    }

    pub fn norm(&self) -> f64 {
        (self.x * self.x + self.y * self.y + self.z * self.z).sqrt()
    }

    pub fn dot(&self, o: &Vector3<f64>) -> f64 {
        self.x * o.x + self.y * o.y + self.z * o.z
    }

    pub fn cross(&self, o: &Vector3<f64>) -> Vector3<f64> {
        Vector3::new(
            self.y * o.z - self.z * o.y,
            self.z * o.x - self.x * o.z,
            self.x * o.y - self.y * o.x,
        )
    }

    pub fn transpose(&self) -> RowVector3<f64> {
        RowVector3 { d: [self.x, self.y, self.z], _p: PhantomData }
    }
}

impl Index<usize> for Vector3<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, i: usize) -> &f64 {
        match i {
            0 => &self.x,
            1 => &self.y,
            2 => &self.z,
            _ => panic!("Vector3 index out of range: {i}"),
        }
    }
}

impl IndexMut<usize> for Vector3<f64> {
    #[inline]
    fn index_mut(&mut self, i: usize) -> &mut f64 {
        match i {
            0 => &mut self.x,
            1 => &mut self.y,
            2 => &mut self.z,
            _ => panic!("Vector3 index out of range: {i}"),
        }
    }
}

impl Div<f64> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn div(self, s: f64) -> Vector3<f64> {
        Vector3::new(self.x / s, self.y / s, self.z / s)
    }
}

impl DivAssign<f64> for Vector3<f64> {
    fn div_assign(&mut self, s: f64) {
        self.x /= s;
        self.y /= s;
        self.z /= s;
    }
}

impl Mul<f64> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn mul(self, s: f64) -> Vector3<f64> {
        Vector3::new(self.x * s, self.y * s, self.z * s)
    }
}

impl std::ops::Add<Vector3<f64>> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn add(self, o: Vector3<f64>) -> Vector3<f64> {
        Vector3::new(self.x + o.x, self.y + o.y, self.z + o.z)
    }
}

impl std::ops::Sub<Vector3<f64>> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn sub(self, o: Vector3<f64>) -> Vector3<f64> {
        Vector3::new(self.x - o.x, self.y - o.y, self.z - o.z)
    }
}

impl Neg for Vector3<f64> {
    type Output = Vector3<f64>;
    fn neg(self) -> Vector3<f64> {
        Vector3::new(-self.x, -self.y, -self.z)
    }
}

impl AddAssign<Vector3<f64>> for Vector3<f64> {
    fn add_assign(&mut self, o: Vector3<f64>) {
        self.x += o.x;
        self.y += o.y;
        self.z += o.z;
    }
}

/// Outer product `v * vᵀ` → 3×3 matrix.
impl Mul<RowVector3<f64>> for Vector3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, row: RowVector3<f64>) -> Matrix3<f64> {
        let (a, b, c) = (self.x, self.y, self.z);
        let (p, q, r) = (row.d[0], row.d[1], row.d[2]);
        Matrix3::new(a * p, a * q, a * r, b * p, b * q, b * r, c * p, c * q, c * r)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct RowVector3<T = f64> {
    pub(crate) d: [f64; 3],
    _p: PhantomData<T>,
}

#[cfg(test)] #[path = "vector_tests.rs"] mod vector_tests;
