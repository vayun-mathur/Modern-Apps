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

        let Some(code) = read_string(env, &activation_code) else {
            let json = serde_json::json!({ "success": false, "message": "Invalid activation code" });
            return Ok(new_jstring(env, &json.to_string()));
        };

        let json = match crate::download::download_profile(env, &code) {
            Ok(result) => serde_json::json!({
                "success": result.success,
                "message": result.message,
                "iccid": result.installed_iccid,
            }),
            Err(message) => serde_json::json!({ "success": false, "message": message }),
        };
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
    // Stash the authenticated session for finish/cancel.
    let json = match crate::download::authenticate(env, &code, &options) {
        Ok(session) => {
            let handle = session.transaction_id.clone();
            let reply = serde_json::json!({
                "error": serde_json::Value::Null,
                "transactionId": handle,
                "carrier": empty_to_null(&session.metadata.service_provider),
                "profileName": empty_to_null(&session.metadata.profile_name),
                "iccid": empty_to_null(&session.metadata.iccid_display),
                "ccRequired": session.cc_required,
            });
            session_put(handle, session);
            reply
        }
        Err(message) => serde_json::json!({
            "error": message,
            "transactionId": serde_json::Value::Null,
            "carrier": serde_json::Value::Null,
            "profileName": serde_json::Value::Null,
            "iccid": serde_json::Value::Null,
            "ccRequired": false,
        }),
    };
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
        let code = read_string(env, &confirmation_code).filter(|c| !c.is_empty());
        let nickname = read_string(env, &nickname).filter(|c| !c.trim().is_empty());
        let session = session_take(&handle);
        let Some(session) = session else {
            return Ok(finish_json(env, false, "Download session expired — start again"));
        };

        let mut progress = JniProgress {
            callback: env.new_global_ref(&callback).ok(),
        };
        let json = match crate::download::finish_download(env, &session, code.as_deref(), &mut progress)
    {
        Ok(result) => {
            // Seed the profile nickname from the carrier preview while the
            // channel is open; best-effort (a rename can always be retried).
            if result.success {
                if let Some(name) = nickname.as_deref() {
                    let iccid = crate::es10::hex_decode(&result.installed_iccid);
                    if !iccid.is_empty() {
                        let _ = crate::jni::store_data(
                            env,
                            &crate::es10::build_set_nickname(&iccid, name.trim()),
                        );
                    }
                }
            }
            serde_json::json!({
                "success": result.success,
                "message": result.message,
                "iccid": result.installed_iccid,
            })
        }
        Err(message) => serde_json::json!({ "success": false, "message": message }),
    };
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
        let Some(handle) = read_string(env, &transaction_id) else {
            return Ok(false);
        };
        let session = session_take(&handle);
        if let Some(session) = session {
            crate::download::cancel_session(env, &session, cancel_reason(reason));
        }
        Ok(true)
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
    let confirmation_code = read_string(env, confirmation_code).filter(|c| !c.trim().is_empty());
    let imei = read_string(env, imei).filter(|c| !c.trim().is_empty());
    let tac = read_string(env, tac_hex)
        .filter(|t| !t.trim().is_empty())
        .and_then(|t| parse_tac(&t))
        .or(Some(es10b::DEFAULT_TAC));
    download::DownloadOptions {
        confirmation_code,
        tac,
        imei,
    }
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
    let json = serde_json::json!({
        "error": message,
        "transactionId": serde_json::Value::Null,
        "carrier": serde_json::Value::Null,
        "profileName": serde_json::Value::Null,
        "iccid": serde_json::Value::Null,
        "ccRequired": false,
    });
    new_jstring(env, &json.to_string())
}

/// Finish-path `{success, message}` reply.
fn finish_json(env: &mut Env, success: bool, message: &str) -> jstring {
    let json = serde_json::json!({ "success": success, "message": message });
    new_jstring(env, &json.to_string())
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
        result.unwrap_or(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(id: &str) -> download::DownloadSession {
        download::DownloadSession::for_test(id)
    }

    #[test]
    fn session_survives_take_on_another_thread() {
        sessions().clear();
        session_put("tx-cross-thread".to_string(), session("tx-cross-thread"));
        // Dispatchers.IO does not pin authenticate/finish to one thread; the
        // take must succeed from a different OS thread than the put.
        let taken = std::thread::spawn(|| session_take("tx-cross-thread"))
            .join()
            .unwrap();
        assert_eq!(taken.unwrap().transaction_id, "tx-cross-thread");
        assert!(session_take("tx-cross-thread").is_none());
    }

    #[test]
    fn session_eviction_is_oldest_first() {
        sessions().clear();
        for i in 0..=MAX_SESSIONS {
            session_put(format!("tx-{i}"), session(&format!("tx-{i}")));
        }
        assert!(session_take("tx-0").is_none(), "oldest must be evicted");
        for i in 1..=MAX_SESSIONS {
            assert!(session_take(&format!("tx-{i}")).is_some());
        }
    }
}
