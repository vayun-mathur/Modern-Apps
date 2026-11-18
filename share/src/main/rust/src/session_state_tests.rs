//! Session accessor and handshake-refusal tests: `query_state`,
//! `peer_name`, `trace_text` on live sessions, and the failure paths
//! for frames that arrive in the wrong phase or do not parse.

use super::*;
use crate::frame;
use crate::payload;

/// The first length-prefixed frame of a drained batch.
fn first_frame(raw: &[u8]) -> Vec<u8> {
    let len = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) as usize;
    raw[4..4 + len].to_vec()
}

#[test]
fn a_fresh_session_is_handshaking_with_no_peer_or_trace() {
    let session = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
    assert_eq!(session.query_state(), State::Handshaking as i32);
    assert_eq!(session.peer_name(), None);
    assert_eq!(session.trace_text(), "");
}

#[test]
fn a_connected_pair_knows_each_other_and_traced_it() {
    let (initiator, responder) = connected_pair();
    // The responder learns the initiator's name from the connection
    // request (its endpoint-info bytes here are not a real blob, so
    // the request's endpoint_name is the source).
    assert_eq!(responder.peer_name(), Some("Alice"));
    let trace = responder.trace_text();
    assert!(trace.contains("Alice"), "trace should name the peer: {trace}");
    assert!(initiator.trace_text().contains('\n') || !initiator.trace_text().is_empty());
}

#[test]
fn a_responder_refuses_a_first_frame_that_is_not_an_offline_frame() {
    let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
    let raw = frame::frame_with_length(b"this is not an OfflineFrame");
    assert_eq!(responder.feed_inbound(&raw), -2);
    assert_eq!(responder.query_state(), State::Failed as i32);
    let reason = responder.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("not a parseable OfflineFrame"), "{reason}");
    // A failed session ignores later bytes rather than failing again.
    assert_eq!(responder.feed_inbound(&raw), 0);
}

#[test]
fn a_responder_refuses_a_connection_response_as_its_first_frame() {
    let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
    let body = payload::build_offline_connection_response(true);
    let raw = frame::frame_with_length(&body);
    assert_eq!(responder.feed_inbound(&raw), -2);
    let reason = responder.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("expected a CONNECTION_REQUEST"), "{reason}");
}

#[test]
fn a_responder_refuses_ukey2_phase_garbage() {
    let mut initiator = test_session(Role::Initiator, "Alice", b"alice-info".to_vec());
    let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
    let batch = initiator.outbound_drain().expect("initiator speaks first");
    // Feed only the CONNECTION_REQUEST; the responder is now the UKEY2
    // server and a non-UKEY2 frame must fail the session.
    let request = first_frame(&batch);
    let raw = frame::frame_with_length(&request);
    // `request` already carries its length prefix from the drain.
    let _ = raw;
    assert_eq!(responder.feed_inbound(&batch[..4 + request.len()]), 0);
    let junk = frame::frame_with_length(b"definitely not a UKEY2 message");
    assert_eq!(responder.feed_inbound(&junk), -2);
    assert_eq!(responder.query_state(), State::Failed as i32);
    assert!(responder.failure_reason().is_some());
}

#[test]
fn an_encrypted_frame_before_the_secure_channel_exists_fails() {
    let (mut initiator, _responder) = connected_pair();
    // Strip the channel and rewind to the paired-key phase: an
    // encrypted frame now has nothing to decrypt with.
    initiator.secure = None;
    initiator.phase = Phase::PairedKey;
    let junk = frame::frame_with_length(b"ciphertext that cannot be read");
    assert_eq!(initiator.feed_inbound(&junk), -2);
    let reason = initiator.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("secure channel"), "{reason}");
}

#[test]
fn outbound_drain_empties_the_queue() {
    let mut initiator = test_session(Role::Initiator, "Alice", Vec::new());
    assert!(initiator.outbound_drain().is_some(), "the request batch is queued at once");
    assert_eq!(initiator.outbound_drain(), None);
}

#[test]
fn transfer_guards_refuse_calls_before_their_phase() {
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    // Nothing to accept before an introduction arrives.
    assert_eq!(session.accept(true, ""), -2);
    // No introduction without files, and no keep-alive before Ready.
    assert_eq!(session.queue_introduction(), 0);
    assert_eq!(session.send_keep_alive(), -2);
    // File transfer needs the secure channel and an open file.
    assert_eq!(session.open_file("a.txt", 1), -2);
    assert_eq!(session.write_chunk(b"x"), -2);
    assert_eq!(session.close_file(), 0, "closing nothing is a no-op");
    assert_eq!(session.drain_received(), None);
    assert!(session.pending_files().is_empty());
    // Staging files is always allowed; the announcement is deferred.
    session.set_pending_files_for_send(vec![FileMeta {
        name: "a.txt".to_string(),
        size_bytes: 1,
        mime_type: String::new(),
        payload_id: 0,
    }]);
    assert_eq!(session.queue_introduction(), 0);
    assert!(!session.introduction_sent, "not Ready, so nothing went out");
}

/// An `OfflineFrame` of the given v1 type with no other fields set.
fn offline_of_type(t: frame::OfflineFrameType) -> Vec<u8> {
    use prost::Message as _;
    frame::OfflineFrame {
        version: frame::OfflineVersion::V1 as i32,
        v1: Some(frame::OfflineV1Frame { r#type: t as i32, ..Default::default() }),
    }
    .encode_to_vec()
}

/// Send `body` from `from` to `to` through `from`'s encryptor.
fn send_encrypted(from: &mut Session, to: &mut Session, body: &[u8]) -> i32 {
    assert_eq!(from.send_encrypted(body), 0, "crafting the encrypted frame");
    let wire = from.outbound_drain().expect("the frame is queued");
    to.feed_inbound(&wire)
}

#[test]
fn an_empty_first_frame_fails_as_missing_v1() {
    let mut responder = test_session(Role::Responder, "Bob", b"bob-info".to_vec());
    // A zero-length body decodes as a default OfflineFrame: no v1.
    assert_eq!(responder.feed_inbound(&frame::frame_with_length(&[])), -2);
    let reason = responder.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("OfflineFrame without v1"), "{reason}");
}

#[test]
fn the_connection_accept_phase_refuses_wrong_frames() {
    // Undecodable body.
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    session.phase = Phase::ConnectionAccept;
    assert_eq!(session.feed_inbound(&frame::frame_with_length(b"garbage")), -2);
    assert!(session.failure_reason().expect("reason").contains("not a parseable OfflineFrame"));

    // No v1.
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    session.phase = Phase::ConnectionAccept;
    assert_eq!(session.feed_inbound(&frame::frame_with_length(&[])), -2);
    assert!(session.failure_reason().expect("reason").contains("without v1"));

    // A request where a response belongs.
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    session.phase = Phase::ConnectionAccept;
    let req = offline_of_type(frame::OfflineFrameType::ConnectionRequest);
    assert_eq!(session.feed_inbound(&frame::frame_with_length(&req)), -2);
    assert!(session.failure_reason().expect("reason").contains("expected a CONNECTION_RESPONSE"));

    // A response frame with no connection_response field.
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    session.phase = Phase::ConnectionAccept;
    let resp = offline_of_type(frame::OfflineFrameType::ConnectionResponse);
    assert_eq!(session.feed_inbound(&frame::frame_with_length(&resp)), -2);
    assert!(session.failure_reason().expect("reason").contains("without a connection_response field"));
}

#[test]
fn a_handshake_frame_with_no_handshake_in_progress_fails() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    session.phase = Phase::Ukey2;
    session.handshake = HandshakeState::None;
    assert_eq!(session.feed_inbound(&frame::frame_with_length(b"ClientInit?")), -2);
    let reason = session.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("no handshake in progress"), "{reason}");
}

#[test]
fn undecryptable_bytes_fail_the_session_once_encryption_starts() {
    let (mut a, mut b) = connected_pair();
    // `b` is in the paired-key phase with a live channel; random bytes
    // are not a D2D record its key can open.
    let junk = frame::frame_with_length(&[0x5A; 48]);
    assert_eq!(b.feed_inbound(&junk), -2);
    let reason = b.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("D2D decrypt failed"), "{reason}");
    let _ = a.outbound_drain();
}

#[test]
fn a_disconnection_mid_handshake_fails_but_afterwards_is_ignored() {
    let (mut a, mut b) = connected_pair();
    // Both sides are still in Handshaking state at Ready phase, so a
    // hangup here is a failure worth naming.
    let disc = offline_of_type(frame::OfflineFrameType::Disconnection);
    assert_eq!(send_encrypted(&mut b, &mut a, &disc), -2);
    let reason = a.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("peer disconnected during"), "{reason}");

    // Once the UI has accepted (AwaitingAccept/Transferring gate), a
    // hangup outside the transfer states is simply noted, not fatal:
    // emulate by moving the receiver out of the named states.
    let (mut a, mut b) = connected_pair();
    a.state = State::AwaitingAccept;
    let disc = offline_of_type(frame::OfflineFrameType::Disconnection);
    assert_eq!(send_encrypted(&mut b, &mut a, &disc), 0);
    assert_eq!(a.query_state(), State::AwaitingAccept as i32);
}

#[test]
fn unrelated_offline_types_are_ignored_and_empty_transfers_are_noops() {
    let (mut a, mut b) = connected_pair();
    // A Connections-layer type we do not implement (here: a second
    // CONNECTION_REQUEST over the encrypted channel) is ignored.
    let req = offline_of_type(frame::OfflineFrameType::ConnectionRequest);
    assert_eq!(send_encrypted(&mut b, &mut a, &req), 0);
    assert!(a.failure_reason().is_none());
    // A PayloadTransfer frame header with no payload_transfer field
    // attached is likewise a no-op (the handler is covered end-to-end
    // by the transfer tests; this is its empty arm).
    let empty_pt = offline_of_type(frame::OfflineFrameType::PayloadTransfer);
    assert_eq!(send_encrypted(&mut b, &mut a, &empty_pt), 0);
    assert!(a.failure_reason().is_none());
}

/// A `SharingFrame` of the given type with no other fields set.
fn sharing_of_type(t: frame::SharingFrameType) -> Vec<u8> {
    use prost::Message as _;
    frame::SharingFrame {
        version: frame::SharingVersion::V1 as i32,
        v1: Some(frame::SharingV1Frame { r#type: t as i32, ..Default::default() }),
    }
    .encode_to_vec()
}

#[test]
fn sharing_frame_guards_note_and_ignore_bad_frames() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    // Undecodable bytes are noted, not fatal.
    assert_eq!(session.handle_sharing_frame(b"garbage"), 0);
    assert!(session.trace_text().contains("undecodable"), "{}", session.trace_text());
    // A frame with no v1 is likewise noted.
    use prost::Message as _;
    let bare = frame::SharingFrame { version: 1, v1: None }.encode_to_vec();
    assert_eq!(session.handle_sharing_frame(&bare), 0);
    assert!(session.trace_text().contains("without v1"));
    // An introduction announcing nothing is a no-op.
    let empty_intro = payload::build_introduction_frame(&[], 1);
    assert_eq!(session.handle_sharing_frame(&empty_intro), 0);
    assert_eq!(session.query_state(), State::Handshaking as i32);
}

#[test]
fn a_peer_cancel_fails_the_session() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    let cancel = sharing_of_type(frame::SharingFrameType::Cancel);
    assert_eq!(session.handle_sharing_frame(&cancel), -2);
    let reason = session.failure_reason().expect("a reason is recorded");
    assert!(reason.contains("cancelled"), "{reason}");
}

#[test]
fn response_statuses_fail_with_the_peers_reason() {
    for (status, want) in [
        (frame::SharingResponseStatus::NotEnoughSpace, "not enough space"),
        (frame::SharingResponseStatus::UnsupportedAttachmentType, "does not support"),
        (frame::SharingResponseStatus::TimedOut, "timed out"),
    ] {
        let mut session = test_session(Role::Initiator, "Alice", Vec::new());
        session.introduction_sent = true;
        // The session fails, but the handler itself reports no error.
        assert_eq!(session.handle_response_status(status as i32), 0);
        let reason = session.failure_reason().expect("a reason is recorded");
        assert!(reason.contains(want), "status {status:?}: {reason}");
    }
    // An unlisted status is named as unknown, not mapped to a guess.
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    session.introduction_sent = true;
    assert_eq!(session.handle_response_status(999), 0);
    assert!(session.failure_reason().expect("reason").contains("unknown connection response status"));
    // A RESPONSE to an introduction we never sent is ignored.
    let mut session = test_session(Role::Initiator, "Alice", Vec::new());
    assert_eq!(session.handle_response_status(frame::SharingResponseStatus::Reject as i32), 0);
    assert!(session.failure_reason().is_none());
}

#[test]
fn payload_transfer_guards_ignore_non_data_and_headerless_unknowns() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    let control = frame::PayloadTransferFrame {
        packet_type: frame::PayloadPacketType::Control as i32,
        ..Default::default()
    };
    assert_eq!(session.handle_payload_transfer(control), 0);
    // DATA with no chunk at all.
    let empty = frame::PayloadTransferFrame {
        packet_type: frame::PayloadPacketType::Data as i32,
        ..Default::default()
    };
    assert_eq!(session.handle_payload_transfer(empty), 0);
    // A continuation chunk (no header) for a payload we never saw.
    session.last_data_payload_id = Some(4242);
    let continuation = frame::PayloadTransferFrame {
        packet_type: frame::PayloadPacketType::Data as i32,
        payload_chunk: Some(frame::PayloadChunk {
            flags: Some(1),
            offset: Some(0),
            body: Some(b"x".to_vec()),
        }),
        ..Default::default()
    };
    assert_eq!(session.handle_payload_transfer(continuation), 0);
    assert!(session.drain_received().is_none(), "nothing was recorded");
}

#[test]
fn accept_fails_when_the_response_cannot_be_sent() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    session.state = State::AwaitingAccept;
    session.secure = None;
    assert_eq!(session.accept(true, ""), -2);
}

#[test]
fn open_file_allocates_an_id_for_an_unannounced_name() {
    let (mut a, _b) = connected_pair();
    assert_eq!(a.open_file("never-announced.bin", 4), 0);
    assert_eq!(a.write_chunk(b"data"), 0);
    assert_eq!(a.close_file(), 0);
    // With the channel gone the same chunk cannot be written.
    let (mut a, _b) = connected_pair();
    assert_eq!(a.open_file("x.bin", 4), 0);
    a.secure = None;
    assert_eq!(a.write_chunk(b"data"), -2);
}

#[test]
fn a_received_name_longer_than_the_record_field_is_truncated_on_a_boundary() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    // 40k two-byte characters: 80_000 name bytes against a u16 length.
    let name = "é".repeat(40_000);
    session.received_queue.push_back(ReceivedChunk {
        payload_id: 9,
        offset: 0,
        total_size: 1,
        last: true,
        name,
        body: b"z".to_vec(),
    });
    let record = session.drain_received().expect("one record drains");
    let drained = decode_received_record(&record);
    assert_eq!(drained.payload_id, 9);
    assert!(drained.name.len() <= u16::MAX as usize, "name fits the u16 length");
    assert!(drained.name.chars().all(|c| c == 'é'), "no split character");
    assert_eq!(drained.body, b"z".to_vec());
}

#[test]
fn encrypted_garbage_and_empty_bodies_fail_after_decryption() {
    // Decryption succeeds (the peer really sent these), but the
    // plaintext is not an OfflineFrame.
    let (mut a, mut b) = connected_pair();
    assert_eq!(send_encrypted(&mut b, &mut a, b"not an offline frame"), -2);
    assert!(a.failure_reason().expect("reason").contains("not a parseable OfflineFrame"));

    let (mut a, mut b) = connected_pair();
    assert_eq!(send_encrypted(&mut b, &mut a, &[]), -2);
    assert!(a.failure_reason().expect("reason").contains("OfflineFrame without v1"));
}

#[test]
fn a_bandwidth_upgrade_request_is_declined_not_ignored() {
    let (mut a, mut b) = connected_pair();
    use prost::Message as _;
    let frame = frame::OfflineFrame {
        version: frame::OfflineVersion::V1 as i32,
        v1: Some(frame::OfflineV1Frame {
            r#type: frame::OfflineFrameType::BandwidthUpgradeNegotiation as i32,
            bandwidth_upgrade_negotiation: Some(frame::BandwidthUpgradeNegotiationFrame {
                event_type: 1,
                ..Default::default()
            }),
            ..Default::default()
        }),
    }
    .encode_to_vec();
    assert_eq!(send_encrypted(&mut b, &mut a, &frame), 0);
    assert!(a.failure_reason().is_none());
    assert!(a.trace_text().contains("declining bandwidth upgrade"), "{}", a.trace_text());
    // The decline itself is on its way back to the peer.
    assert!(a.outbound_drain().is_some());
}

#[test]
fn sharing_introduction_and_response_without_bodies_are_noops() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    assert_eq!(session.handle_sharing_frame(&sharing_of_type(frame::SharingFrameType::Introduction)), 0);
    assert_eq!(session.handle_sharing_frame(&sharing_of_type(frame::SharingFrameType::Response)), 0);
    assert!(session.failure_reason().is_none());
    // Entering readiness from the wrong phase is also a no-op.
    session.maybe_enter_ready();
    assert_eq!(session.phase, Phase::Connecting);
}

#[test]
fn a_paired_key_encryption_we_cannot_answer_fails_the_send() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    // No secure channel: answering the peer's paired-key opening with
    // our own (and the UNABLE result) cannot be encrypted.
    let frame = payload::build_paired_key_encryption_decoy(|b: &mut [u8]| b.fill(9));
    assert_eq!(session.handle_sharing_frame(&frame), -2);
}

#[test]
fn a_continuation_chunk_joins_the_payload_it_follows() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    session.recvs.insert(
        4242,
        ActiveRecv { name: "f.bin".to_string(), expected_size: 1, next_offset: 0, completed: false },
    );
    session.last_data_payload_id = Some(4242);
    let continuation = frame::PayloadTransferFrame {
        packet_type: frame::PayloadPacketType::Data as i32,
        payload_chunk: Some(frame::PayloadChunk {
            flags: Some(1),
            offset: Some(0),
            body: Some(b"x".to_vec()),
        }),
        ..Default::default()
    };
    assert_eq!(session.handle_payload_transfer(continuation), 0);
    let record = session.drain_received().expect("the chunk was recorded");
    let drained = decode_received_record(&record);
    assert_eq!(drained.name, "f.bin");
    assert_eq!(drained.body, b"x".to_vec());
    assert!(drained.last);
}

#[test]
fn a_bytes_chunk_after_a_gap_drops_the_partial_frame() {
    let mut session = test_session(Role::Responder, "Bob", Vec::new());
    let header = frame::PayloadHeader {
        id: 7,
        r#type: frame::PayloadType::Bytes as i32,
        total_size: 0,
        file_name: String::new(),
        ..Default::default()
    };
    let first = frame::PayloadTransferFrame {
        packet_type: frame::PayloadPacketType::Data as i32,
        payload_header: Some(header.clone()),
        payload_chunk: Some(frame::PayloadChunk {
            flags: Some(0),
            offset: Some(0),
            body: Some(b"partial".to_vec()),
        }),
        ..Default::default()
    };
    assert_eq!(session.handle_payload_transfer(first), 0);
    // The next chunk claims offset 100 while we hold 7 bytes: the
    // partial frame is dropped rather than reassembled with a hole.
    let gapped = frame::PayloadTransferFrame {
        packet_type: frame::PayloadPacketType::Data as i32,
        payload_header: Some(header),
        payload_chunk: Some(frame::PayloadChunk {
            flags: Some(0),
            offset: Some(100),
            body: Some(b"x".to_vec()),
        }),
        ..Default::default()
    };
    assert_eq!(session.handle_payload_transfer(gapped), 0);
    assert!(session.trace_text().contains("gap"), "{}", session.trace_text());
}
