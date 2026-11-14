use crate::mamaps::body::Body;
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::{
    COMPRESSION_DEFLATE, COMPRESSION_NONE, COMPRESSION_ZSTD, COMPRESSION_ZSTD_DICT, Header, HEADER_LEN,
};
use crate::mamaps::index::{self, RootEntry};
use crate::proto::{Error, Result, err};
use std::io::Read as _;

/// Inflate one body frame, allocating exactly once.
///
/// The output size comes from the body header, which the writer keeps **outside** the compressed
/// frame precisely so this is possible: the length is known before the first byte is inflated, so
/// there is no grow-and-copy and no guess.
pub fn decompress(compression: u8, stored: &[u8]) -> Result<Vec<u8>> {
    decompress_with(compression, stored, None)
}

/// [`decompress`] for an archive that may carry trained dictionaries (planet5,
/// [`COMPRESSION_ZSTD_DICT`]): `dicts` is that archive's section.
pub fn decompress_with(
    compression: u8,
    stored: &[u8],
    dicts: Option<&crate::mamaps::zdict::ZDicts>,
) -> Result<Vec<u8>> {
    match compression {
        COMPRESSION_ZSTD_DICT => {
            let Some(dicts) = dicts else {
                return err("a dictionary-zstd .mamaps body was read without its dictionaries");
            };
            let raw_len = Body::raw_len(stored)? as usize;
            let header_len = crate::mamaps::body::BODY_HEADER_LEN;
            if raw_len < header_len {
                return err("a .mamaps body frame declares a length shorter than a body header");
            }
            let mut out = vec![0u8; raw_len];
            out[..header_len].copy_from_slice(&stored[..header_len]);
            let n = dicts.decode_into(&stored[header_len..], &mut out[header_len..])?;
            if header_len + n != raw_len {
                return err(format!(
                    "a .mamaps body declared {raw_len} bytes but decoded to {}",
                    header_len + n
                ));
            }
            Ok(out)
        }
        COMPRESSION_NONE => Ok(stored.to_vec()),
        COMPRESSION_DEFLATE => {
            let raw_len = Body::raw_len(stored)? as usize;
            if raw_len < crate::mamaps::body::BODY_HEADER_LEN {
                return err("a .mamaps body frame declares a length shorter than a body header");
            }
            let mut out = vec![0u8; raw_len];
            // The body header is stored uncompressed ahead of the frame, so the frame itself is
            // everything after it and inflates to `raw_len` less that header.
            let header_len = crate::mamaps::body::BODY_HEADER_LEN;
            out[..header_len].copy_from_slice(&stored[..header_len]);
            let written = miniz_oxide::inflate::decompress_slice_iter_to_slice(
                &mut out[header_len..],
                std::iter::once(&stored[header_len..]),
                // Raw DEFLATE, matching what the writer emits: no zlib header, so no adler32
                // either.
                false,
                false,
            )
            .map_err(|e| Error(format!("a .mamaps body will not inflate: {e:?}")))?;
            if written != raw_len - header_len {
                return err(format!(
                    "a .mamaps body inflated to {} bytes, not the {} it declares",
                    written + header_len,
                    raw_len,
                ));
            }
            Ok(out)
        }
        COMPRESSION_ZSTD => {
            let raw_len = Body::raw_len(stored)? as usize;
            if raw_len < crate::mamaps::body::BODY_HEADER_LEN {
                return err("a .mamaps body frame declares a length shorter than a body header");
            }
            let mut out = vec![0u8; raw_len];
            let header_len = crate::mamaps::body::BODY_HEADER_LEN;
            out[..header_len].copy_from_slice(&stored[..header_len]);
            // Same framing as DEFLATE (raw frame behind the uncompressed
            // header), different codec. `ruzstd` is decode-only pure Rust.
            let mut decoder = ruzstd::decoding::StreamingDecoder::new(&stored[header_len..])
                .map_err(|e| Error(format!("a .mamaps zstd body will not decode: {e:?}")))?;
            let mut at = header_len;
            loop {
                if at >= raw_len {
                    break;
                }
                let n = decoder
                    .read(&mut out[at..])
                    .map_err(|e| Error(format!("a .mamaps zstd body will not decode: {e:?}")))?;
                if n == 0 {
                    break;
                }
                at += n;
            }
            if at != raw_len {
                return err(format!(
                    "a .mamaps zstd body decoded to {} bytes, not the {} it declares",
                    at, raw_len,
                ));
            }
            Ok(out)
        }
        other => err(format!("unknown .mamaps compression {other}")),
    }
}

/// The header, dictionary and root index of a file already in memory.
///
/// What `mamaps_dump` and the writer's own tests open with, so neither needs a `RangeReader`.
pub fn open_prefix(bytes: &[u8]) -> Result<(Header, Dictionary, Vec<RootEntry>)> {
    let header = Header::parse(bytes)?;
    if bytes.len() as u64 != header.file_len {
        return err(format!(
            "a .mamaps file declares {} bytes but is {}",
            header.file_len,
            bytes.len(),
        ));
    }
    let at = |offset: u64, len: u64| &bytes[offset as usize..(offset + len) as usize];
    let dictionary = Dictionary::parse(at(header.dict_offset, header.dict_len as u64))?;
    let root = index::parse_root(at(header.root_offset, header.root_len as u64))?;
    Ok((header, dictionary, root))
}

/// Every stored tile of a file already in memory, as `(tile_id, run_length, body bytes)`.
///
/// One entry per *stored body*, not per addressed tile, so a caller can see the dedup rather than
/// having it expanded away. Ascending by tile id.
pub fn read_all(bytes: &[u8]) -> Result<Vec<(u64, u32, Vec<u8>)>> {
    let (header, dictionary, root) = open_prefix(bytes)?;
    dictionary.check_matches_schema()?;
    let zdicts = zdicts_in(&header, bytes)?;
    let mut out = Vec::new();
    for entry in &root {
        let raw_len = entry.leaf_entry_count as usize * index::LEAF_ENTRY_LEN;
        let stored_len = if entry.stored_len == 0 { raw_len } else { entry.stored_len as usize };
        let at = (header.leaf_offset + entry.leaf_offset) as usize;
        if at + stored_len > bytes.len() {
            return err("a .mamaps root entry runs past the file");
        }
        let raw = if entry.stored_len == 0 {
            bytes[at..at + stored_len].to_vec()
        } else {
            index::decompress_leaf(&bytes[at..at + stored_len], raw_len)?
        };
        for tile in index::parse_leaf(&raw)? {
            let start = (header.data_offset + entry.base_data_offset + tile.offset_delta as u64)
                as usize;
            let end = start + tile.length as usize;
            if end > bytes.len() {
                return err("a .mamaps leaf entry runs past the file");
            }
            out.push((
                entry.base_tile_id + tile.tile_id_lo as u64,
                tile.run_length,
                decompress_with(header.compression, &bytes[start..end], zdicts.as_ref())?,
            ));
        }
    }
    if out.windows(2).any(|pair| pair[1].0 <= pair[0].0) {
        return err("a .mamaps archive's tiles are not in ascending id order");
    }
    Ok(out)
}

/// A sanity bound so a reader can reject a prefix that is not a `.mamaps` file at all before
/// allocating anything, without depending on the reader's own constant.
pub const MIN_FILE_LEN: usize = HEADER_LEN;

/// The dictionary section of an archive held whole in memory, or `None` when its bodies use none.
pub fn zdicts_in(header: &Header, bytes: &[u8]) -> Result<Option<crate::mamaps::zdict::ZDicts>> {
    if header.compression != COMPRESSION_ZSTD_DICT {
        return Ok(None);
    }
    let (start, end) = zdict_extent(header)?;
    if end as usize > bytes.len() {
        return err("a .mamaps dictionary section runs past the file");
    }
    Ok(Some(crate::mamaps::zdict::ZDicts::parse(&bytes[start as usize..end as usize])?))
}

/// Where a v10 archive's dictionary section lives: from the end of the root index to the start
/// of the leaves (see [`crate::mamaps::zdict`]).
pub fn zdict_extent(header: &Header) -> Result<(u64, u64)> {
    let start = header.root_offset + header.root_len as u64;
    let end = header.leaf_offset;
    if end < start || end - start > crate::mamaps::zdict::ZDICT_MAX_BYTES {
        return err("a .mamaps dictionary section has an impossible extent");
    }
    Ok((start, end))
}
