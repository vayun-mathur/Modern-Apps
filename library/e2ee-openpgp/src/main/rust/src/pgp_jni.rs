//! JNI surface for `com.vayunmathur.e2ee.PgpNative`.
//! Armor crosses as UTF-8 bytes; ciphertext crosses as raw bytes.
//! Decision logic lives in `crate::pgp` (`*_from_bytes` helpers); this file
//! only converts JByteArray <-> Vec<u8>/String and maps Option to null.

use crate::pgp::*;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jobjectArray};
use jni::{Env, EnvUnowned};

fn inp(env: &Env, a: &JByteArray) -> Option<Vec<u8>> { env.convert_byte_array(a).ok() }
fn out_bytes(env: &mut Env, d: &[u8]) -> jbyteArray { env.byte_array_from_slice(d).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()) }
fn out_array(env: &mut Env, items: &[&[u8]]) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(items.len(), JByteArray::null()) { Ok(a) => a, Err(_) => return std::ptr::null_mut() };
    for (i, d) in items.iter().enumerate() { let el = match env.byte_array_from_slice(d) { Ok(x) => x, Err(_) => return std::ptr::null_mut() }; if arr.set_element(env, i, &el).is_err() { return std::ptr::null_mut(); } }
    arr.into_raw()
}
macro_rules! jni_fn {
    ($name:ident, $call:expr) => {
        #[no_mangle] pub extern "system" fn $name<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, a: JByteArray<'l>) -> jbyteArray {
            env.with_env(|e| -> jni::errors::Result<jbyteArray> { Ok(match inp(e, &a).and_then(|x| $call(&x)) { Some(v) => out_bytes(e, v.as_bytes()), None => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
        }
    };
}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpGenerate<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, uid: JByteArray<'l>, pw: JByteArray<'l>) -> jobjectArray {
    env.with_env(|e| -> jni::errors::Result<jobjectArray> { Ok(match (inp(e, &uid), inp(e, &pw)) { (Some(a), Some(b)) => match generate_from_bytes(&a, &b) { Some((s, p, f)) => out_array(e, &[s.as_bytes(), p.as_bytes(), f.as_bytes()]), None => std::ptr::null_mut() }, _ => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpNormalizeKey<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, input: JByteArray<'l>) -> jbyteArray {
    env.with_env(|e| -> jni::errors::Result<jbyteArray> { Ok(match inp(e, &input).and_then(|b| pgp_normalize_key(&b)) { Some(v) => out_bytes(e, v.as_bytes()), None => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
jni_fn!(Java_com_vayunmathur_e2ee_PgpNative_nativePgpFingerprint, fingerprint_from_bytes);
jni_fn!(Java_com_vayunmathur_e2ee_PgpNative_nativePgpArmorMessage, armor_from_bytes);
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpEncrypt<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, armor: JByteArray<'l>, plain: JByteArray<'l>) -> jbyteArray {
    env.with_env(|e| -> jni::errors::Result<jbyteArray> { Ok(match (inp(e, &armor), inp(e, &plain)) { (Some(a), Some(p)) => match encrypt_from_bytes(&a, &p) { Some(v) => out_bytes(e, &v), None => std::ptr::null_mut() }, _ => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpDecrypt<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, secret: JByteArray<'l>, pw: JByteArray<'l>, ct: JByteArray<'l>) -> jbyteArray {
    env.with_env(|e| -> jni::errors::Result<jbyteArray> { Ok(match (inp(e, &secret), inp(e, &pw), inp(e, &ct)) { (Some(a), Some(b), Some(c)) => match decrypt_from_bytes(&a, &b, &c) { Some(v) => out_bytes(e, &v), None => std::ptr::null_mut() }, _ => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpEncryptSigned<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, recip: JByteArray<'l>, secret: JByteArray<'l>, pw: JByteArray<'l>, plain: JByteArray<'l>) -> jbyteArray {
    env.with_env(|e| -> jni::errors::Result<jbyteArray> { Ok(match (inp(e, &recip), inp(e, &secret), inp(e, &pw), inp(e, &plain)) { (Some(a), Some(b), Some(c), Some(d)) => match encrypt_signed_from_bytes(&a, &b, &c, &d) { Some(v) => out_bytes(e, &v), None => std::ptr::null_mut() }, _ => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpSignDetached<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, secret: JByteArray<'l>, pw: JByteArray<'l>, data: JByteArray<'l>) -> jbyteArray {
    env.with_env(|e| -> jni::errors::Result<jbyteArray> { Ok(match (inp(e, &secret), inp(e, &pw), inp(e, &data)) { (Some(a), Some(b), Some(c)) => match sign_detached_from_bytes(&a, &b, &c) { Some(v) => out_bytes(e, &v), None => std::ptr::null_mut() }, _ => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpDecryptVerify<'l>(mut env: EnvUnowned<'l>, _c: JClass<'l>, secret: JByteArray<'l>, pw: JByteArray<'l>, ct: JByteArray<'l>, verifier: JByteArray<'l>) -> jobjectArray {
    env.with_env(|e| -> jni::errors::Result<jobjectArray> { Ok(match (inp(e, &secret), inp(e, &pw), inp(e, &ct), inp(e, &verifier)) { (Some(a), Some(b), Some(c), Some(d)) => match decrypt_verify_from_bytes(&a, &b, &c, &d) { Some((p, f)) => out_array(e, &[&p, f.as_bytes()]), None => std::ptr::null_mut() }, _ => std::ptr::null_mut() }) }).resolve::<jni::errors::LogErrorAndDefault>()
}
