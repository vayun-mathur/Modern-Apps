// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// This file incorporates work covered by the following copyright and
// permission notice:
//
//   Copyright (c) Mullvad VPN AB. All rights reserved.
//   Copyright (c) 2019 Cloudflare, Inc. All rights reserved.
//
// SPDX-License-Identifier: MPL-2.0

//! Noise protocol implementation for WireGuard cryptographic handshakes and sessions.

/// Error types for WireGuard protocol operations.
pub mod errors;
/// WireGuard handshake implementation using the Noise protocol.
pub mod handshake;
/// A table of locally unique session IDs.
pub mod index_table;
/// Rate limiting for handshake initiation packets.
pub mod rate_limiter;

mod session;
mod timers;

use rand::{SeedableRng, rngs::StdRng};

use crate::noise::errors::WireGuardError;
use crate::noise::handshake::Handshake;
use crate::noise::index_table::IndexTable;
use crate::noise::rate_limiter::RateLimiter;
use crate::noise::timers::{TimerName, Timers};

pub use crate::noise::timers::TimerParams;
use crate::packet::{Packet, WgCookieReply, WgData, WgHandshakeInit, WgHandshakeResp, WgKind};
use crate::tun::MtuWatcher;
use crate::x25519;

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

const MAX_QUEUE_DEPTH: usize = 256;
/// number of sessions in the ring, better keep a PoT.
const N_SESSIONS: usize = 8;

/// Result of processing a WireGuard packet through the [`Tunn`].
#[derive(Debug)]
pub enum TunnResult {
    /// Operation completed successfully with no further action needed.
    Done,
    /// An error occurred during processing.
    Err(WireGuardError),
    /// A packet should be written to the network (UDP).
    WriteToNetwork(WgKind),
    /// A decrypted packet should be written to the tunnel (TUN).
    WriteToTunnel(Packet),
}

impl From<WireGuardError> for TunnResult {
    fn from(err: WireGuardError) -> TunnResult {
        TunnResult::Err(err)
    }
}

/// Tunnel represents a point-to-point WireGuard connection.
pub struct Tunn<R: rand::Rng + Send = StdRng> {
    handshake: handshake::Handshake,
    sessions: [Option<session::Session>; N_SESSIONS],
    current: usize,
    session_counter: usize,
    packet_queue: VecDeque<Packet>,
    timers: timers::Timers,
    tx_bytes: usize,
    rx_bytes: usize,
    rate_limiter: Arc<RateLimiter>,
    jitter_rng: R,
}

impl Tunn<StdRng> {
    pub fn new(
        static_private: x25519::StaticSecret,
        peer_static_public: x25519::PublicKey,
        preshared_key: Option<[u8; 32]>,
        persistent_keepalive: Option<u16>,
        index_table: IndexTable,
        rate_limiter: Arc<RateLimiter>,
    ) -> Self {
        Self::new_with_rng(
            static_private,
            peer_static_public,
            preshared_key,
            persistent_keepalive,
            index_table,
            rate_limiter,
            StdRng::try_from_rng(&mut rand::rngs::SysRng).expect("OS RNG unavailable"),
        )
    }
}

impl<R: rand::Rng + Send> Tunn<R> {
    pub fn new_with_rng(
        static_private: x25519::StaticSecret,
        peer_static_public: x25519::PublicKey,
        preshared_key: Option<[u8; 32]>,
        persistent_keepalive: Option<u16>,
        index_table: IndexTable,
        rate_limiter: Arc<RateLimiter>,
        jitter_rng: R,
    ) -> Self {
        let static_public = x25519::PublicKey::from(&static_private);
        Tunn {
            handshake: Handshake::new(
                static_private,
                static_public,
                peer_static_public,
                index_table,
                preshared_key,
            ),
            sessions: Default::default(),
            current: Default::default(),
            session_counter: Default::default(),
            tx_bytes: Default::default(),
            rx_bytes: Default::default(),
            packet_queue: VecDeque::new(),
            timers: Timers::new(persistent_keepalive),
            rate_limiter,
            jitter_rng,
        }
    }

    pub fn is_expired(&self) -> bool {
        self.handshake.is_expired()
    }

    pub fn reset(&mut self) {
        self.clear_all();
        self.handshake.reset();
    }

    pub fn set_static_private(
        &mut self,
        static_private: x25519::StaticSecret,
        static_public: x25519::PublicKey,
        rate_limiter: Arc<RateLimiter>,
    ) {
        self.rate_limiter = rate_limiter;
        self.handshake.set_static_private(static_private, static_public);
        for s in &mut self.sessions {
            *s = None;
        }
    }

    pub fn set_preshared_key(&mut self, preshared_key: Option<[u8; 32]>) {
        self.handshake.set_preshared_key(preshared_key);
    }

    pub fn preshared_key(&self) -> Option<[u8; 32]> {
        self.handshake.preshared_key()
    }

    pub fn handle_outgoing_packet(
        &mut self,
        mut packet: Packet,
        tun_mtu: Option<&mut MtuWatcher>,
    ) -> Option<WgKind> {
        if let Some(tun_mtu) = tun_mtu {
            packet = pad_to_x16(packet, tun_mtu);
        }
        match self.encapsulate_with_session(packet) {
            Ok(p) => Some(p.into()),
            Err(packet) => {
                self.queue_packet(packet);
                self.format_handshake_initiation(false).map(Into::into)
            }
        }
    }

    pub fn encapsulate_with_session(&mut self, packet: Packet) -> Result<Packet<WgData>, Packet> {
        let current = self.current;
        if let Some(ref session) = self.sessions[current % N_SESSIONS] {
            let packet = session.format_packet_data(packet)?;
            self.timer_tick(TimerName::TimeLastPacketSent);
            if !packet.is_keepalive() {
                self.timer_tick(TimerName::TimeLastDataPacketSent);
            }
            self.tx_bytes += packet.as_bytes().len();
            Ok(packet)
        } else {
            Err(packet)
        }
    }

    pub fn handle_incoming_packet(&mut self, packet: WgKind) -> TunnResult {
        match packet {
            WgKind::HandshakeInit(p) => self.handle_handshake_init(p),
            WgKind::HandshakeResp(p) => self.handle_handshake_response(p),
            WgKind::CookieReply(p) => self.handle_cookie_reply(&p),
            WgKind::Data(p) => self.handle_data(p),
        }
        .unwrap_or_else(TunnResult::from)
    }

    fn handle_handshake_init(
        &mut self,
        p: Packet<WgHandshakeInit>,
    ) -> Result<TunnResult, WireGuardError> {
        let n_bytes = p.as_bytes().len();
        let (packet, session) = self.handshake.receive_handshake_initialization(p)?;
        self.rx_bytes += n_bytes;
        let slot = self.next_session_slot();
        self.put_session(slot, session);
        self.timer_tick(TimerName::TimeLastPacketReceived);
        self.timer_tick(TimerName::TimeLastPacketSent);
        self.timer_tick_session_established(false, slot);
        self.tx_bytes += packet.as_bytes().len();
        Ok(TunnResult::WriteToNetwork(packet.into()))
    }

    fn handle_handshake_response(
        &mut self,
        p: Packet<WgHandshakeResp>,
    ) -> Result<TunnResult, WireGuardError> {
        let session = self.handshake.receive_handshake_response(&p)?;
        self.rx_bytes += p.as_bytes().len();
        let mut p = p.into_bytes();
        p.truncate(0);
        let keepalive_packet = session
            .format_packet_data(p)
            .expect("fresh session counter usable");
        let slot = self.next_session_slot();
        self.put_session(slot, session);
        self.timer_tick(TimerName::TimeLastPacketReceived);
        self.timer_tick_session_established(true, slot);
        self.set_current_session(slot);
        self.tx_bytes += keepalive_packet.as_bytes().len();
        Ok(TunnResult::WriteToNetwork(keepalive_packet.into()))
    }

    fn handle_cookie_reply(&mut self, p: &WgCookieReply) -> Result<TunnResult, WireGuardError> {
        self.handshake.receive_cookie_reply(p)?;
        self.timer_tick(TimerName::TimeCookieReceived);
        Ok(TunnResult::Done)
    }

    fn set_current_session(&mut self, new_slot: usize) {
        let cur_slot = self.current;
        if cur_slot == new_slot {
            return;
        }
        if self.sessions[cur_slot % N_SESSIONS].is_none()
            || self.timers.session_timers[new_slot % N_SESSIONS]
                >= self.timers.session_timers[cur_slot % N_SESSIONS]
        {
            self.current = new_slot;
        }
    }

    fn next_session_slot(&mut self) -> usize {
        let slot = self.session_counter % N_SESSIONS;
        self.session_counter = self.session_counter.wrapping_add(1);
        slot
    }

    fn put_session(&mut self, slot: usize, session: session::Session) {
        self.sessions[slot % N_SESSIONS] = Some(session);
    }

    fn handle_data(&mut self, packet: Packet<WgData>) -> Result<TunnResult, WireGuardError> {
        let decapsulated = self.decapsulate_with_session(packet)?;
        if !decapsulated.is_empty() {
            self.timer_tick(TimerName::TimeLastDataPacketReceived);
        }
        Ok(TunnResult::WriteToTunnel(decapsulated))
    }

    pub fn decapsulate_with_session(
        &mut self,
        packet: Packet<WgData>,
    ) -> Result<Packet, WireGuardError> {
        let r_idx = packet.header.receiver_idx.get();
        let (slot, session) = self
            .sessions
            .iter()
            .enumerate()
            .filter_map(|(i, s)| s.as_ref().map(|s| (i, s)))
            .find(|(_, s)| s.receiving_index.value() == r_idx)
            .ok_or(WireGuardError::NoCurrentSession)?;
        let decapsulated = session.receive_packet_data(packet)?;
        self.set_current_session(slot);
        self.timer_tick(TimerName::TimeLastPacketReceived);
        self.rx_bytes += decapsulated.as_bytes().len();
        Ok(decapsulated)
    }

    pub fn format_handshake_initiation(
        &mut self,
        force_resend: bool,
    ) -> Option<Packet<WgHandshakeInit>> {
        if self.handshake.is_in_progress() && !force_resend {
            return None;
        }
        if self.handshake.is_expired() {
            self.timers.clear();
        }
        let starting_new = !self.handshake.is_in_progress();
        let packet = self.handshake.format_handshake_initiation();
        if starting_new {
            self.timer_tick(TimerName::TimeLastHandshakeStarted);
        }
        self.timer_tick(TimerName::TimeLastPacketSent);
        self.update_rekey_timeout();
        self.tx_bytes += packet.as_bytes().len();
        Some(packet)
    }

    fn update_rekey_timeout(&mut self) {
        self.timers.rekey_timeout = self.sample_timer(|p| &p.rekey_timeout);
    }

    pub fn get_queued_packets(&mut self, tun_mtu: &mut MtuWatcher) -> Vec<WgKind> {
        let mut out = Vec::new();
        while let Some(packet) = self.dequeue_packet() {
            if let Some(wg) = self.handle_outgoing_packet(packet, Some(tun_mtu)) {
                out.push(wg);
            }
        }
        out
    }

    fn queue_packet(&mut self, packet: Packet) {
        if self.packet_queue.len() < MAX_QUEUE_DEPTH {
            self.packet_queue.push_back(packet);
        }
    }

    fn dequeue_packet(&mut self) -> Option<Packet> {
        self.packet_queue.pop_front()
    }

    fn estimate_loss(&self) -> f32 {
        let session_idx = self.current;
        let mut weight = 9.0;
        let mut cur_avg = 0.0;
        let mut total_weight = 0.0;
        for i in 0..N_SESSIONS {
            if let Some(ref s) = self.sessions[session_idx.wrapping_sub(i) % N_SESSIONS] {
                let (expected, received) = s.current_packet_cnt();
                let loss = if expected == 0 { 0.0 } else { 1.0 - received as f32 / expected as f32 };
                cur_avg += loss * weight;
                total_weight += weight;
                weight /= 3.0;
            }
        }
        if total_weight == 0.0 { 0.0 } else { cur_avg / total_weight }
    }

    pub fn stats(&self) -> (Option<Duration>, usize, usize, f32, Option<u32>) {
        (self.time_since_last_handshake(), self.tx_bytes, self.rx_bytes, self.estimate_loss(), self.handshake.last_rtt)
    }
}

fn pad_to_x16(mut packet: Packet, tun_mtu: &mut MtuWatcher) -> Packet {
    if packet.len().is_multiple_of(16) {
        return packet;
    }
    let padded_len = {
        let mtu = usize::from(tun_mtu.get());
        packet.len().next_multiple_of(16).min(mtu).max(packet.len())
    };
    packet.buf_mut().resize(padded_len, 0);
    packet
}
