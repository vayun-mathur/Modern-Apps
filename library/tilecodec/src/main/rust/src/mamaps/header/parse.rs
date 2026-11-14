//! Header parsing and validation: [`Header::parse`] plus its internal `check`.
//!
//! Pure moves out of the former single-file header module; nothing here changed
//! except re-rooting `super::shared` / `super::index` to `crate::mamaps::…`,
//! which the extra module level requires.

use crate::proto::{err, Result};

use super::consts::{
    COMPRESSION_DEFLATE, COMPRESSION_NONE, COMPRESSION_ZSTD, COMPRESSION_ZSTD_DICT, FLAG_LEAF_LEN_64,
    FORMAT_VERSION, FORMAT_VERSION_HEADER_V10, FORMAT_VERSION_V9, HEADER_LEN, KNOWN_FLAGS, MAGIC,
    MAX_ZOOM,
};
use super::types::Header;

impl Header {
    /// Read a header out of the opening prefix.
    ///
    /// Byte map, all little-endian: `0..7` magic, `7` format version, `8..10` header_len,
    /// `10..12` flags, `12` compression, `13` layer_count, `14` min_zoom, `15` max_zoom,
    /// `16..24` build_id, `24..32` file_len, `32..40` dict_offset, `40..44` dict_len,
    /// `44..48` leaf_entry_capacity, `48..56` root_offset, `56..60` root_len, `60..64`
    /// leaf_count, `64..72` leaf_offset, `72..76` leaf_len, `76..80` reserved, `80..88`
    /// data_offset, `88..96` data_len, `96..104` tiles_addressed, `104..112` bodies_written,
    /// `112..128` the bbox as four `i32` of degrees times 1e7.
    ///
    /// Every `u64` sits on an 8-byte boundary so a reader may take them as aligned loads.
    pub fn parse(buf: &[u8]) -> Result<Header> {
        if buf.len() < HEADER_LEN {
            return err(format!("a .mamaps header is {HEADER_LEN} bytes, got {}", buf.len()));
        }
        if &buf[0..7] != MAGIC {
            return err("not a .mamaps archive (bad magic)");
        }
        let version = buf[7];
        if version != FORMAT_VERSION && version != FORMAT_VERSION_V9 && version != FORMAT_VERSION_HEADER_V10 {
            return err(format!(
                "unsupported .mamaps format version {version} (this reader speaks v{FORMAT_VERSION}, \
                 v{FORMAT_VERSION_V9} and v{FORMAT_VERSION_HEADER_V10})",
            ));
        }
        // Dictionary compression is exactly what makes a header v10, both ways.
        if (version == FORMAT_VERSION_HEADER_V10) != (buf[12] == COMPRESSION_ZSTD_DICT) {
            return err(format!(
                "a .mamaps v{version} header with compression {} (v{FORMAT_VERSION_HEADER_V10} means \
                 dictionary-zstd bodies and nothing else)",
                buf[12]
            ));
        }
        let u16_at = |o: usize| u16::from_le_bytes([buf[o], buf[o + 1]]);
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let i32_at = |o: usize| i32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ])
        };

        // The version declares exactly one length: 128. Anything else is refused
        // rather than parsed as the shape it resembles.
        let header_len = u16_at(8);
        if header_len as usize != HEADER_LEN {
            return err(format!(
                "a .mamaps v{version} header declares {header_len} bytes, not {HEADER_LEN}"
            ));
        }
        if buf.len() < HEADER_LEN {
            return err(format!(
                "a .mamaps v{version} header needs {HEADER_LEN} bytes, got {}",
                buf.len()
            ));
        }
        let flags = u16_at(10);
        // An unknown flag means the writer recorded something about the bodies that this reader
        // would ignore, and every flag in this format changes how a body must be handled. Better
        // to refuse the archive than to draw it wrong.
        if flags & !KNOWN_FLAGS != 0 {
            return err(format!("a .mamaps header sets unknown flags {:#06X}", flags & !KNOWN_FLAGS));
        }
        // Bytes 72..80 hold leaf_len: 72..76 low 32, 76..80 high 32 when
        // FLAG_LEAF_LEN_64 is set, otherwise 76..80 must be zero (v1 reserved).
        // Common path (NA ~418 MB) stays flag 0 + u32 + 0 — byte-identical to v1.
        let leaf_len = if (flags & FLAG_LEAF_LEN_64) != 0 {
            let lo = u32_at(72) as u64;
            let hi = u32_at(76) as u64;
            let v = lo | (hi << 32);
            if v <= u32::MAX as u64 {
                return err(format!(
                    "a .mamaps header sets FLAG_LEAF_LEN_64 but leaf_len {} fits u32",
                    v
                ));
            }
            v
        } else {
            if u32_at(76) != 0 {
                return err("a .mamaps header has a non-zero reserved word");
            }
            u32_at(72) as u64
        };
        // Anything past byte 128 in the buffer is the dictionary, not the header,
        // and is ignored the way it always was.
        let header = Header {
            flags,
            compression: buf[12],
            layer_count: buf[13],
            min_zoom: buf[14],
            max_zoom: buf[15],
            build_id: u64_at(16),
            file_len: u64_at(24),
            dict_offset: u64_at(32),
            dict_len: u32_at(40),
            leaf_entry_capacity: u32_at(44),
            root_offset: u64_at(48),
            root_len: u32_at(56),
            leaf_count: u32_at(60),
            leaf_offset: u64_at(64),
            leaf_len,
            data_offset: u64_at(80),
            data_len: u64_at(88),
            tiles_addressed: u64_at(96),
            bodies_written: u64_at(104),
            min_lon_e7: i32_at(112),
            min_lat_e7: i32_at(116),
            max_lon_e7: i32_at(120),
            max_lat_e7: i32_at(124),
        };
        header.check()?;
        Ok(header)
    }

    /// Everything a later read would otherwise discover the hard way.
    ///
    /// A corrupt or hostile header must not be able to name a section that overlaps another, runs
    /// past the file, or is implausibly large — because every subsequent read takes its length
    /// from a field here, and a wrong length is a read that either fails somewhere uninformative
    /// or poisons a disk cache entry with a body of the wrong size.
    pub(super) fn check(&self) -> Result<()> {
        if !matches!(
            self.compression,
            COMPRESSION_NONE | COMPRESSION_DEFLATE | COMPRESSION_ZSTD | COMPRESSION_ZSTD_DICT
        ) {
            return err(format!("unknown .mamaps compression {}", self.compression));
        }
        if self.compressed() != (self.compression != COMPRESSION_NONE) {
            return err("a .mamaps header disagrees with itself about whether bodies are compressed");
        }
        if self.min_zoom > self.max_zoom || self.max_zoom > MAX_ZOOM {
            return err(format!(
                "a .mamaps header declares an impossible zoom range {}..={}",
                self.min_zoom, self.max_zoom,
            ));
        }
        if !self.leaf_entry_capacity.is_power_of_two() {
            return err(format!(
                "a .mamaps leaf capacity of {} is not a power of two",
                self.leaf_entry_capacity,
            ));
        }
        if self.leaf_count == 0 {
            return err("a .mamaps archive needs at least one leaf");
        }
        // No section may start inside the 128-byte header.
        let header_floor = HEADER_LEN as u64;
        if self.file_len < header_floor {
            return err(format!("a .mamaps header declares a {} byte file", self.file_len));
        }
        let sections = [
            ("the dictionary", self.dict_offset, self.dict_len as u64),
            ("the root index", self.root_offset, self.root_len as u64),
            ("the leaf index", self.leaf_offset, self.leaf_len as u64),
            ("the tile data", self.data_offset, self.data_len),
        ];
        for (what, offset, len) in sections {
            if offset < header_floor {
                return err(format!("{what} overlaps the .mamaps header"));
            }
            match offset.checked_add(len) {
                Some(end) if end <= self.file_len => {}
                _ => return err(format!("{what} runs past the end of the .mamaps file")),
            }
        }
        // Pairwise, because a writer that patched one offset and not another would otherwise
        // produce a file whose index and data disagree about where a body lives.
        for (i, (what, offset, len)) in sections.iter().enumerate() {
            for (other, other_offset, other_len) in &sections[i + 1..] {
                if *len == 0 || *other_len == 0 {
                    continue;
                }
                if *offset < other_offset + other_len && *other_offset < offset + len {
                    return err(format!("{what} and {other} overlap in the .mamaps file"));
                }
            }
        }
        if self.root_len as usize % crate::mamaps::index::ROOT_ENTRY_LEN != 0 {
            return err(format!(
                "a .mamaps root index of {} bytes is not a whole number of {} byte entries",
                self.root_len,
                crate::mamaps::index::ROOT_ENTRY_LEN,
            ));
        }
        if self.compression == COMPRESSION_ZSTD_DICT
            && self.root_offset + self.root_len as u64 > self.leaf_offset
        {
            return err("a .mamaps v10 header leaves no room for its dictionary section");
        }
        if self.bodies_written > self.tiles_addressed {
            return err("a .mamaps header stores more bodies than it addresses tiles");
        }
        Ok(())
    }
}
