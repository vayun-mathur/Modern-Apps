// Copyright 2023 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Provides an [`ArrayVecOption`] wrapper implementation over [`ArrayVec`],
//! which stores all the elements in an `Option` in order to satisfy
//! `ArrayVec`'s requirement that the elements must implement `Default`.

use tinyvec::{ArrayVec, ArrayVecIterator};
#[cfg(any(test, feature = "alloc"))]
extern crate alloc;
#[cfg(any(test, feature = "alloc"))]
use alloc::vec::Vec;

/// A wrapper of [`ArrayVec`] that stores it values as [`Option`], in order to
/// satisfy `ArrayVec`'s requirement that the elements must implement `Default`.
/// The implementation guarantees that any items in the wrapped `ArrayVec`
/// within `0..len` is `Some`, and therefore will not panic when unwrapped.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArrayVecOption<A, const N: usize>(ArrayVec<[Option<A>; N]>);

// Cannot derive due to https://github.com/rust-lang/rust/issues/74462
impl<A, const N: usize> Default for ArrayVecOption<A, N> {
    fn default() -> Self {
        Self(Default::default())
    }
}

/// Iterator type returned by `ArrayVecOption.iter()`, which can be used as
/// `impl Iterator<Item = &A>`
pub type ArrayVecOptionRefIter<'a, A> =
    core::iter::Map<core::slice::Iter<'a, Option<A>>, fn(&'a Option<A>) -> &'a A>;

/// Iterator type returned by `ArrayVecOption.into_iter()`, which can be used as
/// `impl Iterator<Item = A>`
pub type ArrayVecOptionIntoIter<A, const N: usize> =
    core::iter::Map<ArrayVecIterator<[Option<A>; N]>, fn(Option<A>) -> A>;

impl<A, const N: usize> ArrayVecOption<A, N> {
    /// Returns an iterator over this vec.
    pub fn iter(&self) -> ArrayVecOptionRefIter<'_, A> {
        self.0
            .iter()
            .map(|v| v.as_ref().expect("ArrayVecOption value before .len() should not be None"))
    }

    /// The length of the vec (in elements).
    pub fn len(&self) -> usize {
        self.0.len()
    }

    /// Checks if the length is 0.
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Returns the first element of the vec, or `None` if it is empty.
    pub fn first(&self) -> Option<&A> {
        self.iter().next()
    }

    /// Place an element onto the end of the vec.
    ///
    /// # Panics
    /// * If the length of the vec would overflow the capacity.
    pub fn push(&mut self, value: A) {
        self.0.push(Some(value))
    }

    /// Tries to place an element onto the end of the vec.
    /// Returns back the element if the capacity is exhausted,
    /// otherwise returns None.
    pub fn try_push(&mut self, value: A) -> Option<A> {
        self.0.try_push(Some(value)).unwrap_or_else(|| None)
    }

    /// Returns a reference to an element at the given index.
    pub fn get(&self, index: usize) -> Option<&A> {
        self.0.get(index).and_then(|opt| opt.as_ref())
    }

    /// Sorts the slice with a key extraction function, but might not preserve
    /// the order of equal elements.
    ///
    /// This sort is unstable (i.e., may reorder equal elements), in-place
    /// (i.e., does not allocate), and *O*(*m* \* *n* \* log(*n*)) worst-case,
    /// where the key function is *O*(*m*).
    pub fn sort_unstable_by_key<K: Ord>(&mut self, f: impl Fn(&A) -> K) {
        self.0.sort_unstable_by_key(|a| {
            f(a.as_ref().expect("Iterated values in ArrayVecOption should never be None"))
        })
    }

    /// Remove an element, swapping the end of the vec into its place.
    pub fn swap_remove(&mut self, index: usize) -> A {
        self.0.swap_remove(index).expect("since index is is bounds, the value at index will always be Some which is safe to unwrap")
    }

    /// Converts this vector into a regular `Vec`, unwrapping all of the
    /// `Option` in the process.
    #[cfg(any(test, feature = "alloc"))]
    pub fn into_vec(self) -> Vec<A> {
        self.into_iter().collect()
    }
}

impl<A, const N: usize> IntoIterator for ArrayVecOption<A, N> {
    type Item = A;
    type IntoIter = ArrayVecOptionIntoIter<A, N>;
    fn into_iter(self) -> Self::IntoIter {
        self.0
            .into_iter()
            .map(|v| v.expect("ArrayVecOption value before .len() should not be None"))
    }
}

// Implement `FromIterator` to enable `iter.collect::<ArrayVecOption<_>>()`
impl<A, const N: usize> FromIterator<A> for ArrayVecOption<A, N> {
    fn from_iter<T: IntoIterator<Item = A>>(iter: T) -> Self {
        Self(iter.into_iter().map(Some).collect())
    }
}

impl<A, const N: usize> core::ops::Index<usize> for ArrayVecOption<A, N> {
    type Output = A;
    fn index(&self, index: usize) -> &Self::Output {
        self.0[index].as_ref().expect("This panics if provided index is out of bounds")
    }
}

impl<A, const N: usize> core::ops::IndexMut<usize> for ArrayVecOption<A, N> {
    fn index_mut(&mut self, index: usize) -> &mut Self::Output {
        self.0[index].as_mut().expect("This panics if provided index is out of bounds")
    }
}

#[cfg(test)]
mod test {
    extern crate std;
    use super::ArrayVecOption;
    use std::vec;

    #[test]
    fn test_default_array_vec() {
        let vec = ArrayVecOption::<u32, 5>::default();
        assert_eq!(0, vec.len());
        assert_eq!(None, vec.iter().next());
        assert!(vec.is_empty());
        assert_eq!(None, vec.first());
        assert_eq!(None, vec.get(0));
        assert_eq!(vec![0_u32; 0], vec.into_vec());
    }

    #[test]
    fn test_array_vec_with_contents() {
        let mut vec = ArrayVecOption::<u32, 5>::default();
        vec.push(1);
        vec.push(2);
        vec.push(3);
        vec.push(4);
        vec.push(5);
        assert_eq!(5, vec.len());
        let mut iter = vec.iter();
        assert_eq!(Some(&1_u32), iter.next());
        assert_eq!(Some(&2_u32), iter.next());
        assert_eq!(Some(&3_u32), iter.next());
        assert_eq!(Some(&4_u32), iter.next());
        assert_eq!(Some(&5_u32), iter.next());
        assert_eq!(None, iter.next());
        assert!(!vec.is_empty());
        assert_eq!(Some(&1_u32), vec.first());
        assert_eq!(Some(&5_u32), vec.get(4));
        assert_eq!(vec![1_u32, 2, 3, 4, 5], vec.clone().into_vec());

        let _ = vec.swap_remove(2);
        assert_eq!(vec![1_u32, 2, 5, 4], vec.clone().into_vec());
    }

    #[test]
    #[should_panic]
    fn test_array_vec_push_overflow() {
        let mut vec = ArrayVecOption::<u32, 5>::default();
        vec.push(1);
        vec.push(2);
        vec.push(3);
        vec.push(4);
        vec.push(5);
        vec.push(6);
    }

    #[test]
    fn test_sort() {
        let mut vec = ArrayVecOption::<u32, 5>::default();
        vec.push(3);
        vec.push(1);
        vec.push(4);
        vec.push(1);
        vec.push(2);
        vec.sort_unstable_by_key(|k| *k);
        assert_eq!(vec![1_u32, 1, 2, 3, 4], vec.clone().into_vec());
    }

    #[test]
    fn test_collect() {
        let vec: ArrayVecOption<u32, 5> = [5_u32, 4, 3, 2, 1].into_iter().collect();
        assert_eq!(vec![5_u32, 4, 3, 2, 1], vec.clone().into_vec());
    }
}
