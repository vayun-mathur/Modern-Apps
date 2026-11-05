//! Session auth: argon2 password verification, random session tokens,
//! in-memory login rate limiting.
//!
//! The session cookie holds a 32-byte random token (base64url). Only the
//! SHA-256 hash of the token is stored in SQLite.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use argon2::{Argon2, PasswordHash, PasswordVerifier};
use rand::RngCore;
use sha2::{Digest, Sha256};

use crate::models::now_unix;
use crate::store::Store;

pub const SESSION_COOKIE: &str = "agentd_session";
/// 7-day session lifetime, in seconds.
pub const SESSION_TTL_SECS: i64 = 7 * 24 * 3600;

#[derive(Clone)]
pub struct Auth {
    pub store: Store,
    password_hash: String,
    limiter: Arc<Mutex<LoginLimiter>>,
}

impl Auth {
    pub fn new(store: Store, password_hash: String) -> Auth {
        Auth {
            store,
            password_hash,
            limiter: Arc::new(Mutex::new(LoginLimiter::default())),
        }
    }

    /// Verify a password against the configured argon2 PHC hash.
    /// Returns `false` (not an error) on mismatch so callers can 401/429.
    pub fn verify_password(&self, password: &str) -> bool {
        let parsed = match PasswordHash::new(&self.password_hash) {
            Ok(h) => h,
            Err(_) => return false,
        };
        Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .is_ok()
    }

    /// Rate-limit check for a login attempt from `ip`. Returns `Some(retry
    /// after)` when the attempt must be rejected with 429.
    pub fn login_gate(&self, ip: &str) -> Option<Duration> {
        self.limiter
            .lock()
            .map(|mut l| l.check(ip))
            .unwrap_or(None)
    }

    pub fn login_failed(&self, ip: &str) {
        if let Ok(mut l) = self.limiter.lock() {
            l.failed(ip);
        }
    }

    pub fn login_ok(&self, ip: &str) {
        if let Ok(mut l) = self.limiter.lock() {
            l.succeeded(ip);
        }
    }

    /// Issue a session: random token + stored hash. Returns the raw token
    /// (goes into the cookie only).
    pub fn issue_session(&self) -> Result<String, String> {
        let mut bytes = [0u8; 32];
        rand::rng().fill_bytes(&mut bytes);
        let token = base64url(&bytes);
        let hash = token_sha_hex(&token);
        let exp = now_unix() + SESSION_TTL_SECS;
        self.store.put_session(&hash, exp)?;
        Ok(token)
    }

    pub fn check_token(&self, token: &str) -> bool {
        if token.is_empty() || token.len() > 256 {
            return false;
        }
        let hash = token_sha_hex(token);
        self.store
            .session_valid(&hash, now_unix())
            .unwrap_or(false)
    }

    pub fn revoke_token(&self, token: &str) {
        let hash = token_sha_hex(token);
        let _ = self.store.delete_session(&hash);
    }
}

pub fn token_sha_hex(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    hex_encode(&digest)
}

pub fn hex_encode(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 0xf) as usize] as char);
    }
    s
}

fn base64url(bytes: &[u8]) -> String {
    const ALPH: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut s = String::with_capacity((bytes.len() * 4).div_ceil(3));
    let mut i = 0;
    while i + 3 <= bytes.len() {
        let n = ((bytes[i] as u32) << 16) | ((bytes[i + 1] as u32) << 8) | bytes[i + 2] as u32;
        s.push(ALPH[((n >> 18) & 63) as usize] as char);
        s.push(ALPH[((n >> 12) & 63) as usize] as char);
        s.push(ALPH[((n >> 6) & 63) as usize] as char);
        s.push(ALPH[(n & 63) as usize] as char);
        i += 3;
    }
    let rem = bytes.len() - i;
    if rem == 1 {
        let n = (bytes[i] as u32) << 16;
        s.push(ALPH[((n >> 18) & 63) as usize] as char);
        s.push(ALPH[((n >> 12) & 63) as usize] as char);
    } else if rem == 2 {
        let n = ((bytes[i] as u32) << 16) | ((bytes[i + 1] as u32) << 8);
        s.push(ALPH[((n >> 18) & 63) as usize] as char);
        s.push(ALPH[((n >> 12) & 63) as usize] as char);
        s.push(ALPH[((n >> 6) & 63) as usize] as char);
    }
    s
}

/// In-memory exponential-backoff login limiter: per-IP backoff on failures
/// (1s, 2s, 4s, … capped at 5 min) plus a global cap of 60 attempts/min
/// across all IPs.
#[derive(Default)]
struct LoginLimiter {
    per_ip: HashMap<String, (u32, Instant)>,
    global: Vec<Instant>,
}

impl LoginLimiter {
    const MAX_BACKOFF: Duration = Duration::from_secs(300);
    const GLOBAL_CAP: usize = 60;

    fn check(&mut self, ip: &str) -> Option<Duration> {
        let now = Instant::now();
        self.global.retain(|t| now.duration_since(*t) < Duration::from_secs(60));
        if self.global.len() >= Self::GLOBAL_CAP {
            return Some(Duration::from_secs(30));
        }
        if let Some((fails, last)) = self.per_ip.get(ip) {
            if *fails > 0 {
                let shift = (*fails - 1).min(8);
                let backoff = Duration::from_secs(1 << shift).min(Self::MAX_BACKOFF);
                let elapsed = now.duration_since(*last);
                if elapsed < backoff {
                    return Some(backoff - elapsed);
                }
            }
        }
        None
    }

    fn failed(&mut self, ip: &str) {
        let now = Instant::now();
        self.global.push(now);
        let e = self.per_ip.entry(ip.to_string()).or_insert((0, now));
        e.0 = e.0.saturating_add(1);
        e.1 = now;
    }

    fn succeeded(&mut self, ip: &str) {
        self.per_ip.remove(ip);
    }
}

/// Client IP for rate limiting: first hop of `X-Forwarded-For` (set by
/// Caddy), else the peer socket address.
pub fn client_ip(forwarded_for: Option<&str>, peer: Option<std::net::SocketAddr>) -> String {
    if let Some(ff) = forwarded_for {
        let first = ff.split(',').next().unwrap_or("").trim();
        if !first.is_empty() {
            return first.to_string();
        }
    }
    peer.map(|p| p.ip().to_string()).unwrap_or_else(|| "unknown".to_string())
}

/// Parse the session token out of a `Cookie` header value.
pub fn session_token_from_cookie(header: &str) -> Option<String> {
    for part in header.split(';') {
        let part = part.trim();
        if let Some(v) = part.strip_prefix(&format!("{SESSION_COOKIE}=")) {
            let v = v.trim().trim_matches('"').to_string();
            if !v.is_empty() {
                return Some(v);
            }
        }
    }
    None
}

/// Strict `Origin` check for state-changing routes: the header must be
/// present and exactly equal the configured public origin.
pub fn origin_allowed(origin: Option<&str>, public_origin: &str) -> bool {
    matches!(origin, Some(o) if o == public_origin)
}
