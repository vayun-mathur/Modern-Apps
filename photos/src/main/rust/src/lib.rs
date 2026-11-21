//! Native pixel filters for the photos app, exposed to Kotlin via JNI.
//!
//! All algorithms are faithful ports of the corresponding Kotlin sources under
//! `photos/src/main/java/com/vayunmathur/photos/data/`. Pure algorithm code
//! lives in normal modules (compiled always, so `cargo test` can exercise it);
//! the JNI shims live in `#[cfg(not(test))] mod jni_bindings`.

pub mod blur;
pub mod inpaint;
pub mod liquify;
pub mod pixel;
pub mod sharpen;
pub mod stylize;

#[cfg(test)]
mod blur_tests;
#[cfg(test)]
mod inpaint_tests;
#[cfg(test)]
mod liquify_tests;
#[cfg(test)]
mod pixel_tests;
#[cfg(test)]
mod sharpen_tests;
#[cfg(test)]
mod stylize_tests;

#[cfg(not(test))]
mod jni_inplace;

#[cfg(not(test))]
mod jni_bindings {
    use crate::{blur, inpaint, liquify, sharpen, stylize};
    use jni::objects::{JClass, JFloatArray, JIntArray};
    use jni::sys::{jint, jintArray};
    use jni::{Env, EnvUnowned};

    /// Read a Java `int[]` into a `Vec<i32>`; `None` on error.
    fn read_ints(env: &Env, arr: &JIntArray) -> Option<Vec<i32>> {
        let len = env.get_array_length(arr).ok()? as usize;
        let mut buf = vec![0i32; len];
        if env.get_int_array_region(arr, 0, &mut buf).is_err() {
            return None;
        }
        Some(buf)
    }

    /// Read a Java `float[]` into a `Vec<f32>`; `None` on error.
    fn read_floats(env: &Env, arr: &JFloatArray) -> Option<Vec<f32>> {
        let len = env.get_array_length(arr).ok()? as usize;
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(arr, 0, &mut buf).is_err() {
            return None;
        }
        Some(buf)
    }

    /// Produce a Java `int[]` from `result`, or a null array on error.
    fn write_ints(env: &mut Env, result: &[i32]) -> jintArray {
        let out = match env.new_int_array(result.len()) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_int_array_region(&out, 0, result).is_err() {
            return std::ptr::null_mut();
        }
        out.into_raw()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_stylize<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        pixels: JIntArray<'l>,
        w: jint,
        h: jint,
        mode: jint,
    ) -> jintArray {
        env.with_env(|env| -> jni::errors::Result<jintArray> {
            let null = std::ptr::null_mut();
            if w <= 0 || h <= 0 {
                return Ok(null);
            }
            let buf = match read_ints(env, &pixels) {
                Some(b) if b.len() == (w as usize) * (h as usize) => b,
                _ => return Ok(null),
            };
            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                stylize::stylize(&buf, w as usize, h as usize, mode)
            })) {
                Ok(r) => r,
                Err(_) => return Ok(null),
            };
            Ok(write_ints(env, &result))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_unsharp<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        pixels: JIntArray<'l>,
        w: jint,
        h: jint,
        amount: jni::sys::jfloat,
        radius: jni::sys::jfloat,
        threshold: jint,
    ) -> jintArray {
        env.with_env(|env| -> jni::errors::Result<jintArray> {
            let null = std::ptr::null_mut();
            if w <= 0 || h <= 0 {
                return Ok(null);
            }
            let buf = match read_ints(env, &pixels) {
                Some(b) if b.len() == (w as usize) * (h as usize) => b,
                _ => return Ok(null),
            };
            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                sharpen::unsharp(&buf, w as usize, h as usize, amount, radius, threshold)
            })) {
                Ok(r) => r,
                Err(_) => return Ok(null),
            };
            Ok(write_ints(env, &result))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_liquify<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        pixels: JIntArray<'l>,
        w: jint,
        h: jint,
        tools: JIntArray<'l>,
        params: JFloatArray<'l>,
    ) -> jintArray {
        env.with_env(|env| -> jni::errors::Result<jintArray> {
            let null = std::ptr::null_mut();
            if w <= 0 || h <= 0 {
                return Ok(null);
            }
            let buf = match read_ints(env, &pixels) {
                Some(b) if b.len() == (w as usize) * (h as usize) => b,
                _ => return Ok(null),
            };
            let tools_v = match read_ints(env, &tools) {
                Some(t) => t,
                None => return Ok(null),
            };
            let params_v = match read_floats(env, &params) {
                Some(p) => p,
                None => return Ok(null),
            };
            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                liquify::liquify(&buf, w as usize, h as usize, &tools_v, &params_v)
            })) {
                Ok(r) => r,
                Err(_) => return Ok(null),
            };
            Ok(write_ints(env, &result))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_blurParams<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        pixels: JIntArray<'l>,
        w: jint,
        h: jint,
        mode: jint,
        center_x: jni::sys::jfloat,
        center_y: jni::sys::jfloat,
        radius: jni::sys::jfloat,
        intensity: jni::sys::jfloat,
        feather: jni::sys::jfloat,
        angle: jni::sys::jfloat,
    ) -> jintArray {
        env.with_env(|env| -> jni::errors::Result<jintArray> {
            let null = std::ptr::null_mut();
            if w <= 0 || h <= 0 {
                return Ok(null);
            }
            let buf = match read_ints(env, &pixels) {
                Some(b) if b.len() == (w as usize) * (h as usize) => b,
                _ => return Ok(null),
            };
            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                blur::blur_params(
                    &buf, w as usize, h as usize, mode, center_x, center_y, radius, intensity, feather,
                    angle,
                )
            })) {
                Ok(r) => r,
                Err(_) => return Ok(null),
            };
            Ok(write_ints(env, &result))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_filterBlur<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        pixels: JIntArray<'l>,
        w: jint,
        h: jint,
        mode: jint,
        amount: jni::sys::jfloat,
        angle: jni::sys::jfloat,
        center_x: jni::sys::jfloat,
        center_y: jni::sys::jfloat,
    ) -> jintArray {
        env.with_env(|env| -> jni::errors::Result<jintArray> {
            let null = std::ptr::null_mut();
            if w <= 0 || h <= 0 {
                return Ok(null);
            }
            let buf = match read_ints(env, &pixels) {
                Some(b) if b.len() == (w as usize) * (h as usize) => b,
                _ => return Ok(null),
            };
            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                blur::filter_blur(&buf, w as usize, h as usize, mode, amount, angle, center_x, center_y)
            })) {
                Ok(r) => r,
                Err(_) => return Ok(null),
            };
            Ok(write_ints(env, &result))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_photos_platform_PhotosNative_inpaint<'l>(
        mut env: EnvUnowned<'l>,
        _class: JClass<'l>,
        pixels: JIntArray<'l>,
        w: jint,
        h: jint,
        hole_mask: JFloatArray<'l>,
        mask_w: jint,
        mask_h: jint,
        passes: jint,
    ) -> jintArray {
        env.with_env(|env| -> jni::errors::Result<jintArray> {
            let null = std::ptr::null_mut();
            if w <= 0 || h <= 0 || mask_w <= 0 || mask_h <= 0 {
                return Ok(null);
            }
            let buf = match read_ints(env, &pixels) {
                Some(b) if b.len() == (w as usize) * (h as usize) => b,
                _ => return Ok(null),
            };
            let mask = match read_floats(env, &hole_mask) {
                Some(m) if m.len() == (mask_w as usize) * (mask_h as usize) => m,
                _ => return Ok(null),
            };
            let result = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                inpaint::inpaint(
                    &buf,
                    w as usize,
                    h as usize,
                    &mask,
                    mask_w as usize,
                    mask_h as usize,
                    passes,
                )
            })) {
                Ok(r) => r,
                Err(_) => return Ok(null),
            };
            Ok(write_ints(env, &result))
        })
        .resolve::<jni::errors::LogErrorAndDefault>()
    }
}

#[cfg(test)]
mod tests {
    use crate::pixel::pack;
    use crate::{blur, inpaint, liquify, sharpen, stylize};

    fn checker(w: usize, h: usize) -> Vec<i32> {
        (0..w * h)
            .map(|i| {
                let x = i % w;
                let y = i / w;
                let v = (((x * 37 + y * 91) % 256) as i32).clamp(0, 255);
                pack(255, v, (255 - v) & 0xFF, (v * 2) & 0xFF)
            })
            .collect()
    }

    #[test]
    fn stylize_none_is_identity() {
        let (w, h) = (16, 12);
        let src = checker(w, h);
        let out = stylize::stylize(&src, w, h, 0);
        assert_eq!(out, src, "mode None must be a bit-exact copy");
    }

    #[test]
    fn stylize_edges_and_emboss_preserve_dims_and_alpha() {
        let (w, h) = (16, 12);
        let src = checker(w, h);
        for mode in [1, 2] {
            let out = stylize::stylize(&src, w, h, mode);
            assert_eq!(out.len(), w * h);
            for px in &out {
                // Alpha preserved; result is grayscale (R==G==B).
                assert_eq!((*px as u32) >> 24, 0xFF, "alpha preserved");
                let r = (px >> 16) & 0xFF;
                let g = (px >> 8) & 0xFF;
                let b = px & 0xFF;
                assert_eq!(r, g);
                assert_eq!(g, b);
            }
        }
    }

    #[test]
    fn stylize_edges_flat_image_is_white() {
        // A flat image has zero gradient => mag 0 => v = 255 (white).
        let (w, h) = (8, 8);
        let src = vec![pack(255, 100, 100, 100); w * h];
        let out = stylize::stylize(&src, w, h, 1);
        for p in out {
            assert_eq!(p, pack(255, 255, 255, 255));
        }
    }

    #[test]
    fn unsharp_amount_zero_is_identity_rgb() {
        let (w, h) = (16, 16);
        let src = checker(w, h);
        // amount 0 => strength 0 => each channel returns orig.
        let out = sharpen::unsharp(&src, w, h, 0.0, 2.0, 0);
        assert_eq!(out, src);
    }

    #[test]
    fn unsharp_preserves_dims_and_alpha() {
        let (w, h) = (20, 14);
        let src = checker(w, h);
        let out = sharpen::unsharp(&src, w, h, 80.0, 3.0, 2);
        assert_eq!(out.len(), w * h);
        for i in 0..w * h {
            assert_eq!((out[i] as u32) >> 24, (src[i] as u32) >> 24, "alpha preserved");
        }
    }

    #[test]
    fn gaussian_blur_uniform_image_near_unchanged() {
        // A flat image blurs back to (nearly) itself. It is not bit-exact
        // because the f32 kernel weights sum to ~1.0 and each channel is
        // truncated via `as i32` — this matches the Kotlin `.toInt()` behaviour
        // exactly, so allow a small (<=2) per-channel truncation drift.
        let (w, h) = (12, 9);
        let src = vec![pack(255, 40, 130, 210); w * h];
        let out = blur::gaussian_blur(&src, w, h, 4);
        for p in out {
            for shift in [24u32, 16, 8, 0] {
                let a = ((p as u32) >> shift) & 0xFF;
                let b = ((src[0] as u32) >> shift) & 0xFF;
                assert!((a as i32 - b as i32).abs() <= 2, "channel drift too large");
            }
        }
    }

    #[test]
    fn gaussian_blur_zero_radius_is_copy() {
        let (w, h) = (10, 10);
        let src = checker(w, h);
        assert_eq!(blur::gaussian_blur(&src, w, h, 0), src);
    }

    #[test]
    fn filter_blur_modes_preserve_dims() {
        let (w, h) = (24, 18);
        let src = checker(w, h);
        for mode in 0..4 {
            let out = blur::filter_blur(&src, w, h, mode, 30.0, 45.0, 0.5, 0.5);
            assert_eq!(out.len(), w * h, "mode {mode} dims");
        }
    }

    #[test]
    fn blur_params_preserves_dims() {
        let (w, h) = (24, 18);
        let src = checker(w, h);
        for mode in 0..3 {
            let out = blur::blur_params(&src, w, h, mode, 0.5, 0.5, 0.3, 10.0, 0.3, 0.0);
            assert_eq!(out.len(), w * h, "mode {mode}");
        }
    }

    #[test]
    fn liquify_empty_ops_is_identity() {
        let (w, h) = (16, 16);
        let src = checker(w, h);
        let out = liquify::liquify(&src, w, h, &[], &[]);
        assert_eq!(out, src);
    }

    #[test]
    fn liquify_push_preserves_dims_and_changes_something() {
        let (w, h) = (32, 32);
        let src = checker(w, h);
        // One Push op (tool 0) at the center with a drag.
        let tools = [0i32];
        let params = [0.5f32, 0.5, 0.1, 0.0, 0.3, 0.8];
        let out = liquify::liquify(&src, w, h, &tools, &params);
        assert_eq!(out.len(), w * h);
        assert_ne!(out, src, "a non-trivial push should displace pixels");
    }

    #[test]
    fn liquify_reconstruct_only_is_identity() {
        // Reconstruct (tool 4) only scales an all-zero displacement field, so
        // the resample samples each pixel at its own location => identity.
        let (w, h) = (24, 24);
        let src = checker(w, h);
        let tools = [4i32];
        let params = [0.5f32, 0.5, 0.0, 0.0, 0.4, 0.7];
        let out = liquify::liquify(&src, w, h, &tools, &params);
        assert_eq!(out, src);
    }

    #[test]
    fn inpaint_no_hole_returns_input() {
        let (w, h) = (16, 16);
        let src = checker(w, h);
        let mask = vec![0f32; w * h];
        let out = inpaint::inpaint(&src, w, h, &mask, w, h, 10);
        assert_eq!(out, src, "empty mask => unchanged");
    }

    #[test]
    fn inpaint_small_hole_exemplar_fills_and_preserves_dims() {
        let (w, h) = (32, 32);
        let mut src = checker(w, h);
        // Mark a small central hole in the source with a sentinel color.
        let sentinel = pack(255, 0, 0, 0);
        let mut mask = vec![0f32; w * h];
        for y in 14..18 {
            for x in 14..18 {
                mask[y * w + x] = 1.0;
                src[y * w + x] = sentinel;
            }
        }
        let out = inpaint::inpaint(&src, w, h, &mask, w, h, 60);
        assert_eq!(out.len(), w * h);
        // Alpha must stay opaque everywhere.
        for p in &out {
            assert_eq!((*p as u32) >> 24, 0xFF);
        }
        // Hole pixels should have been overwritten by exemplar copies.
        let mut changed = 0;
        for y in 14..18 {
            for x in 14..18 {
                if out[y * w + x] != sentinel {
                    changed += 1;
                }
            }
        }
        assert!(changed > 0, "exemplar fill should replace hole pixels");
    }

    #[test]
    fn inpaint_large_hole_uses_diffusion() {
        // >20000 hole pixels forces the diffuse branch; just assert it runs and
        // preserves dims + alpha.
        let (w, h) = (200, 200);
        let src = checker(w, h);
        let mut mask = vec![0f32; w * h];
        for y in 10..190 {
            for x in 10..190 {
                mask[y * w + x] = 1.0;
            }
        }
        let out = inpaint::inpaint(&src, w, h, &mask, w, h, 5);
        assert_eq!(out.len(), w * h);
        for p in &out {
            assert_eq!((*p as u32) >> 24, 0xFF);
        }
    }
}
