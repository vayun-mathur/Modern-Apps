//! Debug settings, read from the environment or from a system property.
//!
//! Two sources because there are two ways this code runs and neither covers the other:
//!
//! * `MODELRUNNER_<NAME>` in the environment. The measurement loop is the cross-compiled test
//!   binary, and `adb shell MODELRUNNER_TIMING=1 ./mr_test ...` is how it is set. Also the only
//!   one that works on a host build.
//! * `debug.modelrunner.<name>`, on Android. An app is not launched from a shell and has no
//!   environment to set, so an env var alone would leave the shipped path unmeasurable.
//!   `adb shell setprop debug.modelrunner.barrier global` reaches it.
//!
//! Every one of these is off by default and none changes what a release build computes. They
//! exist because this runtime's cost is concentrated somewhere a profile of the arithmetic does
//! not show — see `analysis/maml_vs_litert.md` — so the alternatives have to be switchable at run
//! time to be compared honestly. Rebuilding between variants is how the first attempt at this
//! measured a shader change and called it a barrier change.
//!
//! Callers cache their own answer. Nothing here does, because the lookups happen once per process
//! at a natural point and a shared cache would need a lock for no benefit.

/// The value of a debug setting, from either source, or `None` if it is not set.
pub fn get(name: &str) -> Option<String> {
    let env = format!("MODELRUNNER_{}", name.to_uppercase());
    if let Some(value) = std::env::var_os(env).and_then(|v| v.into_string().ok()) {
        return Some(value);
    }
    property(&format!("debug.modelrunner.{name}"))
}

/// Whether a setting is present at all, whatever its value.
pub fn is_set(name: &str) -> bool {
    get(name).is_some()
}

/// Read an Android system property.
///
/// `__system_property_get` writes at most `PROP_VALUE_MAX` bytes including the terminator and
/// returns the length without it. It is in libc, which is linked unconditionally.
#[cfg(target_os = "android")]
fn property(name: &str) -> Option<String> {
    extern "C" {
        fn __system_property_get(name: *const u8, value: *mut u8) -> i32;
    }
    /// `PROP_VALUE_MAX` from `sys/system_properties.h`.
    const VALUE_MAX: usize = 92;
    let mut key: Vec<u8> = name.as_bytes().to_vec();
    key.push(0);
    let mut value = [0u8; VALUE_MAX];
    // SAFETY: the name is NUL-terminated and the buffer is `PROP_VALUE_MAX`, which is the most
    // the call may write.
    let len = unsafe { __system_property_get(key.as_ptr(), value.as_mut_ptr()) };
    if len <= 0 {
        return None;
    }
    String::from_utf8(value[..len as usize].to_vec()).ok()
}

#[cfg(not(target_os = "android"))]
fn property(_name: &str) -> Option<String> {
    None
}

#[cfg(test)]
#[path = "knobs_tests.rs"]
mod knobs_tests;
