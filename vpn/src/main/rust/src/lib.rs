pub mod crypto;
pub mod noise;
pub mod packet;
pub mod tun;
mod sleepyinstant;

pub mod x25519 {
    pub use x25519_dalek::{PublicKey, ReusableSecret, StaticSecret};
}

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring, jlong};
use jni::{Env, EnvUnowned};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

type Handle = u64;
struct Entry {
    tunn: noise::Tunn,
    _table: noise::index_table::IndexTable,
    _rl: Arc<noise::rate_limiter::RateLimiter>,
}
static TUNNELS: OnceLock<Mutex<HashMap<Handle, Entry>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn reg() -> &'static Mutex<HashMap<Handle, Entry>> { TUNNELS.get_or_init(|| Mutex::new(HashMap::new())) }

fn decode_key(s: &str) -> Option<[u8; 32]> {
    let clean = s.trim();
    let bytes = if clean.len() == 44 && clean.ends_with('=') {
        use base64::Engine; base64::engine::general_purpose::STANDARD.decode(clean).ok()?
    } else if clean.len() == 43 {
        use base64::Engine; base64::engine::general_purpose::STANDARD.decode(format!("{clean}=")).ok()?
    } else if clean.len() == 64 {
        hex::decode(clean).ok()?
    } else {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.decode(clean)
            .or_else(|_| base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(clean)).ok()?
    };
    if bytes.len() != 32 { return None; }
    let mut arr = [0u8; 32]; arr.copy_from_slice(&bytes); Some(arr)
}
fn b64e(k: &[u8]) -> String { use base64::Engine; base64::engine::general_purpose::STANDARD.encode(k) }
/// Infallible OS RNG adapter for `CryptoRng`-bounded APIs (`random_from_rng`).
/// Seeds a `StdRng` from `SysRng`; OS RNG failure is unrecoverable on Android,
/// so this expect-matches the pre-existing `from_entropy().unwrap()` behavior.
pub(crate) fn os_rng() -> rand::rngs::StdRng {
    use rand::SeedableRng;
    let mut sys = rand::rngs::SysRng;
    rand::rngs::StdRng::try_from_rng(&mut sys).expect("OS RNG unavailable")
}
fn js_to_rs(env: &Env, js: &JString) -> String { js.try_to_string(env).unwrap_or_default() }
fn rs_to_js(env: &mut Env, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_init<'l>(_env: EnvUnowned<'l>, _class: JClass<'l>) {}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_generatePrivateKey<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let secret = x25519_dalek::StaticSecret::random_from_rng(&mut crate::os_rng());
        Ok(rs_to_js(env, &b64e(secret.as_bytes())))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_derivePublicKey<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, priv_j: JString<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let ps = js_to_rs(env, &priv_j);
        let Some(pb) = decode_key(&ps) else { return Ok(std::ptr::null_mut()); };
        let secret = x25519_dalek::StaticSecret::from(pb);
        Ok(rs_to_js(env, &b64e(x25519_dalek::PublicKey::from(&secret).as_bytes())))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_newTunnel<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
    priv_j: JString<'l>, peer_j: JString<'l>, psk_j: JString<'l>, keepalive: i32,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<jlong> {
        let priv_s = js_to_rs(env, &priv_j);
        let peer_s = js_to_rs(env, &peer_j);
        let psk_s = js_to_rs(env, &psk_j);
        let Some(priv_b) = decode_key(&priv_s) else { return Ok(-1); };
        let Some(peer_b) = decode_key(&peer_s) else { return Ok(-2); };
        let psk = if psk_s.trim().is_empty() { None } else { decode_key(&psk_s) };
        let ka = if keepalive <= 0 { None } else { Some(keepalive as u16) };
        let sec = x25519_dalek::StaticSecret::from(priv_b);
        let pubk = x25519_dalek::PublicKey::from(peer_b);
        let table = noise::index_table::IndexTable::from_os_rng();
        let rl = Arc::new(noise::rate_limiter::RateLimiter::new(
            &x25519_dalek::PublicKey::from(&sec), 100,
        ));
        let tunn = noise::Tunn::new(sec, pubk, psk, ka, table.clone(), rl.clone());
        let h = NEXT.fetch_add(1, Ordering::Relaxed);
        reg().lock().unwrap().insert(h, Entry { tunn, _table: table, _rl: rl });
        Ok(h as jlong)
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_freeTunnel<'l>(_env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong) {
    reg().lock().unwrap().remove(&(handle as u64));
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_formatHandshakeInit<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let mut guard = reg().lock().unwrap();
        let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return Ok(null) };
        let pkt = match e.tunn.format_handshake_initiation(false) { Some(p) => p, None => return Ok(null) };
        Ok(env.byte_array_from_slice(pkt.as_bytes()).map(|a| a.into_raw()).unwrap_or(null))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_consumeIncomingPacketDetailed<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong, bytes: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let slice = match env.convert_byte_array(&bytes) { Ok(b) => b, Err(_) => return Ok(null) };
        let mut guard = reg().lock().unwrap();
        let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return Ok(null) };
        let pkt = packet::Packet::from_bytes(bytes::BytesMut::from(&slice[..]));
        let wg = match pkt.try_into_wg() { Ok(p) => p, Err(_) => return Ok(null) };
        let res = e.tunn.handle_incoming_packet(wg);
        let (tag, payload) = match res {
            noise::TunnResult::WriteToNetwork(p) => { let o: packet::Packet = p.into(); (1u8, o.as_bytes().to_vec()) }
            noise::TunnResult::WriteToTunnel(p) => {
                if p.is_empty() { (3u8, Vec::new()) } else { (2u8, p.as_bytes().to_vec()) }
            }
            _ => return Ok(null),
        };
        let mut out = Vec::with_capacity(1 + payload.len());
        out.push(tag); out.extend_from_slice(&payload);
        Ok(env.byte_array_from_slice(&out).map(|a| a.into_raw()).unwrap_or(null))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_encapsulate<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong, ip_bytes: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let slice = match env.convert_byte_array(&ip_bytes) { Ok(b) => b, Err(_) => return Ok(null) };
        let mut guard = reg().lock().unwrap();
        let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return Ok(null) };
        let pkt = packet::Packet::from_bytes(bytes::BytesMut::from(&slice[..]));
        let mut mtu = tun::MtuWatcher::new(1280);
        let Some(kind) = e.tunn.handle_outgoing_packet(pkt, Some(&mut mtu)) else { return Ok(null) };
        let out: packet::Packet = kind.into();
        Ok(env.byte_array_from_slice(out.as_bytes()).map(|a| a.into_raw()).unwrap_or(null))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_tickTimersDetailed<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let mut guard = reg().lock().unwrap();
        let e = match guard.get_mut(&(handle as u64)) { Some(x) => x, None => return Ok(null) };
        Ok(match e.tunn.update_timers() {
            Ok(Some(kind)) => {
                let out: packet::Packet = kind.into();
                let mut buf = vec![1u8];
                buf.extend_from_slice(out.as_bytes());
                env.byte_array_from_slice(&buf).map(|a| a.into_raw()).unwrap_or(null)
            }
            _ => null,
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_vpn_util_VpnNative_getStats<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, handle: jlong,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let guard = reg().lock().unwrap();
        let e = match guard.get(&(handle as u64)) { Some(x) => x, None => return Ok(std::ptr::null_mut()) };
        let (time, tx, rx, loss, rtt) = e.tunn.stats();
        let s = format!(
            "{{\"handshakeMs\":{},\"tx\":{},\"rx\":{},\"loss\":{},\"rtt\":{}}}",
            time.map(|d| d.as_millis() as u64).unwrap_or(0), tx, rx, loss, rtt.unwrap_or(0)
        );
        Ok(rs_to_js(env, &s))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
