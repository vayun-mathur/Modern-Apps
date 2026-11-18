//! Session state machine: Nearby Connections handshake, UKEY2, paired key, transfer.
//!
//! # Phases
//!
//! Everything is carried over the 4-byte-big-endian-framed socket described in
//! [`crate::frame`]. The channel changes meaning three times:
//!
//! | Phase | Body of each framed message | Source |
//! |---|---|---|
//! | [`Phase::Connecting`] | plaintext `OfflineFrame` — `CONNECTION_REQUEST` | `p000\dnlx.java:669-675` validates it |
//! | [`Phase::Ukey2`] | plaintext `Ukey2Message` — ClientInit / ServerInit / ClientFinished | `p000\dnij.java:102-107` writes all three through the same channel |
//! | [`Phase::ConnectionAccept`] | plaintext `OfflineFrame` — `CONNECTION_RESPONSE` | `p000\dncj.java:1204` writes it before the encryptor exists at `:1208` |
//! | [`Phase::PairedKey`], [`Phase::Ready`] | D2D-encrypted `OfflineFrame` | `p000\dnhn.java:212` encrypts before the length prefix, `:372` decrypts after it |
//!
//! # Ordering
//!
//! The response comes **after** UKEY2, not before, and both sides send it in
//! plaintext. Verified at four independent sites:
//!
//! - the client writes `CONNECTION_REQUEST` and then *immediately* starts the UKEY2
//!   client without waiting for anything (`p000\dnsi.java:9582` then `:9633`);
//! - the server reads `CONNECTION_REQUEST` and starts the UKEY2 server, sending no
//!   response (`p000\dnsi.java:5106` then `:5129`);
//! - `acceptConnection` writes `CONNECTION_RESPONSE` at `p000\dncj.java:1204` and
//!   only *then* calls `doeq.mo63639c()` at `:1208`, so it cannot have been
//!   encrypted;
//! - the encryptor is installed by `evaluateConnectionResult`
//!   (`p000\dnsi.java:4319`), which returns early unless **both** sides have
//!   accepted (`:4327-4339`).
//!
//! Accepting is programmatic at this layer, not a user prompt — Quick Share calls
//! `acceptConnection` as soon as the connection is offered (`p000\dzuj.java:76`) and
//! prompts the user later, with the Sharing-layer `INTRODUCTION` / `RESPONSE`.
//!
//! Sharing frames (`INTRODUCTION`, `RESPONSE`, `PAIRED_KEY_*`) are never framed
//! directly: each one is the body of a **BYTES** payload inside a
//! `PAYLOAD_TRANSFER` `OfflineFrame`. File bytes are **FILE** payloads.
//!
//! # UKEY2 cipher
//!
//! The record protocol is `AES_256_CBC-HMAC_SHA256` and nothing else. GMS offers
//! exactly that string (`p000\jgzt.java:798`) and rejects any other with
//! `"Incorrect next protocol"` / alert 103 (`p000\jgzt.java:550-551`). Offering
//! `Aes256GcmSiv`, as this module previously did, is rejected outright.

use std::collections::{HashMap, HashSet, VecDeque};

use crypto_provider_default::CryptoProviderImpl;
use ukey2_connections::{
    D2DConnectionContextV1, D2DHandshakeContext, HandshakeImplementation,
    InitiatorD2DHandshakeContext, NextProtocol, ServerD2DHandshakeContext,
};

use crate::frame::{
    self, ConsumeResult, OfflineFrameType, PairedKeyResultStatus, PayloadPacketType, PayloadType,
    SharingFrameType, SharingResponseStatus,
};
use crate::payload::{self, FileMeta};

/// Public state surfaced over JNI. Ordinals are the `ShareState` contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum State {
    /// Connection request, UKEY2 and paired-key exchange in progress.
    Handshaking = 0,
    /// An `INTRODUCTION` arrived; the UI must prompt accept/reject.
    AwaitingAccept = 1,
    /// Accepted; payload bytes are flowing.
    Transferring = 2,
    /// Every announced payload arrived.
    Completed = 3,
    /// Protocol failure or user rejection.
    Failed = 4,
}

/// Which side opened the TCP socket. Determines who sends `CONNECTION_REQUEST`
/// and who plays the UKEY2 server.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Dialled out; sends `CONNECTION_REQUEST` and drives UKEY2 as client.
    Initiator,
    /// Accepted the socket; is the UKEY2 server.
    Responder,
}

/// What the next framed message on the wire means.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// Plaintext `OfflineFrame`: awaiting `CONNECTION_REQUEST`. Responder only — an
    /// initiator leaves this phase inside [`Session::new`].
    Connecting,
    /// Plaintext `Ukey2Message`.
    Ukey2,
    /// Plaintext `OfflineFrame`: the mutual `CONNECTION_RESPONSE` exchange. The
    /// D2D context already exists but must not be used yet.
    ConnectionAccept,
    /// Encrypted; the mandatory paired-key frame pair is in flight.
    PairedKey,
    /// Encrypted; introduction, response and payloads.
    Ready,
}

struct ActiveSend {
    payload_id: i64,
    name: String,
    total_size: i64,
    sent_offset: i64,
}

struct ActiveRecv {
    name: String,
    expected_size: i64,
    next_offset: i64,
    completed: bool,
}

/// One decrypted FILE chunk, waiting to be handed to Kotlin by
/// [`Session::drain_received`].
///
/// The body is owned only until it is drained, so a `Session` never holds a whole
/// received file: memory stays proportional to the drain interval, not to the file.
struct ReceivedChunk {
    payload_id: i64,
    offset: i64,
    total_size: i64,
    last: bool,
    name: String,
    body: Vec<u8>,
}

/// Version byte of the [`Session::drain_received`] record — `PROTOCOL_CONTRACT.md` §6.
pub const RECEIVED_RECORD_VERSION: u8 = 1;

/// [`ReceivedChunk`] `flags` bit 0: this is the last chunk of its payload.
pub const RECEIVED_FLAG_LAST: u8 = 1;

/// Encode one received chunk in the self-describing layout of
/// `PROTOCOL_CONTRACT.md` §6.
///
/// Big-endian and length-prefixed throughout, matching the wire framing convention,
/// so the whole record can be pinned by a golden-byte test.
fn encode_received_record(chunk: &ReceivedChunk) -> Vec<u8> {
    let name = chunk.name.as_bytes();
    // A name longer than u16 cannot be described by the layout. Truncating on a
    // char boundary keeps the record decodable as UTF-8; Kotlin only uses the name
    // to pick a file name, so a truncated one is recoverable where a corrupt record
    // is not.
    let name = if name.len() > u16::MAX as usize {
        let mut end = u16::MAX as usize;
        while end > 0 && !chunk.name.is_char_boundary(end) {
            end -= 1;
        }
        &chunk.name.as_bytes()[..end]
    } else {
        name
    };
    let mut out = Vec::with_capacity(32 + name.len() + chunk.body.len());
    out.push(RECEIVED_RECORD_VERSION);
    out.extend_from_slice(&chunk.payload_id.to_be_bytes());
    out.extend_from_slice(&chunk.offset.to_be_bytes());
    out.extend_from_slice(&chunk.total_size.to_be_bytes());
    out.push(if chunk.last { RECEIVED_FLAG_LAST } else { 0 });
    out.extend_from_slice(&(name.len() as u16).to_be_bytes());
    out.extend_from_slice(name);
    out.extend_from_slice(&(chunk.body.len() as u32).to_be_bytes());
    out.extend_from_slice(&chunk.body);
    out
}

enum HandshakeState {
    None,
    Initiator(Box<InitiatorD2DHandshakeContext<CryptoProviderImpl>>),
    Server(Box<ServerD2DHandshakeContext<CryptoProviderImpl>>),
    Done,
    Failed,
}

/// One peer connection's protocol state.
pub struct Session {
    /// Human-readable local device name, sent as `ConnectionRequestFrame.endpoint_name`.
    pub local_name: String,
    /// Opaque Nearby Sharing endpoint blob, sent as `ConnectionRequestFrame.endpoint_info`.
    pub local_endpoint_info: Vec<u8>,
    /// Public state for the UI.
    pub state: State,
    phase: Phase,
    local_endpoint_id: String,
    outbound: Vec<Vec<u8>>,
    inbound_buf: Vec<u8>,
    handshake: HandshakeState,
    secure: Option<D2DConnectionContextV1>,
    pending_files: Vec<FileMeta>,
    files_to_send: Vec<FileMeta>,
    introduction_pending: bool,
    /// True once we have sent an `INTRODUCTION`, i.e. we are the sharing *sender*.
    ///
    /// A Sharing `RESPONSE` only answers an `INTRODUCTION`, so only the side that sent one
    /// may act on it — see [`Session::handle_response_status`].
    introduction_sent: bool,
    next_payload_id: i64,
    keep_alive_seq: u32,
    paired_key_encryption_sent: bool,
    paired_key_result_sent: bool,
    peer_paired_key_result_seen: bool,
    active_send: Option<ActiveSend>,
    /// Announced payload ids whose `FLAG_LAST` chunk has been written.
    ///
    /// The sender's completion condition, and the only one it has: nothing comes back from
    /// the peer to confirm a payload landed.
    sent_payloads: HashSet<i64>,
    recvs: HashMap<i64, ActiveRecv>,
    /// Partially received BYTES payloads, keyed by payload id.
    bytes_recvs: HashMap<i64, Vec<u8>>,
    /// Recent protocol events, for [`Session::trace`]. Bounded; oldest dropped.
    trace: VecDeque<String>,
    received_queue: VecDeque<ReceivedChunk>,
    last_data_payload_id: Option<i64>,
    accepted: Option<bool>,
    /// The peer's advertised device name, learned from its `CONNECTION_REQUEST`.
    peer_name: Option<String>,
    failed_reason: Option<String>,
}

/// `AES_256_CBC-HMAC_SHA256` — the only record protocol GMS accepts
/// (`p000\jgzt.java:550`, `:798`).
const NEXT_PROTOCOL: NextProtocol = NextProtocol::Aes256CbcHmacSha256;

/// GMS uses the **Java** UKEY2 encoding, not the spec's.
///
/// `p000\jgzt.java:88-97` decodes the peer's public key by *parsing it as a protobuf*
/// (`jhav`, then `jhao.m136773e`) and alerts with `104 "Cannot parse public key"` when that
/// fails — so the P-256 key travels as a serialized `GenericPublicKey`/`EcP256PublicKey{x,y}`,
/// not as the SEC 1 point [`HandshakeImplementation::Spec`] emits. Using `Spec` against a
/// real device fails with a UKEY2 `BAD_MESSAGE_DATA` alert.
const HANDSHAKE_IMPL: HandshakeImplementation = HandshakeImplementation::PublicKeyInProtobuf;

fn new_initiator_handshake() -> InitiatorD2DHandshakeContext<CryptoProviderImpl> {
    InitiatorD2DHandshakeContext::new(HANDSHAKE_IMPL, vec![NEXT_PROTOCOL])
}

fn new_server_handshake() -> ServerD2DHandshakeContext<CryptoProviderImpl> {
    ServerD2DHandshakeContext::new(HANDSHAKE_IMPL, &[NEXT_PROTOCOL])
}

pub(crate) fn fill_random(buf: &mut [u8]) {
    if getrandom::fill(buf).is_err() {
        // getrandom only fails if the OS entropy source is unavailable, which on
        // Android means the process is already unusable. Leaving the buffer zeroed
        // would silently emit a constant-pattern decoy, defeating its purpose, so
        // callers must treat this as fatal — see `Session::enter_paired_key`.
        buf.fill(0);
    }
}

/// A random starting point for the payload-id counter.
///
/// GMS picks a fresh random `int64` per payload — the two `PAIRED_KEY_ENCRYPTION` payloads
/// captured from a Pixel 7 Pro were `-8810033913771563443` and `-4647218023940673867` — and
/// `rquickshare` does the same (`core_lib/src/hdl/inbound.rs::send_encrypted_frame`). Ours
/// stays a counter so a multi-file `INTRODUCTION` can reserve a consecutive run, but it
/// starts somewhere unpredictable instead of at 1.
///
/// Masked to 48 bits: positive, and with enough headroom that reserving one id per file
/// cannot overflow.
fn random_payload_id_seed() -> i64 {
    let mut raw = [0u8; 8];
    fill_random(&mut raw);
    let id = i64::from_be_bytes(raw) & 0x0000_ffff_ffff_ffff;
    // 0 is not a usable payload id.
    id.max(1)
}

fn random_endpoint_id() -> String {
    // Nearby endpoint ids are 4 characters (`p000\dnlx.java:669` treats them as an
    // opaque required string; GMS generates 4 alphanumerics).
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let mut raw = [0u8; 4];
    fill_random(&mut raw);
    raw.iter()
        .map(|b| {
            let idx = (*b as usize) % ALPHABET.len();
            char::from(ALPHABET.get(idx).copied().unwrap_or(b'A'))
        })
        .collect()
}

#[cfg(test)]
/// Split a drained outbound buffer back into its individual framed messages.
fn split_frames(raw: &[u8]) -> Vec<Vec<u8>> {
    let mut buf = raw.to_vec();
    let mut out = Vec::new();
    loop {
        match frame::try_consume_frame(&mut buf) {
            ConsumeResult::Frame(body) => out.push(body),
            ConsumeResult::Incomplete => break,
            ConsumeResult::Invalid => panic!("drained buffer is not well framed"),
        }
    }
    assert!(buf.is_empty(), "trailing bytes after the last frame");
    out
}

#[cfg(test)]
/// The `V1Frame` of `body`, if `body` really is a plaintext `OfflineFrame`.
///
/// Re-encoding and requiring byte equality is what makes this a *proof* rather
/// than a guess: `prost` will happily decode many byte strings into a mostly
/// empty message, but an encrypted body will not round-trip.
fn plaintext_offline(body: &[u8]) -> Option<frame::OfflineV1Frame> {
    let offline = payload::parse_offline_frame(body).ok()?;
    if prost::Message::encode_to_vec(&offline) != body {
        return None;
    }
    offline.v1
}

#[cfg(test)]
fn frame_types(raw: &[u8]) -> Vec<Option<i32>> {
    split_frames(raw)
        .iter()
        .map(|f| plaintext_offline(f).map(|v1| v1.r#type))
        .collect()
}

#[cfg(test)]
/// One decoded `drain_received` record.
struct Drained {
    payload_id: i64,
    offset: i64,
    total_size: i64,
    last: bool,
    name: String,
    body: Vec<u8>,
}

#[cfg(test)]
fn decode_received_record(raw: &[u8]) -> Drained {
    fn i64_at(raw: &[u8], at: usize) -> i64 {
        i64::from_be_bytes(raw[at..at + 8].try_into().expect("8 bytes"))
    }
    assert_eq!(raw[0], RECEIVED_RECORD_VERSION, "record version");
    let payload_id = i64_at(raw, 1);
    let offset = i64_at(raw, 9);
    let total_size = i64_at(raw, 17);
    let flags = raw[25];
    let name_len = u16::from_be_bytes(raw[26..28].try_into().expect("2 bytes")) as usize;
    let name = String::from_utf8(raw[28..28 + name_len].to_vec()).expect("utf8 name");
    let body_at = 28 + name_len;
    let body_len =
        u32::from_be_bytes(raw[body_at..body_at + 4].try_into().expect("4 bytes")) as usize;
    let body = raw[body_at + 4..body_at + 4 + body_len].to_vec();
    assert_eq!(body_at + 4 + body_len, raw.len(), "record has trailing bytes");
    Drained {
        payload_id,
        offset,
        total_size,
        last: (flags & RECEIVED_FLAG_LAST) != 0,
        name,
        body,
    }
}

#[cfg(test)]
fn drain_all_received(s: &mut Session) -> Vec<Drained> {
    let mut out = Vec::new();
    while let Some(raw) = s.drain_received() {
        out.push(decode_received_record(&raw));
    }
    out
}

#[cfg(test)]
/// Pump both sides until neither has anything left to send.
fn settle(a: &mut Session, b: &mut Session) {
    for _ in 0..32 {
        let mut moved = false;
        if let Some(out) = a.outbound_drain() {
            assert!(b.feed_inbound(&out) >= 0, "b failed: {:?}", b.failed_reason.as_deref());
            moved = true;
        }
        if let Some(out) = b.outbound_drain() {
            assert!(a.feed_inbound(&out) >= 0, "a failed: {:?}", a.failed_reason.as_deref());
            moved = true;
        }
        if !moved {
            return;
        }
    }
    panic!("sessions did not settle");
}

#[cfg(test)]
/// A session with a random endpoint id, which the empty-id fallback supplies.
fn test_session(role: Role, local_name: &str, local_endpoint_info: Vec<u8>) -> Session {
    Session::new(
        role,
        local_name.to_string(),
        local_endpoint_info,
        String::new(),
    )
}

#[cfg(test)]
fn connected_pair() -> (Session, Session) {
    let mut initiator = test_session(Role::Initiator, "Alice", b"alice-info".to_vec());
    let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
    settle(&mut initiator, &mut responder);
    (initiator, responder)
}

include!("session_part1.rs");
include!("session_part2.rs");
include!("session_part3.rs");
include!("session_part4.rs");

#[cfg(test)]
#[path = "session_state_tests.rs"]
mod session_state_tests;
