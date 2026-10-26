//! The JNI surface: three entry points per network, and nothing per-pixel.
//!
//! Kotlin creates a segmenter, hands it a bitmap's pixels, gets a mask back, and closes
//! it. Preprocessing, the whole forward pass and the readback all happen on this side, so
//! the boundary is crossed three times per inference rather than once per element.
//!
//! # Why pixels and not the `Bitmap`
//!
//! `AndroidBitmap_lockPixels` from `libjnigraphics` would avoid copying the pixel array
//! across, but it also means handling every `Bitmap.Config` the platform might hand over
//! — including `HARDWARE`, which cannot be locked at all — and both call sites already
//! hold an ARGB_8888 copy. So Kotlin does `getPixels` into a reused `IntArray` and passes
//! that, which is one copy of at most 512x512 ints and no `libjnigraphics` dependency.
//!
//! # Threading
//!
//! Neither entry point is thread-safe, and neither needs to be: `BokehAnalyzer` holds its
//! segmenter behind a `synchronized(lock)` and `MlSegmentation` behind `segLock`. That
//! discipline is what the comments in `BokehAnalyzer.kt` are about — a use-after-free
//! tombstone with the ncnn net — and the hazard is identical with a Vulkan handle, which
//! is why the Kotlin side keeps it.
//!
//! # Handles
//!
//! A handle is a leaked `Box`, handed to Kotlin as an opaque `jlong`. `0` means failure,
//! and every entry point tolerates it, so a device without fp16 compute degrades to "no
//! bokeh" rather than to a crash.

use std::fs::File;
use std::os::fd::FromRawFd;

use jni::objects::{
    JByteArray, JClass, JFloatArray, JIntArray, JLongArray, JObjectArray, JShortArray, JString,
};
use jni::sys::{jfloatArray, jint, jintArray, jlong, jstring};
use jni::{Env, EnvUnowned};

use crate::nets::{
    gemma4, gemma4_audio, gemma4_vision, madlad, madlad_part1, maia, mobilefacenet, modnet, nllb,
    nnfp, ppocr_det, ppocr_rec, scrfd, selfie, supertonic_duration, supertonic_sampler,
    supertonic_text, supertonic_vocoder, tinyclip, u2netp, whisper, Plan,
};
use crate::post::ctc::Dictionary;
use crate::post::madlad as madlad_post;
use crate::post::nms::{self, Face, Maps};
use crate::post::ocr::{self, Line};
use crate::post::sentencepiece::Table;
use crate::post::sentencepiece_flavours::{GEMMA, T5};
use crate::post::supertonic;
use crate::post::translate;
use crate::post::whisper as whisper_post;
use crate::preprocess::{
    Letterbox, FACE_EMBED, IMAGENET, MODNET, PPOCR_DET, PPOCR_REC, RESCALE_ONLY, SCRFD,
};
use crate::vulkan::context;
use crate::vulkan::reshape::Reshaped;
use crate::vulkan::run::{Net, StepParams};
use crate::weights::{graph, Offsets, Streamed, Weights};

/// One segmenter. Handed to Kotlin as an opaque `jlong`.
struct Handle {
    net: Net,
    /// The pixel array, reused across calls.
    ///
    /// `:camera` runs this ~15 times a second on frames of up to 512x512, so a fresh
    /// `Vec<i32>` per call would be a megabyte of allocation and free per second on the
    /// preview path for nothing.
    pixels: Vec<i32>,
}

/// `SelfieSegmenter`'s constructor. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createSelfie<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        Ok(open(env, weights, graph::SELFIE, "selfie"))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `SubjectSegmenter`'s constructor. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createU2netp<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        Ok(open(env, weights, graph::U2NETP, "u2netp"))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `FaceDetector`'s constructor: SCRFD 500M at a fixed square. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createScrfd<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        Ok(open(env, weights, graph::SCRFD, "scrfd"))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `FaceEmbedder`'s constructor: MobileFaceNet at 112x112. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMobilefacenet<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        Ok(open(env, weights, graph::MOBILEFACENET, "mobilefacenet"))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `ModnetSegmenter`'s constructor: MODNet photographic-portrait matting at a
/// fixed 512x512. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and a `weights` array it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createModnet<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    weights: JByteArray<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        Ok(open(env, weights, graph::MODNET, "modnet"))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn open<'l>(env: &Env, weights: JByteArray<'l>, graph_id: u32, name: &str) -> jlong {
    match build(env, weights, graph_id) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("{name} is unavailable: {e}"));
            0
        }
    }
}

fn build<'l>(
    env: &Env,
    weights: JByteArray<'l>,
    graph_id: u32,
) -> Result<Handle, String> {
    // The blob arrives as bytes rather than a path because an asset lives inside the APK
    // and has no filesystem path unless it is extracted first. Both `.maml` files are
    // `noCompress`, so Kotlin's read is a straight copy out of the mapped APK.
    let bytes = env
        .convert_byte_array(&weights)
        .map_err(|e| format!("cannot read the weights array: {e}"))?;
    let parsed = Weights::parse(&bytes, graph_id)?;
    // The device comes up lazily and is shared, so `:camera`'s two segmenters do not
    // create two `VkDevice`s.
    let shared = context::shared()?;
    let (plan, normalise) = match graph_id {
        graph::SELFIE => (selfie::build(&parsed)?, RESCALE_ONLY),
        graph::U2NETP => (u2netp::build(&parsed)?, IMAGENET),
        // A fixed square rather than the tight multiple-of-32 letterbox `scrfd.cpp` uses;
        // see `preprocess::Letterbox::square` for why, and what it costs.
        graph::SCRFD => (
            scrfd::build(&parsed, scrfd::LONG_SIDE, scrfd::LONG_SIDE)?,
            SCRFD,
        ),
        graph::MOBILEFACENET => (mobilefacenet::build(&parsed)?, FACE_EMBED),
        graph::MODNET => (modnet::build(&parsed)?, MODNET),
        other => return Err(format!("no forward pass for graph {other}")),
    };
    Ok(Handle {
        net: Net::new(shared, plan, &parsed, normalise)?,
        pixels: Vec::new(),
    })
}

/// Run the network over `pixels` and return the mask as a `float[]`.
///
/// Returns null on failure or on a `0` handle, which the Kotlin wrapper turns into "no
/// mask this frame" rather than an exception.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by one of the create functions and not yet
/// destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_segment<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jfloatArray> {
        let null = std::ptr::null_mut();
        if handle == 0 || width <= 0 || height <= 0 {
            return Ok(null);
        }
        // SAFETY: the caller guarantees `handle` came from a create function and is live, and
        // the Kotlin side serialises `segment` against `destroy` with a lock held across the
        // whole call.
        let state = unsafe { &mut *(handle as *mut Handle) };

        let count = match env.get_array_length(&pixels) {
            Ok(n) if n >= 0 => n as usize,
            Ok(n) => {
                log(&format!("a pixel array of length {n}"));
                return Ok(null);
            }
            Err(e) => {
                log(&format!("cannot size the pixel array: {e}"));
                return Ok(null);
            }
        };
        if count != (width as usize) * (height as usize) {
            log(&format!("{count} pixels for a {width}x{height} bitmap"));
            return Ok(null);
        }
        state.pixels.resize(count, 0);
        if let Err(e) = env.get_int_array_region(&pixels, 0, &mut state.pixels) {
            log(&format!("cannot read the pixel array: {e}"));
            return Ok(null);
        }
        let mask = match state.net.infer(&state.pixels, width as u32, height as u32) {
            Ok(m) => m,
            Err(e) => {
                log(&format!("inference failed: {e}"));
                return Ok(null);
            }
        };
        Ok(match new_float_array(env, &mask) {
            Ok(array) => array,
            Err(e) => {
                log(&format!("cannot return the mask: {e}"));
                null
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Detect faces in `pixels` and return them flattened, nine floats each.
///
/// The layout per face is `left, top, right, bottom, leftEyeX, leftEyeY, rightEyeX,
/// rightEyeY, score` — deliberately the argument order of the old ncnn JNI's `Face`
/// constructor, so the Kotlin mapping is a straight read and a reviewer can diff the two.
/// Every coordinate is a fraction of the source bitmap, so it survives the caller
/// resizing.
///
/// A flat `float[]` rather than an array of objects: constructing `n` Java objects across
/// JNI is `n` class lookups and `n` constructor calls, and the Kotlin side has to allocate
/// its own data class anyway.
///
/// Returns null on failure or on a `0` handle. An empty array means no faces, which is
/// the common case and not an error.
///
/// # Safety
///
/// `handle` must be `0` or a value returned by
/// [`Java_com_vayunmathur_library_ml_MlNative_createScrfd`] and not yet destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_detectFaces<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JIntArray<'l>,
    width: jint,
    height: jint,
) -> jfloatArray {
    env.with_env(|env| -> jni::errors::Result<jfloatArray> {
        let null = std::ptr::null_mut();
        if handle == 0 || width <= 0 || height <= 0 {
            return Ok(null);
        }
        // SAFETY: as `segment` — the caller guarantees the handle is live and serialises this
        // against `destroy`.
        let state = unsafe { &mut *(handle as *mut Handle) };

        if let Err(e) = read_pixels(env, &pixels, width, height, &mut state.pixels) {
            log(&e);
            return Ok(null);
        }
        Ok(match detect(state, width as u32, height as u32) {
            Ok(flat) => match new_float_array(env, &flat) {
                Ok(array) => array,
                Err(e) => {
                    log(&format!("cannot return the detections: {e}"));
                    null
                }
            },
            Err(e) => {
                log(&format!("face detection failed: {e}"));
                null
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Nine floats per detected face. See the entry point above for the order.
const FACE_FLOATS: usize = 9;

/// Letterbox, run, decode, suppress, and flatten.
fn detect(state: &mut Handle, width: u32, height: u32) -> Result<Vec<f32>, String> {
    let fit = Letterbox::square(width, height, scrfd::LONG_SIDE)?;
    let maps = state.net.infer_letterboxed(&state.pixels, width, height, &fit)?;
    let shapes: Vec<crate::nets::Shape> =
        state.net.output_shapes().iter().map(|b| b.shape).collect();
    if maps.len() != shapes.len() || maps.len() != scrfd::STRIDES.len() * 3 {
        return Err(format!("{} output maps, expected {}", maps.len(), scrfd::STRIDES.len() * 3));
    }

    let mut faces: Vec<Face> = Vec::new();
    for (level, stride) in scrfd::STRIDES.iter().enumerate() {
        let at = level * 3;
        let (score, bbox, keypoints) = match (maps.get(at), maps.get(at + 1), maps.get(at + 2)) {
            (Some(s), Some(b), Some(k)) => (s, b, k),
            _ => return Err(format!("stride {stride} is missing a map")),
        };
        let shape = shapes.get(at).copied().ok_or("a map with no shape")?;
        nms::decode(
            &Maps { score, bbox, keypoints, shape },
            *stride,
            nms::SCORE_THRESHOLD,
            &mut faces,
        )?;
    }
    nms::suppress(&mut faces, nms::IOU_THRESHOLD);
    nms::to_source(&mut faces, &fit, width, height);

    let mut flat = Vec::with_capacity(faces.len() * FACE_FLOATS);
    for face in &faces {
        flat.extend_from_slice(&face.bounds);
        // Landmarks 0 and 1 are the eyes, which is all the alignment in
        // `FaceRecognizer.alignFace` uses. The nose and mouth corners are decoded and
        // dropped here rather than carried across a boundary nothing reads them through.
        let (left_eye, right_eye) = match (face.keypoints.first(), face.keypoints.get(1)) {
            (Some(a), Some(b)) => (*a, *b),
            _ => ((0.0, 0.0), (0.0, 0.0)),
        };
        flat.push(left_eye.0);
        flat.push(left_eye.1);
        flat.push(right_eye.0);
        flat.push(right_eye.1);
        flat.push(face.score);
    }
    Ok(flat)
}

/// Both PP-OCRv5 networks and the dictionary between them. A separate handle type from
/// [`Handle`] because it owns two `Net`s, and separately destroyed for the same reason:
/// one `destroy` that guessed which kind of pointer it had been given would be a
/// type-confusion bug waiting for a caller to mix them up.
struct OcrHandle {
    det: Net,
    rec: Net,
    rec_batches: Vec<(u32, Net)>,
    dictionary: Dictionary,
    pixels: Vec<i32>,
}

/// `TextRecognizer`'s constructor: detection at a fixed square, recognition at a fixed
/// width, and the character table. Returns 0 on failure, having logged why.
///
/// Three assets rather than one because they are three files in the APK, and the dictionary
/// arrives as a `String` rather than bytes because Kotlin already has to decode it as UTF-8
/// to know it is text.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env` and arrays it owns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createPpocr<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    detection: JByteArray<'l>,
    recognition: JByteArray<'l>,
    keys: JString<'l>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        Ok(match build_ocr(env, detection, recognition, keys) {
            Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
            Err(e) => {
                log(&format!("ppocr is unavailable: {e}"));
                0
            }
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn build_ocr<'l>(
    env: &Env,
    detection: JByteArray<'l>,
    recognition: JByteArray<'l>,
    keys: JString<'l>,
) -> Result<OcrHandle, String> {
    let det_bytes = env
        .convert_byte_array(&detection)
        .map_err(|e| format!("cannot read the detection weights: {e}"))?;
    let rec_bytes = env
        .convert_byte_array(&recognition)
        .map_err(|e| format!("cannot read the recognition weights: {e}"))?;
    let text: String =
        keys.try_to_string(env).map_err(|e| format!("cannot read the dictionary: {e}"))?;
    let dictionary = Dictionary::parse(&text)?;

    let det_weights = Weights::parse(&det_bytes, graph::PPOCR_DET)?;
    let rec_weights = Weights::parse(&rec_bytes, graph::PPOCR_REC)?;
    let shared = context::shared()?;
    // Both at fixed shapes so each records its command buffer once; see `post::ocr`.
    let det_plan = ppocr_det::build(&det_weights, ppocr_det::LONG_SIDE, ppocr_det::LONG_SIDE)?;
    let rec_plan = ppocr_rec::build(&rec_weights, ocr::REC_WIDTH)?;
    Ok(OcrHandle {
        det: Net::new(shared.clone(), det_plan, &det_weights, PPOCR_DET)?,
        rec_batches: rec_batch_nets(&shared, &rec_weights)?,
        rec: Net::new(shared, rec_plan, &rec_weights, PPOCR_REC)?,
        dictionary,
        pixels: Vec::new(),
    })
}

include!("bridge_part1.rs");
include!("bridge_part2.rs");
include!("bridge_part3.rs");
include!("bridge_part4.rs");
include!("bridge_part5.rs");
include!("bridge_part6.rs");
include!("bridge_part7.rs");
include!("bridge_part8.rs");
include!("bridge_part9.rs");
include!("bridge_part10.rs");
include!("bridge_part11.rs");