//! Closed-loop MLS groups: create, add/remove, encrypt/decrypt.
//!
//! Every function takes the Kotlin-persisted blobs in and returns updated
//! blobs out — no globals, no callbacks:
//!
//! - `storage`: opaque provider snapshot ([`crate::store`])
//! - `identity`: serialized [`crate::keys::IdentityState`]
//! - groups are addressed by their MLS `GroupId` bytes; Kotlin maps
//!   conversation ⇔ group id.
//!
//! Wire payloads are TLS-serialized `MlsMessageIn/Out` bytes, sent over RCS
//! (SIP MESSAGE / MSRP) as opaque application payloads. Welcome delivery uses
//! a small framed envelope (see [`WELCOME_MAGIC`]) carrying Welcome +
//! ratchet tree so joiners never need a side channel.

use openmls::group::{MlsGroup, MlsGroupCreateConfig, MlsGroupJoinConfig};
use openmls::key_packages::{KeyPackage, KeyPackageIn};
use openmls::prelude::{
    GroupId, KeyPackageBundle, MlsMessageBodyIn, MlsMessageIn, MlsMessageOut,
    ProcessedMessageContent, ProtocolMessage, ProtocolVersion, StagedWelcome,
};
use openmls_traits::OpenMlsProvider as _;
use tls_codec::{Deserialize as _, Serialize as _};

use crate::keys::{self};
use crate::store::SnapshotProvider;

/// Errors from group operations. Variants carry strings only so the JNI
/// boundary stays a flat byte protocol.
#[derive(Debug)]
pub enum GroupError {
    /// Caller input failed validation (bad identity, bad framing, …).
    InvalidInput(&'static str),
    /// Identity bytes failed to parse.
    BadIdentity,
    /// Storage snapshot failed to parse.
    BadSnapshot,
    /// An `OpenMLS` operation failed.
    Mls(String),
}

impl core::fmt::Display for GroupError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            GroupError::InvalidInput(m) => write!(f, "invalid input: {m}"),
            GroupError::BadIdentity => write!(f, "bad identity"),
            GroupError::BadSnapshot => write!(f, "bad snapshot"),
            GroupError::Mls(m) => write!(f, "mls error: {m}"),
        }
    }
}

/// Magic prefix for the Welcome envelope: `b"RCSMLS1W"`.
pub const WELCOME_MAGIC: &[u8; 8] = b"RCSMLS1W";

/// Magic prefix for encrypted application payloads: `b"RCSMLS1M"`.
pub const MESSAGE_MAGIC: &[u8; 8] = b"RCSMLS1M";

/// Result of creating a group: updated storage + group id.
pub struct GroupCreated {
    /// Updated provider snapshot for Kotlin to persist.
    pub storage: Vec<u8>,
    /// MLS group id bytes (conversation ⇔ group mapping key).
    pub group_id: Vec<u8>,
}

/// Result of adding members: updated storage + commit + welcome envelope.
pub struct MembersAdded {
    /// Updated provider snapshot for Kotlin to persist.
    pub storage: Vec<u8>,
    /// TLS-serialized commit (`MlsMessageOut`) to send the group.
    pub commit: Vec<u8>,
    /// [`WELCOME_MAGIC`]-framed Welcome + ratchet tree for the joiners.
    pub welcome: Vec<u8>,
}

/// Result of encrypting: updated storage + framed payload.
pub struct Encrypted {
    /// Updated provider snapshot for Kotlin to persist.
    pub storage: Vec<u8>,
    /// [`MESSAGE_MAGIC`]-framed TLS `MlsMessageOut`.
    pub payload: Vec<u8>,
}

/// A decrypted inbound message.
pub struct Decrypted {
    /// Updated provider snapshot for Kotlin to persist.
    pub storage: Vec<u8>,
    /// Plaintext application bytes. Empty for non-application content.
    pub plaintext: Vec<u8>,
    /// True when the payload was an application message (vs commit/proposal).
    pub is_application: bool,
}

/// Build a fresh key package for [identity] (publish to peers so they can add
/// us). Returns `(storage, key_package_tls)` where the TLS bytes are the raw
/// `KeyPackage` encoding (not wrapped in an MLS message).
pub fn build_key_package(
    storage_bytes: &[u8],
    identity_bytes: &[u8],
) -> Result<(Vec<u8>, Vec<u8>), GroupError> {
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let identity = keys::parse_identity(identity_bytes).map_err(|_| GroupError::BadIdentity)?;
    let (credential_with_key, signer) =
        keys::credential_and_signer(&identity).map_err(|_| GroupError::BadIdentity)?;
    let bundle: KeyPackageBundle = KeyPackage::builder()
        .build(
            keys::ciphersuite(),
            &provider,
            &signer,
            credential_with_key,
        )
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let tls = bundle
        .key_package()
        .tls_serialize_detached()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    Ok((provider.snapshot(), tls))
}

/// Create a group with us as the only member. Returns [`GroupCreated`].
pub fn create_group(
    storage_bytes: &[u8],
    identity_bytes: &[u8],
) -> Result<GroupCreated, GroupError> {
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let identity = keys::parse_identity(identity_bytes).map_err(|_| GroupError::BadIdentity)?;
    let (credential_with_key, signer) =
        keys::credential_and_signer(&identity).map_err(|_| GroupError::BadIdentity)?;
    let config = MlsGroupCreateConfig::builder()
        .ciphersuite(keys::ciphersuite())
        .build();
    let group = MlsGroup::new(
        &provider,
        &signer,
        &config,
        credential_with_key,
    )
    .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    Ok(GroupCreated {
        storage: provider.snapshot(),
        group_id: group.group_id().as_slice().to_vec(),
    })
}

/// Add [`key_packages_tls`] (each a TLS `KeyPackage`) to [`group_id`].
/// Merges our own pending commit so subsequent encrypts work; returns the
/// commit for the group plus the framed Welcome for the joiners.
pub fn add_members(
    storage_bytes: &[u8],
    identity_bytes: &[u8],
    group_id: &[u8],
    key_packages_tls: &[Vec<u8>],
) -> Result<MembersAdded, GroupError> {
    if key_packages_tls.is_empty() {
        return Err(GroupError::InvalidInput("no key packages"));
    }
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let identity = keys::parse_identity(identity_bytes).map_err(|_| GroupError::BadIdentity)?;
    let (_, signer) =
        keys::credential_and_signer(&identity).map_err(|_| GroupError::BadIdentity)?;
    let mut group = load_group(&provider, group_id)?;
    let mut key_packages = Vec::with_capacity(key_packages_tls.len());
    for tls in key_packages_tls {
        let kp_in = KeyPackageIn::tls_deserialize(&mut tls.as_slice())
            .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
        let kp = kp_in
            .validate(provider.crypto(), ProtocolVersion::Mls10)
            .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
        key_packages.push(kp);
    }
    let (commit_out, welcome_out, group_info) = group
        .add_members(&provider, &signer, &key_packages)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    group
        .merge_pending_commit(&provider)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let commit = commit_out
        .tls_serialize_detached()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let welcome_msg = welcome_out
        .tls_serialize_detached()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let tree_tls = group
        .export_ratchet_tree()
        .tls_serialize_detached()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let _ = group_info;
    let mut welcome = Vec::with_capacity(8 + 4 + welcome_msg.len() + 4 + tree_tls.len());
    welcome.extend_from_slice(WELCOME_MAGIC);
    push_u32(&mut welcome, welcome_msg.len());
    welcome.extend_from_slice(&welcome_msg);
    push_u32(&mut welcome, tree_tls.len());
    welcome.extend_from_slice(&tree_tls);
    Ok(MembersAdded {
        storage: provider.snapshot(),
        commit,
        welcome,
    })
}

/// Join a group from a [`WELCOME_MAGIC`]-framed envelope. Returns the updated
/// storage (the group is persisted inside it under its group id).
pub fn join_group(
    storage_bytes: &[u8],
    welcome_framed: &[u8],
) -> Result<(Vec<u8>, Vec<u8>), GroupError> {
    let (welcome_tls, tree_tls) = split_welcome(welcome_framed)?;
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let welcome_msg = MlsMessageIn::tls_deserialize(&mut welcome_tls.as_slice())
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let welcome = match welcome_msg.extract() {
        MlsMessageBodyIn::Welcome(w) => w,
        _ => return Err(GroupError::InvalidInput("not a welcome")),
    };
    let tree = openmls::treesync::RatchetTreeIn::tls_deserialize(&mut tree_tls.as_slice())
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let config = MlsGroupJoinConfig::builder().build();
    let staged = StagedWelcome::new_from_welcome(&provider, &config, welcome, Some(tree))
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let group = staged
        .into_group(&provider)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let group_id = group.group_id().as_slice().to_vec();
    Ok((provider.snapshot(), group_id))
}

/// Remove members at [`removed_indices`] (leaf indices) from [`group_id`].
/// Returns `(storage, commit_tls)`; the commit goes to the group.
pub fn remove_members(
    storage_bytes: &[u8],
    identity_bytes: &[u8],
    group_id: &[u8],
    removed_indices: &[u32],
) -> Result<(Vec<u8>, Vec<u8>), GroupError> {
    if removed_indices.is_empty() {
        return Err(GroupError::InvalidInput("no members"));
    }
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let identity = keys::parse_identity(identity_bytes).map_err(|_| GroupError::BadIdentity)?;
    let (_, signer) =
        keys::credential_and_signer(&identity).map_err(|_| GroupError::BadIdentity)?;
    let mut group = load_group(&provider, group_id)?;
    let indices: Vec<openmls::prelude::LeafNodeIndex> = removed_indices
        .iter()
        .map(|i| openmls::prelude::LeafNodeIndex::new(*i))
        .collect();
    let (commit_out, _, _) = group
        .remove_members(&provider, &signer, &indices)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    group
        .merge_pending_commit(&provider)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let commit = commit_out
        .tls_serialize_detached()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    Ok((provider.snapshot(), commit))
}

/// Encrypt [plaintext] for [`group_id`]. Returns [`Encrypted`] (framed payload).
pub fn encrypt(
    storage_bytes: &[u8],
    identity_bytes: &[u8],
    group_id: &[u8],
    plaintext: &[u8],
) -> Result<Encrypted, GroupError> {
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let identity = keys::parse_identity(identity_bytes).map_err(|_| GroupError::BadIdentity)?;
    let (_, signer) =
        keys::credential_and_signer(&identity).map_err(|_| GroupError::BadIdentity)?;
    let mut group = load_group(&provider, group_id)?;
    let out: MlsMessageOut = group
        .create_message(&provider, &signer, plaintext)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let tls = out
        .tls_serialize_detached()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let mut payload = Vec::with_capacity(8 + tls.len());
    payload.extend_from_slice(MESSAGE_MAGIC);
    payload.extend_from_slice(&tls);
    Ok(Encrypted {
        storage: provider.snapshot(),
        payload,
    })
}

/// Decrypt a [`MESSAGE_MAGIC`]-framed payload for [`group_id`].
///
/// Commits and proposals are merged into group state automatically
/// (closed-loop peers are trusted); application messages yield plaintext.
pub fn decrypt(
    storage_bytes: &[u8],
    group_id: &[u8],
    payload_framed: &[u8],
) -> Result<Decrypted, GroupError> {
    let tls = strip_message(payload_framed)?;
    let provider = SnapshotProvider::restore(storage_bytes).map_err(|_| GroupError::BadSnapshot)?;
    let mut group = load_group(&provider, group_id)?;
    let inbound = MlsMessageIn::tls_deserialize(&mut tls.as_slice())
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let protocol: ProtocolMessage = inbound
        .try_into()
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let processed = group
        .process_message(&provider, protocol)
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
    let (plaintext, is_application) = match processed.into_content() {
        ProcessedMessageContent::ApplicationMessage(app) => (app.into_bytes(), true),
        ProcessedMessageContent::ProposalMessage(_) => {
            // Standalone proposal: the committer sweeps it; nothing to show.
            (Vec::new(), false)
        }
        ProcessedMessageContent::ExternalJoinProposalMessage(_) => (Vec::new(), false),
        ProcessedMessageContent::StagedCommitMessage(staged) => {
            group
                .merge_staged_commit(&provider, *staged)
                .map_err(|e| GroupError::Mls(format!("{e:?}")))?;
            (Vec::new(), false)
        }
        _ => (Vec::new(), false),
    };
    Ok(Decrypted {
        storage: provider.snapshot(),
        plaintext,
        is_application,
    })
}

fn load_group(
    provider: &SnapshotProvider,
    group_id: &[u8],
) -> Result<MlsGroup, GroupError> {
    if group_id.is_empty() {
        return Err(GroupError::InvalidInput("empty group id"));
    }
    MlsGroup::load(provider.storage(), &GroupId::from_slice(group_id))
        .map_err(|e| GroupError::Mls(format!("{e:?}")))?
        .ok_or(GroupError::InvalidInput("unknown group"))
}

fn split_welcome(framed: &[u8]) -> Result<(Vec<u8>, Vec<u8>), GroupError> {
    if framed.get(..8) != Some(WELCOME_MAGIC.as_slice()) {
        return Err(GroupError::InvalidInput("bad welcome magic"));
    }
    let Some(mut cur) = framed.get(8..) else {
        return Err(GroupError::InvalidInput("bad welcome magic"));
    };
    let w_len = take_u32(&mut cur).ok_or(GroupError::InvalidInput("short welcome"))? as usize;
    let welcome = take_bytes(&mut cur, w_len).ok_or(GroupError::InvalidInput("short welcome"))?;
    let t_len = take_u32(&mut cur).ok_or(GroupError::InvalidInput("short tree"))? as usize;
    let tree = take_bytes(&mut cur, t_len).ok_or(GroupError::InvalidInput("short tree"))?;
    if !cur.is_empty() {
        return Err(GroupError::InvalidInput("trailing bytes"));
    }
    Ok((welcome, tree))
}

fn strip_message(framed: &[u8]) -> Result<Vec<u8>, GroupError> {
    if framed.get(..8) != Some(MESSAGE_MAGIC.as_slice()) {
        return Err(GroupError::InvalidInput("bad message magic"));
    }
    framed
        .get(8..)
        .map(<[u8]>::to_vec)
        .ok_or(GroupError::InvalidInput("bad message magic"))
}

fn push_u32(out: &mut Vec<u8>, v: usize) {
    out.extend_from_slice(&(v.min(u32::MAX as usize) as u32).to_be_bytes());
}

fn take_u32(cur: &mut &[u8]) -> Option<u32> {
    let (head, rest) = cur.split_at_checked(4)?;
    *cur = rest;
    let bytes: [u8; 4] = head.try_into().ok()?;
    Some(u32::from_be_bytes(bytes))
}

fn take_bytes(cur: &mut &[u8], len: usize) -> Option<Vec<u8>> {
    let (head, rest) = cur.split_at_checked(len)?;
    *cur = rest;
    Some(head.to_vec())
}

/// Re-exported for the JNI layer.
pub use crate::keys::credential_and_signer;
