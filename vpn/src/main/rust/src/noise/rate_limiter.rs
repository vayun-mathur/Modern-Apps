use super::handshake::{b2s_hash, b2s_keyed_mac_16, b2s_keyed_mac_16_2, b2s_mac_24};
use crate::noise::handshake::{LABEL_COOKIE, LABEL_MAC1};
use crate::noise::{TunnResult, WireGuardError};
use crate::packet::{Packet, WgCookieReply, WgHandshakeBase, WgKind};
use constant_time_eq::constant_time_eq;
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;
use crate::sleepyinstant::Instant;
use aead::{AeadInOut, KeyInit};
use chacha20poly1305::{Key, XChaCha20Poly1305};
use chacha20poly1305::aead::inout::InOutBuf;
use parking_lot::Mutex;
use rand::rngs::SysRng;

const COOKIE_REFRESH: u64 = 128;
const COOKIE_SIZE: usize = 16;
const COOKIE_NONCE_SIZE: usize = 24;
const RESET_PERIOD: Duration = Duration::from_secs(1);
type Cookie = [u8; COOKIE_SIZE];

struct IpCounts { counts: HashMap<IpAddr, u64>, last_reset: Instant }

pub struct RateLimiter {
    nonce_key: [u8; 32],
    secret_key: [u8; 16],
    start_time: Instant,
    nonce_ctr: AtomicU64,
    mac1_key: [u8; 32],
    cookie_key: Key,
    limit: u64,
    ip_counts: Mutex<IpCounts>,
}

impl RateLimiter {
    pub fn new(public_key: &crate::x25519::PublicKey, limit: u64) -> Self {
        use rand_core::TryRng;
        let mut secret_key = [0u8; 16];
        let _ = SysRng.try_fill_bytes(&mut secret_key);
        RateLimiter {
            nonce_key: Self::rand_bytes(),
            secret_key,
            start_time: Instant::now(),
            nonce_ctr: AtomicU64::new(0),
            mac1_key: b2s_hash(LABEL_MAC1, public_key.as_bytes()),
            cookie_key: b2s_hash(LABEL_COOKIE, public_key.as_bytes()).into(),
            limit,
            ip_counts: Mutex::new(IpCounts { counts: HashMap::new(), last_reset: Instant::now() }),
        }
    }

    fn rand_bytes() -> [u8; 32] {
        use rand_core::TryRng;
        let mut key = [0u8; 32];
        let _ = SysRng.try_fill_bytes(&mut key);
        key
    }

    pub fn try_reset_count(&self) {
        let current_time = Instant::now();
        let mut ip_counts = self.ip_counts.lock();
        if current_time.duration_since(ip_counts.last_reset) >= RESET_PERIOD {
            ip_counts.counts.clear();
            ip_counts.last_reset = current_time;
        }
    }

    fn current_cookie(&self, addr: SocketAddr) -> Cookie {
        let mut addr_bytes = [0u8; 18];
        match addr.ip() {
            IpAddr::V4(a) => addr_bytes[..4].copy_from_slice(&a.octets()),
            IpAddr::V6(a) => addr_bytes[..16].copy_from_slice(&a.octets()),
        }
        addr_bytes[16..18].copy_from_slice(&addr.port().to_be_bytes());
        let cur_counter = Instant::now().duration_since(self.start_time).as_secs() / COOKIE_REFRESH;
        b2s_keyed_mac_16_2(&self.secret_key, &cur_counter.to_le_bytes(), &addr_bytes)
    }

    fn nonce(&self) -> [u8; COOKIE_NONCE_SIZE] {
        let ctr = self.nonce_ctr.fetch_add(1, Ordering::Relaxed);
        b2s_mac_24(&self.nonce_key, &ctr.to_le_bytes())
    }

    fn is_under_load(&self, src_addr: IpAddr) -> bool {
        let mut ip_counts = self.ip_counts.lock();
        let count = ip_counts.counts.entry(src_addr).or_insert(0);
        *count += 1;
        *count > self.limit
    }

    pub(crate) fn format_cookie_reply(&self, idx: u32, cookie: Cookie, mac1: &[u8]) -> WgCookieReply {
        let mut rep = WgCookieReply::new();
        rep.receiver_idx.set(idx);
        rep.nonce = self.nonce();
        let cipher = XChaCha20Poly1305::new(&self.cookie_key);
        let iv = chacha20poly1305::XNonce::from(rep.nonce);
        rep.encrypted_cookie.encrypted = cookie;
        let tag = cipher.encrypt_inout_detached(&iv, mac1, InOutBuf::from(&mut rep.encrypted_cookie.encrypted[..]))
            .expect("wg_cookie_reply size ok");
        rep.encrypted_cookie.tag = tag.into();
        rep
    }

    pub fn verify_packet(&self, src_addr: SocketAddr, packet: Packet) -> Result<WgKind, TunnResult> {
        let packet = packet.try_into_wg().map_err(|_| TunnResult::Err(WireGuardError::InvalidPacket))?;
        match packet {
            WgKind::HandshakeInit(p) => self.verify_handshake(src_addr, p).map(WgKind::HandshakeInit),
            WgKind::HandshakeResp(p) => self.verify_handshake(src_addr, p).map(WgKind::HandshakeResp),
            _ => Ok(packet),
        }
    }

    pub(crate) fn verify_handshake<P: WgHandshakeBase>(
        &self, src_addr: SocketAddr, handshake: Packet<P>,
    ) -> Result<Packet<P>, TunnResult> {
        let sender_idx = handshake.sender_idx();
        let mac1 = handshake.mac1();
        let mac2 = handshake.mac2();
        let computed_mac1 = b2s_keyed_mac_16(&self.mac1_key, handshake.until_mac1());
        if !constant_time_eq(&computed_mac1, mac1) {
            return Err(TunnResult::Err(WireGuardError::InvalidMac));
        }
        if self.is_under_load(src_addr.ip()) {
            let cookie = self.current_cookie(src_addr);
            let computed_mac2 = b2s_keyed_mac_16_2(&cookie, handshake.until_mac1(), mac1);
            if !constant_time_eq(&computed_mac2, mac2) {
                let crep = self.format_cookie_reply(sender_idx, cookie, mac1);
                let pkt = handshake.overwrite_with(&crep);
                return Err(TunnResult::WriteToNetwork(pkt.into()));
            }
            return Ok(handshake);
        }
        Ok(handshake)
    }
}
