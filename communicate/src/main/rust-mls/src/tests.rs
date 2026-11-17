//! Closed-loop MLS round-trip tests (creator ↔ joiner, no network).
//!
//! Exercises the exact JNI-adjacent paths: identity → key package → create →
//! add → welcome/join → encrypt → decrypt, plus snapshot restore between
//! every step (mirroring Kotlin persisting blobs in Room).

use crate::group;
use crate::keys;
use crate::message::{payload_kind, PayloadKind};
use crate::store::SnapshotProvider;

fn alice() -> Result<keys::IdentityState, String> {
    keys::generate_identity("+15550001111").map_err(|e| e.to_string())
}

fn bob() -> Result<keys::IdentityState, String> {
    keys::generate_identity("+15550002222").map_err(|e| e.to_string())
}

#[test]
fn identity_round_trip() -> Result<(), String> {
    let state = alice()?;
    let bytes = keys::serialize_identity(&state);
    let back = keys::parse_identity(&bytes).map_err(|e| e.to_string())?;
    assert_eq!(back.identity, "+15550001111");
    assert_eq!(back.sign_private, state.sign_private);
    assert_eq!(back.sign_public, state.sign_public);
    assert!(keys::parse_identity(&[]).is_err());
    assert!(keys::parse_identity(&[9u8; 16]).is_err());
    assert!(keys::generate_identity("  ").is_err());
    Ok(())
}

#[test]
fn snapshot_round_trip() -> Result<(), String> {
    let provider = SnapshotProvider::fresh();
    let snap = provider.snapshot();
    let back = SnapshotProvider::restore(&snap).map_err(|e| e.to_string())?;
    assert_eq!(back.snapshot(), snap);
    assert!(SnapshotProvider::restore(&[9u8; 8]).is_err());
    Ok(())
}

#[test]
fn closed_loop_text() -> Result<(), String> {
    let alice = alice()?;
    let bob = bob()?;
    let alice_id = keys::serialize_identity(&alice);
    let bob_id = keys::serialize_identity(&bob);

    // Bob publishes a key package (storage evolves; Kotlin would persist it).
    let (store_bob, bob_kp) =
        group::build_key_package(&[], &bob_id).map_err(|e| e.to_string())?;

    // Alice creates a group and adds Bob.
    let created = group::create_group(&[], &alice_id).map_err(|e| e.to_string())?;
    let added = group::add_members(&created.storage, &alice_id, &created.group_id, &[bob_kp])
        .map_err(|e| e.to_string())?;
    assert!(!added.commit.is_empty());
    assert_eq!(payload_kind(&added.welcome), PayloadKind::Welcome);

    // Bob joins from the Welcome envelope (fresh provider + his storage).
    let (store_bob2, bob_gid) =
        group::join_group(&store_bob, &added.welcome).map_err(|e| e.to_string())?;
    assert_eq!(bob_gid, created.group_id);

    // Alice encrypts; Bob decrypts.
    let enc = group::encrypt(&added.storage, &alice_id, &created.group_id, b"hello mls")
        .map_err(|e| e.to_string())?;
    assert_eq!(payload_kind(&enc.payload), PayloadKind::Message);
    let dec =
        group::decrypt(&store_bob2, &bob_gid, &enc.payload).map_err(|e| e.to_string())?;
    assert!(dec.is_application);
    assert_eq!(dec.plaintext, b"hello mls");

    // Bob replies; Alice decrypts (storage chained through snapshots).
    let enc2 = group::encrypt(&dec.storage, &bob_id, &bob_gid, b"hello back")
        .map_err(|e| e.to_string())?;
    let dec2 = group::decrypt(&enc.storage, &created.group_id, &enc2.payload)
        .map_err(|e| e.to_string())?;
    assert!(dec2.is_application);
    assert_eq!(dec2.plaintext, b"hello back");
    Ok(())
}

#[test]
fn framing_rejects_garbage() -> Result<(), String> {
    assert_eq!(payload_kind(&[]), PayloadKind::Unknown);
    assert_eq!(payload_kind(b"nope-nope-nope!"), PayloadKind::Unknown);
    let store = SnapshotProvider::fresh().snapshot();
    let alice_id = keys::serialize_identity(&alice()?);
    let created = group::create_group(&store, &alice_id).map_err(|e| e.to_string())?;
    assert!(group::decrypt(&store, &created.group_id, b"garbage!").is_err());
    assert!(group::join_group(&store, b"garbage!!").is_err());
    Ok(())
}
