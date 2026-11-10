pub(crate) fn ru32(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}
pub(crate) fn ri32(b: &[u8], off: usize) -> i32 {
    i32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}
pub(crate) fn ru64(b: &[u8], off: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[off..off + 8]);
    u64::from_le_bytes(a)
}

/// Read one unsigned LEB128 varint, advancing `pos`.
pub fn read_uvarint(b: &[u8], pos: &mut usize) -> u64 {
    let mut result = 0u64;
    let mut shift = 0;
    loop {
        let byte = b[*pos];
        *pos += 1;
        result |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            break;
        }
        shift += 7;
    }
    result
}

pub(crate) fn zigzag(u: u64) -> i64 {
    ((u >> 1) as i64) ^ -((u & 1) as i64)
}

/// One RAIL_LINES entry: a whole, unsimplified rail shape and the route it draws for.
pub struct RailLineRec {
    pub feed_idx: u32,
    pub route_type: u32,
    /// The feed's own `route_color`, or 0 when it gave none.
    pub color: u32,
    pub name: String,
    pub route_id: String,
    /// `(lat_e7, lon_e7)`, every `shapes.txt` point in sequence order.
    pub points: Vec<(i32, i32)>,
}

impl crate::reader::Reader {
    /// The v7 RAIL_LINES section, in the order it was written (feed, route_id, shape_id).
    /// `None` for a v6 pack, which predates it. Panics on a malformed section: the pack is a
    /// build input written by this crate, not untrusted data.
    pub fn rail_lines(&self) -> Option<Vec<RailLineRec>> {
        use crate::index::{NONE, RAIL_LINE_REC_BYTES};
        if self.section_count() as usize <= crate::reader::SEC_RAIL_LINES {
            return None;
        }
        let s = self.sec_bytes(crate::reader::SEC_RAIL_LINES);
        let count = ru32(s, 0) as usize;
        let recs = 4;
        let strings_at = recs + count * RAIL_LINE_REC_BYTES;
        let strings_len = ru32(s, strings_at) as usize;
        let strings = &s[strings_at + 4..strings_at + 4 + strings_len];
        let blobs = &s[strings_at + 4 + strings_len..];
        let text = |off: u32| -> String {
            if off == NONE {
                return String::new();
            }
            let tail = &strings[off as usize..];
            let end = tail.iter().position(|&b| b == 0).unwrap_or(tail.len());
            String::from_utf8_lossy(&tail[..end]).into_owned()
        };
        // Each record decodes on its own; `collect` keeps pack order.
        use rayon::prelude::*;
        let out = (0..count)
            .into_par_iter()
            .map(|i| {
                let at = recs + i * RAIL_LINE_REC_BYTES;
                let field = |k: usize| ru32(s, at + k * 4);
                let mut pos = field(5) as usize;
                let n = ru32(blobs, pos) as usize;
                pos += 4;
                let (mut lat, mut lon) = (0i64, 0i64);
                let points = (0..n)
                    .map(|_| {
                        lat += zigzag(read_uvarint(blobs, &mut pos));
                        lon += zigzag(read_uvarint(blobs, &mut pos));
                        (lat as i32, lon as i32)
                    })
                    .collect();
                RailLineRec {
                    feed_idx: field(0),
                    route_type: field(1),
                    color: field(2),
                    name: text(field(3)),
                    route_id: text(field(4)),
                    points,
                }
            })
            .collect();
        Some(out)
    }
}
