//! JNI surface for `com.vayunmathur.passwords.util.KdbxNative`.
//!
//! - `nativeImport(password, kdbxBytes) -> String?`  (JSON array of entries)
//! - `nativeExport(password, entriesJson) -> byte[]?` (encrypted KDBX4 bytes)
//!
//! Both return null on failure (wrong password, malformed vault, bad JSON).
//! All dispatch logic lives in the testable `crate::{import_for_jni,
//! export_for_jni}` helpers; these wrappers only convert JNI types.

use crate::{export_for_jni, import_for_jni};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use jni::{Env, EnvUnowned};

fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_passwords_util_KdbxNative_nativeImport<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, password: JString<'l>, kdbx: JByteArray<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let result = import_for_jni(get_string(env, &password), env.convert_byte_array(&kdbx).ok());
        Ok(result.and_then(|json| env.new_string(json).ok()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()))
    }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_passwords_util_KdbxNative_nativeExport<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, password: JString<'l>, entries_json: JString<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let result = export_for_jni(get_string(env, &password), get_string(env, &entries_json));
        Ok(result.and_then(|bytes| env.byte_array_from_slice(&bytes).ok()).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut()))
    }).resolve::<jni::errors::LogErrorAndDefault>()
}
