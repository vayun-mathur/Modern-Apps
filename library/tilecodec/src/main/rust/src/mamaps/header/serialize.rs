//! Header serialization: the 128 v7 bytes, unconditionally.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

use super::consts::{
    COMPRESSION_ZSTD_DICT, FLAG_LEAF_LEN_64, FLAG_LEAVES_COMPRESSED, FORMAT_VERSION,
    FORMAT_VERSION_HEADER_V10, FORMAT_VERSION_V9, HEADER_LEN, MAGIC,
};
use super::types::Header;

impl Header {
    /// The 128 v7 bytes.
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_LEN);
        out.extend_from_slice(MAGIC);
        // Compressed leaves are a v9 frame: the flag is unknown to a v8
        // reader, which refuses cleanly rather than misreading the leaf
        // section. Everything else keeps the v8 byte (and v8 readers).
        out.push(if self.compression == COMPRESSION_ZSTD_DICT {
            FORMAT_VERSION_HEADER_V10
        } else if (self.flags & FLAG_LEAVES_COMPRESSED) != 0 {
            FORMAT_VERSION_V9
        } else {
            FORMAT_VERSION
        });
        out.extend_from_slice(&(HEADER_LEN as u16).to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.push(self.compression);
        out.push(self.layer_count);
        out.push(self.min_zoom);
        out.push(self.max_zoom);
        out.extend_from_slice(&self.build_id.to_le_bytes());
        out.extend_from_slice(&self.file_len.to_le_bytes());
        out.extend_from_slice(&self.dict_offset.to_le_bytes());
        out.extend_from_slice(&self.dict_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_entry_capacity.to_le_bytes());
        out.extend_from_slice(&self.root_offset.to_le_bytes());
        out.extend_from_slice(&self.root_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_count.to_le_bytes());
        out.extend_from_slice(&self.leaf_offset.to_le_bytes());
        // 72..76 is always low 32 of leaf_len; 76..80 is high 32 when extended,
        // otherwise reserved zero. Common path (<4 GiB): high 32 == 0 → byte-identical to v1 (u32 + 0).
        out.extend_from_slice(&(self.leaf_len as u32).to_le_bytes());
        if (self.flags & FLAG_LEAF_LEN_64) != 0 {
            // Extended: 76..80 holds high 32 of the true u64 leaf_len (planet ~4.29 GB).
            out.extend_from_slice(&((self.leaf_len >> 32) as u32).to_le_bytes());
        } else {
            out.extend_from_slice(&0u32.to_le_bytes());
        }
        out.extend_from_slice(&self.data_offset.to_le_bytes());
        out.extend_from_slice(&self.data_len.to_le_bytes());
        out.extend_from_slice(&self.tiles_addressed.to_le_bytes());
        out.extend_from_slice(&self.bodies_written.to_le_bytes());
        for v in [self.min_lon_e7, self.min_lat_e7, self.max_lon_e7, self.max_lat_e7] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        debug_assert_eq!(out.len(), HEADER_LEN);
        out
    }
}
