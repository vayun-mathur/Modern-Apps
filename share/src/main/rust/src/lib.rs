//! Nearby Share / Quick Share protocol core (no Play Services).
//!
//! Owns: the Nearby Connections connection handshake, the BetoCore UKEY2 D2D
//! handshake with the **`AES_256_CBC-HMAC_SHA256`** record protocol, the paired-key
//! exchange, `OfflineFrame`/Sharing `Frame` encode/decode and payload chunking,
//! plus the `BleAdvertisement` byte codec. Transport (NSD/BLE discovery, TCP
//! socket) stays in Kotlin.
//!
//! Wire facts are recovered from the GMS 26.24.34 decompile; see
//! `share/QUICK_SHARE_VERIFICATION.md` and the module docs in [`frame`].

// The JNI entry points mirror the Java parameter names in `ShareNative.kt`, which are
// camelCase by Kotlin convention. Renaming them here would only make the two sides
// harder to diff.
#![allow(non_snake_case)]

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::{Env, EnvUnowned};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

mod ble_adv;
mod endpoint_info;
mod frame;
mod payload;
mod presence;
mod session;

use session::{Role, Session};

// ---------------------------------------------------------------------------
// Global session registry
// ---------------------------------------------------------------------------

static SESSIONS: OnceLock<Mutex<HashMap<i64, Session>>> = OnceLock::new();
static NEXT_ID: OnceLock<Mutex<i64>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn next_id() -> &'static Mutex<i64> {
    NEXT_ID.get_or_init(|| Mutex::new(1))
}

/// Run `f` with the session for `handle`, or return `fallback` if it is gone.
///
/// A poisoned mutex means another thread panicked inside the protocol core; the
/// session map is still structurally sound, so recover the guard rather than
/// propagating the panic across the JNI boundary (which is undefined behaviour).
fn with_session<T>(handle: i64, fallback: T, f: impl FnOnce(&mut Session) -> T) -> T {
    let mut map = match sessions().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    match map.get_mut(&handle) {
        Some(s) => f(s),
        None => fallback,
    }
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

fn bytes_in(env: &Env, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}
fn bytes_out(env: &mut Env, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
fn str_in(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

// ---------------------------------------------------------------------------
// JNI API — must match share/PROTOCOL_CONTRACT.md and ShareNative.kt.
// Class: com.vayunmathur.share.protocol.ShareNative
// Naming: Java_com_vayunmathur_share_protocol_ShareNative_<method>
// ---------------------------------------------------------------------------

/// long nativeInit(String localName, byte[] localEndpointInfo, String localEndpointId, boolean isInitiator) -> sessionHandle
///
/// `isInitiator` must be `true` for the side that dialled the TCP socket and
/// `false` for the side that accepted it. Only the initiator sends
/// `CONNECTION_REQUEST`, and only the initiator is the UKEY2 client.
///
/// `localEndpointId` must be the id this device advertises, so
/// `ConnectionRequestFrame.endpoint_id` matches the mDNS `WifiLanServiceInfo`; an empty
/// string falls back to a fresh random id.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeInit<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    jLocalName: JString<'l>,
    jLocalEndpointInfo: JByteArray<'l>,
    jLocalEndpointId: JString<'l>,
    isInitiator: jboolean,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        let local_name = str_in(env, &jLocalName).unwrap_or_else(|| "Share".to_string());
        let local_info = bytes_in(env, &jLocalEndpointInfo).unwrap_or_default();
        let local_endpoint_id = str_in(env, &jLocalEndpointId).unwrap_or_default();
        let role = if isInitiator {
            Role::Initiator
        } else {
            Role::Responder
        };
        let id = {
            let mut guard = match next_id().lock() {
                Ok(g) => g,
                Err(poisoned) => poisoned.into_inner(),
            };
            let id = *guard;
            *guard = guard.saturating_add(1);
            id
        };
        let sess = Session::new(role, local_name, local_info, local_endpoint_id);
        let mut map = match sessions().lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let _ = map.insert(id, sess);
        Ok(id)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeFeedInbound(long handle, byte[] bytes) -> 0 ok, <0 error
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeFeedInbound<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jBytes: JByteArray<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let bytes = match bytes_in(env, &jBytes) {
            Some(b) => b,
            None => return Ok(-1),
        };
        Ok(with_session(handle, -2, |s| s.feed_inbound(&bytes)))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// byte[] nativeDrainOutbound(long handle) -> bytes to send, or null if none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDrainOutbound<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        Ok(match with_session(handle, None, Session::outbound_drain) {
            Some(bytes) if !bytes.is_empty() => bytes_out(env, &bytes),
            _ => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeQueryState(long handle) -> State ordinal (see PROTOCOL_CONTRACT.md)
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryState<'l>(
    _env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, |s| s.query_state())
}

/// byte[] nativeQueryPendingFiles(long handle) -> JSON array of {name,sizeBytes,mimeType}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryPendingFiles<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let json = with_session(handle, None, |s| Some(file_list_json(s.pending_files())));
        Ok(match json {
            Some(j) => bytes_out(env, j.as_bytes()),
            None => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeAccept(long handle, boolean accept, String destDir) 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeAccept<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    accept: jboolean,
    jDestDir: JString<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let dest = str_in(env, &jDestDir).unwrap_or_default();
        Ok(with_session(handle, -1, |s| s.accept(accept, &dest)))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeSetFilesToSend(long handle, byte[] json) -> 0 ok
///
/// `json` has the same `[{"name","sizeBytes","mimeType"}]` shape that
/// `nativeQueryPendingFiles` returns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeSetFilesToSend<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jJson: JByteArray<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let Some(bytes) = bytes_in(env, &jJson) else {
            return Ok(-1);
        };
        let Ok(text) = String::from_utf8(bytes) else {
            return Ok(-1);
        };
        let Some(files) = parse_file_list_json(&text) else {
            return Ok(-1);
        };
        Ok(with_session(handle, -1, |s| {
            s.set_pending_files_for_send(files);
            0
        }))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeQueueIntroduction(long handle) -> 0 ok
///
/// Announces the files staged by `nativeSetFilesToSend`. Safe to call before the
/// paired-key exchange finishes: the frame is held and emitted once the session is
/// ready.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueueIntroduction<'l>(
    _env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, Session::queue_introduction)
}

/// int nativeSendKeepAlive(long handle) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeSendKeepAlive<'l>(
    _env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, Session::send_keep_alive)
}

/// String nativeQueryFailureReason(long handle) -> why the session failed, or null.
///
/// Null while the session is healthy, for an unknown handle, or when the failure
/// carried no reason.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryFailureReason<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jni::sys::jobject {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jobject> {
        let reason = with_session(handle, None, |s| s.failure_reason().map(str::to_owned));
        Ok(match reason {
            Some(r) => match env.new_string(r) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// String nativeQueryPeerName(long handle) -> the peer's advertised device name, or null.
///
/// Null until the peer's `CONNECTION_REQUEST` has been read, and for an unknown handle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryPeerName<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jni::sys::jobject {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jobject> {
        let name = with_session(handle, None, |s| s.peer_name().map(str::to_owned));
        Ok(match name {
            Some(n) => match env.new_string(n) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}


// ---------------------------------------------------------------------------
// Nearby Connections BleAdvertisement + service-id derivation (ble_adv.rs).
// The remaining discovery codecs live in lib_part1.rs as
// Java_com_vayunmathur_share_protocol_ShareNativeDiscovery_* to match
// ShareNativeDiscovery.kt.
// ---------------------------------------------------------------------------

/// String nativeMdnsServiceType() -> `_FC9F5ED42C8A._tcp`
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNativeDiscovery_nativeMdnsServiceType<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
) -> jni::sys::jobject {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jobject> {
        Ok(match env.new_string(ble_adv::mdns_service_type()) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// byte[] nativeBleServiceIdHash() -> the 3-byte truncated SHA-256 of "NearbySharing"
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNativeDiscovery_nativeBleServiceIdHash<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        Ok(bytes_out(env, &ble_adv::ble_service_id_hash()))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// String nativeQueryTrace(long handle) -> recent protocol events, one per line, or null.
///
/// Diagnostic: names the frames each side actually exchanged. A peer that goes quiet gives
/// no other clue about which frame it disliked, and the wire is encrypted, so a packet
/// capture cannot answer it either.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryTrace<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jni::sys::jobject {
    env.with_env(|env| -> jni::errors::Result<jni::sys::jobject> {
        let text = with_session(handle, None, |s| Some(s.trace_text()));
        Ok(match text {
            Some(t) => match env.new_string(t) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// void nativeDestroy(long handle)
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDestroy<'l>(
    _env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) {
    let mut map = match sessions().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let _ = map.remove(&handle);
}

include!("lib_part1.rs");
include!("lib_part2.rs");
include!("lib_part3.rs");

#[cfg(test)]
#[path = "lib_tests.rs"]
mod lib_tests;
#[cfg(test)]
#[path = "discovery_tests.rs"]
mod discovery_tests;
