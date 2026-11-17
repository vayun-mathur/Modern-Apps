//! `mamaps_kinds` — per-kind feature/point/stored breakdown for one layer.
//!
//! Usage: mamaps_kinds IN.mamaps LAYER [ZOOM] [SAMPLES_PER_ZOOM]
//!
//! Samples bodies (optionally at one zoom), decodes the named layer, and
//! reports per kind: features, points, raw arena bytes (measured by
//! re-serializing single-kind payloads), stored bytes (same + DEFLATE-9).
use std::collections::BTreeMap;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::process::ExitCode;

use tile_build::mamaps::body::{Body, Layer};
use tile_build::mamaps::{header, index};
use tile_build::proto::Result;
use tilecodec::pmtiles::tile_zxy;
use tilecodec::stream::RangeReader;

struct FileRanges {
    file: std::sync::Mutex<File>,
}

impl RangeReader for FileRanges {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        let mut file = self.file.lock().map_err(|_| tile_build::proto::Error("lock".into()))?;
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| tile_build::proto::Error(format!("seek {offset}: {e}")))?;
        let mut buf = vec![0u8; length as usize];
        let mut got = 0usize;
        while got < buf.len() {
            match file.read(&mut buf[got..]) {
                Ok(0) => break,
                Ok(n) => got += n,
                Err(e) => return tile_build::proto::err(format!("read at {offset}: {e}")),
            }
        }
        buf.truncate(got);
        Ok(buf)
    }
}

fn read_leaf(
    ranges: &FileRanges,
    hdr: &header::Header,
    re: &index::RootEntry,
) -> Result<Vec<index::LeafEntry>> {
    let raw_len = re.leaf_entry_count as usize * index::LEAF_ENTRY_LEN;
    let stored_len = if re.stored_len == 0 { raw_len } else { re.stored_len as usize };
    let stored = ranges.read(hdr.leaf_offset + re.leaf_offset, stored_len as u32)?;
    let raw = if re.stored_len == 0 {
        stored
    } else {
        index::decompress_leaf(&stored, raw_len)?
    };
    index::parse_leaf(&raw)
}

#[derive(Default)]
struct KindStats {
    feats: u64,
    points: u64,
    raw: u64,
    stored: u64,
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: mamaps_kinds IN.mamaps LAYER [ZOOM] [SAMPLES_PER_ZOOM]");
        return ExitCode::from(2);
    }
    let path = &args[1];
    let layer_name = &args[2];
    let only_zoom: Option<u8> = args.get(3).and_then(|s| s.parse().ok());
    let per_zoom: usize = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(150);

    let file = File::open(path).expect("open");
    let ranges = FileRanges { file: std::sync::Mutex::new(file) };
    let prefix = ranges.read(0, tilecodec::stream::OPEN_PREFIX_BYTES).expect("prefix");
    let hdr = header::Header::parse(&prefix).expect("header");
    let root = index::parse_root(
        &prefix[hdr.root_offset as usize..(hdr.root_offset + hdr.root_len as u64) as usize],
    )
    .expect("root");
    let dict_data = ranges.read(hdr.dict_offset, hdr.dict_len).expect("dict");
    let dict = tile_build::mamaps::dict::Dictionary::parse(&dict_data).expect("dict");
    let lid = dict
        .layers
        .iter()
        .position(|l| l == layer_name)
        .unwrap_or_else(|| panic!("no layer {layer_name}")) as u8;

    let mut zoom_entries: BTreeMap<u8, Vec<(u64, u32)>> = BTreeMap::new();
    for re in &root {
        let leaf = read_leaf(&ranges, &hdr, re).expect("leaf");
        for e in &leaf {
            let (z, _, _) = tile_zxy(re.base_tile_id + e.tile_id_lo as u64);
            if only_zoom.is_some_and(|oz| oz != z) {
                continue;
            }
            let at = hdr.data_offset + re.base_data_offset + e.offset_delta as u64;
            zoom_entries.entry(z).or_default().push((at, e.length));
        }
    }

    let mut stats: BTreeMap<u16, KindStats> = BTreeMap::new();
    let mut bodies = 0u64;
    for (z, entries) in &zoom_entries {
        let step = (entries.len() / per_zoom.max(1)).max(1);
        let mut idx = 0usize;
        while idx < entries.len() {
            let (at, len) = entries[idx];
            idx += step;
            let stored = match ranges.read(at, len) {
                Ok(s) => s,
                Err(_) => continue,
            };
            let raw = match tile_build::mamaps::read::decompress(hdr.compression, &stored) {
                Ok(r) => r,
                Err(_) => continue,
            };
            let body = match Body::parse(&raw) {
                Ok(b) => b,
                Err(_) => continue,
            };
            let Some(layer) = body.layer(lid) else { continue };
            if layer.features.is_empty() {
                continue;
            }
            bodies += 1;
            // Group features by kind, build single-kind payloads, measure.
            let mut by_kind: BTreeMap<u16, Vec<usize>> = BTreeMap::new();
            for (i, f) in layer.features.iter().enumerate() {
                by_kind.entry(f.kind).or_default().push(i);
            }
            for (kind, feat_ids) in by_kind {
                let mut nl = Layer::new(lid);
                let mut pts = 0u64;
                for &i in &feat_ids {
                    let f = &layer.features[i];
                    let start = nl.parts.len() as u32;
                    let from = f.parts_offset as usize;
                    for p in &layer.parts[from..from + f.part_count as usize] {
                        let ppts = layer.points(p);
                        pts += ppts.len() as u64;
                        nl.parts.push(tile_build::mamaps::body::Part {
                            coord_start: nl.coords.len() as u32,
                            point_count: ppts.len() as u32,
                            winding: p.winding,
                        });
                        nl.coords.extend_from_slice(ppts);
                    }
                    let mut nf = *f;
                    nf.parts_offset = start;
                    nl.features.push(nf);
                }
                let b = Body {
                    extent: body.extent,
                    layers: vec![nl],
                    names: vec![],
                    ids: vec![],
                    turn_lanes: vec![],
                    buildings: vec![],
                    heightmap: None,
                    carriageways: vec![],
                    convention: None,
                    region_links: vec![],
                };
                let mut scratch = tile_build::mamaps::body::Scratch::default();
                if let Ok(raw2) = tile_build::mamaps::body::serialize_into(&b, &mut scratch) {
                    let h = tile_build::mamaps::body::BODY_HEADER_LEN;
                    let mut st = Vec::with_capacity(raw2.len());
                    st.extend_from_slice(&raw2[..h]);
                    st.extend_from_slice(&miniz_oxide::deflate::compress_to_vec(&raw2[h..], 9));
                    let stt = stats.entry(kind).or_default();
                    stt.feats += feat_ids.len() as u64;
                    stt.points += pts;
                    stt.raw += raw2.len() as u64;
                    stt.stored += st.len() as u64;
                }
            }
        }
        let _ = z;
    }

    println!("layer {layer_name}: sampled {bodies} bodies");
    println!("{:>16} {:>10} {:>12} {:>10} {:>10}", "kind", "feats", "points", "raw(MB)", "stored(MB)");
    let mut rows: Vec<(u16, KindStats)> = stats.into_iter().collect();
    rows.sort_by_key(|(_, s)| std::cmp::Reverse(s.stored));
    let total_st: u64 = rows.iter().map(|(_, s)| s.stored).sum();
    for (kind, s) in rows {
        let name = if kind == tile_build::mamaps::dict::NONE {
            "(NONE)".to_string()
        } else {
            dict.kind_name(kind).unwrap_or("?").to_string()
        };
        println!(
            "{:>16} {:>10} {:>12} {:>10.2} {:>10.2}  {:>5.1}%",
            name,
            s.feats,
            s.points,
            s.raw as f64 / 1e6,
            s.stored as f64 / 1e6,
            100.0 * s.stored as f64 / total_st.max(1) as f64
        );
    }
    ExitCode::SUCCESS
}
