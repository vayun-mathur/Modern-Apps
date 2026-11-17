//! `graph_cols` — planet-50 measurement: ZBLK graph blocks re-encoded column-wise.
//!
//!   graph_cols GRAPH_DIR [STRIDE]
//!
//! For nodes.bin (12-byte NodeRec: lat_e7, lon_e7, edge_ptr) and edges.bin (11-byte EdgeRec:
//! target_delta i16, dist u24, type u8, speed u8), every STRIDE-th ZBLK block is inflated and
//! recompressed at zstd 19 as: (a) rows (today), (b) byte planes (shuffle), (c) field-aware
//! columns (deltas of monotone/smooth fields, zigzag, then planes).
use rayon::prelude::*;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use tilecodec::blocked::Blocked;

fn z19(b: &[u8]) -> u64 {
    zstd::bulk::compress(b, 19).expect("zstd").len() as u64
}

fn planes(rows: &[u8], w: usize) -> Vec<u8> {
    let n = rows.len() / w;
    let mut out = vec![0u8; n * w];
    for i in 0..n {
        for j in 0..w {
            out[j * n + i] = rows[i * w + j];
        }
    }
    out
}

fn zz32(v: i32) -> u32 {
    ((v << 1) ^ (v >> 31)) as u32
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = std::path::PathBuf::from(&args[1]);
    let stride: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(1);
    for (file, w) in [("nodes.bin", 12usize), ("edges.bin", 7usize)] {
        let bytes = std::fs::read(dir.join(file)).expect("read");
        let z = Blocked::parse(&bytes).expect("ZBLK");
        assert_eq!(z.record_len(), w);
        let (cur, rows_c, plan_c, cols_c, raw) =
            (AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0), AtomicU64::new(0));
        let blocks: Vec<usize> = (0..z.block_count()).step_by(stride).collect();
        blocks.par_iter().for_each(|&b| {
            let start = z.block_offset(b).unwrap();
            let end = z.block_offset(b + 1).unwrap();
            cur.fetch_add(end - start, Relaxed);
            let rows = z.block(b).expect("block");
            raw.fetch_add(rows.len() as u64, Relaxed);
            rows_c.fetch_add(z19(&rows), Relaxed);
            plan_c.fetch_add(z19(&planes(&rows, w)), Relaxed);
            let n = rows.len() / w;
            let cols;
            if w == 12 {
                // lat, lon: delta from the previous node (curve-sorted); edge_ptr: delta (= degree)
                let rd = |i: usize, o: usize| i32::from_le_bytes(rows[i * 12 + o..i * 12 + o + 4].try_into().unwrap());
                let mut t = Vec::with_capacity(rows.len());
                let (mut pa, mut po, mut pe) = (0i32, 0i32, 0i32);
                for i in 0..n {
                    let (a, o, e) = (rd(i, 0), rd(i, 4), rd(i, 8));
                    t.extend_from_slice(&zz32(a.wrapping_sub(pa)).to_le_bytes());
                    t.extend_from_slice(&zz32(o.wrapping_sub(po)).to_le_bytes());
                    t.extend_from_slice(&zz32(e.wrapping_sub(pe)).to_le_bytes());
                    pa = a;
                    po = o;
                    pe = e;
                }
                cols = planes(&t, 12);
            } else {
                // 7-byte records: first i16 zigzag (target delta), rest as planes
                let mut t = Vec::with_capacity(rows.len());
                for i in 0..n {
                    let r = &rows[i * w..i * w + w];
                    let td = i16::from_le_bytes([r[0], r[1]]);
                    let zt = ((td << 1) ^ (td >> 15)) as u16;
                    t.extend_from_slice(&zt.to_le_bytes());
                    t.extend_from_slice(&r[2..w]);
                }
                cols = planes(&t, w);
            }
            cols_c.fetch_add(z19(&cols), Relaxed);
        });
        let f = |a: &AtomicU64| a.load(Relaxed) as f64;
        let scale = z.block_count() as f64 / blocks.len() as f64;
        println!(
            "{file}: blocks {} sampled {} raw {:.2} GB | stored {:.3} GB | rows z19 {:.3} | planes {:.3} ({:+.1}%) | columns {:.3} ({:+.1}%)",
            z.block_count(),
            blocks.len(),
            f(&raw) * scale / 1e9,
            f(&cur) * scale / 1e9,
            f(&rows_c) * scale / 1e9,
            f(&plan_c) * scale / 1e9,
            100.0 * (f(&plan_c) / f(&rows_c) - 1.0),
            f(&cols_c) * scale / 1e9,
            100.0 * (f(&cols_c) / f(&rows_c) - 1.0),
        );
    }
}
