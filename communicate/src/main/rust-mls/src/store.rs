//! Snapshot-able `OpenMLS` provider.
//!
//! `OpenMLS` keeps group state in its storage provider, but this crate must be
//! stateless across JNI calls (Kotlin owns persistence in Room). This module
//! wraps `RustCrypto` + `MemoryStorage` and snapshots the storage map —
//! `MemoryStorage.values` is a pub `RwLock<HashMap<Vec<u8>, Vec<u8>>>` — into
//! an opaque, versioned byte blob Kotlin passes back on every call.
//!
//! Snapshot layout: `version:u8(1) || count:u32be || entries…` where each
//! entry is `k_len:u32be || k || v_len:u32be || v`. Empty input = fresh store.

use openmls_memory_storage::MemoryStorage;
use openmls_rust_crypto::RustCrypto;
use openmls_traits::OpenMlsProvider;
use std::collections::HashMap;
use std::sync::RwLock;

/// Errors from snapshot handling.
#[derive(Debug)]
pub enum StoreError {
    /// Snapshot failed validation (bad version, truncation, overlong entry).
    InvalidSnapshot(&'static str),
}

impl core::fmt::Display for StoreError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            StoreError::InvalidSnapshot(m) => write!(f, "invalid snapshot: {m}"),
        }
    }
}

/// A provider whose storage round-trips through [`snapshot`] / [`restore`].
pub struct SnapshotProvider {
    crypto: RustCrypto,
    store: MemoryStorage,
}

impl SnapshotProvider {
    /// Fresh provider with empty storage.
    pub fn fresh() -> Self {
        Self {
            crypto: RustCrypto::default(),
            store: MemoryStorage::default(),
        }
    }

    /// Restore storage from a Kotlin-persisted [snapshot]; empty = fresh.
    pub fn restore(snapshot: &[u8]) -> Result<Self, StoreError> {
        let map = decode_snapshot(snapshot)?;
        Ok(Self {
            crypto: RustCrypto::default(),
            store: MemoryStorage {
                values: RwLock::new(map),
            },
        })
    }

    /// Snapshot current storage for Kotlin to persist.
    pub fn snapshot(&self) -> Vec<u8> {
        let values = self.store.values.read().unwrap_or_else(|e| e.into_inner());
        encode_snapshot(&values)
    }
}

impl OpenMlsProvider for SnapshotProvider {
    type CryptoProvider = RustCrypto;
    type RandProvider = RustCrypto;
    type StorageProvider = MemoryStorage;

    fn storage(&self) -> &Self::StorageProvider {
        &self.store
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        &self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        &self.crypto
    }
}

const SNAPSHOT_VERSION: u8 = 1;
const MAX_ENTRIES: usize = 4096;
const MAX_ENTRY_BYTES: usize = 1 << 20;

fn encode_snapshot(map: &HashMap<Vec<u8>, Vec<u8>>) -> Vec<u8> {
    let mut out = Vec::new();
    out.push(SNAPSHOT_VERSION);
    let count = map.len().min(MAX_ENTRIES) as u32;
    out.extend_from_slice(&count.to_be_bytes());
    for (k, v) in map.iter().take(MAX_ENTRIES) {
        let k = k.split_at_checked(k.len().min(MAX_ENTRY_BYTES)).map_or(k.as_slice(), |(head, _)| head);
        let v = v.split_at_checked(v.len().min(MAX_ENTRY_BYTES)).map_or(v.as_slice(), |(head, _)| head);
        out.extend_from_slice(&(k.len() as u32).to_be_bytes());
        out.extend_from_slice(k);
        out.extend_from_slice(&(v.len() as u32).to_be_bytes());
        out.extend_from_slice(v);
    }
    out
}

fn decode_snapshot(bytes: &[u8]) -> Result<HashMap<Vec<u8>, Vec<u8>>, StoreError> {
    if bytes.is_empty() {
        return Ok(HashMap::new());
    }
    let mut cur = bytes;
    let version = take_byte(&mut cur).ok_or(StoreError::InvalidSnapshot("short header"))?;
    if version != SNAPSHOT_VERSION {
        return Err(StoreError::InvalidSnapshot("bad version"));
    }
    let count = take_u32(&mut cur).ok_or(StoreError::InvalidSnapshot("short count"))? as usize;
    if count > MAX_ENTRIES {
        return Err(StoreError::InvalidSnapshot("too many entries"));
    }
    let mut map = HashMap::with_capacity(count.min(64));
    for _ in 0..count {
        let k_len =
            take_u32(&mut cur).ok_or(StoreError::InvalidSnapshot("short key"))? as usize;
        if k_len > MAX_ENTRY_BYTES {
            return Err(StoreError::InvalidSnapshot("key too long"));
        }
        let k = take_bytes(&mut cur, k_len).ok_or(StoreError::InvalidSnapshot("short key"))?;
        let v_len =
            take_u32(&mut cur).ok_or(StoreError::InvalidSnapshot("short value"))? as usize;
        if v_len > MAX_ENTRY_BYTES {
            return Err(StoreError::InvalidSnapshot("value too long"));
        }
        let v = take_bytes(&mut cur, v_len).ok_or(StoreError::InvalidSnapshot("short value"))?;
        let _ = map.insert(k, v);
    }
    Ok(map)
}

fn take_byte(cur: &mut &[u8]) -> Option<u8> {
    let (first, rest) = cur.split_first()?;
    *cur = rest;
    Some(*first)
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
