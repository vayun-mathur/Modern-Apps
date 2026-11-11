use std::fs::File;
use std::path::Path;

use crate::pbf::{BlobLoc, PrimitiveBlock, inflate_blob, read_exact_at};
use crate::proto::{self, Error, Reader, Result, WIRE_BYTES};

/// `BlobHeader { required string type = 1; required int32 datasize = 3; }`
pub(crate) fn decode_blob_header(buf: &[u8]) -> Result<(&[u8], u32)> {
    let mut r = Reader::new(buf);
    let mut kind: &[u8] = b"";
    let mut datasize: Option<u32> = None;
    while let Some((field, wire)) = r.next_field()? {
        match (field, wire) {
            (1, WIRE_BYTES) => kind = r.bytes()?,
            (3, proto::WIRE_VARINT) => datasize = Some(r.uvarint()? as u32),
            _ => r.skip(wire)?,
        }
    }
    match datasize {
        Some(d) => Ok((kind, d)),
        None => proto::err("BlobHeader without datasize"),
    }
}

/// Inflate and decode the first data blob, so a file we cannot read fails in
/// seconds instead of at the end of a multi-hour pass.
///
/// Worth its own step because the compression is a property of the whole file:
/// a planet mirror published with zstd blobs is unreadable here, and finding
/// that out after an 80 GB download and one full pass is the expensive way to
/// learn it.
pub fn probe_compression(path: &Path, blobs: &[BlobLoc]) -> Result<()> {
    let Some(first) = blobs.first() else {
        return Ok(());
    };
    let mut file =
        File::open(path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
    let mut compressed = Vec::new();
    let mut inflated = Vec::new();
    read_exact_at(&mut file, first, &mut compressed)?;
    inflate_blob(&compressed, &mut inflated)?;
    PrimitiveBlock::decode(&inflated)?;
    Ok(())
}

/// The file name a full pass's blob-kinds mask is cached under, beside a tool's output.
pub const BLOB_KINDS_FILE: &str = "blob_kinds.bin";

/// Magic + version for the blob-kinds sidecar. Bumped if the header or payload layout changes, so an
/// old sidecar is rejected rather than misread. `MBK2` replaced `MBK1`'s source mtime with a content
/// fingerprint, so the graph directory is byte-identical wherever and whenever the `.pbf` lives.
const BLOB_KINDS_MAGIC: &[u8; 4] = b"MBK2";

/// Header bytes before the per-blob kind payload: magic, blob count, source length, fingerprint.
const BLOB_KINDS_HEAD: usize = 4 + 8 + 8 + 8;

/// What guards a blob-kinds sidecar against a different `.pbf`: the file's content fingerprint
/// (length, first and last 4 MiB -- [`crate::fingerprint::file_fingerprint`]) mixed with the blob
/// index pass 1 already holds (every blob's offset and size), so an edit anywhere that resizes a
/// blob changes it. Never a path or a timestamp: a copy of the same file elsewhere, or touched,
/// still matches, and the sidecar's bytes depend only on the input's bytes.
fn blob_kinds_guard(input: &Path, blobs: &[BlobLoc]) -> Result<u64> {
    let mut h = crate::fingerprint::Fnv::default();
    h.eat(b"blob-kinds/2");
    h.eat_u64(
        crate::fingerprint::file_fingerprint(input)
            .map_err(|e| Error(format!("cannot read {}: {e}", input.display())))?,
    );
    h.eat_u64(blobs.len() as u64);
    for b in blobs {
        h.eat_u64(b.offset);
        h.eat_u64(b.datasize as u64);
    }
    Ok(h.finish())
}

/// Persist a complete per-blob kind mask beside a tool's output, guarded against the source `.pbf`
/// it describes.
///
/// A full pass already learns which blobs hold nodes, ways or relations; two tools run back to back
/// over the same file (`road_graph` then `mamaps_build`) both want that mask, and the second would
/// otherwise pay a full scan to recompute it. Writing it here lets the second load it instead. The
/// guard -- blob count, file length and a content fingerprint ([`blob_kinds_guard`]) -- is what
/// makes a stale sidecar a fall-back rather than a wrong answer; see [`load_blob_kinds`].
pub fn write_blob_kinds(dir: &Path, input: &Path, blobs: &[BlobLoc], kinds: &[u8]) -> Result<()> {
    let len = std::fs::metadata(input)
        .map_err(|e| Error(format!("cannot stat {}: {e}", input.display())))?
        .len();
    let guard = blob_kinds_guard(input, blobs)?;
    let mut out = Vec::with_capacity(BLOB_KINDS_HEAD + kinds.len());
    out.extend_from_slice(BLOB_KINDS_MAGIC);
    out.extend_from_slice(&(kinds.len() as u64).to_le_bytes());
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(&guard.to_le_bytes());
    out.extend_from_slice(kinds);
    let path = dir.join(BLOB_KINDS_FILE);
    std::fs::write(&path, &out)
        .map_err(|e| Error(format!("cannot write {}: {e}", path.display())))
}

/// Load a blob-kinds mask written by [`write_blob_kinds`], but only if it still describes `input`.
///
/// `None` on any mismatch or error -- a missing file, wrong magic (an `MBK1` sidecar included), a
/// different blob count, a changed length or fingerprint, a short read -- so the caller falls back
/// to a full scan. `blobs` supplies the count the mask must match; nothing here trusts the file's
/// own count past that check. The returned mask is byte-for-byte what a full pass over `input`
/// would produce, so feeding it to a later pass changes no output.
pub fn load_blob_kinds(dir: &Path, input: &Path, blobs: &[BlobLoc]) -> Option<Vec<u8>> {
    let raw = std::fs::read(dir.join(BLOB_KINDS_FILE)).ok()?;
    if raw.len() < BLOB_KINDS_HEAD || &raw[0..4] != BLOB_KINDS_MAGIC {
        return None;
    }
    let count = u64::from_le_bytes(raw[4..12].try_into().ok()?);
    let file_len = u64::from_le_bytes(raw[12..20].try_into().ok()?);
    let guard = u64::from_le_bytes(raw[20..28].try_into().ok()?);
    // The count must match the file being read now, and the payload must be exactly that many bytes:
    // a truncated or padded sidecar is as untrustworthy as a stale one.
    if count as usize != blobs.len() || raw.len() != BLOB_KINDS_HEAD + blobs.len() {
        return None;
    }
    if std::fs::metadata(input).ok()?.len() != file_len
        || blob_kinds_guard(input, blobs).ok()? != guard
    {
        return None;
    }
    Some(raw[BLOB_KINDS_HEAD..].to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pbf::{CHUNK_BLOBS, KIND_NODES, KIND_RELATIONS, KIND_WAYS, run_pass, run_pass_sink, scan_blobs};
    use crate::testpbf;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// A file with enough `OSMData` blobs to span several chunks, so the reorder
    /// buffer and its window are actually reached.
    fn multi_chunk_pbf(chunks: usize) -> (std::path::PathBuf, std::path::PathBuf) {
        let block = testpbf::primitive_block();
        let blobs = chunks * CHUNK_BLOBS;
        let refs: Vec<&[u8]> = (0..blobs).map(|_| block.as_slice()).collect();
        let bytes = testpbf::pbf_from_blocks(refs);
        let dir = std::env::temp_dir().join(format!(
            "osm_ingest_window_{}_{chunks}_{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("many.osm.pbf");
        std::fs::write(&path, &bytes).unwrap();
        (path, dir)
    }

    /// The reorder buffer must not grow with the file.
    ///
    /// A worker deposits a finished chunk and immediately claims another, so without a
    /// window one slow chunk 0 parks every later chunk's accumulator at once — and on a
    /// planet pass those accumulators are the largest things in the build. Chunk 0 is
    /// made slow here on purpose; the counter is what proves the bound, because the
    /// pass would produce correct output either way.
    #[test]
    fn the_reorder_buffer_stays_bounded_when_the_first_chunk_lags() {
        let (path, _dir) = multi_chunk_pbf(40);
        crate::par::set_threads(4);
        let blobs = scan_blobs(&path).unwrap();
        let n_chunks = blobs.len().div_ceil(CHUNK_BLOBS);
        assert!(n_chunks >= 40, "{n_chunks} chunk(s) is not enough to bound anything");

        // Accumulators alive right now, and the worst it ever got.
        let live = std::sync::Arc::new(AtomicUsize::new(0));
        let peak = std::sync::Arc::new(AtomicUsize::new(0));

        struct Acc {
            live: std::sync::Arc<AtomicUsize>,
        }
        impl Drop for Acc {
            fn drop(&mut self) {
                self.live.fetch_sub(1, Ordering::SeqCst);
            }
        }

        let first_seen = AtomicUsize::new(0);
        let (mk_live, mk_peak) = (live.clone(), peak.clone());
        let drained = std::sync::Mutex::new(Vec::new());
        run_pass_sink(
            &path,
            &blobs,
            None,
            KIND_NODES,
            "window",
            || {
                let n = mk_live.fetch_add(1, Ordering::SeqCst) + 1;
                mk_peak.fetch_max(n, Ordering::SeqCst);
                Acc { live: mk_live.clone() }
            },
            |_acc, _block| {
                // Only the very first chunk to start is slowed, which is enough to make
                // every other worker run ahead of the sink.
                if first_seen.fetch_add(1, Ordering::SeqCst) == 0 {
                    std::thread::sleep(std::time::Duration::from_millis(300));
                }
                Ok(KIND_NODES)
            },
            |acc: Acc| {
                drained.lock().unwrap().push(());
                drop(acc);
                Ok(())
            },
        )
        .unwrap();

        assert_eq!(drained.into_inner().unwrap().len(), n_chunks, "every chunk drains");
        assert_eq!(live.load(Ordering::SeqCst), 0, "an accumulator leaked");
        // 4 threads => a window of 8, plus the one each worker is filling. Well under
        // the ~40 that would pile up if the workers were free to run to the end of the
        // file, which is what makes this assertion mean something.
        let bound = 8 + 4;
        let got = peak.load(Ordering::SeqCst);
        assert!(
            got <= bound,
            "{got} accumulators were live at once over {n_chunks} chunks; \
             the window should have held it to {bound}"
        );
        crate::par::clear_threads();
    }

    #[test]
    fn round_trips_a_synthetic_file() {
        let (path, _dir) = testpbf::write_sample("pbf_pass");

        let blobs = scan_blobs(&path).unwrap();
        assert_eq!(blobs.len(), 1, "the OSMHeader blob is not returned");

        let (states, kinds) = run_pass(
            &path,
            &blobs,
            None,
            KIND_NODES | KIND_WAYS | KIND_RELATIONS,
            "test",
            || (0usize, 0usize, 0usize),
            |s: &mut (usize, usize, usize), block| {
                let mut mask = 0;
                crate::osm::visit_block(
                    block,
                    KIND_NODES | KIND_WAYS | KIND_RELATIONS,
                    &mut mask,
                    &mut |el: crate::osm::Element| {
                        match el {
                            crate::osm::Element::Node(_) => s.0 += 1,
                            crate::osm::Element::Way(_) => s.1 += 1,
                            crate::osm::Element::Relation(_) => s.2 += 1,
                        }
                        Ok(())
                    },
                )?;
                Ok(mask)
            },
        )
        .unwrap();
        let totals = states
            .iter()
            .fold((0, 0, 0), |a, b| (a.0 + b.0, a.1 + b.1, a.2 + b.2));
        assert_eq!(totals, (testpbf::NODE_COUNT, 3, 1));
        assert_eq!(kinds[0], KIND_NODES | KIND_WAYS | KIND_RELATIONS);
    }

    #[test]
    fn blob_kind_filter_skips_whole_blobs() {
        let (path, _dir) = testpbf::write_sample("pbf_filter");
        let blobs = scan_blobs(&path).unwrap();
        // Pretend the only blob holds nothing but nodes; a ways pass must then
        // never even read it.
        let (states, _) = run_pass(
            &path,
            &blobs,
            Some(&[KIND_NODES]),
            KIND_WAYS,
            "test",
            || 0usize,
            |s: &mut usize, _block| {
                *s += 1;
                Ok(0)
            },
        )
        .unwrap();
        assert_eq!(states.iter().sum::<usize>(), 0);
    }

    #[test]
    fn a_skipped_blob_keeps_its_kinds_in_the_returned_mask() {
        let (path, _dir) = testpbf::write_sample("pbf_carry");
        let blobs = scan_blobs(&path).unwrap();
        let all = KIND_NODES | KIND_WAYS | KIND_RELATIONS;
        // A relations-only pass skips this nodes-and-ways-and-relations blob only
        // if we lie about its contents; do that, and check the lie comes back
        // rather than a zero. A pass that dropped the kinds would make its own
        // returned mask useless to the pass after it.
        let (_, kinds) = run_pass(
            &path,
            &blobs,
            Some(&[KIND_NODES]),
            KIND_WAYS,
            "test",
            || (),
            |_: &mut (), _| Ok(all),
        )
        .unwrap();
        assert_eq!(kinds, vec![KIND_NODES]);

        // A blob the pass does read reports what it actually saw.
        let (_, kinds) = run_pass(
            &path,
            &blobs,
            Some(&[KIND_WAYS]),
            KIND_WAYS,
            "test",
            || (),
            |_: &mut (), _| Ok(all),
        )
        .unwrap();
        assert_eq!(kinds, vec![all]);
    }

    #[test]
    fn the_compression_probe_accepts_zlib_and_rejects_zstd() {
        let (path, dir) = testpbf::write_sample("pbf_probe");
        let blobs = scan_blobs(&path).unwrap();
        probe_compression(&path, &blobs).unwrap();

        // One OSMData blob whose only field is `zstd_data`.
        let blob = [7 << 3 | WIRE_BYTES, 1, 0];
        let mut header = vec![1 << 3 | WIRE_BYTES, 7];
        header.extend_from_slice(b"OSMData");
        header.extend_from_slice(&[3 << 3 | proto::WIRE_VARINT, blob.len() as u8]);
        let mut file = Vec::new();
        file.extend_from_slice(&(header.len() as u32).to_be_bytes());
        file.extend_from_slice(&header);
        file.extend_from_slice(&blob);
        let zstd_path = dir.join("zstd.osm.pbf");
        std::fs::write(&zstd_path, &file).unwrap();

        let blobs = scan_blobs(&zstd_path).unwrap();
        assert_eq!(blobs.len(), 1);
        let err = probe_compression(&zstd_path, &blobs).unwrap_err();
        assert!(err.0.contains("zstd"), "{}", err.0);
    }

    /// Unsigned varint, for the two length-delimited fields the helpers below write. The
    /// crate's `proto` has the decoder but no encoder — nothing in `osm_ingest` writes
    /// protobuf outside tests.
    fn put_uvarint(out: &mut Vec<u8>, mut value: u64) {
        loop {
            let byte = (value & 0x7f) as u8;
            value >>= 7;
            if value == 0 {
                out.push(byte);
                return;
            }
            out.push(byte | 0x80);
        }
    }

    /// A zlib stream wrapping `data` in a single STORED deflate block.
    ///
    /// Hand-rolled rather than compressed, because `osm_ingest` takes miniz_oxide for its
    /// inflate side only and a test should not pull in a compressor to make three bytes.
    /// A stored block still exercises the whole path this cares about: the zlib header is
    /// parsed and the Adler-32 trailer is verified exactly as it is for a real blob.
    fn zlib_stored(data: &[u8]) -> Vec<u8> {
        let mut out = vec![0x78, 0x01];
        let len = data.len() as u16;
        out.push(0x01); // BFINAL = 1, BTYPE = 00 (stored)
        out.extend_from_slice(&len.to_le_bytes());
        out.extend_from_slice(&(!len).to_le_bytes());
        out.extend_from_slice(data);
        let (mut a, mut b) = (1u32, 0u32);
        for &byte in data {
            a = (a + byte as u32) % 65521;
            b = (b + a) % 65521;
        }
        out.extend_from_slice(&((b << 16) | a).to_be_bytes());
        out
    }

    /// A `Blob` message carrying `zlib_data` and a `raw_size`.
    fn zlib_blob(data: &[u8], raw_size: usize) -> Vec<u8> {
        let stream = zlib_stored(data);
        let mut blob = Vec::new();
        blob.push(2 << 3 | proto::WIRE_VARINT);
        put_uvarint(&mut blob, raw_size as u64);
        blob.push(3 << 3 | WIRE_BYTES);
        put_uvarint(&mut blob, stream.len() as u64);
        blob.extend_from_slice(&stream);
        blob
    }

    /// **The allocation bound on a parallel pass.** `run_pass_sink` gives each worker one
    /// inflate buffer for the whole file, so `inflate_blob` must decompress *into* it.
    ///
    /// Assigning a fresh `Vec` over `out` instead measured 1.5 GB of a 2.19 GB California
    /// peak — 64 workers each holding a blob buffer, briefly two, none of them reused —
    /// and that peak was the whole build's, one second in. Nothing about the output bytes
    /// changes either way, so only a capacity assertion can catch a regression.
    #[test]
    fn inflating_reuses_the_callers_buffer_rather_than_replacing_it() {
        let big = vec![7u8; 40_000];
        let small = b"tiny".to_vec();
        let mut out = Vec::new();

        inflate_blob(&zlib_blob(&big, big.len()), &mut out).expect("big");
        assert_eq!(out, big, "the big blob must round trip");
        let capacity = out.capacity();
        assert!(capacity >= big.len(), "buffer must hold what it inflated");

        inflate_blob(&zlib_blob(&small, small.len()), &mut out).expect("small");
        assert_eq!(out, small, "the small blob must round trip");
        assert_eq!(
            out.capacity(),
            capacity,
            "a second, smaller blob must reuse the buffer rather than allocate a new one",
        );
    }

    /// The allocating path verified the Adler-32 trailer, and the in-place one has to as
    /// well. A corrupt blob that inflated silently would be a quietly incomplete graph.
    #[test]
    fn a_corrupt_adler_trailer_is_still_rejected() {
        let data = b"the quick brown fox".to_vec();
        let mut blob = zlib_blob(&data, data.len());
        let last = blob.len() - 1;
        blob[last] ^= 0xff;
        let mut out = Vec::new();
        let err = inflate_blob(&blob, &mut out).expect_err("a bad checksum must be refused");
        assert!(err.0.contains("inflate failed"), "{}", err.0);
    }

    /// A `raw_size` smaller than the payload must error rather than hand back a truncated
    /// block. The buffer is sized from `raw_size`, so `out.len()` matches it whatever
    /// happened and the length check at the end of `inflate_blob` cannot see this.
    #[test]
    fn a_zlib_blob_whose_raw_size_is_too_small_is_rejected() {
        let data = b"0123456789".to_vec();
        let mut out = Vec::new();
        let err = inflate_blob(&zlib_blob(&data, 4), &mut out)
            .expect_err("a short raw_size must be refused");
        assert!(err.0.contains("inflate"), "{}", err.0);
    }

    #[test]
    fn raw_size_mismatch_is_rejected() {
        // Blob claiming a raw_size that disagrees with the payload.
        let mut blob = Vec::new();
        blob.push(1 << 3 | WIRE_BYTES);
        blob.push(3);
        blob.extend_from_slice(b"abc");
        blob.push(2 << 3 | proto::WIRE_VARINT);
        blob.push(9);
        let mut out = Vec::new();
        assert!(inflate_blob(&blob, &mut out).is_err());
    }

    #[test]
    fn unsupported_compression_is_reported() {
        let mut blob = vec![7 << 3 | WIRE_BYTES, 1, 0];
        let mut out = Vec::new();
        let err = inflate_blob(&blob, &mut out).unwrap_err();
        assert!(err.0.contains("zstd"), "{}", err.0);
        blob[0] = 4 << 3 | WIRE_BYTES;
        let err = inflate_blob(&blob, &mut out).unwrap_err();
        assert!(err.0.contains("lzma"), "{}", err.0);
    }

    /// The blob-kinds sidecar round trips, and every guard rejects a file that no longer matches: a
    /// stale sidecar must load as `None` (a full-scan fallback), never as a wrong mask.
    #[test]
    fn a_blob_kinds_sidecar_round_trips_and_the_guards_reject_a_changed_file() {
        let dir = std::env::temp_dir().join(format!(
            "osm_ingest_bk_{}_{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let input = dir.join("in.osm.pbf");
        std::fs::write(&input, b"a fake pbf, only its bytes matter here").unwrap();

        let blobs: Vec<BlobLoc> =
            (0..5).map(|i| BlobLoc { offset: i * 10, datasize: 10 }).collect();
        let kinds = vec![KIND_NODES, KIND_NODES | KIND_WAYS, KIND_WAYS, KIND_RELATIONS, 0];
        write_blob_kinds(&dir, &input, &blobs, &kinds).unwrap();

        // Happy path: same file, same blob count.
        assert_eq!(load_blob_kinds(&dir, &input, &blobs).as_deref(), Some(kinds.as_slice()));
        // Place and time are not content: the same bytes copied elsewhere and touched later still
        // match, and the sidecar written for the copy is byte-identical.
        let elsewhere = dir.join("moved");
        std::fs::create_dir_all(&elsewhere).unwrap();
        let copy = elsewhere.join("renamed.osm.pbf");
        std::thread::sleep(std::time::Duration::from_millis(20));
        std::fs::copy(&input, &copy).unwrap();
        assert_eq!(load_blob_kinds(&dir, &copy, &blobs).as_deref(), Some(kinds.as_slice()));
        write_blob_kinds(&elsewhere, &copy, &blobs, &kinds).unwrap();
        assert_eq!(
            std::fs::read(dir.join(BLOB_KINDS_FILE)).unwrap(),
            std::fs::read(elsewhere.join(BLOB_KINDS_FILE)).unwrap(),
            "the sidecar depends on the input's path or mtime"
        );
        // Same length, different bytes: the content guard fires.
        std::fs::write(&copy, b"a fake pbf, only its BYTES matter here").unwrap();
        assert!(load_blob_kinds(&dir, &copy, &blobs).is_none(), "content guard did not fire");
        // A resized blob in the index is a different file even with the same bytes sampled.
        let mut moved = blobs.clone();
        moved[2].datasize += 1;
        assert!(load_blob_kinds(&dir, &input, &moved).is_none(), "blob-index guard did not fire");
        // A different blob count is a different file to pass 1, whatever the sidecar claims.
        let fewer: Vec<BlobLoc> = blobs[..4].to_vec();
        assert!(load_blob_kinds(&dir, &input, &fewer).is_none(), "blob-count guard did not fire");
        // A changed source length must invalidate it.
        std::fs::write(&input, b"a different length entirely").unwrap();
        assert!(load_blob_kinds(&dir, &input, &blobs).is_none(), "length guard did not fire");
        // Wrong magic: a foreign or corrupt file at the path is rejected, not misread.
        std::fs::write(dir.join(BLOB_KINDS_FILE), b"not a real sidecar").unwrap();
        assert!(load_blob_kinds(&dir, &input, &blobs).is_none(), "magic guard did not fire");
        // A missing sidecar is simply a full-scan fallback.
        std::fs::remove_file(dir.join(BLOB_KINDS_FILE)).unwrap();
        assert!(load_blob_kinds(&dir, &input, &blobs).is_none(), "a missing sidecar must be None");

        let _ = std::fs::remove_dir_all(&dir);
    }
}
