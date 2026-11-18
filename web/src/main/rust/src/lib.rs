//! Brave Shields engine for the `:web` browser, exposed to Kotlin as
//! `com.vayunmathur.web.shields.ShieldsNative`.
//!
//! The engine is Brave's own `adblock` crate, so the filter syntax, exception
//! precedence, `$removeparam` rewrites, `$redirect` bodies and cosmetic rules all
//! behave exactly as they do in Brave itself.
//!
//! Handle model: `nativeCreate*` leaks a `Box<Engine>` and returns the pointer as a
//! `jlong`; every other call borrows it. `Engine` is `Send + Sync` here (the
//! `single-thread` feature is off), so `nativeCheck` can be called from the WebView
//! render thread while the UI thread reads cosmetic resources.
//!
//! Everything crossing JNI is a JSON string — one hop per request, no object
//! construction on the hot path.
//!
//! The pure core (`create_engine`, `check_request`, the `*_handle` dispatchers, …)
//! is `pub(crate)` so host unit tests in `shields_core_tests` can exercise the
//! exact logic the thin JNI wrappers delegate to, without a JVM. The wrappers
//! only convert `JString`/`JByteArray` arguments and hand the result back.

use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong, jstring};
use jni::{Env, EnvUnowned};
use serde_json::{json, Value};
use std::collections::HashSet;

#[cfg(test)]
mod shields_core_tests;

/// Borrows the engine behind a handle. Returns `None` for the null handle, which is
/// what Kotlin passes before the engine has finished loading.
///
/// # Safety
/// `handle` must be a pointer previously returned by `nativeCreate`/`nativeCreateFromCache`
/// and not yet passed to `nativeDestroy`.
pub(crate) unsafe fn engine<'a>(handle: jlong) -> Option<&'a Engine> {
    if handle == 0 {
        return None;
    }
    Some(&*(handle as *const Engine))
}

fn jstr(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

fn out(env: &mut Env, s: String) -> jstring {
    match env.new_string(s) { Ok(s) => s.into_raw(), Err(_) => std::ptr::null_mut() }
}

pub(crate) fn json_string_array(raw: &str) -> Vec<String> {
    match serde_json::from_str::<Vec<String>>(raw) {
        Ok(v) => v,
        Err(_) => Vec::new(),
    }
}

/// uBlock Origin's `resources.json` and Brave's `adblock-resources` both deserialize
/// straight into `Vec<Resource>`; anything unparseable leaves scriptlets and
/// `$redirect` unavailable rather than failing the whole engine.
pub(crate) fn apply_resources(engine: &mut Engine, resources_json: &str) {
    if let Ok(resources) = serde_json::from_str::<Vec<Resource>>(resources_json) {
        engine.use_resources(resources);
    }
}

pub(crate) fn create_engine(filters: &str, resources_json: &str) -> Engine {
    let mut engine = Engine::new_with_list_text(filters);
    apply_resources(&mut engine, resources_json);
    engine
}

pub(crate) fn create_engine_from_cache(cache: &[u8], resources_json: &str) -> Option<Engine> {
    let mut engine = Engine::default();
    if engine.deserialize(cache).is_err() {
        return None;
    }
    // `deserialize` replaces the filter data but not the resource storage.
    apply_resources(&mut engine, resources_json);
    Some(engine)
}

pub(crate) fn serialize_engine(engine: &Engine) -> Vec<u8> {
    engine.serialize()
}

/// `{"blocked":bool,"important":bool,"exception":bool,"redirect":str?,"rewritten":str?}`
pub(crate) fn check_request(
    engine: &Engine,
    url: &str,
    source_url: &str,
    request_type: &str,
) -> Option<String> {
    let request = Request::new(url, source_url, request_type, "get").ok()?;
    let result = engine.check_network_request(&request);
    let blocked = result.should_block();
    let important = result.important;
    let exception = result.exception.is_some();
    let redirect = result.redirect;
    let rewritten = result.rewritten_url;
    let payload = json!({
        "blocked": blocked,
        "important": important,
        "exception": exception,
        "redirect": redirect,
        "rewritten": rewritten,
    });
    Some(payload.to_string())
}

pub(crate) fn set_to_value(set: HashSet<String>) -> Value {
    let values: Vec<String> = set.into_iter().collect();
    Value::from(values)
}

/// `{"hide":[sel],"procedural":[json],"exceptions":[sel],"script":str,"generichide":bool}`
pub(crate) fn cosmetic_resources_json(engine: &Engine, url: &str) -> Option<String> {
    let res = engine.url_cosmetic_resources(url);
    let hide = set_to_value(res.hide_selectors);
    let procedural = set_to_value(res.procedural_actions);
    let exceptions = set_to_value(res.exceptions);
    let script = res.injected_script;
    let generichide = res.generichide;
    let payload = json!({
        "hide": hide,
        "procedural": procedural,
        "exceptions": exceptions,
        "script": script,
        "generichide": generichide,
    });
    Some(payload.to_string())
}

pub(crate) fn hidden_class_id_json(
    engine: &Engine,
    classes_json: &str,
    ids_json: &str,
    exceptions_json: &str,
) -> Option<String> {
    let classes = json_string_array(classes_json);
    let ids = json_string_array(ids_json);
    let exception_list = json_string_array(exceptions_json);
    let exceptions: HashSet<String> = exception_list.into_iter().collect();
    let selectors = engine.hidden_class_id_selectors(classes, ids, &exceptions);
    let value = Value::from(selectors);
    Some(value.to_string())
}

// Handle dispatchers: the handle/argument validation the JNI wrappers used to
// inline, kept here so it is host-testable. A `None` argument or a null handle
// yields `None`/`0`, matching the wrappers' historical null/0 returns.

pub(crate) fn create_handle(filters: Option<String>, resources_json: Option<String>) -> jlong {
    let filters = match filters {
        Some(f) => f,
        None => return 0,
    };
    let resources = resources_json.unwrap_or_default();
    let engine = create_engine(&filters, &resources);
    Box::into_raw(Box::new(engine)) as jlong
}

pub(crate) fn create_cache_handle(bytes: Vec<u8>, resources_json: Option<String>) -> jlong {
    let resources = resources_json.unwrap_or_default();
    match create_engine_from_cache(&bytes, &resources) {
        Some(engine) => Box::into_raw(Box::new(engine)) as jlong,
        None => 0,
    }
}

pub(crate) fn serialize_handle(handle: jlong) -> Option<Vec<u8>> {
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return None,
    };
    Some(serialize_engine(engine))
}

pub(crate) fn check_handle(
    handle: jlong,
    url: Option<String>,
    source_url: Option<String>,
    request_type: Option<String>,
) -> Option<String> {
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return None,
    };
    let (url, source_url, request_type) = match (url, source_url, request_type) {
        (Some(u), Some(s), Some(t)) => (u, s, t),
        _ => return None,
    };
    check_request(engine, &url, &source_url, &request_type)
}

pub(crate) fn cosmetic_handle(handle: jlong, url: Option<String>) -> Option<String> {
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return None,
    };
    let url = match url {
        Some(u) => u,
        None => return None,
    };
    cosmetic_resources_json(engine, &url)
}

pub(crate) fn hidden_handle(
    handle: jlong,
    classes: Option<String>,
    ids: Option<String>,
    exceptions: Option<String>,
) -> Option<String> {
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return None,
    };
    let (classes, ids, exceptions) = match (classes, ids, exceptions) {
        (Some(c), Some(i), Some(e)) => (c, i, e),
        _ => return None,
    };
    hidden_class_id_json(engine, &classes, &ids, &exceptions)
}

pub(crate) fn destroy_handle(handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCreate<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, filters: JString<'l>, resources_json: JString<'l>) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> { Ok(create_handle(jstr(env, &filters), jstr(env, &resources_json))) }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCreateFromCache<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, cache: JByteArray<'l>, resources_json: JString<'l>) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> { match env.convert_byte_array(&cache) { Ok(bytes) => Ok(create_cache_handle(bytes, jstr(env, &resources_json))), Err(_) => Ok(0) } }).resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeSerialize<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> { match serialize_handle(handle) { Some(bytes) => match env.byte_array_from_slice(&bytes) { Ok(a) => Ok(a.into_raw()), Err(_) => Ok(std::ptr::null_mut()) }, None => Ok(std::ptr::null_mut()) } }).resolve::<jni::errors::LogErrorAndDefault>()
}

/// `{"blocked":bool,"important":bool,"exception":bool,"redirect":str?,"rewritten":str?}`
///
/// `redirect` carries the body of the uBO resource named by a `$redirect` rule, already
/// resolved by the engine. `rewritten` is the `$removeparam`-cleaned URL, which applies
/// only when the request is not blocked.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCheck<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong, url: JString<'l>, source_url: JString<'l>, request_type: JString<'l>) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> { match check_handle(handle, jstr(env, &url), jstr(env, &source_url), jstr(env, &request_type)) { Some(s) => Ok(out(env, s)), None => Ok(std::ptr::null_mut()) } }).resolve::<jni::errors::LogErrorAndDefault>()
}

/// `{"hide":[sel],"procedural":[json],"exceptions":[sel],"script":str,"generichide":bool}`
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCosmeticResources<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong, url: JString<'l>) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> { match cosmetic_handle(handle, jstr(env, &url)) { Some(s) => Ok(out(env, s)), None => Ok(std::ptr::null_mut()) } }).resolve::<jni::errors::LogErrorAndDefault>()
}

/// Second cosmetic pass: classes and ids discovered by the page's `MutationObserver`
/// are mapped to any generic hide rules that reference them. All three arguments are
/// JSON string arrays; the result is a JSON array of CSS selectors.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeHiddenClassIdSelectors<'l>(mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong, classes: JString<'l>, ids: JString<'l>, exceptions: JString<'l>) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> { match hidden_handle(handle, jstr(env, &classes), jstr(env, &ids), jstr(env, &exceptions)) { Some(s) => Ok(out(env, s)), None => Ok(std::ptr::null_mut()) } }).resolve::<jni::errors::LogErrorAndDefault>()
}

/// # Safety
/// `handle` must not be used again after this call.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeDestroy<'l>(_env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong) {
    destroy_handle(handle)
}
