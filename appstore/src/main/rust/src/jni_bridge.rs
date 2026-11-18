//! JNI surface for `com.vayunmathur.appstore.data.accrescent.SignifyNative`.
//!
//! Thin by design: all logic lives in [`crate::verify_inputs`] so it is
//! testable without a JVM. This file only marshals the three byte arrays.
use crate::verify_inputs;
use jni::objects::{JByteArray, JClass};
use jni::sys::jboolean;
use jni::{Env, EnvUnowned};

fn bytes_in(env: &Env, arr: &JByteArray) -> Option<Vec<u8>> { env.convert_byte_array(arr).ok() }

/// Returns `1` only for a valid signature; `0` for everything else, including
/// a failed array read. Never throws — the Kotlin caller treats `false` as
/// "not verified", so a thrown exception would only turn a clean rejection
/// into a crash.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_appstore_data_accrescent_SignifyNative_nativeVerify<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, pubkey: JByteArray<'l>, msg: JByteArray<'l>, sig: JByteArray<'l>) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        Ok(verify_inputs(bytes_in(env, &pubkey), bytes_in(env, &msg), bytes_in(env, &sig)))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
