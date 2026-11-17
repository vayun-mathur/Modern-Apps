//! JNI surface for `…rcs.e2e.RustMlsCrypto`.
//!
//! Flat byte protocol: every call takes opaque blobs in and returns
//! `byte[][]` (`[storage_out, result…]`) or `null` on error (with a
//! `RuntimeException` thrown carrying the message). Kotlin persists
//! `storage_out` in Room alongside its conversation rows.

use crate::group::{self, MESSAGE_MAGIC, WELCOME_MAGIC};
use crate::keys;
use crate::message::payload_kind;
use jni::objects::{JByteArray, JClass, JObjectArray};
use jni::strings::JNIString;
use jni::sys::{jbyteArray, jint, jobjectArray};
use jni::{Env, EnvUnowned, jni_str};

// PACKAGE STRUCTURE EXCEPTION (JNI)

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

fn throw_runtime(env: &mut Env, msg: &str) {
    let _ = env.throw_new(jni_str!("java/lang/RuntimeException"), JNIString::from(msg));
}

fn arrays_out(env: &mut Env, parts: &[&[u8]]) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(parts.len(), JByteArray::null()) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, part) in parts.iter().enumerate() {
        let jb = match env.byte_array_from_slice(part) {
            Ok(x) => x,
            Err(_) => return std::ptr::null_mut(),
        };
        if arr.set_element(&*env, i, &jb).is_err() {
            return std::ptr::null_mut();
        }
    }
    arr.into_raw()
}

fn fail(env: &mut Env, msg: String) -> jobjectArray {
    throw_runtime(env, &msg);
    std::ptr::null_mut()
}

/// Generate an identity for `e164`. Returns `[storage(empty), identity]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_generateIdentity<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    e164: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let id_b = match bytes_in(&mut *env, &e164) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "e164 null".to_string())),
        };
        let id = String::from_utf8(id_b).unwrap_or_default();
        Ok(match keys::generate_identity(&id) {
            Ok(state) => arrays_out(&mut *env, &[&[], &keys::serialize_identity(&state)]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Build a key package. `(storage_in, identity)` → `[storage_out, key_package_tls]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_buildKeyPackage<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let id = match bytes_in(&mut *env, &identity) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "identity null".to_string())),
        };
        Ok(match group::build_key_package(&s, &id) {
            Ok((storage_out, kp)) => arrays_out(&mut *env, &[&storage_out, &kp]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Create a group. `(storage_in, identity)` → `[storage_out, group_id]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_createGroup<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let id = match bytes_in(&mut *env, &identity) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "identity null".to_string())),
        };
        Ok(match group::create_group(&s, &id) {
            Ok(created) => arrays_out(&mut *env, &[&created.storage, &created.group_id]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Add members. `(storage_in, identity, group_id, key_packages[])`
/// → `[storage_out, commit_tls, welcome_framed]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_addMembers<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
    group_id: JByteArray<'local>,
    key_packages: JObjectArray<'local, JByteArray<'local>>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let id = match bytes_in(&mut *env, &identity) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "identity null".to_string())),
        };
        let gid = match bytes_in(&mut *env, &group_id) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "group_id null".to_string())),
        };
        let len = key_packages.len(&*env).unwrap_or(0);
        let mut kps = Vec::with_capacity(len);
        for i in 0..len {
            let item: JByteArray = match key_packages.get_element(&mut *env, i) {
                Ok(o) => o,
                Err(_) => return Ok(fail(&mut *env, "key package null".to_string())),
            };
            match bytes_in(&mut *env, &item) {
                Some(b) => kps.push(b),
                None => return Ok(fail(&mut *env, "key package null".to_string())),
            }
        }
        Ok(match group::add_members(&s, &id, &gid, &kps) {
            Ok(added) => arrays_out(&mut *env, &[&added.storage, &added.commit, &added.welcome]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Join from a Welcome envelope. `(storage_in, welcome_framed)`
/// → `[storage_out, group_id]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_joinGroup<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    welcome: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let w = match bytes_in(&mut *env, &welcome) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "welcome null".to_string())),
        };
        Ok(match group::join_group(&s, &w) {
            Ok((storage_out, gid)) => arrays_out(&mut *env, &[&storage_out, &gid]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Encrypt. `(storage_in, identity, group_id, plaintext)`
/// → `[storage_out, framed_payload]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_encrypt<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
    group_id: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let id = match bytes_in(&mut *env, &identity) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "identity null".to_string())),
        };
        let gid = match bytes_in(&mut *env, &group_id) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "group_id null".to_string())),
        };
        let pt = bytes_in(&mut *env, &plaintext).unwrap_or_default();
        Ok(match group::encrypt(&s, &id, &gid, &pt) {
            Ok(enc) => arrays_out(&mut *env, &[&enc.storage, &enc.payload]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Decrypt. `(storage_in, group_id, framed_payload)`
/// → `[storage_out, is_application(1B), plaintext]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_decrypt<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    group_id: JByteArray<'local>,
    payload: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let gid = match bytes_in(&mut *env, &group_id) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "group_id null".to_string())),
        };
        let p = match bytes_in(&mut *env, &payload) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "payload null".to_string())),
        };
        Ok(match group::decrypt(&s, &gid, &p) {
            Ok(dec) => {
                let flag = [u8::from(dec.is_application)];
                arrays_out(&mut *env, &[&dec.storage, &flag, &dec.plaintext])
            }
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Remove members by leaf index. `(storage_in, identity, group_id, indices_csv)`
/// → `[storage_out, commit_tls]`. Indices are ASCII decimal, comma-separated.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_removeMembers<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
    group_id: JByteArray<'local>,
    indices_csv: JByteArray<'local>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let s = bytes_in(&mut *env, &storage).unwrap_or_default();
        let id = match bytes_in(&mut *env, &identity) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "identity null".to_string())),
        };
        let gid = match bytes_in(&mut *env, &group_id) {
            Some(b) => b,
            None => return Ok(fail(&mut *env, "group_id null".to_string())),
        };
        let csv = bytes_in(&mut *env, &indices_csv).unwrap_or_default();
        let indices: Vec<u32> = String::from_utf8_lossy(&csv)
            .split(',')
            .filter_map(|t| t.trim().parse().ok())
            .collect();
        Ok(match group::remove_members(&s, &id, &gid, &indices) {
            Ok((storage_out, commit)) => arrays_out(&mut *env, &[&storage_out, &commit]),
            Err(e) => fail(&mut *env, e.to_string()),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Probe framing: 0=welcome, 1=message, 2=unknown.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_payloadKind<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
    payload: JByteArray<'local>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let p = bytes_in(&mut *env, &payload).unwrap_or_default();
        Ok(match payload_kind(&p) {
            crate::message::PayloadKind::Welcome => 0,
            crate::message::PayloadKind::Message => 1,
            crate::message::PayloadKind::Unknown => 2,
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Magic prefixes (for Kotlin-side routing without a JNI round trip).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_welcomeMagic<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        Ok(bytes_out(&mut *env, WELCOME_MAGIC))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Magic prefixes (for Kotlin-side routing without a JNI round trip).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_messageMagic<
    'local,
>(
    mut env: EnvUnowned<'local>,
    _cls: JClass<'local>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        Ok(bytes_out(&mut *env, MESSAGE_MAGIC))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
