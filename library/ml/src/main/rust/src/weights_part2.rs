impl<'a> Reader<'a> {
    /// A reader over `table` and `data`, which must describe the same file.
    pub fn new(table: &'a Offsets, data: &'a dyn Blob) -> Reader<'a> {
        Reader { table, data }
    }

    /// File identity for host-side process-constant caches.
    ///
    /// The table's address plus the data section's length: every flow that
    /// gathers opens its EMBED file once and holds it for the process, so a
    /// cache keyed on this serves the same file's bytes every step. The
    /// combine cache pairs it with the tensors' descriptors (see
    /// [`Reader::describe`]), so a second file at a reused address with a
    /// different layout still misses.
    pub fn file_id(&self) -> (usize, u64) {
        (self.table as *const _ as usize, self.data.data_len())
    }

    /// Tensor `index`'s descriptor, checked against `dims`, without reading bytes.
    ///
    /// The table is in memory, so this is a lookup, not I/O: cache keys are
    /// built from it on every step while the bulk bytes are read only on a miss.
    pub fn describe(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        self.table.shaped(index, dims)
    }

    /// Tensor `index` as `f32`, in the file's order, checked against `dims`.
    ///
    /// fp16 only: every tensor the host reads is fp16, and an int8 one would need its companion
    /// scale, which is a caller's decision rather than something to guess at here.
    pub fn fp16(&self, index: usize, dims: &[u32]) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype.is_quantised() {
            return Err(format!("tensor {index} is {:?}, and the host reads fp16", found.dtype));
        }
        let mut bytes = vec![0u8; (found.len as usize) * 2];
        self.data
            .read_at(found.offset as u64, &mut bytes)
            .map_err(|e| format!("tensor {index}: {e}"))?;
        Ok(bytes
            .chunks_exact(2)
            .map(|c| f16_to_f32(u16::from_le_bytes([c[0], c[1]])))
            .collect())
    }

    /// One row of an int8 tensor, dequantised by that row's scale.
    ///
    /// The counterpart of [`Reader::fp16`] for a quantised table, and it exists for one caller:
    /// [`crate::nets::nllb`] gathers rows of the tied embedding on the host rather than in a
    /// shader. Doing so removes the need for an int8 `embed.comp`, lets `sqrt(d_model)` and the
    /// sinusoidal position be applied in f32 before anything is rounded, and reads 1 KB per token
    /// instead of uploading a ~250 MiB table a second time.
    ///
    /// A row rather than the whole tensor because the whole tensor is ~250 MiB. `dims[0]` is the
    /// row count and a row is contiguous, which is a property of the `[out, in, 1, 1]` layout
    /// `scripts/ml/maml_convert.py` writes rather than an assumption — and `shaped` checks it.
    pub fn int8_row(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        row: u32,
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype != Dtype::I8 {
            return Err(format!("tensor {index} is {:?}, and this dequantises int8", found.dtype));
        }
        let rows = *dims.first().ok_or("an int8 row read needs a row count")?;
        if row >= rows {
            return Err(format!("row {row} of a {rows}-row tensor {index}"));
        }
        let stride = (found.len / rows) as usize;
        let mut bytes = vec![0u8; stride];
        let at = u64::from(found.offset) + u64::from(row) * stride as u64;
        self.data.read_at(at, &mut bytes).map_err(|e| format!("tensor {index} row {row}: {e}"))?;

        let scale = self.table.shaped(scale_index, &[rows])?;
        if scale.dtype.is_quantised() {
            return Err(format!("tensor {scale_index} is {:?}, and a scale is fp16", scale.dtype));
        }
        let mut half = [0u8; 2];
        self.data
            .read_at(u64::from(scale.offset) + u64::from(row) * 2, &mut half)
            .map_err(|e| format!("tensor {scale_index} row {row}: {e}"))?;
        let scale = f16_to_f32(u16::from_le_bytes(half));
        Ok(bytes.iter().map(|&b| f32::from(b as i8) * scale).collect())
    }

    /// One row of a Q2_K tensor, dequantised by that row's superblock `(d, dmin)` pairs.
    ///
    /// The Q2_K counterpart of [`Reader::int8_row`], and it exists for one caller:
    /// [`crate::nets::madlad`] gathers rows of the untied input table on the host rather
    /// than in a shader. Doing so removes the need for a Q2_K `embed.comp` and reads 1 KB
    /// per token instead of uploading a ~250 MiB table a second time.
    ///
    /// A row rather than the whole tensor because the whole tensor is ~250 MiB. `dims[0]` is
    /// the row count and a row is contiguous superblocks, which is a property of the flat
    /// layout `scripts/ml/maml_convert.py` writes rather than an assumption — and `shaped`
    /// checks it. The exact `to_float` transcription: `y = d*d*(q*lo) - dmin*d*hi` per
    /// 16-tap lane, so this and the shader are the same function by construction.
    pub fn q2k_row(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        row: u32,
    ) -> Result<Vec<f32>, String> {
        use crate::weights::{Q2K_BLOCK, Q2K_BYTES};
        let found = self.table.shaped(index, dims)?;
        if found.dtype != Dtype::Q2K {
            return Err(format!("tensor {index} is {:?}, and this dequantises Q2_K", found.dtype));
        }
        let rows = *dims.first().ok_or("a Q2_K row read needs a row count")?;
        if row >= rows {
            return Err(format!("row {row} of a {rows}-row tensor {index}"));
        }
        let taps = (found.len / rows) as usize;
        if taps % (Q2K_BLOCK as usize) != 0 {
            return Err(format!("tensor {index} has {taps} taps a row, not block-divisible"));
        }
        let blocks = taps / Q2K_BLOCK as usize;
        let stride = blocks * Q2K_BYTES as usize;
        let mut payload = vec![0u8; stride];
        let at = u64::from(found.offset) + u64::from(row) * stride as u64;
        self.data.read_at(at, &mut payload).map_err(|e| format!("tensor {index} row {row}: {e}"))?;

        let scale = self.table.shaped(scale_index, &[rows * blocks as u32 * 2])?;
        if scale.dtype.is_quantised() {
            return Err(format!("tensor {scale_index} is {:?}, and a scale is fp16", scale.dtype));
        }
        let mut scale_bytes = vec![0u8; blocks * 4];
        let sat = u64::from(scale.offset) + u64::from(row) * (blocks as u64) * 4;
        self.data
            .read_at(sat, &mut scale_bytes)
            .map_err(|e| format!("tensor {scale_index} row {row}: {e}"))?;

        let mut out = Vec::with_capacity(taps);
        for (b, chunk) in payload.chunks_exact(Q2K_BYTES as usize).enumerate() {
            let d = f16_to_f32(u16::from_le_bytes([chunk[80], chunk[81]]));
            let m = f16_to_f32(u16::from_le_bytes([chunk[82], chunk[83]]));
            // Prefer the table's pair (same values, and it is what the shader reads);
            // fall back is unnecessary — the table is checked above, so read it.
            let td = f16_to_f32(u16::from_le_bytes([
                scale_bytes[b * 4],
                scale_bytes[b * 4 + 1],
            ]));
            let tm = f16_to_f32(u16::from_le_bytes([
                scale_bytes[b * 4 + 2],
                scale_bytes[b * 4 + 3],
            ]));
            let (dall, dmin) = (td * td, tm * td);
            let _ = (d, m);
            let scales = &chunk[..16];
            let qs = &chunk[16..80];
            for half in 0..2usize {
                let hqs = &qs[half * 32..half * 32 + 32];
                let mut shift = 0u32;
                for lane in 0..4usize {
                    let sc = scales[half * 8 + lane * 2];
                    let lo = f32::from(sc & 15);
                    let hi = f32::from(sc >> 4);
                    for &hq in hqs.iter().take(16) {
                        let q = f32::from((hq >> shift) & 3);
                        out.push(dall * q * lo - dmin * hi);
                    }
                    let sc = scales[half * 8 + lane * 2 + 1];
                    let lo = f32::from(sc & 15);
                    let hi = f32::from(sc >> 4);
                    for l in 0..16usize {
                        let q = f32::from((hqs[16 + l] >> shift) & 3);
                        out.push(dall * q * lo - dmin * hi);
                    }
                    shift += 2;
                }
            }
        }
        Ok(out)
    }

    /// A whole int8 tensor, dequantised row by row.
    ///
    /// The bulk counterpart of [`Reader::int8_row`] for the one caller that needs every
    /// row at once: Gemma 4's shared per-layer projection is `[8960, 1536]`, applied on
    /// the host in `nets::gemma4::gather`, and reading it a row at a time would cost
    /// two file reads per row - ~18k syscalls a token. This reads the payload and the
    /// per-row fp16 scales each in one span.
    pub fn int8_all(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype != Dtype::I8 {
            return Err(format!("tensor {index} is {:?}, and this dequantises int8", found.dtype));
        }
        let rows = *dims.first().ok_or("an int8 bulk read needs a row count")?;
        let stride = (found.len / rows) as usize;
        let mut bytes = vec![0u8; (found.len) as usize];
        self.data
            .read_at(u64::from(found.offset), &mut bytes)
            .map_err(|e| format!("tensor {index}: {e}"))?;
        let scale = self.table.shaped(scale_index, &[rows])?;
        if scale.dtype.is_quantised() {
            return Err(format!("tensor {scale_index} is {:?}, and a scale is fp16", scale.dtype));
        }
        let mut scale_bytes = vec![0u8; (rows as usize) * 2];
        self.data
            .read_at(u64::from(scale.offset), &mut scale_bytes)
            .map_err(|e| format!("tensor {scale_index}: {e}"))?;
        let scales: Vec<f32> = scale_bytes
            .chunks_exact(2)
            .map(|pair| f16_to_f32(u16::from_le_bytes([pair[0], pair[1]])))
            .collect();
        Ok(bytes
            .chunks_exact(stride)
            .zip(scales.iter())
            .flat_map(|(row, &s)| row.iter().map(move |&b| f32::from(b as i8) * s))
            .collect())
    }

    /// One row of an int4 tensor, dequantised by that row's **block** scales.
    ///
    /// The int4 counterpart of [`Reader::int8_row`], and the difference is the scale: an int8 row
    /// has one, an int4 row has `ceil(stride / I4_BLOCK)` of them and each covers its own span of
    /// the row. Reading the first and applying it to the whole row would produce numbers of
    /// entirely the right magnitude for the first 32 columns and nonsense after.
    ///
    /// Exists for the same caller [`Reader::int8_row`] does: Gemma 4's embedding is two tables of
    /// 262144 rows, and a decode step needs one row of each. Binding 4.7 GB of table to gather
    /// 1536 values is not a trade worth making.
    ///
    /// # Odd strides
    ///
    /// A row starts on a **byte** boundary only if `stride` is even. Every table this reads has
    /// an even stride - 1536 and 8960 - so rather than carry a nibble offset through the loop,
    /// an odd stride is refused. A silent half-byte skew would be far harder to find later than
    /// this error is now.
    pub fn int4_row(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        row: u32,
    ) -> Result<Vec<f32>, String> {
        self.int4_rows(index, scale_index, dims, row, 1)
    }

    /// A contiguous block of rows of an fp16 tensor.
    ///
    /// The bulk counterpart of [`Reader::fp16`] for the tied head: logits are
    /// hidden @ H^T over all 262144 classes of the raw-scale head table, which
    /// no row-at-a-time loop can serve. Callers split the vocabulary (see
    /// `gemma4::HEAD_SPLITS`) so no single call holds more than a quarter of
    /// the dequantised table (~400 MB fp32).
    pub fn fp16_rows(
        &self,
        index: usize,
        dims: &[u32],
        start: u32,
        count: u32,
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype.is_quantised() {
            return Err(format!("tensor {index} is {:?}, and the host reads fp16", found.dtype));
        }
        let rows = *dims.first().ok_or("an fp16 block read needs a row count")?;
        if start + count > rows {
            return Err(format!("rows {start}..{} of a {rows}-row tensor {index}", start + count));
        }
        let stride: usize = (found.len as usize / rows as usize) * 2;
        let mut bytes = vec![0u8; (count as usize) * stride];
        let at = u64::from(found.offset) + u64::from(start) * (stride as u64);
        self.data.read_at(at, &mut bytes).map_err(|e| format!("tensor {index} rows: {e}"))?;
        Ok(bytes
            .chunks_exact(2)
            .map(|c| f16_to_f32(u16::from_le_bytes([c[0], c[1]])))
            .collect())
    }

    /// A contiguous block of rows of an int4 tensor, dequantised.
    ///
    /// The bulk counterpart of [`Reader::int4_row`] for the tied head: logits are
    /// hidden @ E^T over all 262144 classes, which no row-at-a-time loop can serve.
    /// Callers split the vocabulary (see `gemma4::HEAD_SPLITS`) so no single call
    /// holds more than a quarter of the dequantised table (~400 MB fp32).
    pub fn int4_rows(
        &self,
        index: usize,
        scale_index: usize,
        dims: &[u32],
        start: u32,
        count: u32,
    ) -> Result<Vec<f32>, String> {
        let found = self.table.shaped(index, dims)?;
        if found.dtype != Dtype::I4 {
            return Err(format!("tensor {index} is {:?}, and this dequantises int4", found.dtype));
        }
        let rows = *dims.first().ok_or("an int4 block read needs a row count")?;
        if start + count > rows {
            return Err(format!("rows {start}..{} of a {rows}-row tensor {index}", start + count));
        }
        let stride = (found.len / rows) as usize;
        if stride % (2) != 0 {
            return Err(format!(
                "tensor {index} has a {stride}-element row, which does not start on a byte"
            ));
        }
        let blocks = (stride as u32).div_ceil(I4_BLOCK);
        let mut packed = vec![0u8; (count as usize) * (stride / 2)];
        let at = u64::from(found.offset) + u64::from(start) * (stride as u64 / 2);
        self.data.read_at(at, &mut packed).map_err(|e| format!("tensor {index} rows: {e}"))?;
        let scale = self.table.shaped(scale_index, &[rows, blocks])?;
        if scale.dtype.is_quantised() {
            return Err(format!("tensor {scale_index} is {:?}, and a scale is fp16", scale.dtype));
        }
        let mut scale_bytes = vec![0u8; (count as usize) * (blocks as usize) * 2];
        let sat = u64::from(scale.offset) + u64::from(start) * u64::from(blocks) * 2;
        self.data.read_at(sat, &mut scale_bytes).map_err(|e| format!("tensor {scale_index}: {e}"))?;
        let mut out = Vec::with_capacity((count as usize) * stride);
        for r in 0..count as usize {
            let sbase = r * blocks as usize;
            let scales: Vec<f32> = scale_bytes[sbase * 2..(sbase + blocks as usize) * 2]
                .chunks_exact(2)
                .map(|pair| f16_to_f32(u16::from_le_bytes([pair[0], pair[1]])))
                .collect();
            let row = &packed[r * stride / 2..(r + 1) * stride / 2];
            for &byte in row.iter() {
                for nibble in [byte & 0x0f, byte >> 4] {
                    let code = if nibble >= 8 { i32::from(nibble) - 16 } else { i32::from(nibble) };
                    let column = out.len() - r * stride;
                    out.push(code as f32 * scales[column / I4_BLOCK as usize]);
                }
            }
            debug_assert!(out.len() == (r + 1) * stride, "row {r} overran");
        }
        Ok(out)
    }
}

/// Fill `buf` from `offset` without moving the file's cursor.
///
/// The same helper as `library/tilecodec`'s `pmtiles::read_exact_at`, and copied rather than shared
/// because these two crates have no dependency between them and this is six lines. Both platforms
/// expose a positional read; neither is guaranteed to return everything at once, hence the loop.
///
/// Positional rather than seek-then-read because the cursor is shared with whatever else holds this
/// descriptor — on Android the descriptor came out of `AssetManager`, and moving its cursor is not
/// this module's business.
fn read_exact_at(file: &File, mut buf: &mut [u8], mut offset: u64) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "the file ended mid-tensor",
            ));
        }
        buf = buf.get_mut(n..).unwrap_or(&mut []);
        offset += n as u64;
    }
    Ok(())
}

/// One tensor for [`write_mixed`]: fp16 values, or the int8 payload of a quantised kernel.
#[cfg(test)]
pub(crate) enum Fixture {
    /// fp16, the format every tensor but a quantised kernel is in.
    F16(Vec<u32>, Vec<f32>),
    /// int8, addressed by the shaders as a 32-bit word offset. See [`Tensor::word_offset`].
    I8(Vec<u32>, Vec<i8>),
    /// int4, two per byte, low nibble first. Values outside -8..=7 are a bug in the caller and
    /// are masked rather than clamped, so a fixture that overflows shows up as a wrong number
    /// rather than a silently saturated one.
    I4(Vec<u32>, Vec<i8>),
    /// GGUF Q2_K superblocks, raw: [`Q2K_BYTES`] per [`Q2K_BLOCK`] taps of a `[out, taps]` kernel.
    /// The `(d, dmin)` pairs are a separate fp16 tensor, as in a converted file.
    Q2K(Vec<u32>, Vec<u8>),
}

/// Build a `.maml` blob from a mix of fp16 and int8 tensors, for the fixtures.
///
/// The `write` helper in this module's tests emits fp16 only, and an int8 convolution needs a table
/// where one tensor is int8 and the two after it — its per-channel scale and its bias — are not.
/// Shared rather than hand-rolled per test because the 16-byte alignment and the resulting word
/// offsets are precisely what a second copy would get subtly wrong, and a wrong offset here reads a
/// neighbouring tensor at the right shape.
#[cfg(test)]
pub(crate) fn write_mixed(graph_id: u32, tensors: &[Fixture]) -> Vec<u8> {
    fn f32_to_f16(v: f32) -> u16 {
        let bits = v.to_bits();
        let sign = ((bits >> 16) & 0x8000) as u16;
        let exponent = ((bits >> 23) & 0xFF) as i32 - 127 + 15;
        let mantissa = bits & 0x007F_FFFF;
        if exponent >= 0x1F {
            return sign | 0x7C00;
        }
        if exponent <= 0 {
            return sign;
        }
        sign | ((exponent as u16) << 10) | ((mantissa >> 13) as u16)
    }

    let mut table = Vec::new();
    let mut data: Vec<u8> = Vec::new();
    for tensor in tensors {
        let (dims, dtype, len, bytes) = match tensor {
            Fixture::F16(dims, values) => (
                dims,
                DTYPE_F16,
                values.len() as u32,
                values.iter().flat_map(|&v| f32_to_f16(v).to_le_bytes()).collect::<Vec<u8>>(),
            ),
            Fixture::I8(dims, values) => (
                dims,
                DTYPE_I8,
                values.len() as u32,
                values.iter().map(|&v| v as u8).collect::<Vec<u8>>(),
            ),
            Fixture::I4(dims, values) => (
                dims,
                DTYPE_I4,
                values.len() as u32,
                values
                    .chunks(2)
                    .map(|pair| match pair {
                        [low, high] => (*low as u8 & 0x0f) | ((*high as u8 & 0x0f) << 4),
                        // An odd length leaves the high nibble of the last byte as padding,
                        // which is what `Dtype::bytes`'s `div_ceil` accounts for.
                        [low] => *low as u8 & 0x0f,
                        _ => 0,
                    })
                    .collect::<Vec<u8>>(),
            ),
            Fixture::Q2K(dims, bytes) => {
                let elements: u32 = dims.iter().product();
                (dims, DTYPE_Q2K, elements, bytes.clone())
            }
        };
        while data.len() % (ALIGNMENT as usize) != 0 {
            data.push(0);
        }
        let offset = data.len() as u32;
        data.extend_from_slice(&bytes);
        table.extend_from_slice(&(dims.len() as u32).to_le_bytes());
        for slot in 0..4 {
            table.extend_from_slice(&dims.get(slot).copied().unwrap_or(0).to_le_bytes());
        }
        table.extend_from_slice(&dtype.to_le_bytes());
        table.extend_from_slice(&offset.to_le_bytes());
        table.extend_from_slice(&len.to_le_bytes());
    }

    let mut blob = Vec::new();
    blob.extend_from_slice(&MAGIC);
    blob.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
    blob.extend_from_slice(&graph_id.to_le_bytes());
    blob.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
    blob.extend_from_slice(&[0u8; 32]);
    blob.extend_from_slice(&((HEADER_BYTES + table.len()) as u32).to_le_bytes());
    blob.extend_from_slice(&(data.len() as u32).to_le_bytes());
    blob.extend_from_slice(&[0u8; 8]);
    blob.extend_from_slice(&table);
    blob.extend_from_slice(&data);
    blob
}

fn u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}
