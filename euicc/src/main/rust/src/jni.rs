//! Thin JNI marshalling layer bridging the Kotlin `EuiccNative` object to the
//! native SGP.22 core, plus the STORE DATA transport that carries ES10 command
//! TLVs to the ISD-R.
//!
//! The protocol/ASN.1 logic lives in [`crate::asn1`] and [`crate::es10`]; this
//! file only wraps requests in GlobalPlatform STORE DATA APDUs, drives them over
//! the `EuiccNative.transmitApdu` upcall (which owns the telephony logical
//! channel on the Kotlin side), and converts results to Kotlin types.

use jni::objects::{JByteArray, JClass, JString, JValue};
use jni::sys::{jint, jstring};
use jni::{Env, EnvUnowned, jni_sig, jni_str};

use crate::{es10, VERSION};

#[allow(dead_code)]
const EUICC_NATIVE_CLASS: &str = "com/vayunmathur/euicc/EuiccNative";
#[allow(dead_code)]
const TRANSMIT_METHOD: &str = "transmitApdu";
/// `([B)[B` — command APDU bytes → response APDU bytes (data followed by SW1 SW2).
#[allow(dead_code)]
const TRANSMIT_SIG: &str = "([B)[B";

/// Sends one command APDU to the eUICC by calling back into Kotlin
/// `EuiccNative.transmitApdu`, which transmits over the open logical channel and
/// returns the response bytes (response data followed by the two status bytes).
fn transmit_apdu(env: &mut Env, apdu: &[u8]) -> Result<Vec<u8>, String> {
    let arr: JByteArray = env
        .byte_array_from_slice(apdu)
        .map_err(|e| format!("apdu marshal: {e}"))?;
    let result = env
        .call_static_method(
            jni_str!("com/vayunmathur/euicc/EuiccNative"),
            jni_str!("transmitApdu"),
            jni_sig!("([B)[B"),
            &[JValue::Object(&arr)],
        )
        .map_err(|e| {
            // A pending exception poisons the next JNI call on this thread.
            let _ = env.exception_clear();
            format!("transmitApdu call failed: {e}")
        })?;
    let object = result.l().map_err(|e| format!("bad return: {e}"))?;
    if object.is_null() {
        return Err("transmitApdu returned null".into());
    }
    env.convert_byte_array(env.cast_local::<JByteArray>(object).map_err(|e| format!("bad return: {e}"))?)
        .map_err(|e| format!("reading response: {e}"))
}

/// Sends an ES10 command TLV to the ISD-R via GlobalPlatform STORE DATA,
/// splitting into small blocks, and returns the final response TLV with the
/// trailing `90 00` status stripped.
///
/// Block size is 63 bytes like OpenEUICC's default chunk (`PreferenceUtils`;
/// lpac's `euicc_init` falls back to 120) rather than the APDU maximum: only
/// multi-KB download payloads stress this path, and conservative chunking is
/// what the reference LPAs ship.
pub(crate) fn store_data(env: &mut Env, command: &[u8]) -> Result<Vec<u8>, String> {
    store_data_chunked(env, command, STORE_DATA_CHUNK)
}

/// STORE DATA payload bytes per APDU. OpenEUICC default; see [`store_data`].
pub(crate) const STORE_DATA_CHUNK: usize = 63;

/// Builds the STORE DATA APDUs for `command`, split into `chunk`-sized
/// blocks (clamped to 1..=255). P1 is 0x11 for an intermediate block and
/// 0x91 for the last (b8 = last block); P1's low bits carry the block
/// sequence number. A zero-length command still sends one (empty) block.
/// Pure, so the chunking is host-testable without a JVM.
pub(crate) fn build_store_data_apdus(command: &[u8], chunk: usize) -> Vec<Vec<u8>> {
    let chunk = chunk.clamp(1, 255);
    // A zero-length command still sends one (empty) block.
    let blocks: Vec<&[u8]> = if command.is_empty() {
        vec![&command[0..0]]
    } else {
        command.chunks(chunk).collect()
    };
    let last = blocks.len() - 1;
    blocks
        .iter()
        .enumerate()
        .map(|(i, block)| {
            let p1 = if i == last { 0x91 } else { 0x11 };
            let mut apdu = vec![0x80u8, 0xE2, p1, i as u8, block.len() as u8];
            apdu.extend_from_slice(block);
            apdu
        })
        .collect()
}

pub(crate) fn store_data_chunked(
    env: &mut Env,
    command: &[u8],
    chunk: usize,
) -> Result<Vec<u8>, String> {
    let mut transmit = |e: &mut Env, apdu: &[u8]| transmit_apdu(e, apdu);
    store_data_with(env, command, chunk, &mut transmit)
}

/// [`store_data_chunked`] with the APDU transport injected, so the response
/// reassembly / status-word rules are host-testable without a JVM.
pub(crate) fn store_data_with<E, F>(
    env: &mut E,
    command: &[u8],
    chunk: usize,
    transmit: &mut F,
) -> Result<Vec<u8>, String>
where
    F: FnMut(&mut E, &[u8]) -> Result<Vec<u8>, String>,
{
    let apdus = build_store_data_apdus(command, chunk);
    let last = apdus.len() - 1;

    let mut response = Vec::new();
    for (i, apdu) in apdus.iter().enumerate() {
        // Accumulate every block's response data like lpac's
        // `iter_es10x_command` (the Kotlin transport already resolves 61xx
        // per APDU, so intermediate bodies are normally empty — but when a
        // card streams chained reply bytes, only concatenation reassembles
        // them). Fail fast on a non-success status.
        let mut block_resp = transmit(env, apdu)?;
        if block_resp.len() < 2 {
            return Err("APDU response too short".into());
        }
        let sw = [block_resp[block_resp.len() - 2], block_resp[block_resp.len() - 1]];
        block_resp.truncate(block_resp.len() - 2);
        response.append(&mut block_resp);
        if sw != [0x90, 0x00] {
            return Err(format!("eUICC returned SW={:02X}{:02X}", sw[0], sw[1]));
        }
        if i == last {
            response.extend_from_slice(&sw);
        }
    }

    if response.len() < 2 {
        return Err("APDU response too short".into());
    }
    let (data, sw) = response.split_at(response.len() - 2);
    if sw != [0x90, 0x00] {
        return Err(format!("eUICC returned SW={:02X}{:02X}", sw[0], sw[1]));
    }
    Ok(data.to_vec())
}

/// Returns `s` as a new Java string, or null (used on the error path).
pub(crate) fn new_jstring(env: &mut Env, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Reads a Java string into a Rust `String`, or `None` on error.
pub(crate) fn read_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

/// Sends a request and parses its INTEGER result, returning the eUICC result
/// code (0 = success) or -1 on a transport/parse error.
fn run_result(env: &mut Env, request: &[u8], tag: u32) -> jint {
    result_code(store_data(env, request), tag)
}

/// Maps a STORE DATA reply to the JNI result code: parsed code, or -1.
fn result_code(result: Result<Vec<u8>, String>, tag: u32) -> jint {
    match result.and_then(|resp| es10::parse_result(&resp, tag)) {
        Ok(code) => code as jint,
        Err(_) => -1,
    }
}

/// Maps a remove-notification reply to the JNI result code.
fn remove_code(result: Result<Vec<u8>, String>) -> jint {
    match result.and_then(|resp| es10::parse_remove_result(&resp)) {
        Ok(code) => code as jint,
        Err(_) => -1,
    }
}

/// A negative Kotlin sequence number is invalid; anything else is a seq.
fn seq_arg(seq: jint) -> Option<u32> {
    if seq < 0 { None } else { Some(seq as u32) }
}

fn profiles_json(profiles: &[es10::ProfileInfo]) -> String {
    let arr: Vec<_> = profiles
        .iter()
        .map(|p| {
            serde_json::json!({
                "iccid": p.iccid,
                "iccidDisplay": p.iccid_display,
                "isdpAid": p.isdp_aid,
                "state": p.state,
                "class": p.class,
                "nickname": p.nickname,
                "serviceProvider": p.service_provider,
                "name": p.name,
            })
        })
        .collect();
    serde_json::Value::Array(arr).to_string()
}

fn notifications_json(notes: &[es10::NotificationInfo]) -> String {
    let arr: Vec<_> = notes
        .iter()
        .map(|n| {
            serde_json::json!({
                "seqNumber": n.seq_number,
                "operation": n.operation,
                "address": n.address,
                "iccidDisplay": n.iccid_display,
            })
        })
        .collect();
    serde_json::Value::Array(arr).to_string()
}

fn euicc_info_json(info: &es10::EuiccInfo1) -> String {
    serde_json::json!({
        "svn": info.svn,
        "ciPkIdListForVerification": info.ci_pkid_verification,
        "ciPkIdListForSigning": info.ci_pkid_signing,
    })
    .to_string()
}

/// `nativeGetProfiles()` — installed profiles as a JSON array, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeGetProfiles<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let result = store_data(env, &es10::build_get_profiles())
            .and_then(|resp| es10::parse_profiles(&resp));
        Ok(match result {
            Ok(profiles) => new_jstring(env, &profiles_json(&profiles)),
            Err(_) => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeEnableProfile(iccid)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeEnableProfile<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let Some(hex) = read_string(env, &iccid) else {
            return Ok(-1);
        };
        Ok(run_result(env, &es10::build_enable(&es10::hex_decode(&hex), true), es10::RESULT_TAG_ENABLE))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeDisableProfile(iccid)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeDisableProfile<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let Some(hex) = read_string(env, &iccid) else {
            return Ok(-1);
        };
        Ok(run_result(env, &es10::build_disable(&es10::hex_decode(&hex), true), es10::RESULT_TAG_DISABLE))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeDeleteProfile(iccid)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeDeleteProfile<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let Some(hex) = read_string(env, &iccid) else {
            return Ok(-1);
        };
        Ok(run_result(env, &es10::build_delete(&es10::hex_decode(&hex)), es10::RESULT_TAG_DELETE))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeSetNickname(iccid, nickname)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeSetNickname<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
    nickname: JString<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let (Some(hex), Some(name)) = (read_string(env, &iccid), read_string(env, &nickname))
        else {
            return Ok(-1);
        };
        Ok(run_result(
            env,
            &es10::build_set_nickname(&es10::hex_decode(&hex), &name),
            es10::RESULT_TAG_SET_NICKNAME,
        ))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeListNotifications()` — pending notifications as a JSON array, or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeListNotifications<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let result = store_data(env, &es10::build_list_notifications())
            .and_then(|resp| es10::parse_notifications(&resp));
        Ok(match result {
            Ok(notes) => new_jstring(env, &notifications_json(&notes)),
            Err(_) => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeRemoveNotification(seq)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeRemoveNotification<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    seq: jint,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let Some(seq) = seq_arg(seq) else {
            return Ok(-1);
        };
        Ok(remove_code(store_data(env, &es10::build_remove_notification(seq))))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}


/// `nativeVersion()` — native core version string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeVersion<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        Ok(new_jstring(env, VERSION))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeGetEid()` — 32-hex-digit EID, or null on error (with a Kotlin
/// exception left pending is avoided; null signals failure).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeGetEid<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let result = store_data(env, &es10::build_get_eid())
            .and_then(|resp| es10::parse_eid(&resp));
        Ok(match result {
            Ok(eid) => new_jstring(env, &eid),
            Err(_) => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// `nativeGetEuiccInfo()` — EUICCInfo1 subset as a JSON string, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeGetEuiccInfo<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let result = store_data(env, &es10::build_get_euicc_info1())
            .and_then(|resp| es10::parse_euicc_info1(&resp));
        Ok(match result {
            Ok(info) => new_jstring(env, &euicc_info_json(&info)),
            Err(_) => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[cfg(test)]
#[path = "jni_tests.rs"]
mod jni_tests;
