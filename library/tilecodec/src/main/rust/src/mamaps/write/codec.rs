use crate::mamaps::body;

/// Raw DEFLATE over everything but the body header.
///
/// Public so a generator can run it on a worker thread rather than leaving it on the appending
/// thread — see [`StreamWriter::append_stored`] for why that is the difference between using one
/// core and using all of them.
///
/// The 16-byte body header is left uncompressed ahead of the frame so a reader can read `raw_len`
/// out of it and allocate the output exactly once, before inflating a single byte.
pub fn compress_body(encoded: &[u8]) -> Vec<u8> {
    compress_body_with(&mut crate::gz::Compressor::new(), encoded)
}

/// [`compress_body`], reusing a DEFLATE state the caller owns.
///
/// **This is the one a generator should call.** `miniz_oxide::deflate::compress_to_vec` builds
/// a fresh `CompressorOxide` per call — 65,712 bytes of inline arrays, much of it zeroed on
/// construction — and a tiler calls it once per tile across every core it has. At that point
/// the construction *is* the encode pass: a us-west z13 build compressed 237,040 tiles, so
/// 15.6 GB of allocate-and-zero on 64 threads, and encode measured 226 s against 34 s for the
/// zoom below it at 1.6x the output. Every thread was busy and none of them was compressing.
///
/// [`crate::gz::Compressor`] carries the same story from the gzip side, where it was measured
/// going *backwards* past four threads. This is that fix applied to `.mamaps`.
///
/// Byte-identical to [`compress_body`], because `reset` keeps the parameters and DEFLATE
/// output is a function of the input and the parameters alone.
pub fn compress_body_with(deflate: &mut crate::gz::Compressor, encoded: &[u8]) -> Vec<u8> {
    let header_len = body::BODY_HEADER_LEN;
    let mut out = Vec::with_capacity(encoded.len());
    out.extend_from_slice(&encoded[..header_len]);
    // Deflate straight into `out` after the header, rather than into the compressor's `scratch` and
    // then copying it here. The frame is one allocation with no memcpy of the compressed bytes.
    deflate.deflate_into(&encoded[header_len..], &mut out);
    out
}

/// zstd over everything but the body header (v9 only).
///
/// Same framing as [`compress_body`] — 16-byte header ahead of one independent
/// frame — different codec. Host-side only (`write` feature): the device reads
/// v9 bodies with `ruzstd` and never encodes. Level 19 is zstd's archive
/// default (bytes over build seconds, same trade DEFLATE-9 makes).
/// Deterministic for fixed input + config, which is what keeps builds
/// byte-identical.
#[cfg(feature = "write")]
pub fn compress_body_zstd(encoded: &[u8]) -> Vec<u8> {
    let header_len = body::BODY_HEADER_LEN;
    let mut out = Vec::with_capacity(encoded.len());
    out.extend_from_slice(&encoded[..header_len]);
    let cfg = zstd_rs::CompressionConfig {
        level: 19,
        ..zstd_rs::CompressionConfig::DEFAULT
    };
    let mut enc =
        zstd_rs::Compressor::new(cfg).expect("zstd compressor with a valid level");
    enc.compress(&encoded[header_len..], None, &mut out)
        .expect("zstd encode of an in-memory body");
    out
}

/// [`compress_body_zstd`] at a chosen level, reusing one encoder per thread.
///
/// Planet-size prototype (`mamaps_build --body-zstd LEVEL`). A fresh `zstd_rs::Compressor` per
/// body would allocate its match tables per tile on every core — the same trap
/// [`compress_body_with`] documents for DEFLATE — so each worker keeps one per level. Output is
/// a pure function of input + level (the encoder is reset per frame), so dedup and the content
/// build id behave exactly as with DEFLATE.
#[cfg(feature = "write")]
pub fn compress_body_zstd_level(encoded: &[u8], level: i32) -> Vec<u8> {
    use std::cell::RefCell;
    thread_local! {
        static ENC: RefCell<Option<(i32, zstd_rs::Compressor)>> = const { RefCell::new(None) };
    }
    let header_len = body::BODY_HEADER_LEN;
    let mut out = Vec::with_capacity(encoded.len());
    out.extend_from_slice(&encoded[..header_len]);
    ENC.with(|cell| {
        let mut slot = cell.borrow_mut();
        if slot.as_ref().map(|(l, _)| *l) != Some(level) {
            let cfg = zstd_rs::CompressionConfig { level, ..zstd_rs::CompressionConfig::DEFAULT };
            *slot = Some((level, zstd_rs::Compressor::new(cfg).expect("valid zstd level")));
        }
        let (_, enc) = slot.as_mut().expect("encoder");
        enc.compress(&encoded[header_len..], None, &mut out).expect("zstd encode of an in-memory body");
    });
    out
}

/// FNV-1a. Only ever a bucket key — every hit is confirmed by comparing bytes — so it needs to be
/// fast and well spread, not collision-proof.
///
/// Public so a generator can compute the dedup key in its parallel encode worker and hand it to
/// [`super::writer::StreamWriter::append_stored_with_hash`], keeping the hash off the serial append
/// thread — the same reasoning as [`compress_body_with`] for DEFLATE. It is a pure function of the
/// stored bytes, so the key, and therefore every dedup decision, is unchanged.
pub fn hash64(data: &[u8]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

