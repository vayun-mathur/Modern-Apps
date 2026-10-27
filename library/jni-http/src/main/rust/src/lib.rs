//! JNI HTTP bridge for the Rust crates.
//!
//! Networking stays on the platform stack (`library:network`), so Rust inherits the app's proxy,
//! cookie and TLS behaviour and no crate links its own copy of rustls. This is the bridge Rust
//! calls to get bytes.
//!
//! It is built for being called from Rust in a loop, which the previous bridge was not:
//!
//! * **No object creation.** One `byte[]` each way, described in [`frame`]. The old bridge
//!   allocated a `NativeHttpResponse` and Rust read four fields back out with `GetFieldID` +
//!   `GetObjectField`, which is four extra JNI round trips per request.
//! * **No per-call lookups.** The class and method id are resolved once into a
//!   [`jni::objects::Global`] and reused, instead of `FindClass` on every request.
//! * **No thread churn for callers already on a JNI thread.** [`request_with_env`] borrows the
//!   caller's `Env`; only background threads pay `attach_current_thread`.
//! * **Failures are data, not exceptions.** A transport error comes back as status 0 with the
//!   message in the body, so Rust never has to `ExceptionCheck` between calls.
//!
//! The Kotlin counterpart is `com.vayunmathur.library.network.NativeHttpBridge`.

pub mod frame;

use std::sync::OnceLock;

use jni::objects::{Global, JByteArray, JClass, JObject, JString, JValue};
use jni::{Env, JavaVM, jni_sig, jni_str};

pub use frame::{Header, ResponseFrame};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get = 0,
    Post = 1,
    Head = 2,
}

#[derive(Debug)]
pub enum Error {
    /// The JVM could not be reached, or the bridge class is missing.
    Bridge(String),
    /// The request was attempted and failed at the transport level.
    Transport(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Bridge(m) => write!(f, "jni bridge error: {m}"),
            Error::Transport(m) => write!(f, "network error: {m}"),
        }
    }
}

impl From<jni::errors::Error> for Error {
    fn from(value: jni::errors::Error) -> Self {
        Error::Bridge(value.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// Cached JVM handle and bridge class, resolved once per process.
///
/// `batch_ok` records whether the `requestBatch` static method survived shrinking:
/// it is only referenced from here over JNI, so a release build whose keep rule
/// predates it strips the method while keeping the class (issue 792: every tile
/// failing "Method not found: requestBatch" with a blank map). Resolved once in
/// [`init`] so the failure is logged once at startup rather than per tile.
struct Cached {
    vm: JavaVM,
    class: Global<JClass<'static>>,
    batch_ok: bool,
}

static CACHED: OnceLock<Cached> = OnceLock::new();

/// Resolves and caches the JVM and bridge class.
///
/// Call once from any JNI entry point before background threads need the bridge; calling it
/// again is harmless. Returns false if the class could not be found, which means the calling
/// module is missing a dependency on `library:network`.
///
/// Also probes the `requestBatch` batch method and caches the answer. The probe is a
/// `GetStaticMethodID` with no call, so it is free at runtime and cannot throw into the
/// caller: when the method was stripped, `request_batch` degrades to single `request`
/// calls instead of failing every tile with "Method not found".
pub fn init(env: &mut Env) -> bool {
    if CACHED.get().is_some() {
        return true;
    }
    let (Ok(vm), Ok(class)) = (
        env.get_java_vm(),
        env.find_class(jni_str!("com/vayunmathur/library/network/NativeHttpBridge"))
    ) else {
        let _ = env.exception_clear();
        return false;
    };
    let Ok(class) = env.new_global_ref(class) else {
        return false;
    };
    // Probe the batch entry point while an `Env` is at hand: a stripped method answers
    // with an error here, once, instead of per tile on the worker threads.
    let batch_ok = env
        .get_static_method_id(
            &class,
            jni_str!("requestBatch"),
            jni_sig!("(Ljava/lang/String;[Ljava/lang/String;)[B"),
        )
        .is_ok();
    let _ = env.exception_clear();
    CACHED
        .set(Cached { vm, class, batch_ok })
        .is_ok() || CACHED.get().is_some()
}

fn cached() -> Result<&'static Cached> {
    CACHED
        .get()
        .ok_or_else(|| Error::Bridge("bridge not initialised; call jni_http::init first".into()))
}

/// Performs a request using an existing `Env`.
///
/// Prefer this on threads that entered from Java — it avoids an attach/detach per call.
pub fn request_with_env(
    env: &mut Env,
    method: Method,
    url: &str,
    headers: &[Header],
    body: Option<&[u8]>,
) -> Result<ResponseFrame> {
    let class = &cached()?.class;

    let jurl: JString = env
        .new_string(url)
        .map_err(|e| Error::Bridge(format!("url: {e}")))?;
    let jheaders: JByteArray = env
        .byte_array_from_slice(&frame::encode_headers(headers))
        .map_err(|e| Error::Bridge(format!("headers: {e}")))?;
    let jbody: JObject = match body {
        Some(b) => env
            .byte_array_from_slice(b)
            .map_err(|e| Error::Bridge(format!("body: {e}")))?
            .into(),
        None => JObject::null(),
    };

    let result = env
        .call_static_method(
            class,
            jni_str!("request"),
            jni_sig!("(ILjava/lang/String;[B[B)[B"),
            &[
                JValue::Int(method as i32),
                JValue::Object(&jurl),
                JValue::Object(&jheaders),
                JValue::Object(&jbody),
            ],
        )
        .map_err(|e| {
            // Leaving a pending exception poisons the next JNI call on this thread.
            let _ = env.exception_clear();
            Error::Bridge(format!("request call failed: {e}"))
        })?;

    let object = result.l().map_err(|e| Error::Bridge(format!("bad return: {e}")))?;
    if object.is_null() {
        return Err(Error::Transport("bridge returned null".into()));
    }
    let bytes = env
        .convert_byte_array(env.cast_local::<JByteArray>(object)?)
        .map_err(|e| Error::Bridge(format!("reading response frame: {e}")))?;

    let frame = ResponseFrame::decode(&bytes)
        .ok_or_else(|| Error::Bridge("malformed response frame".into()))?;
    match frame.error() {
        Some(message) => Err(Error::Transport(message)),
        None => Ok(frame),
    }
}

/// Performs a request from a thread that may not be attached to the JVM.
///
/// Attaches for the duration of the call. On a thread that already has an `Env`, use
/// [`request_with_env`] instead.
pub fn request(
    method: Method,
    url: &str,
    headers: &[Header],
    body: Option<&[u8]>,
) -> Result<ResponseFrame> {
    let cached = cached()?;
    cached
        .vm
        .attach_current_thread(|env| request_with_env(env, method, url, headers, body))
}

/// A wave of GETs against one URL in a single JNI crossing.
///
/// Sends the `Range` header values and returns one [`ResponseFrame`] per range, in order.
/// A failed slot decodes like any status-0 frame (see [`ResponseFrame::error`]); a batch
/// that cannot be framed at all is an [`Error::Bridge`], and the caller fails every slot
/// rather than hanging one.
///
/// When `init` found the batch method stripped (an old keep rule against a release
/// build), this degrades to one single `request` per range over the attached thread
/// instead of failing the wave: slower (N crossings, N attach-free calls on the
/// already-attached thread), but the map draws. The degradation is logged once at
/// `init`-adjacent volume by the caller, not per wave.
pub fn request_batch(url: &str, ranges: &[String]) -> Result<Vec<ResponseFrame>> {
    let cached = cached()?;
    if !cached.batch_ok {
        return Ok(request_batch_fallback(url, ranges));
    }
    let outcome: Result<Vec<ResponseFrame>> = cached.vm.attach_current_thread(|env| {
        let class = &cached.class;
        let jurl: JString = env
            .new_string(url)
            .map_err(|e| Error::Bridge(format!("url: {e}")))?;
        let jranges = env
            .new_object_array(
                ranges.len() as i32,
                jni_str!("java/lang/String"),
                JObject::null(),
            )
            .map_err(|e| Error::Bridge(format!("ranges: {e}")))?;
        for (i, range) in ranges.iter().enumerate() {
            let jrange: JString = env
                .new_string(range)
                .map_err(|e| Error::Bridge(format!("range: {e}")))?;
            jranges
                .set_element(env, i, jrange)
                .map_err(|e| Error::Bridge(format!("ranges: {e}")))?;
        }

        let result = env
            .call_static_method(
                class,
                jni_str!("requestBatch"),
                jni_sig!("(Ljava/lang/String;[Ljava/lang/String;)[B"),
                &[JValue::Object(&jurl), JValue::Object(&jranges)],
            )
            .map_err(|e| {
                let _ = env.exception_clear();
                Error::Bridge(format!("batch request call failed: {e}"))
            })?;

        let object = result.l().map_err(|e| Error::Bridge(format!("bad return: {e}")))?;
        if object.is_null() {
            return Err(Error::Transport("bridge returned null".into()));
        }
        let bytes = env
            .convert_byte_array(env.cast_local::<JByteArray>(object)?)
            .map_err(|e| Error::Bridge(format!("reading batch frame: {e}")))?;
        decode_batch(&bytes).ok_or_else(|| Error::Bridge("malformed batch frame".into()))
    });
    match outcome {
        // The method resolved at `init` but the call itself failed: treat a
        // "Method not found" at call time the same as a stripped method (a build
        // whose dex changed under a cached probe must still draw, just slower).
        Err(Error::Bridge(message)) if message.contains("Method not found") => {
            Ok(request_batch_fallback(url, ranges))
        }
        other => other,
    }
}

/// Whether the batch entry point is available in this build.
///
/// False when `init` probed it missing (stripped by shrinking). The tile layer
/// uses this to log the degradation once at surface creation instead of per wave.
pub fn batch_available() -> bool {
    cached().map(|cached| cached.batch_ok).unwrap_or(false)
}

/// One single `request` per range on the already-attached worker thread.
///
/// Same order and same per-slot error semantics as the batch path: a failed range
/// becomes that slot's status-0 error, never a poisoned wave. Costs N JNI calls
/// instead of one, which is what makes this a fallback rather than the default.
fn request_batch_fallback(url: &str, ranges: &[String]) -> Vec<ResponseFrame> {
    let cached = match cached() {
        Ok(cached) => cached,
        Err(_) => {
            return ranges
                .iter()
                .map(|_| status_zero_frame(url, "bridge not initialised"))
                .collect();
        }
    };
    cached
        .vm
        .attach_current_thread(|env| {
            let out: Vec<ResponseFrame> = ranges
                .iter()
                .map(|range| {
                    let headers = [(String::from("Range"), range.clone())];
                    match request_with_env(&mut *env, Method::Get, url, &headers, None) {
                        Ok(frame) => frame,
                        // `request_with_env` already reports transport failures as
                        // status-0 frames; this arm is only for bridge-level errors,
                        // which keep the same per-slot shape.
                        Err(Error::Transport(_)) => {
                            status_zero_frame(url, "transport failure")
                        }
                        Err(Error::Bridge(message)) => status_zero_frame(url, &message),
                    }
                })
                .collect();
            Ok::<Vec<ResponseFrame>, Error>(out)
        })
        .unwrap_or_else(|_| {
            ranges
                .iter()
                .map(|_| status_zero_frame(url, "could not attach to the JVM"))
                .collect()
        })
}

/// A status-0 frame carrying `reason`, matching `NativeHttpBridge.packFailure`.
fn status_zero_frame(url: &str, reason: &str) -> ResponseFrame {
    ResponseFrame {
        status: 0,
        final_url: url.into(),
        headers: Vec::new(),
        body: reason.as_bytes().to_vec(),
    }
}

/// Split a `requestBatch` reply: u32 count, then per-reply u32 length + reply frame.
///
/// Mirrors `NativeHttpBridge.packBatch` exactly (big-endian throughout). `None` on any
/// truncation or count/length disagreement — treated as a transport failure for the
/// whole wave, so every slot fails rather than one hanging.
fn decode_batch(bytes: &[u8]) -> Option<Vec<ResponseFrame>> {
    const MAX_BATCH_REPLIES: usize = 4096;
    let mut pos = 0usize;
    let take = |pos: &mut usize, n: usize| -> Option<&[u8]> {
        let end = pos.checked_add(n)?;
        let out = bytes.get(*pos..end)?;
        *pos = end;
        Some(out)
    };
    let u32_at = |pos: &mut usize| -> Option<usize> {
        let b = take(pos, 4)?;
        Some(u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as usize)
    };
    let count = u32_at(&mut pos)?;
    if count > MAX_BATCH_REPLIES {
        return None;
    }
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let len = u32_at(&mut pos)?;
        let frame = take(&mut pos, len)?;
        out.push(ResponseFrame::decode(frame)?);
    }
    if pos != bytes.len() {
        return None;
    }
    Some(out)
}

/// Convenience for a GET with no extra headers.
pub fn get(url: &str) -> Result<ResponseFrame> {
    request(Method::Get, url, &[], None)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(status: u16, body: &[u8]) -> ResponseFrame {
        ResponseFrame {
            status,
            final_url: "https://example.invalid/a".into(),
            headers: vec![("Content-Range".into(), "bytes 0-1/8".into())],
            body: body.to_vec(),
        }
    }

    /// The test-side twin of `NativeHttpBridge.packBatch`: u32 count, then u32 len + frame.
    fn pack_batch(frames: &[ResponseFrame]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&(frames.len() as u32).to_be_bytes());
        for f in frames {
            let encoded = f.encode();
            out.extend_from_slice(&(encoded.len() as u32).to_be_bytes());
            out.extend_from_slice(&encoded);
        }
        out
    }

    #[test]
    fn a_batch_decodes_one_frame_per_range_in_order() {
        let frames = vec![frame(206, &[1, 2]), frame(206, &[3]), frame(0, b"reset")];
        let back = decode_batch(&pack_batch(&frames)).expect("decode");
        assert_eq!(back, frames);
        // And a status-0 slot still decodes (the caller maps it to a per-slot error).
        assert_eq!(back[2].error().as_deref(), Some("reset"));
    }

    #[test]
    fn a_truncated_or_padded_batch_is_refused_not_partially_served() {
        let packed = pack_batch(&[frame(206, &[1]), frame(206, &[2])]);
        for cut in 0..packed.len() {
            assert!(
                decode_batch(&packed[..cut]).is_none(),
                "prefix of {cut} bytes decoded",
            );
        }
        let mut padded = packed.clone();
        padded.push(0);
        assert!(decode_batch(&padded).is_none(), "trailing bytes");
        assert!(decode_batch(&[]).is_none(), "empty");
        // A lying count is refused, not allocated into.
        let mut lying = vec![0xFFu8; 4];
        lying.extend_from_slice(&packed[4..]);
        assert!(decode_batch(&lying).is_none(), "a huge count");
    }

    #[test]
    fn a_frame_that_will_not_parse_fails_its_batch() {
        let mut packed = pack_batch(&[frame(206, &[1])]);
        // Lie about the frame length so `ResponseFrame::decode` runs past the buffer.
        let len_at = 4;
        let len = u32::from_be_bytes([
            packed[len_at],
            packed[len_at + 1],
            packed[len_at + 2],
            packed[len_at + 3],
        ]);
        packed[len_at..len_at + 4].copy_from_slice(&(len + 64).to_be_bytes());
        assert!(decode_batch(&packed).is_none());
    }
}
