//! Trained zstd dictionaries for planet5 tile bodies ([`COMPRESSION_ZSTD_DICT`]).
//!
//! A `.mamaps` body is small (median under 1 KB at z13) and compressed on its own so it can be
//! fetched by range, which leaves most of zstd's window empty: every body re-learns the same
//! record layouts, kinds and varint patterns. A dictionary trained per zoom band supplies that
//! context once for the whole archive — measured −8.5 % (US West, today's settings) to −13 %
//! (coarser geometry) of tile data over zstd-19 alone.
//!
//! # Wire
//!
//! The section sits between the root index and the leaves; its extent is implied by the header
//! (`root_offset + root_len .. leaf_offset`), so the 128-byte header needs no new field:
//!
//! ```text
//! [magic "MZD1"][u32 count] then count x { [u32 len][len bytes: a zstd dictionary] }
//! ```
//!
//! Each dictionary carries its own id and every frame names the dictionary it was compressed
//! with in its frame header, so the decoder needs no zoom-to-dictionary mapping: it holds all of
//! them and the frame picks. A frame without a dictionary id decodes as plain zstd.
//!
//! The decoder is `ruzstd` (pure Rust) like the plain-zstd path, so the Android cdylib keeps its
//! no-C-toolchain property. One decoder per thread per archive is kept with the dictionaries
//! already loaded — loading builds the entropy tables, which costs far more than a tile.
//!
//! [`COMPRESSION_ZSTD_DICT`]: crate::mamaps::header::COMPRESSION_ZSTD_DICT

use crate::proto::{err, Error, Result};
use std::cell::RefCell;
use std::sync::atomic::{AtomicU64, Ordering};

pub const ZDICT_MAGIC: &[u8; 4] = b"MZD1";
/// A sanity bound on the section (5 dictionaries of 112 KiB is ~560 KB).
pub const ZDICT_MAX_BYTES: u64 = 16 << 20;

static NEXT_UID: AtomicU64 = AtomicU64::new(1);

/// The parsed dictionary section of one archive.
#[derive(Debug)]
pub struct ZDicts {
    raw: Vec<Vec<u8>>,
    uid: u64,
}

impl ZDicts {
    pub fn parse(bytes: &[u8]) -> Result<ZDicts> {
        if bytes.len() < 8 || &bytes[..4] != ZDICT_MAGIC {
            return err("a .mamaps dictionary section has no MZD1 magic");
        }
        let count = u32::from_le_bytes(bytes[4..8].try_into().unwrap()) as usize;
        let mut at = 8usize;
        let mut raw = Vec::with_capacity(count);
        for _ in 0..count {
            if at + 4 > bytes.len() {
                return err("a .mamaps dictionary section ends inside a length");
            }
            let len = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
            at += 4;
            if at + len > bytes.len() {
                return err("a .mamaps dictionary runs past its section");
            }
            // Validate now, so a corrupt dictionary is an open error rather than a tile error.
            ruzstd::decoding::Dictionary::decode_dict(&bytes[at..at + len])
                .map_err(|e| Error(format!("a .mamaps dictionary will not load: {e:?}")))?;
            raw.push(bytes[at..at + len].to_vec());
            at += len;
        }
        if at != bytes.len() {
            return err("a .mamaps dictionary section has trailing bytes");
        }
        Ok(ZDicts { raw, uid: NEXT_UID.fetch_add(1, Ordering::Relaxed) })
    }

    /// The section bytes for `dicts`, the inverse of [`ZDicts::parse`].
    pub fn serialize(dicts: &[Vec<u8>]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(ZDICT_MAGIC);
        out.extend_from_slice(&(dicts.len() as u32).to_le_bytes());
        for d in dicts {
            out.extend_from_slice(&(d.len() as u32).to_le_bytes());
            out.extend_from_slice(d);
        }
        out
    }

    pub fn dictionaries(&self) -> &[Vec<u8>] {
        &self.raw
    }

    /// Decode one zstd frame (with or without a dictionary id) into `out`, which must be exactly
    /// the decoded length. Returns the bytes written.
    pub fn decode_into(&self, frame: &[u8], out: &mut [u8]) -> Result<usize> {
        thread_local! {
            static DECODERS: RefCell<Vec<(u64, ruzstd::decoding::FrameDecoder)>> =
                const { RefCell::new(Vec::new()) };
        }
        DECODERS.with(|cell| {
            let mut decs = cell.borrow_mut();
            let pos = match decs.iter().position(|(u, _)| *u == self.uid) {
                Some(p) => p,
                None => {
                    let mut dec = ruzstd::decoding::FrameDecoder::new();
                    for d in &self.raw {
                        let dict = ruzstd::decoding::Dictionary::decode_dict(d)
                            .map_err(|e| Error(format!("a .mamaps dictionary will not load: {e:?}")))?;
                        dec.add_dict(dict)
                            .map_err(|e| Error(format!("a .mamaps dictionary will not register: {e:?}")))?;
                    }
                    // A thread serving many archives over its life keeps a few decoders, not all.
                    if decs.len() >= 4 {
                        decs.remove(0);
                    }
                    decs.push((self.uid, dec));
                    decs.len() - 1
                }
            };
            let dec = &mut decs[pos].1;
            dec.decode_all(frame, out)
                .map_err(|e| Error(format!("a .mamaps dictionary-zstd body will not decode: {e:?}")))
        })
    }
}
