//! Reading the BSSIDs out of the previous-generation `WPSDB1` stores.
//!
//! The v1 stores that `:networklocation` shipped are still published and hold hundreds of
//! millions of real BSSIDs. That makes them by far the best available crawl seed: there is no
//! open bulk WiFi dump, and the alternatives are a local scan (one metro) or guessing
//! addresses for hours.
//!
//! ## Only the keys
//!
//! The coordinates in a v1 store are **deliberately not read**. v1 quantized every position to
//! a 20 m grid and stored no accuracy at all, so the best that could be said of an imported
//! record is "somewhere within ~14 m of here". Carrying that forward would not merely be
//! imprecise, it would be actively harmful: `quality::reduce` keeps whichever observation
//! reports the better accuracy, and Apple typically reports 20-100 m for WiFi, so a v1 record
//! labelled 20 m would **beat most real crawled measurements** and pin the beacon to its old
//! grid cell. The store would end up more precise-looking and less correct.
//!
//! So v1 contributes identities, not positions. Every coordinate in the new store comes from a
//! source that also said how accurate it was.
//!
//! The format, little-endian throughout with LSB-first bit packing:
//!
//! ```text
//! 0  magic "WPSDB1\0\0"
//! 8  coord_bits:u32
//! 12 n:u64
//! 20 n:u64 (repeated), l:u8, universe_bits:u8, high_len_bits:u64
//! 38 high_len:u64, high[high_len]
//!    low_len:u64,  low[low_len]
//!    clen:u64,     coord[clen]        (parsed past, never decoded)
//! ```

use std::io;

/// v1 packed coordinates as a 20-bit latitude and 21-bit longitude index. Only used to
/// recognise the format; the coordinates themselves are never decoded.
const V1_COORD_BITS: u32 = 20 + 21;

/// A decoded v1 store's key set.
pub struct StoreV1 {
    /// Key universe width, 48 for WiFi.
    pub universe_bits: u8,
    /// Keys in ascending order.
    pub keys: Vec<u64>,
}

fn rd_u32(b: &[u8], p: usize) -> io::Result<u32> {
    b.get(p..p + 4)
        .and_then(|s| s.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or_else(|| bad("truncated header"))
}

fn rd_u64(b: &[u8], p: usize) -> io::Result<u64> {
    b.get(p..p + 8)
        .and_then(|s| s.try_into().ok())
        .map(u64::from_le_bytes)
        .ok_or_else(|| bad("truncated header"))
}

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_string())
}

/// Read `nbits` (<= 64) LSB-first from `buf` starting at bit `bit`.
fn bits(buf: &[u8], bit: u64, nbits: u32) -> u64 {
    let mut v = 0u64;
    for k in 0..nbits as u64 {
        let p = bit + k;
        if let Some(&byte) = buf.get((p >> 3) as usize) {
            if (byte >> (p & 7)) & 1 == 1 {
                v |= 1u64 << k;
            }
        }
    }
    v
}

/// Parse a v1 store's key set.
///
/// Walks the Elias-Fano upper bitvector to recover each key, which is the inverse of the
/// reader's `index()` and independent of it — so this does not inherit any misunderstanding
/// that reader might have had. The coordinate section is bounds-checked but never decoded;
/// see the module docs for why.
pub fn parse(buf: &[u8]) -> io::Result<StoreV1> {
    if buf.len() < 46 || &buf[0..8] != b"WPSDB1\x00\x00" {
        return Err(bad("not a WPSDB1 store"));
    }
    let coord_bits = rd_u32(buf, 8)?;
    if coord_bits != V1_COORD_BITS {
        return Err(bad("unexpected coord width; this is not the 20 m grid format"));
    }
    let n = rd_u64(buf, 12)?;
    let l = *buf.get(28).ok_or_else(|| bad("truncated header"))?;
    let universe_bits = *buf.get(29).ok_or_else(|| bad("truncated header"))?;
    let high_len_bits = rd_u64(buf, 30)?;
    let high_len = rd_u64(buf, 38)? as usize;

    let high = buf.get(46..46 + high_len).ok_or_else(|| bad("truncated high bitvector"))?;
    let low_len_off = 46 + high_len;
    let low_len = rd_u64(buf, low_len_off)? as usize;
    let low_off = low_len_off + 8;
    let low = buf.get(low_off..low_off + low_len).ok_or_else(|| bad("truncated low array"))?;
    // Checked so a truncated file is rejected rather than silently yielding a partial key set.
    let clen_off = low_off + low_len;
    let clen = rd_u64(buf, clen_off)? as usize;
    let _ = buf.get(clen_off + 8..clen_off + 8 + clen).ok_or_else(|| bad("truncated coords"))?;

    let mut keys = Vec::with_capacity(n as usize);
    let mut idx = 0u64;
    let mut p = 0u64;
    while p < high_len_bits && idx < n {
        if (high[(p >> 3) as usize] >> (p & 7)) & 1 == 1 {
            let upper = p - idx;
            let lo = if l == 0 { 0 } else { bits(low, idx * l as u64, l as u32) };
            keys.push(if l >= 64 { lo } else { (upper << l) | lo });
            idx += 1;
        }
        p += 1;
    }
    if idx != n {
        return Err(bad("recovered fewer keys than the header declares"));
    }
    Ok(StoreV1 { universe_bits, keys })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a v1 store, so the parser is checked against something other than itself.
    fn build_v1(universe_bits: u8, entries: &[(u64, u64)]) -> Vec<u8> {
        let n = entries.len() as u64;
        // With no keys the bucket space must collapse to one, not to the whole universe: an
        // `l` of 0 over a 48-bit universe asks for 2^48 padding bits. The real writer has the
        // same guard for the same reason.
        let mut l = if n == 0 { universe_bits.min(63) } else { 0 };
        if n > 0 {
            let u = 1u128 << universe_bits;
            while l < 63 && (u >> (l as u32 + 1)) >= n as u128 {
                l += 1;
            }
        }
        let push_bits = |buf: &mut Vec<u8>, bitlen: &mut u64, v: u64, nbits: u32| {
            for k in 0..nbits as u64 {
                if (*bitlen & 7) == 0 {
                    buf.push(0);
                }
                if (v >> k) & 1 == 1 {
                    let p = *bitlen;
                    buf[(p >> 3) as usize] |= 1 << (p & 7);
                }
                *bitlen += 1;
            }
        };

        let (mut high, mut high_bits) = (Vec::new(), 0u64);
        let mut prev_bucket = 0u64;
        for &(key, _) in entries {
            let bucket = key >> l;
            for _ in prev_bucket..bucket {
                push_bits(&mut high, &mut high_bits, 0, 1);
            }
            push_bits(&mut high, &mut high_bits, 1, 1);
            prev_bucket = bucket;
        }
        let buckets = 1u64 << (universe_bits as u32 - l as u32);
        for _ in prev_bucket..buckets {
            push_bits(&mut high, &mut high_bits, 0, 1);
        }

        let (mut low, mut low_bits) = (Vec::new(), 0u64);
        for &(key, _) in entries {
            push_bits(&mut low, &mut low_bits, key & ((1u64 << l) - 1), l as u32);
        }
        let (mut coord, mut coord_bits_len) = (Vec::new(), 0u64);
        for &(_, c) in entries {
            push_bits(&mut coord, &mut coord_bits_len, c, V1_COORD_BITS);
        }

        let mut out = b"WPSDB1\x00\x00".to_vec();
        out.extend_from_slice(&V1_COORD_BITS.to_le_bytes());
        out.extend_from_slice(&n.to_le_bytes());
        out.extend_from_slice(&n.to_le_bytes());
        out.push(l);
        out.push(universe_bits);
        out.extend_from_slice(&high_bits.to_le_bytes());
        out.extend_from_slice(&(high.len() as u64).to_le_bytes());
        out.extend_from_slice(&high);
        out.extend_from_slice(&(low.len() as u64).to_le_bytes());
        out.extend_from_slice(&low);
        out.extend_from_slice(&(coord.len() as u64).to_le_bytes());
        out.extend_from_slice(&coord);
        out
    }

    #[test]
    fn a_v1_store_round_trips() {
        let entries: Vec<(u64, u64)> = (0..500u64)
            .map(|i| (i.wrapping_mul(7_919_401) & 0xFFFF_FFFF_FFFF, (i * 977) & 0x1FF_FFFF_FFFF))
            .collect();
        let mut sorted = entries.clone();
        sorted.sort_unstable_by_key(|e| e.0);
        sorted.dedup_by_key(|e| e.0);

        let bytes = build_v1(48, &sorted);
        let store = parse(&bytes).unwrap();
        assert_eq!(store.universe_bits, 48);
        assert_eq!(store.keys, sorted.iter().map(|e| e.0).collect::<Vec<_>>());
    }

    /// The coordinate section must be present and correctly sized — it is what a truncation
    /// check hangs off — but nothing in the output may depend on its contents.
    #[test]
    fn coordinates_are_never_read() {
        let entries: Vec<(u64, u64)> = (0..100u64).map(|i| (i * 1_000_003, i * 12345)).collect();
        let a = parse(&build_v1(48, &entries)).unwrap();

        // Same keys, completely different coordinates.
        let scrambled: Vec<(u64, u64)> =
            entries.iter().map(|&(k, c)| (k, c ^ 0x1FF_FFFF_FFFF)).collect();
        let b = parse(&build_v1(48, &scrambled)).unwrap();

        assert_eq!(a.keys, b.keys, "the key set must not depend on the coordinates");
    }

    #[test]
    fn a_foreign_file_is_refused() {
        assert!(parse(b"not a store at all............").is_err());
        let mut wrong = b"WPSDB1\x00\x00".to_vec();
        wrong.extend_from_slice(&64u32.to_le_bytes());
        wrong.extend_from_slice(&[0u8; 64]);
        assert!(parse(&wrong).is_err(), "a different coord width must be refused");
    }

    #[test]
    fn an_empty_store_parses_to_nothing() {
        let bytes = build_v1(48, &[]);
        assert!(parse(&bytes).unwrap().keys.is_empty());
    }

    #[test]
    fn truncation_is_detected_rather_than_producing_partial_data() {
        let entries: Vec<(u64, u64)> = (0..50u64).map(|i| (i * 1_000_003, i)).collect();
        let bytes = build_v1(48, &entries);
        for cut in [8, 40, bytes.len() / 2, bytes.len() - 1] {
            assert!(parse(&bytes[..cut]).is_err(), "truncation at {cut} must be caught");
        }
    }
}
