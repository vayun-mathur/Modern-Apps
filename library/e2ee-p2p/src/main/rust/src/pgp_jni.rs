//! JNI surface for `com.vayunmathur.e2ee.PgpNative`.
//! Armor crosses as UTF-8 bytes; ciphertext crosses as raw bytes.

use crate::pgp::*;

use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jobjectArray};
use jni::{Env, EnvUnowned};

fn jbytes_in(env: &Env, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}

fn jbytes_out(env: &mut Env, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn jstring_in(env: &mut Env, arr: &JByteArray) -> Option<String> {
    let bytes = jbytes_in(env, arr)?;
    String::from_utf8(bytes).ok()
}

fn jstring_out(env: &mut Env, s: &str) -> jbyteArray {
    jbytes_out(env, s.as_bytes())
}

/// Return a Java `byte[][]` of exactly three elements (secret, public,
/// fingerprint), or null.
fn triple_out(env: &mut Env, a: &str, b: &str, c: &str) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(3, JByteArray::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let items = [a.as_bytes(), b.as_bytes(), c.as_bytes()];
    for (i, data) in items.iter().enumerate() {
        let element = match env.byte_array_from_slice(data) {
            Ok(x) => x,
            Err(_) => return std::ptr::null_mut(),
        };
        if arr.set_element(env, i, &element).is_err() {
            return std::ptr::null_mut();
        }
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpGenerate<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    user_id: JByteArray<'l>,
    passphrase: JByteArray<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let (uid, pw) = match (jstring_in(env, &user_id), jstring_in(env, &passphrase)) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_generate(&uid, &pw) {
            Some((secret, public, fp)) => Ok(triple_out(env, &secret, &public, &fp)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpFingerprint<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_public: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let armor = match jstring_in(env, &armored_public) {
            Some(a) => a,
            None => return Ok(std::ptr::null_mut()),
        };
        match pgp_fingerprint(&armor) {
            Some(fp) => Ok(jstring_out(env, &fp)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpNormalizeKey<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    input: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let bytes = match jbytes_in(env, &input) {
            Some(b) => b,
            None => return Ok(std::ptr::null_mut()),
        };
        match pgp_normalize_key(&bytes) {
            Some(armor) => Ok(jstring_out(env, &armor)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpEncrypt<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_public: JByteArray<'l>,
    plaintext: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (armor, plain) = match (
            jstring_in(env, &armored_public),
            jbytes_in(env, &plaintext),
        ) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_encrypt(&armor, &plain) {
            Some(raw) => Ok(jbytes_out(env, &raw)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpDecrypt<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_secret: JByteArray<'l>,
    passphrase: JByteArray<'l>,
    ciphertext: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (armor, pw, ct) = match (
            jstring_in(env, &armored_secret),
            jstring_in(env, &passphrase),
            jbytes_in(env, &ciphertext),
        ) {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_decrypt(&armor, &pw, &ct) {
            Some(plain) => Ok(jbytes_out(env, &plain)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpArmorMessage<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    raw: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let bytes = match jbytes_in(env, &raw) {
            Some(b) => b,
            None => return Ok(std::ptr::null_mut()),
        };
        match to_armored_message(&bytes) {
            Some(armor) => Ok(jstring_out(env, &armor)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Encrypt + sign: recipient armor, sender secret armor (empty string = no
/// signing), sender passphrase, plaintext → raw PGP bytes or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpEncryptSigned<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_public: JByteArray<'l>,
    armored_secret: JByteArray<'l>,
    passphrase: JByteArray<'l>,
    plaintext: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (armor, secret, pw, plain) = match (
            jstring_in(env, &armored_public),
            jstring_in(env, &armored_secret),
            jstring_in(env, &passphrase),
            jbytes_in(env, &plaintext),
        ) {
            (Some(a), Some(b), Some(c), Some(d)) => (a, b, c, d),
            _ => return Ok(std::ptr::null_mut()),
        };
        // Empty secret = encrypt-only (same as nativePgpEncrypt).
        let signer = if secret.is_empty() {
            None
        } else {
            Some((secret.as_str(), pw.as_str()))
        };
        match pgp_encrypt_signed(&armor, signer, &plain) {
            Some(raw) => Ok(jbytes_out(env, &raw)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Detached sign: secret armor, passphrase, data → raw signature packet or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpSignDetached<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_secret: JByteArray<'l>,
    passphrase: JByteArray<'l>,
    data: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (secret, pw, bytes) = match (
            jstring_in(env, &armored_secret),
            jstring_in(env, &passphrase),
            jbytes_in(env, &data),
        ) {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_sign_detached(&secret, &pw, &bytes) {
            Some(sig) => Ok(jbytes_out(env, &sig)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Decrypt + verify: secret armor, passphrase, ciphertext, verifier public
/// armor (empty string = no verification) → `byte[][]` [payload, fingerprint]
/// (fingerprint empty when unsigned/unverified) or null on failure/tamper.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpDecryptVerify<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_secret: JByteArray<'l>,
    passphrase: JByteArray<'l>,
    ciphertext: JByteArray<'l>,
    verifier_public: JByteArray<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let (secret, pw, ct, verifier) = match (
            jstring_in(env, &armored_secret),
            jstring_in(env, &passphrase),
            jbytes_in(env, &ciphertext),
            jstring_in(env, &verifier_public),
        ) {
            (Some(a), Some(b), Some(c), Some(d)) => (a, b, c, d),
            _ => return Ok(std::ptr::null_mut()),
        };
        let verifier_opt = if verifier.is_empty() { None } else { Some(verifier.as_str()) };
        match pgp_decrypt_verify(&secret, &pw, &ct, verifier_opt) {
            Some((payload, fp)) => {
                let arr = match env.new_object_type_array::<JByteArray>(2, JByteArray::null()) {
                    Ok(a) => a,
                    Err(_) => return Ok(std::ptr::null_mut()),
                };
                let payload_arr = match env.byte_array_from_slice(&payload) {
                    Ok(x) => x,
                    Err(_) => return Ok(std::ptr::null_mut()),
                };
                let fp_arr = match env.byte_array_from_slice(fp.as_bytes()) {
                    Ok(x) => x,
                    Err(_) => return Ok(std::ptr::null_mut()),
                };
                if arr.set_element(env, 0, &payload_arr).is_err()
                    || arr.set_element(env, 1, &fp_arr).is_err()
                {
                    return Ok(std::ptr::null_mut());
                }
                Ok(arr.into_raw())
            }
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
