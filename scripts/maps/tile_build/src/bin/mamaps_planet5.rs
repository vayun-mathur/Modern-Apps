//! `mamaps_planet5` — transcode a finished tile archive into the planet5 tile format.
//!
//! ```text
//! mamaps_planet5 IN.mamaps OUT.mamaps [--dict-bytes N] [--train-per-band N]
//! ```
//!
//! Reads the tile archive `mamaps_build` wrote (v8/v9 header, DEFLATE or zstd v10 bodies) and
//! writes the same tiles as:
//!
//! * **v11 bodies** — byte-for-byte the v10 body except that the heightmap is MED-residual coded
//!   (`tilecodec::mamaps::body::serialize_into_v11`); decodes to the identical `Body`;
//! * **dictionary zstd** — one zstd dictionary trained per zoom band (z0-9, z10-11, z12, z13,
//!   z14) on a stride sample of that band's v11 bodies, every body compressed at zstd-19 against
//!   its band's dictionary; the dictionaries go into the v10 header's dictionary section.
//!
//! Every addressed tile id is appended in order through `StreamWriter`, so runs and content
//! dedup are rebuilt exactly as the generator builds them, and the content-derived build id is
//! re-derived over the new bodies (seeded with the input's id).
//!
//! Lossless: `--verify` decodes every output body and compares the parsed `Body` with the
//! input's. Deterministic: the training sample is a fixed stride and the encoder is a pure
//! function of input + dictionary, so the output does not depend on the thread count.
use std::collections::HashMap;
use std::fs::File;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::Instant;

use rayon::prelude::*;
use tile_build::mamaps::body::{serialize_into_v11, Body, Scratch, BODY_HEADER_LEN};
use tile_build::mamaps::header::{Header, FLAG_RINGS_VALIDATED};
use tile_build::mamaps::index;
use tile_build::mamaps::read::decompress;
use tile_build::mamaps::write::{hash64, Options, StreamWriter};
use tile_build::mamaps::zdict::ZDicts;
use tilecodec::pmtiles::tile_zxy;

const BANDS: usize = 5;

fn band(z: u8) -> usize {
    match z {
        0..=9 => 0,
        10..=11 => 1,
        12 => 2,
        13 => 3,
        _ => 4,
    }
}

/// One leaf entry: first tile id, run length, absolute body offset and stored length.
#[derive(Clone, Copy)]
struct Ent {
    id: u64,
    run: u32,
    off: u64,
    len: u32,
}

fn entries(map: &[u8], hdr: &Header) -> Result<Vec<Ent>, String> {
    let root = index::parse_root(&map[hdr.root_offset as usize..(hdr.root_offset + hdr.root_len as u64) as usize])
        .map_err(|e| e.0)?;
    let per: Vec<Result<Vec<Ent>, String>> = root
        .par_iter()
        .map(|re| {
            let raw_len = re.leaf_entry_count as usize * index::LEAF_ENTRY_LEN;
            let at = (hdr.leaf_offset + re.leaf_offset) as usize;
            let raw = if re.stored_len == 0 {
                map[at..at + raw_len].to_vec()
            } else {
                index::decompress_leaf(&map[at..at + re.stored_len as usize], raw_len).map_err(|e| e.0)?
            };
            Ok(index::parse_leaf(&raw)
                .map_err(|e| e.0)?
                .iter()
                .map(|e| Ent {
                    id: re.base_tile_id + e.tile_id_lo as u64,
                    run: e.run_length,
                    off: hdr.data_offset + re.base_data_offset + e.offset_delta as u64,
                    len: e.length,
                })
                .collect())
        })
        .collect();
    let mut out = Vec::new();
    for p in per {
        out.extend(p?);
    }
    out.sort_by_key(|e| e.id);
    Ok(out)
}

/// The v11 encoding of one stored input body.
fn to_v11(map: &[u8], hdr: &Header, off: u64, len: u32) -> Result<(Vec<u8>, Body), String> {
    let stored = &map[off as usize..off as usize + len as usize];
    let raw = decompress(hdr.compression, stored).map_err(|e| e.0)?;
    let body = Body::parse(&raw).map_err(|e| e.0)?;
    let mut s = Scratch::default();
    // A v10 sparse carriageway table whose rows are all default is written as a convention with
    // zero entries, and parses back as `convention: Some` with no rows -- a model the serializer
    // refuses. Re-supply the all-default rows for the roads layer so the v11 body carries the
    // identical table (zero entries + the convention) and parses back to the same `Body`.
    let v11 = if body.convention.is_some() && body.carriageways.is_empty() {
        let mut b = body.clone();
        let roads = tile_build::mamaps::dict::LAYER_ROADS;
        let n = b
            .layer(roads)
            .map(|l| l.features.len())
            .ok_or("a body with a marking convention and no roads layer")?;
        b.carriageways = vec![(roads, vec![tile_build::mamaps::body::Carriageway::default(); n])];
        serialize_into_v11(&b, &mut s).map_err(|e| e.0)?.to_vec()
    } else {
        serialize_into_v11(&body, &mut s).map_err(|e| e.0)?.to_vec()
    };
    Ok((v11, body))
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut pos = Vec::new();
    let (mut dict_bytes, mut per_band, mut verify) = (112 * 1024usize, 20_000usize, false);
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--dict-bytes" => {
                dict_bytes = args[i + 1].parse().expect("--dict-bytes N");
                i += 1;
            }
            "--train-per-band" => {
                per_band = args[i + 1].parse().expect("--train-per-band N");
                i += 1;
            }
            "--verify" => verify = true,
            a => pos.push(a.to_string()),
        }
        i += 1;
    }
    if pos.len() != 2 {
        eprintln!("usage: mamaps_planet5 IN.mamaps OUT.mamaps [--dict-bytes N] [--train-per-band N] [--verify]");
        return ExitCode::from(2);
    }
    match run(&pos[0], &pos[1], dict_bytes, per_band, verify) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("mamaps_planet5: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run(input: &str, output: &str, dict_bytes: usize, per_band: usize, verify: bool) -> Result<(), String> {
    let t0 = Instant::now();
    let f = File::open(input).map_err(|e| format!("open {input}: {e}"))?;
    let map = unsafe { memmap2::Mmap::map(&f) }.map_err(|e| format!("mmap {input}: {e}"))?;
    let hdr = Header::parse(&map[..tilecodec::stream::OPEN_PREFIX_BYTES as usize]).map_err(|e| e.0)?;
    if hdr.compression == tile_build::mamaps::header::COMPRESSION_ZSTD_DICT {
        return Err("the input is already a planet5 archive".into());
    }
    let ents = entries(&map, &hdr)?;
    println!("input: {} leaf entries, {} bytes of tile data ({:.1}s)", ents.len(), hdr.data_len, t0.elapsed().as_secs_f64());

    // 1. Training: a fixed stride over each band's distinct bodies.
    let mut uniq: Vec<Vec<(u64, u32)>> = vec![Vec::new(); BANDS];
    {
        let mut seen: Vec<std::collections::HashSet<u64>> = vec![Default::default(); BANDS];
        for e in &ents {
            let b = band(tile_zxy(e.id).0);
            if seen[b].insert(e.off) {
                uniq[b].push((e.off, e.len));
            }
        }
    }
    let dicts: Vec<Vec<u8>> = uniq
        .par_iter()
        .enumerate()
        .map(|(b, v)| -> Result<Vec<u8>, String> {
            if v.len() < 256 {
                return Ok(Vec::new()); // too few bodies to train on: plain zstd frames
            }
            let step = (v.len() / per_band).max(1);
            let samples: Vec<Vec<u8>> = v
                .iter()
                .step_by(step)
                .map(|&(off, len)| to_v11(&map, &hdr, off, len).map(|(r, _)| r[BODY_HEADER_LEN..].to_vec()))
                .collect::<Result<_, _>>()?;
            let d = zstd::dict::from_samples(&samples, dict_bytes).map_err(|e| format!("train band {b}: {e}"))?;
            println!("  band {b}: {} distinct bodies, trained on {} -> {} B dictionary", v.len(), samples.len(), d.len());
            Ok(d)
        })
        .collect::<Result<_, _>>()?;
    let kept: Vec<Vec<u8>> = dicts.iter().filter(|d| !d.is_empty()).cloned().collect();
    let section = ZDicts::serialize(&kept);
    let verify_dicts = ZDicts::parse(&section).map_err(|e| e.0)?;
    let enc_dicts: Vec<Option<zstd_rs::EncoderDictionary>> = dicts
        .iter()
        .map(|d| -> Result<Option<zstd_rs::EncoderDictionary>, String> {
            if d.is_empty() {
                return Ok(None);
            }
            let dd = zstd_rs::Dictionary::new(d, zstd_rs::DictionaryFormat::Zstd).map_err(|e| format!("{e:?}"))?;
            Ok(Some(zstd_rs::EncoderDictionary::new(&dd, 19).map_err(|e| format!("{e:?}"))?))
        })
        .collect::<Result<_, _>>()?;
    println!("trained {} dictionaries ({} B section) in {:.1}s", kept.len(), section.len(), t0.elapsed().as_secs_f64());

    // 2. Re-encode and append, in tile id order, in batches.
    let out_path = PathBuf::from(output);
    let mut writer = StreamWriter::new(Options {
        min_zoom: hdr.min_zoom,
        max_zoom: hdr.max_zoom,
        build_id: hdr.build_id,
        build_id_from_content: true,
        compress: true,
        zdicts: Some(section),
        rings_validated: hdr.flags & FLAG_RINGS_VALIDATED != 0,
        leaf_entry_capacity: hdr.leaf_entry_capacity,
        spill_dir: out_path.parent().map(|p| p.to_path_buf()),
        min_lon_e7: hdr.min_lon_e7,
        min_lat_e7: hdr.min_lat_e7,
        max_lon_e7: hdr.max_lon_e7,
        max_lat_e7: hdr.max_lat_e7,
        ..Options::default()
    })
    .map_err(|e| e.0)?;
    let cfg = zstd_rs::CompressionConfig { level: 19, ..zstd_rs::CompressionConfig::DEFAULT };
    let (mut raw_in, mut stored_out) = (0u64, 0u64);
    const BATCH: usize = 1 << 21;
    for (bi, batch) in ents.chunks(BATCH).enumerate() {
        // Distinct (offset, band) pairs in this batch, encoded in parallel.
        let mut keys: Vec<(u64, u32, usize)> = batch.iter().map(|e| (e.off, e.len, band(tile_zxy(e.id).0))).collect();
        keys.sort_unstable();
        keys.dedup();
        let encoded: Vec<Result<((u64, usize), Arc<(Vec<u8>, u64)>, u64), String>> = keys
            .par_iter()
            .map_init(
                || zstd_rs::Compressor::new(cfg).expect("zstd level"),
                |enc, &(off, len, b)| {
                    let (v11, body) = to_v11(&map, &hdr, off, len)?;
                    let mut stored = Vec::with_capacity(v11.len() / 2);
                    stored.extend_from_slice(&v11[..BODY_HEADER_LEN]);
                    enc.compress(&v11[BODY_HEADER_LEN..], enc_dicts[b].as_ref(), &mut stored)
                        .map_err(|e| format!("zstd: {e:?}"))?;
                    if verify {
                        let back = tile_build::mamaps::read::decompress_with(
                            tile_build::mamaps::header::COMPRESSION_ZSTD_DICT,
                            &stored,
                            Some(&verify_dicts),
                        )
                        .map_err(|e| e.0)?;
                        if Body::parse(&back).map_err(|e| e.0)? != body {
                            return Err(format!("body at {off} does not round-trip"));
                        }
                    }
                    let h = hash64(&stored);
                    Ok(((off, b), Arc::new((stored, h)), v11.len() as u64))
                },
            )
            .collect();
        let mut by_key: HashMap<(u64, usize), Arc<(Vec<u8>, u64)>> = HashMap::with_capacity(encoded.len());
        for r in encoded {
            let (k, v, raw) = r?;
            raw_in += raw;
            by_key.insert(k, v);
        }
        for e in batch {
            let s = &by_key[&(e.off, band(tile_zxy(e.id).0))];
            for k in 0..e.run as u64 {
                writer.append_stored_with_hash(e.id + k, &s.0, s.1).map_err(|e| e.0)?;
            }
        }
        stored_out += by_key.values().map(|v| v.0.len() as u64).sum::<u64>();
        println!(
            "  batch {bi}: {} entries, {} distinct bodies ({:.0}s)",
            batch.len(),
            by_key.len(),
            t0.elapsed().as_secs_f64()
        );
    }
    writer.finish_to_path(&out_path).map_err(|e| e.0)?;
    let len = std::fs::metadata(&out_path).map_err(|e| e.to_string())?.len();
    println!(
        "wrote {output}: {len} bytes (input tile data {} B, v11 raw {raw_in} B, encoded distinct {stored_out} B) in {:.0}s",
        hdr.data_len,
        t0.elapsed().as_secs_f64()
    );
    Ok(())
}
