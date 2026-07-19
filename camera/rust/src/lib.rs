//! Camera stitching native library: feature-based panorama stitcher + night
//! burst aligner, exposed to Kotlin via JNI. Replaces the OpenCV dependency.

mod imgbuf;
mod features;
mod geometry;
mod camera;
mod matching;
mod estimator;
mod bundle;
mod wave;
mod warp;
mod sphere;
mod exposure;
mod seam;
mod blend;
mod stitch;
mod night;

use imgbuf::Rgba;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

struct Session {
    sphere: bool,
    frames: Vec<Rgba>,
    yaw: Vec<f32>,
    pitch: Vec<f32>,
}

fn registry() -> &'static Mutex<HashMap<i64, Session>> {
    static REG: OnceLock<Mutex<HashMap<i64, Session>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> i64 {
    static CTR: OnceLock<Mutex<i64>> = OnceLock::new();
    let m = CTR.get_or_init(|| Mutex::new(1));
    let mut g = m.lock().unwrap();
    let h = *g;
    *g += 1;
    h
}

/// Encode an RGBA image to JPEG bytes (alpha dropped).
fn encode_jpeg(img: &Rgba, quality: u8) -> Option<Vec<u8>> {
    use image::codecs::jpeg::JpegEncoder;
    use image::{ExtendedColorType, ImageEncoder};
    let mut rgb = vec![0u8; img.w * img.h * 3];
    for i in 0..img.w * img.h {
        rgb[i * 3] = img.px[i * 4];
        rgb[i * 3 + 1] = img.px[i * 4 + 1];
        rgb[i * 3 + 2] = img.px[i * 4 + 2];
    }
    let mut buf = Vec::new();
    let enc = JpegEncoder::new_with_quality(&mut buf, quality);
    enc.write_image(&rgb, img.w as u32, img.h as u32, ExtendedColorType::Rgb8)
        .ok()?;
    Some(buf)
}

// Pure-Rust API (also usable from host `cargo test`).
fn do_stitch(s: &mut Session) -> Option<Vec<u8>> {
    let frames = std::mem::take(&mut s.frames);
    let yaw = std::mem::take(&mut s.yaw);
    let pitch = std::mem::take(&mut s.pitch);
    let _ = s.sphere; // sphere vs cylindrical handled by the (planar-chain) pipeline for now
    let result = stitch::stitch_panorama(&frames, &yaw, &pitch)?;
    encode_jpeg(&result, 92)
}

fn do_merge(s: &mut Session) -> Option<Vec<u8>> {
    let frames = std::mem::take(&mut s.frames);
    let result = night::align_and_merge(&frames)?;
    encode_jpeg(&result, 95)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::imgbuf::Rgba;

    fn load(path: &str) -> Rgba {
        let img = image::ImageReader::open(path)
            .expect("open image")
            .decode()
            .expect("decode image")
            .to_rgba8();
        let (w, h) = img.dimensions();
        Rgba::from_bytes(w as usize, h as usize, img.into_raw())
    }

    /// Stitches the two sample photos from linrl3/Image-Stitching-OpenCV and
    /// writes the result to testdata/output.jpg for visual comparison against
    /// testdata/panorama.jpg. Run with:  cargo test stitch_two_samples -- --nocapture
    #[test]
    fn stitch_two_samples() {
        let a = load("testdata/q11.jpg");
        let b = load("testdata/q22.jpg");
        let (aw, ah) = (a.w, a.h);
        let t0 = std::time::Instant::now();
        let out = crate::stitch::stitch_panorama(&[a, b], &[0.0, 0.0], &[0.0, 0.0])
            .expect("stitch returned None");
        let dt = t0.elapsed();
        let jpeg = encode_jpeg(&out, 92).expect("encode");
        std::fs::write("testdata/output.jpg", &jpeg).expect("write output");

        // Quantitatively count pure-black pixels (the uncovered-warp fill) — both
        // overall and specifically on the four borders.
        let total = out.w * out.h;
        let mut black = 0usize;
        for i in 0..total {
            let (r, g, b) = (out.px[i * 4], out.px[i * 4 + 1], out.px[i * 4 + 2]);
            if r == 0 && g == 0 && b == 0 {
                black += 1;
            }
        }
        let mut border_black = 0usize;
        let mut count_px = |x: usize, y: usize, acc: &mut usize| {
            let i = (y * out.w + x) * 4;
            if out.px[i] == 0 && out.px[i + 1] == 0 && out.px[i + 2] == 0 {
                *acc += 1;
            }
        };
        for x in 0..out.w {
            count_px(x, 0, &mut border_black);
            count_px(x, out.h - 1, &mut border_black);
        }
        for y in 0..out.h {
            count_px(0, y, &mut border_black);
            count_px(out.w - 1, y, &mut border_black);
        }
        let black_pct = 100.0 * black as f64 / total as f64;
        // Locate the brightest vertical streak in the sky (top 25%): per-column
        // mean brightness, find the peak column and dump values around it.
        let top = out.h / 4;
        let colmean: Vec<f64> = (0..out.w)
            .map(|x| {
                let mut s = 0.0;
                for y in 0..top {
                    let i = (y * out.w + x) * 4;
                    s += (out.px[i] as f64 + out.px[i + 1] as f64 + out.px[i + 2] as f64) / 3.0;
                }
                s / top as f64
            })
            .collect();
        let avg: f64 = colmean.iter().sum::<f64>() / out.w as f64;
        let peak = (0..out.w).max_by(|&a, &b| colmean[a].total_cmp(&colmean[b])).unwrap();
        eprintln!(
            "streak: peak col {} mean {:.0} vs avg {:.0} (+{:.0})",
            peak, colmean[peak], avg, colmean[peak] - avg
        );
        let y = top / 2;
        let mut line = String::new();
        for x in (peak.saturating_sub(10))..(peak + 11).min(out.w) {
            let i = (y * out.w + x) * 4;
            line.push_str(&format!("{}:{},{},{} ", x, out.px[i], out.px[i + 1], out.px[i + 2]));
        }
        eprintln!("row {y} around peak: {line}");
        eprintln!(
            "inputs {aw}x{ah}; stitched {}x{} in {:?} -> {} KB; black={} ({:.3}%), border_black={}",
            out.w,
            out.h,
            dt,
            jpeg.len() / 1024,
            black,
            black_pct,
            border_black
        );
        assert!(out.w > 800 && out.h > 800, "degenerate crop: {}x{}", out.w, out.h);
        assert_eq!(border_black, 0, "black pixels on the border");
        assert!(black_pct < 0.05, "too many black pixels: {:.3}%", black_pct);
    }
}

#[cfg(not(test))]
mod jni_bindings {
    use super::*;
    use jni::objects::{JByteArray, JClass};
    use jni::sys::{jboolean, jbyteArray, jfloat, jint, jlong};
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_camera_util_StitchNative_newSession<'l>(
        _env: JNIEnv<'l>,
        _class: JClass<'l>,
        sphere: jboolean,
    ) -> jlong {
        let h = next_handle();
        registry().lock().unwrap().insert(
            h,
            Session { sphere: sphere != 0, frames: Vec::new(), yaw: Vec::new(), pitch: Vec::new() },
        );
        h
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_camera_util_StitchNative_addFrame<'l>(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        handle: jlong,
        rgba: JByteArray<'l>,
        width: jint,
        height: jint,
        yaw: jfloat,
        pitch: jfloat,
        _roll: jfloat,
    ) {
        let bytes = match env.convert_byte_array(&rgba) {
            Ok(b) => b,
            Err(_) => return,
        };
        let (w, h) = (width as usize, height as usize);
        if bytes.len() != w * h * 4 {
            return;
        }
        if let Some(s) = registry().lock().unwrap().get_mut(&(handle as i64)) {
            s.frames.push(Rgba::from_bytes(w, h, bytes));
            s.yaw.push(yaw);
            s.pitch.push(pitch);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_camera_util_StitchNative_stitch<'l>(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        handle: jlong,
    ) -> jbyteArray {
        run_and_return(env, handle, true)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_camera_util_StitchNative_merge<'l>(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        handle: jlong,
    ) -> jbyteArray {
        run_and_return(env, handle, false)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_camera_util_StitchNative_free<'l>(
        _env: JNIEnv<'l>,
        _class: JClass<'l>,
        handle: jlong,
    ) {
        registry().lock().unwrap().remove(&(handle as i64));
    }

    fn run_and_return(env: JNIEnv, handle: jlong, panorama: bool) -> jbyteArray {
        let null = std::ptr::null_mut();
        // Pull the session out so the (potentially long) compute doesn't hold the lock.
        let mut session = match registry().lock().unwrap().get_mut(&(handle as i64)) {
            Some(s) => Session {
                sphere: s.sphere,
                frames: std::mem::take(&mut s.frames),
                yaw: std::mem::take(&mut s.yaw),
                pitch: std::mem::take(&mut s.pitch),
            },
            None => return null,
        };
        // Never let a panic unwind across the JNI/FFI boundary — return null instead.
        let bytes = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            if panorama {
                do_stitch(&mut session)
            } else {
                do_merge(&mut session)
            }
        }))
        .unwrap_or(None);
        match bytes {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => arr.into_raw(),
                Err(_) => null,
            },
            None => null,
        }
    }
}
