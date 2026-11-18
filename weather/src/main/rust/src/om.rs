//! Minimal **synchronous** `.om` v3 reader, replacing the `omfiles` crate.
//!
//! `omfiles` pulled in an async runtime (async-executor/async-lock/futures/…)
//! plus `ndarray` — ~17 crates — even though the Weather map only ever does a
//! synchronous 2D sub-window read. All the actual format parsing and chunk
//! decompression already lives in the vendored C library `om-file-format-sys`
//! (which we build regardless). This module is just the thin Rust driver on top:
//! trailer bootstrap, the variable-tree child walk, and the two-loop decode
//! driver — everything `omfiles` did minus async and minus ndarray.
//!
//! Logic ported faithfully from omfiles' `reader.rs` / `traits.rs::decode` /
//! `variable.rs` / `utils/wrapped_decoder.rs` (rev 8ce3f89), so the on-disk
//! interpretation is identical.

use std::ffi::CStr;
use std::ops::Range;
use std::os::raw::c_void;
use std::sync::Arc;

use om_file_format_sys::{
    om_decoder_decode_chunks, om_decoder_init, om_decoder_init_data_read,
    om_decoder_init_index_read, om_decoder_next_data_read, om_decoder_next_index_read,
    om_decoder_read_buffer_size, om_error_string, om_header_size, om_header_type, om_trailer_read,
    om_trailer_size, om_variable_get_children, om_variable_get_children_count, om_variable_get_name,
    om_variable_get_type, om_variable_init, om_variable_validate, OmDecoder_dataRead_t,
    OmDecoder_indexRead_t, OmDecoder_t, OmError_t, OmHeaderType_t, OmVariable_t,
};

/// Synchronous byte-range source backing a `.om` file (e.g. HTTP Range fetches).
pub trait OmBackend: Send + Sync {
    /// Total file size in bytes.
    fn count(&self) -> usize;
    /// Fetch `count` bytes starting at `offset`.
    fn get_bytes(&self, offset: u64, count: u64) -> Result<Vec<u8>, String>;
}

fn err_string(e: OmError_t) -> String {
    unsafe { CStr::from_ptr(om_error_string(e)).to_string_lossy().into_owned() }
}

/// Owns the metadata bytes of a variable and the C pointer that reads from them.
///
/// `om_variable_init` does **not** copy — the returned pointer reads directly
/// from `_marker`, so `_marker` must live (and not move) as long as `ptr` is
/// used. Keeping both in one struct ties their lifetimes together.
struct OmVariablePtr {
    ptr: *const OmVariable_t,
    _marker: Vec<u8>,
}

// Safety: the C library only reads through `ptr` (thread-safe read-only), and
// `_marker` keeps the backing bytes alive for the pointer's whole lifetime.
unsafe impl Send for OmVariablePtr {}
unsafe impl Sync for OmVariablePtr {}

impl OmVariablePtr {
    fn new(data: Vec<u8>) -> Result<Self, String> {
        let ptr = unsafe { om_variable_init(data.as_ptr() as *const c_void) };
        if ptr.is_null() {
            return Err("not an om file".to_string());
        }
        let err = unsafe { om_variable_validate(ptr as *const c_void, data.len() as u64) };
        if err != OmError_t::ERROR_OK {
            return Err(err_string(err));
        }
        Ok(Self { ptr, _marker: data })
    }
}

fn create_variable<B: OmBackend>(
    backend: &Arc<B>,
    offset: u64,
    size: u64,
) -> Result<OmVariablePtr, String> {
    let data = backend.get_bytes(offset, size)?;
    OmVariablePtr::new(data)
}

/// A reader positioned at one variable in the `.om` hierarchy.
pub struct OmReader<B: OmBackend> {
    backend: Arc<B>,
    var: OmVariablePtr,
}

impl<B: OmBackend> OmReader<B> {
    /// Open a `.om` file: read the 24-byte trailer to find the root variable
    /// (v3), falling back to the legacy header-as-root layout (v1/v2).
    pub fn new(backend: Arc<B>) -> Result<Self, String> {
        let file_size = backend.count();
        let trailer_size = unsafe { om_trailer_size() };

        if file_size >= trailer_size {
            let trailer =
                backend.get_bytes((file_size - trailer_size) as u64, trailer_size as u64)?;
            let mut offset = 0u64;
            let mut size = 0u64;
            let ok = unsafe {
                om_trailer_read(trailer.as_ptr() as *const c_void, &mut offset, &mut size)
            };
            if ok {
                let var = create_variable(&backend, offset, size)?;
                return Ok(Self { backend, var });
            }
            // Not a v3 trailer → fall through to legacy.
        }

        let header_size = unsafe { om_header_size() };
        if file_size < header_size {
            return Err("file too small".to_string());
        }
        let header = backend.get_bytes(0, header_size as u64)?;
        let htype = unsafe { om_header_type(header.as_ptr() as *const c_void) };
        if htype != OmHeaderType_t::OM_HEADER_LEGACY {
            return Err("not an om file".to_string());
        }
        let var = OmVariablePtr::new(header)?;
        Ok(Self { backend, var })
    }

    fn number_of_children(&self) -> u32 {
        unsafe { om_variable_get_children_count(self.var.ptr) }
    }

    fn name(&self) -> String {
        unsafe {
            let mut len = 0u16;
            let p = om_variable_get_name(self.var.ptr, &mut len);
            if p.is_null() || len == 0 {
                return String::new();
            }
            // Not NUL-terminated: use the returned length.
            let bytes = std::slice::from_raw_parts(p as *const u8, len as usize);
            String::from_utf8_lossy(bytes).into_owned()
        }
    }

    /// Linear scan of direct children for one whose name matches exactly.
    pub fn get_child_by_name(&self, name: &str) -> Option<OmReader<B>> {
        for i in 0..self.number_of_children() {
            let mut offset = 0u64;
            let mut size = 0u64;
            let ok =
                unsafe { om_variable_get_children(self.var.ptr, i, 1, &mut offset, &mut size) };
            if !ok {
                continue;
            }
            if let Ok(var) = create_variable(&self.backend, offset, size) {
                let child = OmReader { backend: self.backend.clone(), var };
                if child.name() == name {
                    return Some(child);
                }
            }
        }
        None
    }

    /// Whether this variable is an array (data type in the array range,
    /// INT8_ARRAY..=DOUBLE_ARRAY = 12..=21; mirrors omfiles `OmDataType::is_array`).
    pub fn is_array(&self) -> bool {
        let t = unsafe { om_variable_get_type(self.var.ptr) } as u8;
        (12..=21).contains(&t)
    }

    /// Read an N-dimensional sub-window as row-major `f32`. `ranges[d]` is the
    /// `[start, end)` window in dimension `d`; the output has length
    /// `∏ (end-start)`. The C decoder applies scale_factor/add_offset and yields
    /// NaN for missing data.
    pub fn read_f32(&self, ranges: &[Range<u64>]) -> Result<Vec<f32>, String> {
        // `end - start` below would underflow (panic in debug) on an inverted
        // range; reject it as a normal error instead.
        if ranges.iter().any(|r| r.end < r.start) {
            return Err("invalid range: end < start".to_string());
        }
        let dims = ranges.len() as u64;
        // These Vecs are referenced by pointer inside the decoder for its whole
        // lifetime, so they must outlive the decode loop (dropped explicitly
        // at the end so NLL can't free them early behind the raw pointers).
        let read_offset: Vec<u64> = ranges.iter().map(|r| r.start).collect();
        let read_count: Vec<u64> = ranges.iter().map(|r| r.end - r.start).collect();
        let cube_offset: Vec<u64> = vec![0u64; ranges.len()];
        let cube_dim: Vec<u64> = read_count.clone();
        let total: usize = read_count.iter().map(|&c| c as usize).product();

        let mut decoder: OmDecoder_t = unsafe { std::mem::zeroed() };
        let err = unsafe {
            om_decoder_init(
                &mut decoder,
                self.var.ptr,
                dims,
                read_offset.as_ptr(),
                read_count.as_ptr(),
                cube_offset.as_ptr(),
                cube_dim.as_ptr(),
                512,   // io_size_merge
                65536, // io_size_max
            )
        };
        if err != OmError_t::ERROR_OK {
            return Err(err_string(err));
        }

        let mut out = vec![0f32; total];
        let buf_size = unsafe { om_decoder_read_buffer_size(&decoder) } as usize;
        let mut chunk_buffer = vec![0u8; buf_size];
        let into_ptr = out.as_mut_ptr();

        // Two-loop decode driver (ported from omfiles traits.rs::decode).
        let mut index_read: OmDecoder_indexRead_t = unsafe { std::mem::zeroed() };
        unsafe { om_decoder_init_index_read(&decoder, &mut index_read) };
        let result: Result<(), String> = (|| unsafe {
            while om_decoder_next_index_read(&decoder, &mut index_read) {
                let index_data = self.backend.get_bytes(index_read.offset, index_read.count)?;
                let mut data_read: OmDecoder_dataRead_t = std::mem::zeroed();
                om_decoder_init_data_read(&mut data_read, &index_read);
                let mut error = OmError_t::ERROR_OK;
                while om_decoder_next_data_read(
                    &decoder,
                    &mut data_read,
                    index_data.as_ptr() as *const c_void,
                    index_read.count,
                    &mut error,
                ) {
                    let data_data = self.backend.get_bytes(data_read.offset, data_read.count)?;
                    if !om_decoder_decode_chunks(
                        &decoder,
                        data_read.chunkIndex,
                        data_data.as_ptr() as *const c_void,
                        data_read.count,
                        into_ptr as *mut c_void,
                        chunk_buffer.as_mut_ptr() as *mut c_void,
                        &mut error,
                    ) {
                        return Err(err_string(error));
                    }
                }
                if error != OmError_t::ERROR_OK {
                    return Err(err_string(error));
                }
            }
            Ok(())
        })();

        // Ensure the parameter buffers outlive all decoder use above.
        drop((read_offset, read_count, cube_offset, cube_dim));
        result?;
        Ok(out)
    }
}
