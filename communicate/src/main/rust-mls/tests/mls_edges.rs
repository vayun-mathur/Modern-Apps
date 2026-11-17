//! Boundary and malformed-input tests for the communicate MLS crate.
//!
//! Extends `src/tests.rs` without duplicating it: that suite covers identity, snapshot, the
//! happy-path closed loop and garbage framing. These tests cover key-package validation,
//! welcome/message envelope boundaries, member removal, and snapshot/identity parser edges.

use communicate_mls::group;
use communicate_mls::keys;
use communicate_mls::message::{payload_kind, PayloadKind};
use communicate_mls::store::SnapshotProvider;

fn alice_id() -> Result<Vec<u8>, String> {
    Ok(keys::serialize_identity(&keys::generate_identity("+15550001111").map_err(|e| e.to_string())?))
}

fn bob_parts() -> Result<(Vec<u8>, Vec<u8>), String> {
    let bob = keys::generate_identity("+15550002222").map_err(|e| e.to_string())?;
    Ok((SnapshotProvider::fresh().snapshot(), keys::serialize_identity(&bob)))
}

#[test]
fn key_package_rejects_bad_snapshot_and_identity() -> Result<(), String> {
    let (_, bob_id) = bob_parts()?;
    assert!(group::build_key_package(b"not-a-snapshot-at-all!!!!!!!!!!!!!!", &bob_id).is_err());
    assert!(group::build_key_package(&[], b"short").is_err());
    assert!(group::build_key_package(&[], &[]).is_err());
    Ok(())
}

#[test]
fn add_members_rejects_empty_key_packages() -> Result<(), String> {
    let store = SnapshotProvider::fresh().snapshot();
    let alice = alice_id()?;
    let created = group::create_group(&store, &alice).map_err(|e| e.to_string())?;
    assert!(group::add_members(&created.storage, &alice, &created.group_id, &[]).is_err());
    Ok(())
}

#[test]
fn create_group_rejects_empty_identity_and_bad_storage() -> Result<(), String> {
    assert!(group::create_group(&[], &[]).is_err());
    assert!(group::create_group(&SnapshotProvider::fresh().snapshot(), b"junk").is_err());
    Ok(())
}

#[test]
fn welcome_envelope_boundaries_are_rejected() -> Result<(), String> {
    let (store_bob, bob_id) = bob_parts()?;
    let alice = alice_id()?;
    let (store_bob_after, bob_kp) = group::build_key_package(&store_bob, &bob_id).map_err(|e| e.to_string())?;
    let created = group::create_group(&[], &alice).map_err(|e| e.to_string())?;
    let added =
        group::add_members(&created.storage, &alice, &created.group_id, &[bob_kp]).map_err(|e| e.to_string())?;
    assert_eq!(payload_kind(&added.welcome), PayloadKind::Welcome);
    let mut truncated = added.welcome.clone();
    truncated.truncate(added.welcome.len() / 2);
    assert!(group::join_group(&store_bob_after, &truncated).is_err());
    let mut bad_magic = added.welcome.clone();
    if let Some(first) = bad_magic.get_mut(0) {
        *first = b'X';
    }
    assert!(group::join_group(&store_bob_after, &bad_magic).is_err());
    let mut trailing = added.welcome.clone();
    trailing.push(0xFF);
    assert!(group::join_group(&store_bob_after, &trailing).is_err());
    assert!(group::join_group(&store_bob_after, &[]).is_err());
    Ok(())
}

#[test]
fn message_envelope_boundaries_are_rejected() -> Result<(), String> {
    let (store_bob, bob_id) = bob_parts()?;
    let alice = alice_id()?;
    let (store_bob_after, bob_kp) = group::build_key_package(&store_bob, &bob_id).map_err(|e| e.to_string())?;
    let created = group::create_group(&[], &alice).map_err(|e| e.to_string())?;
    let added =
        group::add_members(&created.storage, &alice, &created.group_id, &[bob_kp]).map_err(|e| e.to_string())?;
    let (_, bob_gid) = group::join_group(&store_bob_after, &added.welcome).map_err(|e| e.to_string())?;
    let enc = group::encrypt(&added.storage, &alice, &created.group_id, b"ping").map_err(|e| e.to_string())?;
    assert_eq!(payload_kind(&enc.payload), PayloadKind::Message);
    let mut tampered = enc.payload.clone();
    if let Some(last) = tampered.last_mut() {
        *last ^= 1;
    }
    assert!(group::decrypt(&store_bob_after, &bob_gid, &tampered).is_err());
    let mut bad_magic = enc.payload.clone();
    if let Some(first) = bad_magic.get_mut(0) {
        *first = b'X';
    }
    assert!(group::decrypt(&store_bob_after, &bob_gid, &bad_magic).is_err());
    assert!(group::decrypt(&store_bob_after, &bob_gid, &[]).is_err());
    assert!(group::decrypt(&store_bob_after, b"unknown-group", &enc.payload).is_err());
    Ok(())
}

#[test]
fn removed_member_can_no_longer_decrypt() -> Result<(), String> {
    let (store_bob, bob_id) = bob_parts()?;
    let alice = alice_id()?;
    let (store_bob_after, bob_kp) = group::build_key_package(&store_bob, &bob_id).map_err(|e| e.to_string())?;
    let created = group::create_group(&[], &alice).map_err(|e| e.to_string())?;
    let added =
        group::add_members(&created.storage, &alice, &created.group_id, &[bob_kp]).map_err(|e| e.to_string())?;
    let (store_bob_joined, bob_gid) =
        group::join_group(&store_bob_after, &added.welcome).map_err(|e| e.to_string())?;
    let (storage_after_remove, _commit) =
        group::remove_members(&added.storage, &alice, &created.group_id, &[1]).map_err(|e| e.to_string())?;
    assert!(group::remove_members(&added.storage, &alice, &created.group_id, &[]).is_err());
    let enc = group::encrypt(&storage_after_remove, &alice, &created.group_id, b"after removal")
        .map_err(|e| e.to_string())?;
    assert!(group::decrypt(&store_bob_joined, &bob_gid, &enc.payload).is_err());
    Ok(())
}
