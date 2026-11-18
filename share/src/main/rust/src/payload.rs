//! Sharing-layer frame builders and payload chunking.
//!
//! Two nested layers, both verified against the GMS 26.24.34 decompile — see the
//! module docs in [`crate::frame`] and `share/QUICK_SHARE_VERIFICATION.md`:
//!
//! 1. **Sharing** (`p000\duvt.java` → `p000\duwk.java`): the sender announces
//!    `FileMetadata[]` / `TextMetadata[]` in an `IntroductionFrame`; the receiver
//!    surfaces them and answers with a `ConnectionResponseFrame`. Each such
//!    `Frame` travels as the body of a **BYTES** payload.
//! 2. **Nearby Connections** (`p000\ivla.java`): every post-handshake message is an
//!    `OfflineFrame`. File bytes are **FILE** payloads split into `PayloadChunk`s.
//!
//! `:share` is Everyone-mode only and holds no paired-key certificate, so the
//! mandatory paired-key frames carry constant-shape random decoys — see
//! [`build_paired_key_encryption_decoy`].

use crate::frame::{
    BandwidthUpgradeEvent, BandwidthUpgradeNegotiationFrame, ConnectionRequestFrame,
    ConnectionsMedium, IntroductionFrame, KeepAliveFrame, OfflineFrame,
    OfflineFrameType, OfflineResponseStatus, OfflineV1Frame, PairedKeyEncryptionFrame,
    PairedKeyResultFrame, PairedKeyResultStatus, PayloadChunk, PayloadHeader, PayloadPacketType,
    PayloadTransferFrame, PayloadType, SharingConnectionResponseFrame, SharingFileMetadata,
    SharingFileType, SharingFrame, SharingFrameType, SharingResponseStatus, SharingV1Frame,
    SharingVersion, ShareUseCase, DEFAULT_MIME_TYPE,
};

/// Maximum `PayloadChunk.body` size.
///
/// Nearby Connections' chunk size is a Phenotype-tunable value, so 16 KiB is our
/// choice rather than a recovered constant. Any size the peer can buffer works —
/// the receiver reassembles by `offset`.
pub const MAX_CHUNK: usize = 16 * 1024;

/// `PayloadChunk.flags` bit 0: this is the final chunk of the payload.
pub const FLAG_LAST: i32 = 1;

/// `ConnectionRequestFrame.keep_alive_interval_millis` (field 8).
///
/// GMS's builder always populates this alongside the timeout
/// (`p000\dnlw.java:305` `ConnectRequestParameters{… keepAliveIntervalMillis=…,
/// keepAliveTimeoutMillis=…}`), but the shipped values are Phenotype-driven, so
/// these two numbers are **ours** rather than recovered.
pub const KEEP_ALIVE_INTERVAL_MILLIS: i32 = 5_000;

/// `ConnectionRequestFrame.keep_alive_timeout_millis` (field 9). Ours, as above.
pub const KEEP_ALIVE_TIMEOUT_MILLIS: i32 = 30_000;

/// Length of the `secret_id_hash` decoy, in bytes.
///
/// A device with no matching contact certificate sends 6 random bytes here
/// (`p000\dzvh.java:243`, `ebuk.m70456c(6)`), the same width as the real
/// `HKDF(authToken, secretId)[0..6]` it replaces (`p000\dzux.java:7-13`).
pub const SECRET_ID_HASH_LEN: usize = 6;

/// Length of the `signed_data` decoy, in bytes.
///
/// When the AndroidKeyStore has no paired key, GMS returns 72 random bytes rather
/// than an empty field, at all three failure sites: `p000\dzkz.java:509`, `:522`
/// and `:528`. 72 bytes is a plausible DER `SHA256withECDSA` P-256 signature
/// length, so the decoy is indistinguishable from a real one by size.
pub const SIGNED_DATA_DECOY_LEN: usize = 72;

/// Metadata surfaced to Kotlin's `queryPendingFiles` JSON.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileMeta {
    /// File name as announced by the peer.
    pub name: String,
    /// Declared size in bytes.
    pub size_bytes: u64,
    /// MIME type, with [`DEFAULT_MIME_TYPE`] substituted for an empty field.
    pub mime_type: String,
    /// `FileMetadata.payload_id` — the key the file's `PayloadHeader.id` will use.
    pub payload_id: i64,
}

impl FileMeta {
    /// Convert a decoded `FileMetadata`, applying the proto2 `mime_type` default.
    pub fn from_sharing(m: &SharingFileMetadata) -> Self {
        let mime_type = if m.mime_type.is_empty() {
            DEFAULT_MIME_TYPE.to_string()
        } else {
            m.mime_type.clone()
        };
        Self {
            name: m.name.clone(),
            size_bytes: m.size.max(0) as u64,
            mime_type,
            payload_id: m.payload_id,
        }
    }
}

// ---------------------------------------------------------------------------
// Sharing frames
// ---------------------------------------------------------------------------

fn sharing_frame(frame_type: SharingFrameType, v1: SharingV1Frame) -> Vec<u8> {
    let frame = SharingFrame {
        version: SharingVersion::V1 as i32,
        v1: Some(SharingV1Frame {
            r#type: frame_type as i32,
            ..v1
        }),
    };
    prost::Message::encode_to_vec(&frame)
}

/// Build the `INTRODUCTION` frame for `files`, assigning payload ids from `first_payload_id`.
///
/// `start_transfer` is set so the peer may begin without a second round trip, and
/// `use_case` is `NEARBY_SHARE` (`p000\duvv.java`).
pub fn build_introduction_frame(files: &[FileMeta], first_payload_id: i64) -> Vec<u8> {
    let file_metadata = files
        .iter()
        .enumerate()
        .map(|(i, f)| {
            let id = first_payload_id.saturating_add(i as i64);
            let mime_type = if f.mime_type.is_empty() {
                DEFAULT_MIME_TYPE.to_string()
            } else {
                f.mime_type.clone()
            };
            SharingFileMetadata {
                name: f.name.clone(),
                r#type: guess_file_type(&f.name, &mime_type) as i32,
                payload_id: id,
                size: i64::try_from(f.size_bytes).unwrap_or(i64::MAX),
                mime_type,
                id,
                ..Default::default()
            }
        })
        .collect();
    sharing_frame(
        SharingFrameType::Introduction,
        SharingV1Frame {
            introduction: Some(IntroductionFrame {
                file_metadata,
                start_transfer: true,
                use_case: ShareUseCase::NearbyShare as i32,
                ..Default::default()
            }),
            ..Default::default()
        },
    )
}

/// Build the `RESPONSE` frame carrying `ACCEPT` or `REJECT`.
pub fn build_connection_response(accept: bool) -> Vec<u8> {
    let status = if accept {
        SharingResponseStatus::Accept
    } else {
        SharingResponseStatus::Reject
    };
    sharing_frame(
        SharingFrameType::Response,
        SharingV1Frame {
            connection_response: Some(SharingConnectionResponseFrame {
                status: status as i32,
            }),
            ..Default::default()
        },
    )
}

/// Build the mandatory `PAIRED_KEY_ENCRYPTION` frame as an all-decoy frame.
///
/// The peer times out (~5 s, `share/QUICK_SHARE_VERIFICATION.md` §paired-key) if
/// this never arrives, so it must be sent even though `:share` can never produce a
/// real signature: contact certificates are minted server-side against a Google
/// account (`nearbysharing-pa.googleapis.com`), which is out of reach here.
///
/// `random_bytes` must fill its slice from a CSPRNG. The widths are fixed and the
/// content must not be a constant pattern — the whole point of the decoy is that
/// its *shape* is identical to a real frame, so a peer cannot distinguish
/// "no certificate" from "certificate did not match".
pub fn build_paired_key_encryption_decoy(
    random_bytes: impl Fn(&mut [u8]),
) -> Vec<u8> {
    let mut signed_data = vec![0u8; SIGNED_DATA_DECOY_LEN];
    random_bytes(&mut signed_data);
    let mut secret_id_hash = vec![0u8; SECRET_ID_HASH_LEN];
    random_bytes(&mut secret_id_hash);
    sharing_frame(
        SharingFrameType::PairedKeyEncryption,
        SharingV1Frame {
            paired_key_encryption: Some(PairedKeyEncryptionFrame {
                signed_data,
                secret_id_hash,
                ..Default::default()
            }),
            ..Default::default()
        },
    )
}

/// Build the `PAIRED_KEY_RESULT` frame.
///
/// `UNABLE` (`p000\duvz.java`) is the honest answer for a device that cannot verify
/// a paired key at all, as distinct from `FAIL` (verified and rejected).
pub fn build_paired_key_result(status: PairedKeyResultStatus) -> Vec<u8> {
    sharing_frame(
        SharingFrameType::PairedKeyResult,
        SharingV1Frame {
            paired_key_result: Some(PairedKeyResultFrame {
                status: status as i32,
                ..Default::default()
            }),
            ..Default::default()
        },
    )
}

/// Decode a Sharing `Frame`.
pub fn parse_sharing_frame(bytes: &[u8]) -> Result<SharingFrame, prost::DecodeError> {
    prost::Message::decode(bytes)
}

/// Extract the announced files from a decoded `IntroductionFrame`.
pub fn introduction_files(intro: &IntroductionFrame) -> Vec<FileMeta> {
    intro.file_metadata.iter().map(FileMeta::from_sharing).collect()
}

fn guess_file_type(name: &str, mime: &str) -> SharingFileType {
    let lower_mime = mime.to_ascii_lowercase();
    let lower_name = name.to_ascii_lowercase();
    if lower_mime.starts_with("image/") {
        return SharingFileType::Image;
    }
    if lower_mime.starts_with("video/") {
        return SharingFileType::Video;
    }
    if lower_mime.starts_with("audio/") {
        return SharingFileType::Audio;
    }
    if lower_mime == "application/vnd.android.package-archive" || lower_name.ends_with(".apk") {
        return SharingFileType::AndroidApp;
    }
    if lower_mime == "text/vcard" || lower_mime == "text/x-vcard" || lower_name.ends_with(".vcf") {
        return SharingFileType::ContactCard;
    }
    SharingFileType::Document
}

// ---------------------------------------------------------------------------
// Nearby Connections OfflineFrame wrapping
// ---------------------------------------------------------------------------

fn offline_frame(frame_type: OfflineFrameType, v1: OfflineV1Frame) -> Vec<u8> {
    let frame = OfflineFrame {
        version: crate::frame::OfflineVersion::V1 as i32,
        v1: Some(OfflineV1Frame {
            r#type: frame_type as i32,
            ..v1
        }),
    };
    prost::Message::encode_to_vec(&frame)
}

/// Wrap a `PayloadTransferFrame` in an `OfflineFrame`.
pub fn wrap_payload_transfer(pt: PayloadTransferFrame) -> Vec<u8> {
    offline_frame(
        OfflineFrameType::PayloadTransfer,
        OfflineV1Frame {
            payload_transfer: Some(pt),
            ..Default::default()
        },
    )
}

/// Build the `CONNECTION_REQUEST` frame that opens a Nearby Connections session.
///
/// `endpoint_id` and `endpoint_name` are both required (`p000\dnlx.java:669`, `:675`).
/// Only `WIFI_LAN` is offered: `:share` rides the LAN socket directly and does not
/// implement bandwidth upgrade.
pub fn build_connection_request(
    endpoint_id: &str,
    endpoint_name: &str,
    endpoint_info: &[u8],
    nonce: i32,
) -> Vec<u8> {
    offline_frame(
        OfflineFrameType::ConnectionRequest,
        OfflineV1Frame {
            connection_request: Some(ConnectionRequestFrame {
                endpoint_id: endpoint_id.to_string(),
                endpoint_name: endpoint_name.to_string(),
                endpoint_info: endpoint_info.to_vec(),
                nonce,
                mediums: vec![ConnectionsMedium::WifiLan as i32],
                keep_alive_interval_millis: KEEP_ALIVE_INTERVAL_MILLIS,
                keep_alive_timeout_millis: KEEP_ALIVE_TIMEOUT_MILLIS,
                ..Default::default()
            }),
            ..Default::default()
        },
    )
}

/// Build the `CONNECTION_RESPONSE` frame accepting or rejecting the request.
///
/// Both status fields are written, as GMS's single builder does
/// (`p000\dnlx.java:1039-1051`): the legacy int32 `status` and the `response` enum
/// derived from it. Writing only one of the two is not a shape GMS ever emits.
pub fn build_offline_connection_response(accept: bool) -> Vec<u8> {
    let (status, response) = if accept {
        (
            crate::frame::OFFLINE_RESPONSE_STATUS_ACCEPT,
            OfflineResponseStatus::Accept,
        )
    } else {
        (
            crate::frame::OFFLINE_RESPONSE_STATUS_REJECT,
            OfflineResponseStatus::Reject,
        )
    };
    offline_frame(
        OfflineFrameType::ConnectionResponse,
        OfflineV1Frame {
            connection_response: Some(crate::frame::OfflineConnectionResponseFrame {
                status: Some(status),
                response: Some(response as i32),
                ..Default::default()
            }),
            ..Default::default()
        },
    )
}

/// Build a `KEEP_ALIVE` frame.
pub fn build_keep_alive(ack: bool, seq_num: u32) -> Vec<u8> {
    offline_frame(
        OfflineFrameType::KeepAlive,
        OfflineV1Frame {
            keep_alive: Some(KeepAliveFrame { ack, seq_num }),
            ..Default::default()
        },
    )
}

/// Build a `BANDWIDTH_UPGRADE_NEGOTIATION{UPGRADE_FAILURE}` frame.
///
/// `:share` rides the WIFI_LAN socket it is already on — the medium a peer would upgrade
/// *to* — so it never accepts an upgrade. It must still answer: a Quick Share sender that
/// asks for one and hears nothing stops the Sharing handshake, keep-alives for about fifteen
/// seconds and then disconnects, without ever sending its `INTRODUCTION`.
pub fn build_bandwidth_upgrade_failure() -> Vec<u8> {
    offline_frame(
        OfflineFrameType::BandwidthUpgradeNegotiation,
        OfflineV1Frame {
            bandwidth_upgrade_negotiation: Some(BandwidthUpgradeNegotiationFrame {
                event_type: BandwidthUpgradeEvent::UpgradeFailure as i32,
            }),
            ..Default::default()
        },
    )
}

/// Decode an `OfflineFrame`.
pub fn parse_offline_frame(bytes: &[u8]) -> Result<OfflineFrame, prost::DecodeError> {
    prost::Message::decode(bytes)
}

// ---------------------------------------------------------------------------
// Payload chunking
// ---------------------------------------------------------------------------

/// Build a `PayloadHeader` for `id`.
///
/// `is_sensitive` is written explicitly — see [`PayloadHeader`] for why absence is not the
/// same as false.
pub fn payload_header(
    id: i64,
    payload_type: PayloadType,
    total_size: i64,
    file_name: &str,
) -> PayloadHeader {
    PayloadHeader {
        id,
        r#type: payload_type as i32,
        total_size,
        is_sensitive: Some(false),
        file_name: file_name.to_string(),
        ..Default::default()
    }
}

/// Wrap `body` as a **BYTES** payload: the data chunk, then its terminator.
///
/// Two frames, not one. The data chunk carries the body with `flags = 0` at offset 0, and a
/// second chunk with [`FLAG_LAST`], an empty body and `offset = body.len()` closes the
/// payload — which is what the receiver waits for before handing the bytes up.
///
/// Measured, not assumed: a Pixel 7 Pro on GMS 26.24.34 sends its own Sharing frames exactly
/// this way, e.g. an 89-byte `PAIRED_KEY_ENCRYPTION` as
/// `off 0 len 89 flags 0` followed by `off 89 len 0 flags 1`.
///
/// This is how a Sharing `Frame` reaches the peer: as the body of a BYTES payload inside
/// `PAYLOAD_TRANSFER`, not as a frame of its own.
pub fn bytes_payload(id: i64, body: &[u8]) -> Vec<Vec<u8>> {
    let header = payload_header(id, PayloadType::Bytes, body.len() as i64, "");
    let data = wrap_payload_transfer(PayloadTransferFrame {
        packet_type: PayloadPacketType::Data as i32,
        payload_header: Some(header.clone()),
        payload_chunk: Some(PayloadChunk {
            flags: Some(0),
            offset: Some(0),
            body: Some(body.to_vec()),
        }),
        control_message: None,
    });
    let terminator = wrap_payload_transfer(PayloadTransferFrame {
        packet_type: PayloadPacketType::Data as i32,
        payload_header: Some(header),
        payload_chunk: Some(PayloadChunk {
            flags: Some(FLAG_LAST),
            offset: Some(body.len() as i64),
            body: Some(Vec::new()),
        }),
        control_message: None,
    });
    vec![data, terminator]
}

include!("payload_part1.rs");
#[cfg(test)]
#[path = "payload_extra_tests.rs"]
mod payload_extra_tests;
