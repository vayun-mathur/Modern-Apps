impl WayReader {
#[cfg_attr(not(test), allow(dead_code))]
    pub fn open(path: &Path) -> Result<WayReader> {
        let file = File::open(path)
            .map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
        Ok(WayReader {
            inner: WayIn::File(BufReader::with_capacity(1 << 20, file)),
            last_id: 0,
            seen: 0,
        })
    }

    /// Anonymous twin of [`open`](Self::open): reads the sealed store from
    /// [`WaySink::finish_anon`], shared by refcount. `path` names nothing —
    /// it only rides along for error messages.
    pub fn open_anon(
        path: &Path,
        store: std::sync::Arc<tile_build::anon::AnonStore>,
    ) -> Result<WayReader> {
        let _ = path;
        Ok(WayReader {
            inner: WayIn::Anon { store, at: 0, buf: Vec::new(), used: 0 },
            last_id: 0,
            seen: 0,
        })
    }

    /// Open whichever backend the sink sealed: the shared anon store when present, else the file
    /// at `path`. One helper so the four stage-A readers (two ref collectors, lanefill scan,
    /// materialise) agree.
    pub fn open_either(
        path: &Path,
        store: &Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    ) -> Result<WayReader> {
        match store {
            Some(anon) => Self::open_anon(path, std::sync::Arc::clone(anon)),
            None => Self::open(path),
        }
    }

    /// The next way's id, class, display name, lane count, per-lane turn masks and carriageway,
    /// with its node refs written into `refs`.
    ///
    /// `refs` belongs to the caller and is cleared here, so one allocation serves the whole file.
    /// Returning a fresh `Vec` instead would be one allocation per classified way, several million
    /// of them, for a buffer whose contents are dead by the next call — the same reasoning as
    /// [`WaySink::record`]. The name and masks are returned owned: only a minority of ways carry
    /// them, so cloning costs nothing next to the geometry.
    ///
    /// A record that ends mid-varint or claims more refs than the file holds is a corrupt spill
    /// rather than a way to skip: this file was written by [`WaySink`] moments ago in the same
    /// process, so anything unreadable means it is not the file we wrote.
    #[allow(clippy::type_complexity)]
    pub fn next(
        &mut self,
        refs: &mut Vec<i64>,
    ) -> Result<
        Option<(
            i64,
            Class,
            Option<String>,
            u8,
            Vec<u16>,
            Vec<u16>,
            Carriageway,
            Option<BuildingAttrs>,
        )>,
    > {
        refs.clear();
        let Some(delta) = self.uvarint_or_end()? else {
            return Ok(None);
        };
        let id = self.last_id.wrapping_add(zigzag(delta));
        self.last_id = id;
        self.seen += 1;
        let class = unpack(self.uvarint()?);
        let count = self.uvarint()?;
        // No `reserve` on `count`: it comes off disk, and a corrupt one would be an allocation
        // request rather than the error this returns for every other kind of damage. The buffer is
        // the caller's and is reused across the whole file, so after the first few records it is
        // already as large as any way needs.
        let mut previous: i64 = 0;
        for _ in 0..count {
            previous = previous.wrapping_add(zigzag(self.uvarint()?));
            refs.push(previous);
        }
        // The name: a byte length, then that many UTF-8 bytes. Zero length is nameless, which is
        // nearly every way; a corrupt length is the same error as a corrupt ref count.
        let name_len = self.uvarint()? as usize;
        let name = if name_len == 0 {
            None
        } else {
            if name_len > 1 << 20 {
                return err(format!(
                    "a ways spill way claims a {name_len}-byte name, which is corruption"
                ));
            }
            let mut bytes = vec![0u8; name_len];
            self.read_exact_into(&mut bytes)?;
            Some(
                String::from_utf8(bytes)
                    .map_err(|_| Error("a ways spill way name is not UTF-8".to_string()))?,
            )
        };
        // The lane count, then the two turn-mask lists (forward, backward): a count then each u16.
        let lane_bits = self.uvarint()?;
        let lane_count = u8::try_from(lane_bits)
            .map_err(|_| Error("a ways spill way lane count does not fit a byte".to_string()))?;
        let mut read_masks = || -> Result<Vec<u16>> {
            let n = self.uvarint()? as usize;
            if n > tilecodec::mamaps::body::FEATURE_RECORD_LEN * 1024 {
                return err(format!("a ways spill way claims {n} turn masks, which is corruption"));
            }
            let mut v = Vec::with_capacity(n);
            for _ in 0..n {
                v.push(u16::try_from(self.uvarint()?).map_err(|_| {
                    Error("a ways spill turn mask does not fit a u16".to_string())
                })?);
            }
            Ok(v)
        };
        let turn_fwd = read_masks()?;
        let turn_bwd = read_masks()?;
        let carriageway = unpack_carriageway(self.uvarint()?);
        // The building attributes: a presence byte, then three packed words when set.
        let building = if self.uvarint()? != 0 {
            let a = self.uvarint()?;
            let b = self.uvarint()?;
            let c = self.uvarint()?;
            Some(unpack_building(a, b, c))
        } else {
            None
        };
        Ok(Some((id, class, name, lane_count, turn_fwd, turn_bwd, carriageway, building)))
    }

    /// One varint, or `None` if the file ended cleanly on a record boundary.
    fn uvarint_or_end(&mut self) -> Result<Option<u64>> {
        let mut value: u64 = 0;
        let mut shift: u32 = 0;
        loop {
            let buf = self.fill_buf()?;
            if buf.is_empty() {
                if shift == 0 {
                    return Ok(None);
                }
                return err(format!("the ways spill ends mid-varint after {} way(s)", self.seen));
            }
            // Consumed in one go per buffer fill rather than a byte at a time: a varint almost
            // always lies wholly inside the buffer, and there are hundreds of millions of them.
            let mut used = 0usize;
            for &byte in buf {
                used += 1;
                if shift >= 64 {
                    return err("a ways spill varint is longer than 64 bits".to_string());
                }
                value |= ((byte & 0x7f) as u64) << shift;
                shift += 7;
                if byte & 0x80 == 0 {
                    self.consume(used);
                    return Ok(Some(value));
                }
            }
            self.consume(used);
        }
    }

    /// Peek at the buffered bytes without consuming, filling from the backend first.
    fn fill_buf(&mut self) -> Result<&[u8]> {
        match &mut self.inner {
            WayIn::File(f) => f
                .fill_buf()
                .map_err(|e| Error(format!("cannot read the ways spill: {e}"))),
            WayIn::Anon { store, at, buf, used } => {
                if *used >= buf.len() {
                    // Refill: read the next window from the store. 1MB like the
                    // file backend's buffer, so the varint loop above behaves
                    // identically.
                    const WINDOW: u64 = 1 << 20;
                    let remaining = store.len().saturating_sub(*at);
                    if remaining == 0 {
                        // Empty view: return what's left (nothing).
                        buf.clear();
                        *used = 0;
                        return Ok(&[]);
                    }
                    let take = remaining.min(WINDOW) as usize;
                    buf.resize(take, 0);
                    store.read_at(*at, &mut buf[..]).map_err(|e| {
                        Error(format!("cannot read the anon ways spill: {e}"))
                    })?;
                    *at += take as u64;
                    *used = 0;
                }
                Ok(&buf[*used..])
            }
            WayIn::Slice { buf, used } => Ok(&buf[*used..]),
        }
    }

    fn consume(&mut self, n: usize) {
        match &mut self.inner {
            WayIn::File(f) => f.consume(n),
            WayIn::Anon { used, .. } => *used += n,
            WayIn::Slice { used, .. } => *used += n,
        }
    }

    fn uvarint(&mut self) -> Result<u64> {
        match self.uvarint_or_end()? {
            Some(value) => Ok(value),
            None => err(format!("the ways spill ends mid-record after {} way(s)", self.seen)),
        }
    }

    /// Exactly `bytes.len()` bytes from the cursor, for the name field above.
    fn read_exact_into(&mut self, bytes: &mut [u8]) -> Result<()> {
        let mut at = 0usize;
        while at < bytes.len() {
            let buf = self.fill_buf()?;
            if buf.is_empty() {
                return err(format!(
                    "the ways spill ends mid-name after {} way(s)",
                    self.seen
                ));
            }
            let take = (bytes.len() - at).min(buf.len());
            bytes[at..at + take].copy_from_slice(&buf[..take]);
            self.consume(take);
            at += take;
        }
        Ok(())
    }
}

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

/// Zigzagged, so a gap that happens to run backwards costs one bit rather than ten bytes.
/// [`zigzag`] is the decoder, already in `osm_ingest` because the PBF itself is encoded this way.
fn put_svarint(out: &mut Vec<u8>, value: i64) {
    put_uvarint(out, ((value << 1) ^ (value >> 63)) as u64);
}
