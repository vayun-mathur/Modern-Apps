use super::writer::StreamWriter;
use crate::mamaps::dict;
use crate::mamaps::header::{
    COMPRESSION_DEFLATE, COMPRESSION_NONE, FLAG_BODIES_COMPRESSED, FLAG_LEAVES_COMPRESSED,
    FLAG_RINGS_VALIDATED, FLAG_RUN_LENGTH_PRESENT, HEADER_LEN, Header,
};
use crate::mamaps::index::{self};
use crate::proto::{Result, err};
use crate::stream::OPEN_PREFIX_BYTES;

impl StreamWriter {
    /// Everything ahead of the data section: header, dictionary, root, leaves, in that order.
    ///
    /// Section order on disk is header, dictionary, root, leaves, data — but every one of them is
    /// located by a header field, so a later version may reorder them freely.
    ///
    /// Returned parsed as well as serialized, because every check `Header::parse` makes is a check a
    /// reader will make on open, and finding out then means finding out on a device. It is checked
    /// here, before either finish emits a byte.
    pub(crate) fn prefix(&self) -> Result<(Header, Vec<u8>)> {
        if self.entries.is_empty() {
            return err("a .mamaps archive needs at least one tile");
        }
        let dictionary = dict::Dictionary::schema().serialize();
        let mut capacity = self.options.leaf_entry_capacity;

        // Grow the leaf size until the root fits the opening prefix, which is the same escape the
        // PMTiles writer takes and for the same reason. Twelve doublings takes 4096 to 16 M
        // entries per leaf; a root that still does not fit is a build failure, never a silent
        // third round trip charged to every reader forever.
        let (mut root, leaves) = loop {
            // A `None` is a leaf whose tile-id span will not fit a `u32`. A wider leaf spans more
            // ids, not fewer, so growing the capacity does not fix that one -- it runs out at the
            // cap below and reports. Kept distinct from the root-too-large case because the two want
            // opposite things from the capacity and a future fix has to choose between them.
            //
            // The root length is what the budget accounts: the root stays raw at 32 bytes an
            // entry whatever the leaves store, so leaf compression never moves this loop.
            if let Some(split) = self.partition(capacity)? {
                let root_len = split.0.len() * index::ROOT_ENTRY_LEN;
                if HEADER_LEN + dictionary.len() + root_len <= OPEN_PREFIX_BYTES as usize {
                    break (split.0, split.1);
                }
            }
            if capacity >= 1 << 24 {
                return err(format!(
                    "a .mamaps root index will not fit the {OPEN_PREFIX_BYTES} byte opening \
                     prefix even at {capacity} entries per leaf",
                ));
            }
            capacity *= 2;
        };

        let mut flags = 0u16;
        if self.options.compress {
            flags |= FLAG_BODIES_COMPRESSED;
        }
        if self.runs_used {
            flags |= FLAG_RUN_LENGTH_PRESENT;
        }
        if self.options.rings_validated {
            flags |= FLAG_RINGS_VALIDATED;
        }
        // One raw-DEFLATE frame per leaf, decided here — after the budget loop, exactly once
        // per finish — so `partition` stays about boundaries and the frame bytes are a pure
        // function of the leaf: the same leaf always stores the same bytes, which is what keeps
        // `finish` and `finish_to_path` two ways of emitting one archive. A frame that would
        // grow its leaf is stored raw instead (`stored_len` zero), so this never costs a byte;
        // with `compress_leaves` off every leaf stores raw and the file is the legacy layout
        // bit for bit.
        //
        // The frames are built on `options.threads` threads (a group of leaves at a time, each
        // thread a contiguous run) and laid down in leaf order: same frames, same order, same
        // bytes. Serially this was level-9 DEFLATE over every leaf on one thread -- ~4.3 GB of
        // entries on a planet, minutes of a 192-core box at one core.
        let compress_leaves = self.options.compress_leaves;
        let stored_for = |leaf: &Vec<index::LeafEntry>| -> Vec<u8> {
            let raw = index::serialize_leaf(leaf);
            if compress_leaves {
                let frame = index::compress_leaf(&raw);
                if frame.len() < raw.len() { frame } else { raw }
            } else {
                raw
            }
        };
        let threads = match self.options.threads {
            0 => std::thread::available_parallelism().map_or(1, |n| n.get()),
            n => n,
        };
        const LEAF_GROUP: usize = 2048;
        let mut stored_leaves: Vec<Vec<u8>> = Vec::with_capacity(leaves.len());
        for group in leaves.chunks(LEAF_GROUP) {
            if threads <= 1 || group.len() <= 1 {
                stored_leaves.extend(group.iter().map(&stored_for));
                continue;
            }
            let per = group.len().div_ceil(threads.min(group.len()));
            let parts: Vec<Vec<Vec<u8>>> = std::thread::scope(|scope| {
                let handles: Vec<_> = group
                    .chunks(per)
                    .map(|run| scope.spawn(|| run.iter().map(&stored_for).collect::<Vec<_>>()))
                    .collect();
                handles.into_iter().map(|h| h.join().expect("a leaf compression thread")).collect()
            });
            stored_leaves.extend(parts.into_iter().flatten());
        }
        let mut leaf_bytes = Vec::new();
        for ((entry, leaf), stored) in root.iter_mut().zip(leaves.iter()).zip(stored_leaves) {
            entry.leaf_offset = leaf_bytes.len() as u64;
            entry.stored_len = if stored.len() == leaf.len() * index::LEAF_ENTRY_LEN {
                0
            } else {
                u32::try_from(stored.len()).map_err(|_| {
                    crate::proto::Error("a .mamaps leaf frame is larger than 4 GiB".to_string())
                })?
            };
            leaf_bytes.extend_from_slice(&stored);
        }
        let any_compressed = root.iter().any(|e| e.stored_len != 0);
        if any_compressed {
            flags |= FLAG_LEAVES_COMPRESSED;
        }
        let leaf_len = leaf_bytes.len() as u64;
        // Planet scale: ~268M bodies ×16 B ≈4.29 GB > u32::MAX (1257 B overflow
        // in the failed run). Common path (NA ~418 MB, CA ~tens of MB) stays
        // flag 0 + u32 + 0 — byte-identical to v1. Extended path uses
        // FLAG_LEAF_LEN_64 and the 72..80 u64 slot (was u32+reserved).
        let needs_leaf64 = leaf_len > u32::MAX as u64;
        if needs_leaf64 {
            flags |= crate::mamaps::header::FLAG_LEAF_LEN_64;
        }
        let root_bytes = index::serialize_root(&root);
        // The dictionary starts where the 128-byte v8 header ends.
        let dict_offset = HEADER_LEN as u64;
        let root_offset = dict_offset + dictionary.len() as u64;
        let zdict_bytes: &[u8] = self.options.zdicts.as_deref().unwrap_or(&[]);
        let leaf_offset = root_offset + root_bytes.len() as u64 + zdict_bytes.len() as u64;
        let data_offset = leaf_offset + leaf_bytes.len() as u64;
        let data_len = self.data.len();

        let header = Header {
            flags,
            compression: if !self.options.compress {
                COMPRESSION_NONE
            } else if self.options.zdicts.is_some() {
                crate::mamaps::header::COMPRESSION_ZSTD_DICT
            } else if self.options.zstd {
                crate::mamaps::header::COMPRESSION_ZSTD
            } else {
                COMPRESSION_DEFLATE
            },
            layer_count: dict::LAYERS.len() as u8,
            min_zoom: self.options.min_zoom,
            max_zoom: self.options.max_zoom,
            build_id: self.header_build_id(),
            // The file is exactly header/dictionary/root/leaves/data.
            file_len: data_offset + data_len,
            dict_offset,
            dict_len: dictionary.len() as u32,
            leaf_entry_capacity: capacity,
            root_offset,
            root_len: root_bytes.len() as u32,
            leaf_count: root.len() as u32,
            leaf_offset,
            leaf_len,
            data_offset,
            data_len,
            tiles_addressed: self.tiles_addressed,
            bodies_written: self.distinct,
            min_lon_e7: self.options.min_lon_e7,
            min_lat_e7: self.options.min_lat_e7,
            max_lon_e7: self.options.max_lon_e7,
            max_lat_e7: self.options.max_lat_e7,
        };

        let mut prefix = Vec::with_capacity(data_offset as usize);
        prefix.extend_from_slice(&header.serialize());
        prefix.extend_from_slice(&dictionary);
        prefix.extend_from_slice(&root_bytes);
        prefix.extend_from_slice(zdict_bytes);
        prefix.extend_from_slice(&leaf_bytes);
        debug_assert_eq!(prefix.len() as u64, data_offset);
        Ok((Header::parse(&prefix)?, prefix))
    }
}
