//! JNI surface for `com.vayunmathur.e2ee.PqcNative`. All keys cross as DER bytes.
//! Thin wrappers only: every decision lives in `lib.rs`; helpers below share the
//! bytes-in / pair-out / result-mapping patterns so each export is a one-liner.
use crate::*;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jbyteArray, jobjectArray};
use jni::{Env, EnvUnowned};
use std::ptr::null_mut;

fn bytes_in(env: &Env, arr: &JByteArray) -> Option<Vec<u8>> { env.convert_byte_array(arr).ok() }
fn bytes_in2(env: &Env, a: &JByteArray, b: &JByteArray) -> Option<(Vec<u8>, Vec<u8>)> { Some((bytes_in(env, a)?, bytes_in(env, b)?)) }
fn bytes_in3(env: &Env, a: &JByteArray, b: &JByteArray, c: &JByteArray) -> Option<(Vec<u8>, Vec<u8>, Vec<u8>)> { Some((bytes_in(env, a)?, bytes_in(env, b)?, bytes_in(env, c)?)) }
fn bytes_out(env: &mut Env, data: &[u8]) -> jbyteArray { env.byte_array_from_slice(data).map(|a| a.into_raw()).unwrap_or(null_mut()) }
fn pair_out(env: &mut Env, a: &[u8], b: &[u8]) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(2, JByteArray::null()) { Ok(v) => v, Err(_) => return null_mut() };
    let ja = match env.byte_array_from_slice(a) { Ok(v) => v, Err(_) => return null_mut() };
    let jb = match env.byte_array_from_slice(b) { Ok(v) => v, Err(_) => return null_mut() };
    if arr.set_element(env, 0, &ja).is_err() || arr.set_element(env, 1, &jb).is_err() { return null_mut(); }
    arr.into_raw()
}
fn pair_result(env: &mut Env, r: Option<(Vec<u8>, Vec<u8>)>) -> jobjectArray { match r { Some((a, b)) => pair_out(env, &a, &b), None => null_mut() } }
fn bytes_result(env: &mut Env, r: Option<Vec<u8>>) -> jbyteArray { match r { Some(v) => bytes_out(env, &v), None => null_mut() } }
fn run_pair(mut env: EnvUnowned, f: impl FnOnce(&mut Env) -> Option<(Vec<u8>, Vec<u8>)>) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> { let r = f(env); Ok(pair_result(env, r)) }).resolve::<jni::errors::LogErrorAndDefault>()
}
fn run_bytes(mut env: EnvUnowned, f: impl FnOnce(&mut Env) -> Option<Vec<u8>>) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> { let r = f(env); Ok(bytes_result(env, r)) }).resolve::<jni::errors::LogErrorAndDefault>()
}
fn run_bool(mut env: EnvUnowned, f: impl FnOnce(&Env) -> bool) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> { Ok(f(env)) }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemKeygen<'l>(env: EnvUnowned<'l>, _c: JClass<'l>) -> jobjectArray { run_pair(env, |_| Some(mlkem_keygen_der())) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemLinkKeygen<'l>(env: EnvUnowned<'l>, _c: JClass<'l>) -> jobjectArray { run_pair(env, |_| { let s = mlkem_link_seed_new(); mlkem_link_keygen_from_seed(&s).map(|(p, _)| (s.to_vec(), p)) }) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemLinkPubFromSeed<'l>(env: EnvUnowned<'l>, _c: JClass<'l>, seed: JByteArray<'l>) -> jbyteArray { run_bytes(env, |e| bytes_in(e, &seed).and_then(|s| mlkem_link_keygen_from_seed(&s).map(|(p, _)| p))) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaKeygen<'l>(env: EnvUnowned<'l>, _c: JClass<'l>) -> jobjectArray { run_pair(env, |_| Some(mldsa_keygen_der())) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemEncaps<'l>(env: EnvUnowned<'l>, _c: JClass<'l>, pub_der: JByteArray<'l>) -> jobjectArray { run_pair(env, |e| bytes_in(e, &pub_der).and_then(|d| mlkem_encaps_der(&d))) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMlkemDecaps<'l>(env: EnvUnowned<'l>, _c: JClass<'l>, priv_der: JByteArray<'l>, ct: JByteArray<'l>) -> jbyteArray { run_bytes(env, |e| bytes_in2(e, &priv_der, &ct).and_then(|(d, c)| mlkem_decaps_der(&d, &c))) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaSign<'l>(env: EnvUnowned<'l>, _c: JClass<'l>, priv_der: JByteArray<'l>, msg: JByteArray<'l>) -> jbyteArray { run_bytes(env, |e| bytes_in2(e, &priv_der, &msg).and_then(|(d, m)| mldsa_sign_der(&d, &m))) }
#[no_mangle] pub extern "system" fn Java_com_vayunmathur_e2ee_PqcNative_nativeMldsaVerify<'l>(env: EnvUnowned<'l>, _c: JClass<'l>, pub_der: JByteArray<'l>, msg: JByteArray<'l>, sig: JByteArray<'l>) -> jboolean { run_bool(env, |e| bytes_in3(e, &pub_der, &msg, &sig).map_or(false, |(d, m, s)| mldsa_verify_der(&d, &m, &s))) }
