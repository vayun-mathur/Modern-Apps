use crate::*;
use jni::objects::{JByteArray, JClass, JFloatArray, JString};
use jni::sys::{jbyteArray, jboolean, jfloat, jint, jlong, jstring};
use jni::{Env, EnvUnowned, jni_str};
use std::panic::{catch_unwind, AssertUnwindSafe};

// ---------------------------------------------------------------------------
// Safety / JNI constants
// ---------------------------------------------------------------------------

/// Maximum Java byte[] size we accept (200 MB) to prevent OOM DoS.
/// Audit #7: unbounded convert_byte_array copy. An Intent with huge PDF could OOM native heap,
/// and registry then duplicates via Document::load_mem.
const MAX_JAVA_BYTE_ARRAY_BYTES: usize = 200 * 1024 * 1024;
/// Drops the cached search index for `handle` on scope exit, so a document-mutating
/// entry point cannot leave `ensure_index` serving text from before the edit.
///
/// A guard rather than a call at each return: these functions have several exit paths
/// and a `catch_unwind` arm, and a panic can leave the document partially mutated — the
/// one case where a surviving stale index is worst. Declared before `catch_unwind` so it
/// drops after the unwind is caught.
///
/// Cheap: it only removes a map entry. The rebuild happens lazily on the next search.
struct InvalidateSearchIndex(jlong);
impl Drop for InvalidateSearchIndex {
    fn drop(&mut self) {
        crate::search::invalidate_index(self.0);
    }
}

/// Throw a Java IllegalArgumentException (if possible). Requires &mut JNIEnv.
fn throw_iae<'local>(env: &mut Env<'local>, msg: &str) {
    let _ = env.throw_new(jni_str!("java/lang/IllegalArgumentException"), jni::strings::JNIString::new(msg));
}

fn throw_oom<'local>(env: &mut Env<'local>, msg: &str) {
    let _ = env.throw_new(jni_str!("java/lang/OutOfMemoryError"), jni::strings::JNIString::new(msg));
}

/// Convert JString to Rust String with proper error propagation (no unwrap_or_default hiding errors).
/// Returns Ok(String) or Err with message. Handles null JString.
/// Audit #5 + #3: jstr unwrap_or_default hides malformed Modified UTF-8 and null.
fn jstr_safe<'local>(env: &Env, s: &JString<'local>) -> Result<String, String> {
    if s.is_null() {
        return Ok(String::new());
    }
    match s.try_to_string(env) {
        Ok(js) => Ok(js),
        Err(e) => {
            env.exception_clear();
            Err(format!("Invalid JString (Modified UTF-8): {:?}", e))
        }
    }
}

/// Helper that creates a Java byte[] from Option<Vec<u8>> ensuring local capacity first.
/// Uses &mut JNIEnv since ensure_local_capacity requires &mut and we want to handle errors properly.
/// Returns raw jbyteArray (null on failure).
fn bytes_or_null_mut<'local>(env: &mut Env<'local>, data: Option<Vec<u8>>) -> jbyteArray {
    let null = std::ptr::null_mut();
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return null;
    }
    match data {
        Some(b) => match env.byte_array_from_slice(&b) {
            Ok(arr) => {
                // SAFETY: ownership transferred to JVM via into_raw. Local ref consumed.
                // Java GC will free the array. No further use of `env` for this object needed.
                arr.into_raw()
            }
            Err(_) => null,
        },
        None => null,
    }
}

// ---------------------------------------------------------------------------
// Inner logic functions (panic-safe, wrapped by catch_unwind in extern fns)
// ---------------------------------------------------------------------------

fn open_document_inner<'local>(env: &mut Env<'local>, data: JByteArray<'local>) -> jlong {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = data.len(env).unwrap_or(0);
    if len > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    open_document(&bytes) as jlong
}

fn open_document_pw_inner<'local>(
    env: &mut Env<'local>,
    data: JByteArray<'local>,
    password: JString<'local>,
) -> jlong {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = data.len(env).unwrap_or(0);
    if len > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    let pw = if password.is_null() {
        String::new()
    } else {
        match jstr_safe(env, &password) {
            Ok(s) => s,
            Err(e) => {
                throw_iae(env, &e);
                return 0;
            }
        }
    };
    open_document_pw(&bytes, pw.as_bytes()) as jlong
}

fn pdf_password_state_inner<'local>(env: &mut Env<'local>, data: JByteArray<'local>) -> jint {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = data.len(env).unwrap_or(0);
    if len > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    pdf_password_state(&bytes)
}

fn save_encrypted_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    user_pw: JString<'local>,
    owner_pw: JString<'local>,
) -> jbyteArray {
    let u = match jstr_safe(env, &user_pw) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    let o = match jstr_safe(env, &owner_pw) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return std::ptr::null_mut();
        }
    };
    // Ensure capacity before allocating byte array (audit #4)
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, save_encrypted(handle, u.as_bytes(), o.as_bytes()))
}

fn render_page_inner<'local>(env: &mut Env<'local>, handle: jlong, index: jint) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, render_page(handle, index))
}

fn append_pdf_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jint {
    if data.is_null() {
        throw_iae(env, "data is null");
        return 0;
    }
    let len = data.len(env).unwrap_or(0);
    if len > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "PDF to append too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    append_pdf(handle, &bytes)
}

fn append_image_page_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    jpeg: JByteArray<'local>,
    w: jint,
    h: jint,
) -> jint {
    if jpeg.is_null() {
        throw_iae(env, "jpeg is null");
        return 0;
    }
    let len = jpeg.len(env).unwrap_or(0);
    if len > MAX_JAVA_BYTE_ARRAY_BYTES {
        throw_oom(env, "Image too large (>200MB)");
        return 0;
    }
    let bytes = match env.convert_byte_array(&jpeg) {
        Ok(b) => b,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    append_image_page(handle, &bytes, w as u32, h as u32)
}

fn extract_page_inner<'local>(env: &mut Env<'local>, handle: jlong, index: jint) -> jbyteArray {
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, extract_page(handle, index))
}

fn list_data_inner<'local, F>(env: &mut Env<'local>, f: F) -> jbyteArray
where
    F: FnOnce() -> Option<Vec<u8>>,
{
    if env.ensure_local_capacity(4).is_err() {
        env.exception_clear();
        return std::ptr::null_mut();
    }
    bytes_or_null_mut(env, f())
}

fn add_free_text_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    page: jint,
    // (x0, y0, x1, y1) text-box rect
    rect: [jfloat; 4],
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    add_free_text(
        handle,
        page,
        rect.map(|v| v as f64),
        argb as u32,
        size as f64,
        &t,
    )
    .unwrap_or(0)
}

fn add_note_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    page: jint,
    x: jfloat,
    y: jfloat,
    argb: jint,
    text: JString<'local>,
) -> jlong {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    add_note(handle, page, x as f64, y as f64, argb as u32, &t).unwrap_or(0)
}

fn add_callout_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    page: jint,
    // (anchor x, anchor y, box x, box y): leader anchor and text-box corner
    pts: [jfloat; 4],
    argb: jint,
    size: jfloat,
    text: JString<'local>,
) -> jlong {
    let t = match jstr_safe(env, &text) {
        Ok(s) => s,
        Err(e) => {
            throw_iae(env, &e);
            return 0;
        }
    };
    add_callout(
        handle,
        page,
        (pts[0] as f64, pts[1] as f64),
        (pts[2] as f64, pts[3] as f64),
        argb as u32,
        size as f64,
        &t,
    )
    .unwrap_or(0)
}

fn add_poly_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    // (fill, closed)
    fill_closed: (jboolean, jboolean),
    pts: JFloatArray<'local>,
) -> jlong {
    let (fill, closed) = fill_closed;
    if pts.is_null() {
        throw_iae(env, "pts is null");
        return 0;
    }
    let len = match pts.len(env) {
        Ok(l) => l,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    if len > 100_000 {
        throw_iae(env, "pts too large");
        return 0;
    }
    let mut buf = vec![0f32; len];
    if pts.get_region(env, 0, &mut buf).is_err() {
        env.exception_clear();
        return 0;
    }
    add_poly(
        handle,
        page,
        &buf,
        argb as u32,
        line_width as f64,
        fill,
        closed,
    )
    .unwrap_or(0)
}

fn add_ink_inner<'local>(
    env: &mut Env<'local>,
    handle: jlong,
    page: jint,
    argb: jint,
    line_width: jfloat,
    pts: JFloatArray<'local>,
) -> jlong {
    if pts.is_null() {
        throw_iae(env, "pts is null");
        return 0;
    }
    let len = match pts.len(env) {
        Ok(l) => l,
        Err(_) => {
            env.exception_clear();
            return 0;
        }
    };
    if len > 100_000 {
        throw_iae(env, "pts too large");
        return 0;
    }
    let mut buf = vec![0f32; len];
    if pts.get_region(env, 0, &mut buf).is_err() {
        env.exception_clear();
        return 0;
    }
    add_ink(handle, page, argb as u32, line_width as f64, &buf).unwrap_or(0)
}

include!("jni_bindings_part1.rs");
include!("jni_bindings_part2.rs");
include!("jni_bindings_part3.rs");
