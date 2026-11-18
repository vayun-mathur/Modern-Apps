//! Split-phase download JNI entries: authenticate / finish / cancel plus the
//! atomic download entry kept for the platform service path.
//!
//! Split from `jni.rs` along module lines. The STORE DATA transport and string
//! helpers stay there and are imported; the session store and option parsing
//! live here with the entries that use them.

use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jint, jstring};
use jni::{Env, EnvUnowned, jni_sig, jni_str};

use std::sync::{Mutex, MutexGuard};

use crate::download;
use crate::es10;
use crate::es10b;
use crate::jni::{new_jstring, read_string};

/// Authenticated sessions awaiting confirm / confirmation-code / finish, keyed
/// by the SM-DP+ transaction id (opaque to Kotlin).
///
/// The split-phase flow opens one ISD-R channel per phase (each native entry
/// runs inside its own `withIsdrChannel`), so the session must survive across
/// JNI calls. Kotlin drives those calls from `Dispatchers.IO`, whose pooled
/// threads are not pinned: authenticate and finish routinely land on different
/// Java threads, so the store must be process-global, not `thread_local!` —
/// a thread-local session reads back as "session expired" whenever the pool
/// picks another thread. Insertion order is kept so eviction is oldest-first.
/// The store holds at most a handful of small blobs (signed material + ids);
/// finish/cancel consume.
const MAX_SESSIONS: usize = 4;

static SESSIONS: Mutex<Vec<(String, download::DownloadSession)>> = Mutex::new(Vec::new());

fn sessions() -> MutexGuard<'static, Vec<(String, download::DownloadSession)>> {
    SESSIONS.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Stores an authenticated session under its transaction-id handle,
/// evicting oldest-first beyond [`MAX_SESSIONS`].
fn session_put(handle: String, session: download::DownloadSession) {
    let mut guard = sessions();
    guard.retain(|(h, _)| *h != handle);
    guard.push((handle, session));
    while guard.len() > MAX_SESSIONS {
        guard.remove(0);
    }
}

/// Removes and returns the session for `handle`, if one is stored.
fn session_take(handle: &str) -> Option<download::DownloadSession> {
    let mut guard = sessions();
    let pos = guard.iter().position(|(h, _)| h == handle)?;
    Some(guard.remove(pos).1)
}

/// Invalid activation-code reply for the atomic download entry.
fn invalid_activation_json() -> serde_json::Value {
    serde_json::json!({ "success": false, "message": "Invalid activation code" })
}

/// Atomic download flow with transport injected for host tests.
fn run_download<E, F>(env: &mut E, code: Option<String>, download_fn: &mut F) -> serde_json::Value
where F: FnMut(&mut E, &str) -> Result<es10b::InstallResult, String> {
    let Some(code) = code else { return invalid_activation_json(); };
    download_result_json(download_fn(env, &code))
}

/// Authenticate flow: on success stores the session under its handle.
fn run_authenticate<E, F>(env: &mut E, code: &str, options: &download::DownloadOptions, authenticate_fn: &mut F) -> serde_json::Value
where F: FnMut(&mut E, &str, &download::DownloadOptions) -> Result<download::DownloadSession, String> {
    match authenticate_fn(env, code, options) {
        Ok(session) => {
            let reply = authenticate_success_json(&session);
            session_put(session.transaction_id.clone(), session);
            reply
        }
        Err(message) => authenticate_error_json(&message),
    }
}

/// Finish flow: consumes the session, applies nickname seeding, replies JSON.
fn run_finish<E, F, N>(env: &mut E, handle: &str, code: Option<&str>, nickname: Option<&str>, finish_fn: &mut F, apply_nickname: &mut N) -> serde_json::Value
where
    F: FnMut(&mut E, &download::DownloadSession, Option<&str>) -> Result<es10b::InstallResult, String>,
    N: FnMut(&mut E, &[u8]),
{
    let Some(session) = session_take(handle) else {
        return finish_json_value(false, "Download session expired — start again");
    };
    let result = finish_fn(env, &session, code);
    if let Ok(ref ok_result) = result {
        if let Some(req) = nickname_request(ok_result, nickname) {
            apply_nickname(env, &req);
        }
    }
    download_result_json(result)
}

/// Cancel flow: consumes the session if present; `false` only for a bad handle.
fn run_cancel<E, F>(env: &mut E, handle: Option<String>, reason: jint, cancel_fn: &mut F) -> bool
where F: FnMut(&mut E, &download::DownloadSession, es10b::CancelReason) {
    let Some(handle) = handle else { return false; };
    if let Some(session) = session_take(&handle) {
        cancel_fn(env, &session, cancel_reason(reason));
    }
    true
}

/// `nativeDownloadProfile(activationCode)` — runs the full SGP.22 download for
/// an activation code and returns a JSON `{success, message, iccid}` string.
/// Must be called while the ISD-R channel is open (inside `withIsdrChannel`).
///
/// Kept for the `EuiccManagerService` platform path; the UI flow uses the
/// split-phase entries. No nickname is applied here — Settings shows the
/// carrier metadata, and the user can rename from the LUI.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeDownloadProfile<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    activation_code: JString<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        // Resolve the HTTP bridge (library:network) once; harmless if already done.
        jni_http::init(env);

        let code = read_string(env, &activation_code);
        let mut download_fn = |e: &mut Env, c: &str| crate::download::download_profile(e, c);
        let json = run_download(env, code, &mut download_fn);
        Ok(new_jstring(env, &json.to_string()))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeAuthenticate(activationCode, confirmationCode, tacHex, imei)` —
/// runs InitiateAuthentication → AuthenticateServer → AuthenticateClient and
/// returns a JSON session `{error, transactionId, carrier, profileName, iccid,
/// ccRequired}` without touching the eUICC's profile store. The opaque
/// `transactionId` handle feeds `nativeFinishDownload` / `nativeCancelDownload`.
///
/// `tacHex` is 8 hex digits (defaults to lpac's `35 29 06 11` when blank);
/// `imei` is optional digits (dropped when blank/invalid).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeAuthenticate<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    activation_code: JString<'l>,
    confirmation_code: JString<'l>,
    tac_hex: JString<'l>,
    imei: JString<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        jni_http::init(env);

        let Some(code) = read_string(env, &activation_code) else {
            return Ok(auth_error(env, "Invalid activation code"));
        };
        let options = download_options(env, &confirmation_code, &tac_hex, &imei);
        let mut authenticate_fn = |e: &mut Env, c: &str, o: &download::DownloadOptions| crate::download::authenticate(e, c, o);
        let json = run_authenticate(env, &code, &options, &mut authenticate_fn);
        Ok(new_jstring(env, &json.to_string()))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeFinishDownload(transactionId, confirmationCode)` — runs
/// PrepareDownload (with `hashCc`) → GetBoundProfilePackage → BPP install with
/// progress callbacks, returning `{success, message, iccid}`. Consumes the session.
/// `nickname` (from the carrier preview) is applied via SetNickname while the
/// channel is still open, so the profile shows a name immediately.
/// `nativeFinishDownload(transactionId, confirmationCode, nickname, callback)`
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeFinishDownload<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    transaction_id: JString<'l>,
    confirmation_code: JString<'l>,
    nickname: JString<'l>,
    callback: jni::objects::JObject<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        jni_http::init(env);

        let Some(handle) = read_string(env, &transaction_id) else {
            return Ok(finish_json(env, false, "Invalid download session"));
        };
        let code = non_empty(read_string(env, &confirmation_code));
        let nickname = non_blank(read_string(env, &nickname));
        let mut progress = JniProgress { callback: env.new_global_ref(&callback).ok() };
        let mut finish_fn = |e: &mut Env, s: &download::DownloadSession, c: Option<&str>| crate::download::finish_download(e, s, c, &mut progress);
        let mut apply_nickname = |e: &mut Env, req: &[u8]| { let _ = crate::jni::store_data(e, req); };
        let json = run_finish(env, &handle, code.as_deref(), nickname.as_deref(), &mut finish_fn, &mut apply_nickname);
        Ok(new_jstring(env, &json.to_string()))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeCancelDownload(transactionId, reason)` — ES10 CancelSession + ES9+
/// cancelSession for an authenticated-but-unfinished session. Best-effort;
/// always consumes the session and returns true.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeCancelDownload<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    transaction_id: JString<'l>,
    reason: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        let handle = read_string(env, &transaction_id);
        let mut cancel_fn = |e: &mut Env, s: &download::DownloadSession, r: es10b::CancelReason| crate::download::cancel_session(e, s, r);
        Ok(run_cancel(env, handle, reason, &mut cancel_fn))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

// ---------------------------------------------------------------------------
// Split-phase download helpers
// ---------------------------------------------------------------------------

/// Builds [`download::DownloadOptions`] from the authenticate call's string
/// args. Blank TAC hex falls back to [`es10b::DEFAULT_TAC`]; blank IMEI is
/// dropped; blank confirmation code is dropped.
fn download_options(
    env: &mut Env,
    confirmation_code: &JString,
    tac_hex: &JString,
    imei: &JString,
) -> download::DownloadOptions {
    download_options_from(
        read_string(env, confirmation_code),
        read_string(env, tac_hex),
        read_string(env, imei),
    )
}

fn download_options_from(
    confirmation_code: Option<String>,
    tac_hex: Option<String>,
    imei: Option<String>,
) -> download::DownloadOptions {
    let confirmation_code = non_blank(confirmation_code);
    let imei = non_blank(imei);
    let tac = non_blank(tac_hex)
        .and_then(|t| parse_tac(&t))
        .or(Some(es10b::DEFAULT_TAC));
    download::DownloadOptions { confirmation_code, tac, imei }
}

fn non_empty(s: Option<String>) -> Option<String> {
    s.filter(|c| !c.is_empty())
}

fn non_blank(s: Option<String>) -> Option<String> {
    s.filter(|c| !c.trim().is_empty())
}

/// `{success, message, iccid}` reply shared by both download entries.
fn download_result_json(result: Result<es10b::InstallResult, String>) -> serde_json::Value {
    match result {
        Ok(result) => serde_json::json!({
            "success": result.success,
            "message": result.message,
            "iccid": result.installed_iccid,
        }),
        Err(message) => serde_json::json!({ "success": false, "message": message }),
    }
}

fn authenticate_success_json(session: &download::DownloadSession) -> serde_json::Value {
    serde_json::json!({
        "error": serde_json::Value::Null,
        "transactionId": session.transaction_id,
        "carrier": empty_to_null(&session.metadata.service_provider),
        "profileName": empty_to_null(&session.metadata.profile_name),
        "iccid": empty_to_null(&session.metadata.iccid_display),
        "ccRequired": session.cc_required,
    })
}

fn authenticate_error_json(message: &str) -> serde_json::Value {
    serde_json::json!({
        "error": message,
        "transactionId": serde_json::Value::Null,
        "carrier": serde_json::Value::Null,
        "profileName": serde_json::Value::Null,
        "iccid": serde_json::Value::Null,
        "ccRequired": false,
    })
}

fn finish_json_value(success: bool, message: &str) -> serde_json::Value {
    serde_json::json!({ "success": success, "message": message })
}

/// SetNickname request to seed a freshly installed profile, if applicable.
fn nickname_request(result: &es10b::InstallResult, nickname: Option<&str>) -> Option<Vec<u8>> {
    if !result.success {
        return None;
    }
    let name = nickname?;
    let iccid = crate::es10::hex_decode(&result.installed_iccid);
    if iccid.is_empty() {
        return None;
    }
    Some(crate::es10::build_set_nickname(&iccid, name.trim()))
}

/// A failed/absent Kotlin progress callback never aborts the install.
fn progress_decision(result: Option<bool>) -> bool {
    result.unwrap_or(true)
}

/// Parses 8 hex digits into a TAC. Malformed input falls back to the default
/// (never block a download on a settings typo).
fn parse_tac(hex: &str) -> Option<[u8; 4]> {
    let bytes = es10::hex_decode(hex.trim());
    if bytes.len() == 4 {
        Some([bytes[0], bytes[1], bytes[2], bytes[3]])
    } else {
        None
    }
}

/// Maps the Kotlin cancel-reason int onto [`es10b::CancelReason`].
fn cancel_reason(reason: jint) -> es10b::CancelReason {
    match reason {
        1 => es10b::CancelReason::Postponed,
        2 => es10b::CancelReason::Timeout,
        3 => es10b::CancelReason::PprNotAllowed,
        4 => es10b::CancelReason::MetadataMismatch,
        5 => es10b::CancelReason::LoadBppExecutionError,
        _ => es10b::CancelReason::EndUserRejection,
    }
}

/// Renders empty strings as JSON null for the authenticate reply.
fn empty_to_null(s: &str) -> serde_json::Value {
    if s.is_empty() {
        serde_json::Value::Null
    } else {
        serde_json::Value::String(s.to_string())
    }
}

/// Authenticate-path error reply.
fn auth_error(env: &mut Env, message: &str) -> jstring {
    new_jstring(env, &authenticate_error_json(message).to_string())
}

/// Finish-path `{success, message}` reply.
fn finish_json(env: &mut Env, success: bool, message: &str) -> jstring {
    new_jstring(env, &finish_json_value(success, message).to_string())
}

/// [`download::ProgressCallback`] that forwards segment counts to Kotlin
/// `onProgress(done, total)` and aborts when it returns false. When the
/// callback cannot be retained as a global ref (OOM), progress degrades to a
/// no-op and the install still proceeds.
struct JniProgress {
    callback: Option<jni::objects::Global<jni::objects::JObject<'static>>>,
}

impl download::ProgressCallback for JniProgress {
    fn on_progress(&mut self, env: &mut Env, done: usize, total: usize) -> bool {
        let Some(callback) = &self.callback else {
            return true;
        };
        let result = env
            .call_method(
                callback.as_obj(),
                jni_str!("onProgress"),
                jni_sig!("(II)Z"),
                &[JValue::Int(done as jint), JValue::Int(total as jint)],
            )
            .and_then(|v| v.z());
        // Clear a pending Kotlin exception so later JNI calls stay valid.
        let _ = env.exception_clear();
        progress_decision(result.ok())
    }
}

#[cfg(test)]
#[path = "jni_download_tests.rs"]
mod jni_download_tests;
