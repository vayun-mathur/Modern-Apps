//! Zero-copy Y-plane luma statistics: the native half of the contract in
//! Kotlin's `LumaPlaneMath`. The JNI entry takes a *direct* `ByteBuffer`
//! (as delivered by `Image` planes / `ImageReader`) and reads it in place —
//! no byte-array copy crosses JNI. The Kotlin fallback computes the same
//! statistics from any buffer; tests prove the two agree on synthetic
//! planes, padded strides included.
//!
//! Mean: samples every 16th pixel in raster order, summed as integers, so
//! both sides agree exactly before the final division. Grid: at most 96
//! samples per side, every `strideX`/`strideY`-th pixel.

/// Pixels between mean samples, in raster order.
pub const LUMA_SAMPLE_STEP: usize = 16;
/// Longest side of the downsampled grid.
pub const GRID_MAX_SIDE: usize = 96;

/// Statistics for one Y plane: `(mean, grid, grid_width, grid_height)`.
pub type LumaStats = (f64, Vec<f32>, usize, usize);

fn readable(len: usize, row_stride: usize, pixel_stride: usize, w: usize, h: usize) -> bool {
    if w == 0 || h == 0 || row_stride == 0 || pixel_stride == 0 {
        return false;
    }
    match (h - 1)
        .checked_mul(row_stride)
        .and_then(|v| v.checked_add((w - 1) * pixel_stride))
    {
        Some(last) => last < len,
        None => false,
    }
}

/// Computes the shared statistics over `data`, or `None` when unreadable.
pub fn luma_stats(
    data: &[u8],
    width: usize,
    height: usize,
    row_stride: usize,
    pixel_stride: usize,
) -> Option<LumaStats> {
    if !readable(data.len(), row_stride, pixel_stride, width, height) {
        return None;
    }
    let stride_x = (width / GRID_MAX_SIDE).max(1);
    let stride_y = (height / GRID_MAX_SIDE).max(1);
    let grid_width = width.div_ceil(stride_x);
    let grid_height = height.div_ceil(stride_y);
    let mut grid = vec![0f32; grid_width * grid_height];
    let mut out = 0usize;
    let mut y = 0usize;
    while y < height {
        let row_start = y * row_stride;
        let mut x = 0usize;
        while x < width {
            grid[out] = data[row_start + x * pixel_stride] as f32;
            out += 1;
            x += stride_x;
        }
        y += stride_y;
    }
    let mut sum: u64 = 0;
    let mut count: u64 = 0;
    let mut n: usize = 0;
    let total = width * height;
    while n < total {
        let x = n % width;
        let yy = n / width;
        sum += data[yy * row_stride + x * pixel_stride] as u64;
        count += 1;
        n += LUMA_SAMPLE_STEP;
    }
    if count == 0 {
        return None;
    }
    Some((sum as f64 / count as f64, grid, grid_width, grid_height))
}

#[cfg(test)]
mod luma_tests {
    use super::luma_stats;

    fn plane(w: usize, h: usize, row_stride: usize, pixel_stride: usize) -> Vec<u8> {
        // Padding bytes are poisoned with 0xEE: stats must never read them
        // as pixels, so a stride bug changes the result and fails the test.
        let mut data = vec![0xEEu8; row_stride * h];
        for y in 0..h {
            for x in 0..w {
                data[y * row_stride + x * pixel_stride] = ((x * 7 + y * 13) % 251) as u8;
            }
        }
        data
    }

    #[test]
    fn flat_plane_mean_and_grid() {
        let data = vec![100u8; 64 * 48];
        let (mean, grid, gw, gh) = luma_stats(&data, 64, 48, 64, 1).expect("stats");
        assert_eq!(mean, 100.0);
        assert_eq!((gw, gh), (64, 48));
        assert!(grid.iter().all(|v| *v == 100.0));
    }

    #[test]
    fn padded_strides_ignore_padding() {
        let data = plane(37, 23, 48, 1);
        let (mean, grid, gw, _) = luma_stats(&data, 37, 23, 48, 1).expect("stats");
        // Independent recomputation straight from pixel coordinates.
        let mut sum = 0u64;
        let mut count = 0u64;
        let mut n = 0usize;
        while n < 37 * 23 {
            sum += ((n % 37 * 7 + n / 37 * 13) % 251) as u64;
            count += 1;
            n += 16;
        }
        assert_eq!(mean, sum as f64 / count as f64);
        assert_eq!(gw, 37);
        assert_eq!(grid[0], 0.0);
    }

    #[test]
    fn rejects_unreadable_planes() {
        assert!(luma_stats(&[], 0, 0, 0, 1).is_none());
        assert!(luma_stats(&[0u8; 10], 64, 48, 64, 1).is_none());
        assert!(luma_stats(&[0u8; 64 * 48], 64, 48, 0, 1).is_none());
    }
}

/// JNI entry for `StitchNative.lumaStatsFromYPlane`: direct Y-plane buffer
/// in, `[mean, gridWidth, gridHeight, grid...]` float array out, null on any
/// failure (non-direct buffer, bad strides, undersized buffer) so Kotlin
/// falls back to its own identical computation.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_camera_platform_StitchNative_lumaStatsFromYPlane<'l>(
    mut env: jni::EnvUnowned<'l>,
    _class: jni::objects::JClass<'l>,
    buffer: jni::objects::JByteBuffer<'l>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    row_stride: jni::sys::jint,
    pixel_stride: jni::sys::jint,
) -> jni::sys::jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jfloatArray> {
        let null = std::ptr::null_mut();
        if width <= 0 || height <= 0 || row_stride <= 0 || pixel_stride <= 0 {
            return Ok(null);
        }
        let address = match env.get_direct_buffer_address(&buffer) {
            Ok(a) => a,
            Err(_) => return Ok(null),
        };
        if address.is_null() {
            return Ok(null);
        }
        let capacity = match env.get_direct_buffer_capacity(&buffer) {
            Ok(c) => c,
            Err(_) => return Ok(null),
        };
        // The buffer is a live local reference for the whole call, so the
        // address stays valid while the slice is read; nothing is retained.
        let data = unsafe { std::slice::from_raw_parts(address, capacity) };
        let stats = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            luma_stats(
                data,
                width as usize,
                height as usize,
                row_stride as usize,
                pixel_stride as usize,
            )
        }))
        .unwrap_or(None);
        let Some((mean, grid, grid_width, grid_height)) = stats else {
            return Ok(null);
        };
        let mut out = Vec::with_capacity(3 + grid.len());
        out.push(mean as f32);
        out.push(grid_width as f32);
        out.push(grid_height as f32);
        out.extend_from_slice(&grid);
        let arr = match env.new_float_array(out.len()) {
            Ok(a) => a,
            Err(_) => return Ok(null),
        };
        match arr.set_region(env, 0, &out) {
            Ok(()) => Ok(arr.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
