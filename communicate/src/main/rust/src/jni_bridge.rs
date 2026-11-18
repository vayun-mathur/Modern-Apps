//! JNI surface for `com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto`.
//! Kotlin owns persistence (Room) and passes session/sender-key records as opaque bytes.

use crate::crypto::{self, CryptoError};
use crate::group;
use crate::session::{self, PreKeyBundle};
use crate::signal;
use crate::wire::{PreKeySignalMessage, SenderKeyDistributionMessage, SignalMessage};
use crate::OsRng;
use jni::objects::{JByteArray, JClass};
use jni::strings::JNIString;
use jni::sys::{jboolean, jbyteArray, jint, jobjectArray};
use jni::{Env, EnvUnowned, jni_str};
use rand_core::Rng;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn bytes_in<'a>(env: &mut Env, arr: &JByteArray<'a>) -> Option<Vec<u8>> {
    if arr.is_null() {
        return None;
    }
    env.convert_byte_array(arr).ok()
}

fn bytes_out(env: &mut Env, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn pair_out(env: &mut Env, a: &[u8], b: &[u8]) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(2, JByteArray::null()) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };
    let ja = match env.byte_array_from_slice(a) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jb = match env.byte_array_from_slice(b) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    if arr.set_element(&*env, 0, &ja).is_err() || arr.set_element(&*env, 1, &jb).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

fn triple_out(env: &mut Env, a: &[u8], b: &[u8], c: &[u8]) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(3, JByteArray::null()) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };
    let ja = match env.byte_array_from_slice(a) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jb = match env.byte_array_from_slice(b) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    let jc = match env.byte_array_from_slice(c) {
        Ok(x) => x,
        Err(_) => return std::ptr::null_mut(),
    };
    if arr.set_element(&*env, 0, &ja).is_err()
        || arr.set_element(&*env, 1, &jb).is_err()
        || arr.set_element(&*env, 2, &jc).is_err()
    {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

fn throw_runtime(env: &mut Env, msg: &str) {
    let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), JNIString::from(msg));
}

fn parse_32(label: &str, data: &[u8]) -> Result<[u8; 32], String> {
    if data.len() != 32 {
        return Err(format!("{label} must be 32 bytes, got {}", data.len()));
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(data);
    Ok(out)
}

fn parse_64(label: &str, data: &[u8]) -> Result<[u8; 64], String> {
    if data.len() != 64 {
        return Err(format!("{label} must be 64 bytes, got {}", data.len()));
    }
    let mut out = [0u8; 64];
    out.copy_from_slice(data);
    Ok(out)
}

// ---------------------------------------------------------------------------
// Inner implementations (panic-safe)
// ---------------------------------------------------------------------------

fn generate_keypair_inner(env: &mut Env) -> jbyteArray {
    let mut rng = OsRng::default();
    let (priv_b, pub_b) = crypto::generate_key_pair(&mut rng);
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(&priv_b);
    out.extend_from_slice(&pub_b);
    bytes_out(&mut *env, &out)
}

fn public_from_private_inner<'a>(
    env: &mut Env,
    private: JByteArray<'a>,
) -> jbyteArray {
    let priv_bytes = match bytes_in(&mut *env, &private) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "private key bytes null");
            return std::ptr::null_mut();
        }
    };
    let priv_32 = match parse_32("private", &priv_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let pub_32 = crypto::public_from_private(&priv_32);
    bytes_out(&mut *env, &pub_32)
}

fn x25519_agreement_inner<'a>(
    env: &mut Env,
    private: JByteArray<'a>,
    public: JByteArray<'a>,
) -> jbyteArray {
    let (priv_bytes, pub_bytes) = match (bytes_in(&mut *env, &private), bytes_in(&mut *env, &public)) {
        (Some(a), Some(b)) => (a, b),
        _ => {
            throw_runtime(&mut *env, "private or public null");
            return std::ptr::null_mut();
        }
    };
    let p32 = match parse_32("private", &priv_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let pub32 = match parse_32("public", &pub_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let shared = crypto::agreement(&p32, &pub32);
    bytes_out(&mut *env, &shared)
}

fn sign_inner<'a>(
    env: &mut Env,
    private: JByteArray<'a>,
    message: JByteArray<'a>,
) -> jbyteArray {
    let (priv_bytes, msg_bytes) = match (bytes_in(&mut *env, &private), bytes_in(&mut *env, &message)) {
        (Some(a), Some(b)) => (a, b),
        _ => {
            throw_runtime(&mut *env, "private or message null");
            return std::ptr::null_mut();
        }
    };
    let p32 = match parse_32("private", &priv_bytes) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let mut rng = OsRng::default();
    let mut random = [0u8; 64];
    rng.fill_bytes(&mut random);
    let sig = crypto::sign(&p32, &msg_bytes, &random);
    bytes_out(&mut *env, &sig)
}

fn verify_inner<'a>(
    env: &mut Env,
    public: JByteArray<'a>,
    message: JByteArray<'a>,
    signature: JByteArray<'a>,
) -> jboolean {
    let (pub_b, msg_b, sig_b) = match (
        bytes_in(&mut *env, &public),
        bytes_in(&mut *env, &message),
        bytes_in(&mut *env, &signature),
    ) {
        (Some(a), Some(b), Some(c)) => (a, b, c),
        _ => return false,
    };
    let pub32 = match parse_32("public", &pub_b) {
        Ok(v) => v,
        Err(_) => return false,
    };
    let sig64 = match parse_64("signature", &sig_b) {
        Ok(v) => v,
        Err(_) => return false,
    };
    crypto::verify(&pub32, &msg_b, &sig64)
}

#[allow(clippy::too_many_arguments)]
fn process_prekey_bundle_inner<'a>(
    env: &mut Env,
    local_priv: JByteArray<'a>,
    local_pub: JByteArray<'a>,
    local_reg_id: jint,
    reg_id: jint,
    pre_key_id: jint,
    pre_key_public: JByteArray<'a>,
    signed_pre_key_id: jint,
    signed_pre_key_public: JByteArray<'a>,
    signed_pre_key_sig: JByteArray<'a>,
    identity_key: JByteArray<'a>,
) -> jbyteArray {
    let local_priv_b = match bytes_in(&mut *env, &local_priv) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "local_identity_private null");
            return std::ptr::null_mut();
        }
    };
    let local_pub_b = match bytes_in(&mut *env, &local_pub) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "local_identity_public null");
            return std::ptr::null_mut();
        }
    };
    let spk_pub_b = match bytes_in(&mut *env, &signed_pre_key_public) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "signed_pre_key_public null");
            return std::ptr::null_mut();
        }
    };
    let spk_sig_b = match bytes_in(&mut *env, &signed_pre_key_sig) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "signed_pre_key_signature null");
            return std::ptr::null_mut();
        }
    };
    let id_key_b = match bytes_in(&mut *env, &identity_key) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "identity_key null");
            return std::ptr::null_mut();
        }
    };

    let local_priv_32 = match parse_32("local_identity_private", &local_priv_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let local_pub_32 = match parse_32("local_identity_public", &local_pub_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let spk_pub_32 = match parse_32("signed_pre_key_public", &spk_pub_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };
    let id_key_32 = match parse_32("identity_key", &id_key_b) {
        Ok(v) => v,
        Err(e) => {
            throw_runtime(&mut *env, &e);
            return std::ptr::null_mut();
        }
    };

    let (pre_key_id_opt, pre_key_pub_opt) = if pre_key_public.is_null() || pre_key_id < 0 {
        (None, None)
    } else {
        let pk_pub_b = match bytes_in(&mut *env, &pre_key_public) {
            Some(b) => b,
            None => {
                throw_runtime(&mut *env, "pre_key_public conversion failed");
                return std::ptr::null_mut();
            }
        };
        let pk_pub_32 = match parse_32("pre_key_public", &pk_pub_b) {
            Ok(v) => v,
            Err(e) => {
                throw_runtime(&mut *env, &e);
                return std::ptr::null_mut();
            }
        };
        (Some(pre_key_id as u32), Some(pk_pub_32))
    };

    let bundle = PreKeyBundle {
        registration_id: reg_id as u32,
        pre_key_id: pre_key_id_opt,
        pre_key_public: pre_key_pub_opt,
        signed_pre_key_id: signed_pre_key_id as u32,
        signed_pre_key_public: spk_pub_32,
        signed_pre_key_signature: spk_sig_b,
        identity_key: id_key_32,
    };

    let mut rng = OsRng::default();
    let state = match session::process_pre_key_bundle(
        &mut rng,
        &bundle,
        &local_priv_32,
        &local_pub_32,
        local_reg_id as u32,
    ) {
        Ok(s) => s,
        Err(CryptoError(msg)) => {
            throw_runtime(&mut *env, msg);
            return std::ptr::null_mut();
        }
    };
    bytes_out(&mut *env, &state.serialize())
}

fn encrypt_inner<'a>(
    env: &mut Env,
    session_bytes: JByteArray<'a>,
    plaintext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(&mut *env, &session_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "session_bytes null");
            return std::ptr::null_mut();
        }
    };
    let pt_b = match bytes_in(&mut *env, &plaintext) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "plaintext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match session::SessionState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(msg)) => {
            throw_runtime(&mut *env, msg);
            return std::ptr::null_mut();
        }
    };
    let encrypted = match session::encrypt(&mut state, &pt_b) {
        Ok(e) => e,
        Err(CryptoError(msg)) => {
            throw_runtime(&mut *env, msg);
            return std::ptr::null_mut();
        }
    };
    let new_sess = state.serialize();
    let is_pre = vec![if encrypted.is_pre_key { 1u8 } else { 0u8 }];
    triple_out(&mut *env, &is_pre, &encrypted.body, &new_sess)
}

fn decrypt_message_inner<'a>(
    env: &mut Env,
    session_bytes: JByteArray<'a>,
    ciphertext: JByteArray<'a>,
) -> jobjectArray {
    let sess_b = match bytes_in(&mut *env, &session_bytes) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "session_bytes null");
            return std::ptr::null_mut();
        }
    };
    let ct_b = match bytes_in(&mut *env, &ciphertext) {
        Some(b) => b,
        None => {
            throw_runtime(&mut *env, "ciphertext null");
            return std::ptr::null_mut();
        }
    };
    let mut state = match session::SessionState::deserialize(&sess_b) {
        Ok(s) => s,
        Err(CryptoError(msg)) => {
            throw_runtime(&mut *env, msg);
            return std::ptr::null_mut();
        }
    };
    let msg = match SignalMessage::parse(&ct_b) {
        Ok(m) => m,
        Err(CryptoError(e)) => {
            throw_runtime(&mut *env, e);
            return std::ptr::null_mut();
        }
    };
    let mut rng = OsRng::default();
    let pt = match session::decrypt(&mut rng, &mut state, &msg) {
        Ok(p) => p,
        Err(CryptoError(e)) => {
            throw_runtime(&mut *env, e);
            return std::ptr::null_mut();
        }
    };
    let new_sess = state.serialize();
    pair_out(&mut *env, &pt, &new_sess)
}

include!("jni_bridge_part1.rs");
include!("jni_bridge_part2.rs");

#[cfg(test)]
#[path = "jni_bridge_tests.rs"]
mod jni_tests;
