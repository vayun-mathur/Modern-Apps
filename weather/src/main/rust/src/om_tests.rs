//! Tests for `om::OmReader` over synthetic in-memory `.om` files.
use crate::om::{OmBackend, OmReader};
use om_file_format_sys::*;
use std::ffi::c_void;
use std::ops::Range;
use std::sync::Arc;

fn reader(data: Vec<u8>) -> OmReader<MemBackend> {
    OmReader::new(MemBackend::new(data)).expect("open synthetic om")
}
fn sample() -> Vec<u8> {
    build_om(&[
        array_var("temperature_2m", vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], [2, 3]),
        array_var("wind_u_component_10m", vec![3.0, 0.0, 6.0, 0.0], [2, 2]),
        array_var("wind_v_component_10m", vec![4.0, 0.0, 8.0, 0.0], [2, 2]),
        TestVar { name: "scale", spec: VarSpec::ScalarF32(2.5) },
    ])
}

#[test]
fn open_and_find_children() {
    let r = reader(sample());
    assert!(r.get_child_by_name("temperature_2m").is_some());
    assert!(r.get_child_by_name("wind_u_component_10m").is_some());
    assert!(r.get_child_by_name("scale").is_some());
    assert!(r.get_child_by_name("missing").is_none());
    assert!(r.get_child_by_name("").is_none());
    assert!(r.get_child_by_name("temperature").is_none());
    assert!(r.get_child_by_name("temperature_2m ").is_none());
}
#[test]
fn is_array_distinguishes_array_and_scalar() {
    let r = reader(sample());
    assert!(r.get_child_by_name("temperature_2m").unwrap().is_array());
    assert!(!r.get_child_by_name("scale").unwrap().is_array());
    assert!(!r.is_array()); // root is a NONE scalar holding children
}
#[test]
fn read_f32_round_trip_full_and_subwindow() {
    let r = reader(sample());
    let t = r.get_child_by_name("temperature_2m").unwrap();
    let full = t.read_f32(&[0..2, 0..3]).unwrap();
    assert_eq!(full, vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);
    assert_eq!(t.read_f32(&[0..1, 1..3]).unwrap(), vec![2.0, 3.0]);
    assert_eq!(t.read_f32(&[1..2, 0..2]).unwrap(), vec![4.0, 5.0]);
    assert_eq!(t.read_f32(&[1..2, 2..3]).unwrap(), vec![6.0]);
    assert_eq!(t.read_f32(&[0..2, 0..1]).unwrap(), vec![1.0, 4.0]);
}
#[test]
fn read_f32_exact_values_other_var() {
    let r = reader(sample());
    let u = r.get_child_by_name("wind_u_component_10m").unwrap();
    assert_eq!(u.read_f32(&[0..2, 0..2]).unwrap(), vec![3.0, 0.0, 6.0, 0.0]);
}
#[test]
fn read_f32_on_scalar_is_err() {
    let r = reader(sample());
    let s = r.get_child_by_name("scale").unwrap();
    assert!(s.read_f32(&[0..1]).is_err());
}
#[test]
fn read_f32_out_of_bounds_is_err() {
    let r = reader(sample());
    let t = r.get_child_by_name("temperature_2m").unwrap();
    assert!(t.read_f32(&[0..3, 0..3]).is_err()); // y past dim 2
    assert!(t.read_f32(&[0..2, 0..4]).is_err()); // x past dim 3
    assert!(t.read_f32(&[2..3, 0..1]).is_err()); // offset at end
    assert!(t.read_f32(&[0..2]).is_err()); // wrong dimension count
    assert!(t.read_f32(&[0..2, 0..3, 0..1]).is_err()); // too many dims
}
#[test]
fn read_f32_empty_range_yields_empty() {
    // Zero-count read in one dimension: C decoder validates count<=dim and
    // offset<dim, so 0..0 should produce an empty vector without error.
    let r = reader(sample());
    let t = r.get_child_by_name("temperature_2m").unwrap();
    let out = t.read_f32(&[0..0, 0..3]);
    assert!(out.is_ok(), "{out:?}");
    assert_eq!(out.unwrap().len(), 0);
}
#[test]
fn open_empty_and_tiny_files_is_err() {
    assert!(OmReader::new(MemBackend::new(vec![])).is_err());
    assert!(OmReader::new(MemBackend::new(vec![0u8; 1])).is_err());
    assert!(OmReader::new(MemBackend::new(vec![0u8; 10])).is_err());
    assert!(OmReader::new(MemBackend::new(vec![0u8; 23])).is_err());
}
#[test]
fn open_garbage_is_err() {
    assert!(OmReader::new(MemBackend::new(vec![0xFFu8; 100])).is_err());
    assert!(OmReader::new(MemBackend::new(b"not an om file at all........".to_vec())).is_err());
    let mut g = vec![0u8; 200];
    g[0] = b'O'; g[1] = b'M'; g[2] = 3; // v3 magic but no valid trailer/root
    assert!(OmReader::new(MemBackend::new(g)).is_err());
}
#[test]
fn open_truncated_synthetic_is_err() {
    let full = sample();
    for cut in [full.len() - 1, full.len() - 10, full.len() / 2, 30, 24] {
        assert!(
            OmReader::new(MemBackend::new(full[..cut].to_vec())).is_err(),
            "cut {cut} must fail"
        );
    }
}
#[test]
fn open_corrupt_trailer_points_elsewhere_is_err() {
    let mut f = sample();
    let n = f.len();
    // Corrupt trailer root offset (last 24 bytes: offset at +8, size at +16).
    f[n - 16..n - 8].copy_from_slice(&999_999u64.to_le_bytes());
    assert!(OmReader::new(MemBackend::new(f)).is_err());
}
#[test]
fn single_child_file_round_trip() {
    let data = build_om(&[array_var("x", vec![42.0], [1, 1])]);
    let r = reader(data);
    let x = r.get_child_by_name("x").unwrap();
    assert!(x.is_array());
    assert_eq!(x.read_f32(&[0..1, 0..1]).unwrap(), vec![42.0]);
}
#[test]
fn larger_grid_round_trip_with_negative_and_fractional() {
    let vals: Vec<f32> = (0..20).map(|i| i as f32 * 1.5 - 7.25).collect();
    let data = build_om(&[array_var("g", vals.clone(), [4, 5])]);
    let r = reader(data);
    let g = r.get_child_by_name("g").unwrap();
    assert_eq!(g.read_f32(&[0..4, 0..5]).unwrap(), vals);
    assert_eq!(g.read_f32(&[2..4, 3..5]).unwrap(), vec![vals[13], vals[14], vals[18], vals[19]]);
}
#[test]
fn nan_values_round_trip() {
    let data = build_om(&[array_var("n", vec![1.0, f32::NAN, 3.0, 4.0], [2, 2])]);
    let r = reader(data);
    let out = r.get_child_by_name("n").unwrap().read_f32(&[0..2, 0..2]).unwrap();
    assert_eq!(out[0], 1.0);
    assert!(out[1].is_nan());
    assert_eq!(out[2..], [3.0, 4.0]);
}
#[test]
fn read_f32_inverted_range_is_err_not_panic() {
    let r = reader(sample());
    let t = r.get_child_by_name("temperature_2m").unwrap();
    assert!(t.read_f32(&[1..0, 0..3]).is_err());
    assert!(t.read_f32(&[0..2, 3..1]).is_err());
}
#[test]
fn mem_backend_bounds() {
    let b = MemBackend::new(vec![10, 20, 30]);
    assert_eq!(b.count(), 3);
    assert_eq!(b.get_bytes(1, 2).unwrap(), vec![20, 30]);
    assert_eq!(b.get_bytes(0, 0).unwrap(), Vec::<u8>::new());
    assert!(b.get_bytes(3, 1).is_err());
    assert!(b.get_bytes(2, 2).is_err());
    assert!(b.get_bytes(100, 1).is_err());
    let _: Arc<MemBackend> = b;
}

// Ranges helper kept referenced for documentation of read windows.
#[allow(dead_code)]
fn _range(r: Range<u64>) -> Range<u64> { r }

// ---- synthetic builder (shared with lib_tests via crate::om_tests) ----

#[derive(Clone)]
pub struct MemBackend {
    pub data: Arc<Vec<u8>>,
}
impl MemBackend {
    pub fn new(data: Vec<u8>) -> Arc<Self> {
        Arc::new(Self { data: Arc::new(data) })
    }
}
impl OmBackend for MemBackend {
    fn count(&self) -> usize {
        self.data.len()
    }
    fn get_bytes(&self, offset: u64, count: u64) -> Result<Vec<u8>, String> {
        if count == 0 {
            return Ok(Vec::new());
        }
        let (o, c) = (offset as usize, count as usize);
        if o >= self.data.len() || o + c > self.data.len() {
            return Err("out of bounds".to_string());
        }
        Ok(self.data[o..o + c].to_vec())
    }
}

pub enum VarSpec {
    Array { values: Vec<f32>, dims: [u64; 2] },
    ScalarF32(f32),
}

pub struct TestVar {
    pub name: &'static str,
    pub spec: VarSpec,
}

struct Prepared {
    name: &'static str,
    child_size: usize,
    data_offset: u64,
    lut_offset: u64,
    lut_size: u64,
    lut_bytes: Vec<u8>,
    compressed: Vec<u8>,
    dims: [u64; 2],
    is_array: bool,
    scalar: f32,
}

fn compress_array(values: &[f32], dims: [u64; 2]) -> Vec<u8> {
    unsafe {
        let chunks = dims;
        let mut enc: OmEncoder_t = std::mem::zeroed();
        let err = om_encoder_init(
            &mut enc, 1.0, 0.0,
            OmCompression_t::COMPRESSION_FPX_XOR2D,
            OmDataType_t::DATA_TYPE_FLOAT_ARRAY,
            dims.as_ptr(), chunks.as_ptr(), 2,
        );
        assert_eq!(err, OmError_t::ERROR_OK, "encoder init");
        let mut chunk_buf = vec![0u8; om_encoder_chunk_buffer_size(&enc) as usize];
        let mut out = vec![0u8; om_encoder_compressed_chunk_buffer_size(&enc) as usize];
        let off = [0u64, 0];
        let len = om_encoder_compress_chunk(
            &enc, values.as_ptr() as *const c_void, dims.as_ptr(), off.as_ptr(), dims.as_ptr(),
            0, 0, out.as_mut_ptr(), chunk_buf.as_mut_ptr(),
        );
        out.truncate(len as usize);
        out
    }
}

fn compress_lut(lut: &[u64; 2]) -> (Vec<u8>, u64) {
    unsafe {
        let buf_size = om_encoder_lut_buffer_size(lut.as_ptr(), 2);
        let mut out = vec![0u8; buf_size as usize];
        let size = om_encoder_compress_lut(lut.as_ptr(), 2, out.as_mut_ptr(), buf_size);
        out.truncate(size as usize);
        (out, size)
    }
}

/// Build a minimal valid v3 file: header, per-array data+LUT, variable
/// metadata, root scalar (DATA_TYPE_NONE) with all vars as children, trailer.
pub fn build_om(vars: &[TestVar]) -> Vec<u8> {
    let mut prepared: Vec<Prepared> = Vec::new();
    let mut cursor: u64 = 3; // OmHeaderV3
    for v in vars {
        match &v.spec {
            VarSpec::Array { values, dims } => {
                assert_eq!(values.len() as u64, dims[0] * dims[1]);
                let compressed = compress_array(values, *dims);
                let data_offset = cursor;
                cursor += compressed.len() as u64;
                let lut = [data_offset, data_offset + compressed.len() as u64];
                let (lut_bytes, lut_size) = compress_lut(&lut);
                let lut_offset = cursor;
                cursor += lut_size;
                let child_size = unsafe {
                    om_variable_write_numeric_array_size(v.name.len() as u16, 0, 2)
                };
                prepared.push(Prepared {
                    name: v.name, child_size, data_offset, lut_offset, lut_size,
                    lut_bytes, compressed, dims: *dims, is_array: true, scalar: 0.0,
                });
                cursor += child_size as u64;
            }
            VarSpec::ScalarF32(val) => {
                let child_size = unsafe {
                    om_variable_write_scalar_size(
                        v.name.len() as u16, 0, OmDataType_t::DATA_TYPE_FLOAT, 0,
                    )
                };
                prepared.push(Prepared {
                    name: v.name, child_size, data_offset: 0, lut_offset: 0, lut_size: 0,
                    lut_bytes: Vec::new(), compressed: Vec::new(), dims: [0, 0],
                    is_array: false, scalar: *val,
                });
                cursor += child_size as u64;
            }
        }
    }
    // Child offsets are assigned in a second pass because the loop above
    // interleaved data and metadata; recompute metadata offsets: metadata
    // blocks are contiguous after all data/LUT blocks in `prepared` order is
    // NOT what cursor tracked. Instead compute them from cursor walk again:
    // Our cursor walk placed each child's metadata right after its own
    // data/LUT, so track offsets during that walk.
    // (Recompute by replaying:)
    let mut child_offsets: Vec<u64> = Vec::new();
    {
        let mut c: u64 = 3;
        for p in &prepared {
            if p.is_array {
                c += p.compressed.len() as u64 + p.lut_size;
            }
            child_offsets.push(c);
            c += p.child_size as u64;
        }
    }
    let root_offset = cursor;
    let root_size = unsafe {
        om_variable_write_scalar_size(0, vars.len() as u32, OmDataType_t::DATA_TYPE_NONE, 0)
    };
    let total = root_offset as usize + root_size + 24;
    let mut file = vec![0u8; total];
    unsafe {
        om_header_write(file.as_mut_ptr() as *mut c_void);
        for (p, &child_off) in prepared.iter().zip(&child_offsets) {
            if p.is_array {
                let d = p.data_offset as usize;
                file[d..d + p.compressed.len()].copy_from_slice(&p.compressed);
                let l = p.lut_offset as usize;
                file[l..l + p.lut_bytes.len()].copy_from_slice(&p.lut_bytes);
                om_variable_write_numeric_array(
                    file[child_off as usize..].as_mut_ptr() as *mut c_void,
                    p.name.len() as u16, 0, std::ptr::null(), std::ptr::null(),
                    p.name.as_ptr() as *const std::os::raw::c_char,
                    OmDataType_t::DATA_TYPE_FLOAT_ARRAY,
                    OmCompression_t::COMPRESSION_FPX_XOR2D,
                    1.0, 0.0, 2, p.dims.as_ptr(), p.dims.as_ptr(),
                    p.lut_size, p.lut_offset,
                );
            } else {
                om_variable_write_scalar(
                    file[child_off as usize..].as_mut_ptr() as *mut c_void,
                    p.name.len() as u16, 0, std::ptr::null(), std::ptr::null(),
                    p.name.as_ptr() as *const std::os::raw::c_char,
                    OmDataType_t::DATA_TYPE_FLOAT,
                    &p.scalar as *const f32 as *const c_void, 0,
                );
            }
        }
        let child_sizes: Vec<u64> = prepared.iter().map(|p| p.child_size as u64).collect();
        om_variable_write_scalar(
            file[root_offset as usize..].as_mut_ptr() as *mut c_void,
            0, vars.len() as u32, child_offsets.as_ptr(), child_sizes.as_ptr(),
            std::ptr::null(), OmDataType_t::DATA_TYPE_NONE, std::ptr::null(), 0,
        );
        om_trailer_write(
            file[root_offset as usize + root_size..].as_mut_ptr() as *mut c_void,
            root_offset, root_size as u64,
        );
    }
    file
}

pub fn array_var(name: &'static str, values: Vec<f32>, dims: [u64; 2]) -> TestVar {
    TestVar { name, spec: VarSpec::Array { values, dims } }
}

#[test]
fn legacy_v1_header_opens_as_root_array() {
    // 40-byte legacy header + padding; trailer (last 24 bytes) is not v3,
    // so OmReader must fall back to the header-as-root layout.
    let mut f = vec![0u8; 100];
    f[0] = b'O'; f[1] = b'M'; f[2] = 1; f[3] = 0;
    f[4..8].copy_from_slice(&1.0f32.to_le_bytes());
    f[8..16].copy_from_slice(&2u64.to_le_bytes());
    f[16..24].copy_from_slice(&2u64.to_le_bytes());
    f[24..32].copy_from_slice(&2u64.to_le_bytes());
    f[32..40].copy_from_slice(&2u64.to_le_bytes());
    let r = OmReader::new(MemBackend::new(f)).expect("legacy open");
    assert!(r.is_array());
    assert!(r.get_child_by_name("anything").is_none());
}
#[test]
fn legacy_header_with_bad_dims_is_err() {
    let mut f = vec![0u8; 100];
    f[0] = b'O'; f[1] = b'M'; f[2] = 2; f[3] = 0;
    f[4..8].copy_from_slice(&1.0f32.to_le_bytes());
    // dim0 = 0 -> validate must reject.
    let r = OmReader::new(MemBackend::new(f));
    assert!(r.is_err());
}
#[test]
fn file_between_trailer_and_header_size_is_too_small() {
    // 30 bytes: no valid trailer, and < 40-byte header.
    assert!(OmReader::new(MemBackend::new(vec![0u8; 30])).is_err());
    // 40+ bytes with an invalid (non-OM) header is "not an om file".
    assert!(OmReader::new(MemBackend::new(vec![7u8; 64])).is_err());
}

#[test]
fn empty_named_child_is_findable_by_empty_name() {
    let data = build_om(&[array_var("", vec![5.0], [1, 1])]);
    let r = reader(data);
    let v = r.get_child_by_name("").expect("empty-named child");
    assert!(v.is_array());
    assert_eq!(v.read_f32(&[0..1, 0..1]).unwrap(), vec![5.0]);
}
