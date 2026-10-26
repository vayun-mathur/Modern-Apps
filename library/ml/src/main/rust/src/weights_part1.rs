impl Offsets {
    /// Tensor `index`, or an error naming the index — which is what a mismatch
    /// between the Rust forward pass and the converter's ordering looks like.
    pub fn tensor(&self, index: usize) -> Result<Tensor, String> {
        self.tensors
            .get(index)
            .copied()
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.tensors.len()))
    }

    /// Tensor `index`, checked against the shape the caller expects.
    ///
    /// The net modules use this for every weight, so a table that is the right
    /// length but the wrong order fails on the first layer whose shape differs
    /// rather than silently convolving with someone else's kernel.
    pub fn shaped(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        let tensor = self.tensor(index)?;
        let got = &tensor.dims[..tensor.rank as usize];
        if got != dims {
            return Err(format!("tensor {index} is {got:?}, the forward pass wants {dims:?}"));
        }
        Ok(tensor)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.tensors.len()
    }

    /// An empty table, for callers that resolve nothing through it.
    ///
    /// [`crate::nets::Builder::finish`] records without a file table — it resolves
    /// offsets, never indices — so it passes this rather than threading an `Option`
    /// through the recording. Anything that actually looks a tensor up gets an
    /// out-of-range error, which is the honest answer for an empty table.
    pub fn empty() -> Offsets {
        Offsets { tensors: Vec::new() }
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.tensors.is_empty()
    }

    /// A table over hand-made tensors, for the graph-section round-trip tests.
    ///
    /// Test-only: the emitter inverts offsets through a real table, and the `Shapes`
    /// stub is not one. The caller owns the shapes; this checks nothing.
    #[cfg(test)]
    pub(crate) fn from_test(tensors: Vec<Tensor>) -> Offsets {
        Offsets { tensors }
    }
}

/// A parsed `.maml` file: the tensor table, and the data section to upload.
#[derive(Debug)]
pub struct Weights {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    table: Offsets,
    data: Vec<u8>,
    /// The version-2 graph section, when the file names one. See [`Graph`].
    graph: Option<Graph>,
}

impl Weights {
    /// Parse `bytes`, rejecting anything not built for `expect_graph`.
    ///
    /// The header and table go through `parse_header`, which does every bounds check; the one
    /// thing added here is that the file must end exactly where its last section does — the
    /// data section on a v1 file, the graph section on a v2 file that names one — since with
    /// the whole slice in hand a mismatch means a truncated or padded download.
    pub fn parse(bytes: &[u8], expect_graph: u32) -> Result<Weights, String> {
        let header = parse_header(bytes, expect_graph)?;
        let data_end = header.data_offset + header.data_len;
        let file_end = match header.graph {
            Some((at, len)) => at + len,
            None => data_end,
        };
        if file_end != bytes.len() {
            return Err(format!(
                "sections end at {file_end} but the file is {} bytes",
                bytes.len()
            ));
        }
        let data = bytes.get(header.data_offset..data_end).unwrap_or(&[]).to_vec();
        let graph = match header.graph {
            Some((at, len)) => {
                let section = bytes
                    .get(at..at + len)
                    .ok_or_else(|| format!("graph section {at}..{} past end", at + len))?;
                Some(Graph::parse(section, &header.tensors)?)
            }
            None => None,
        };
        Ok(Weights {
            graph_id: header.graph_id,
            source_sha256: header.source_sha256,
            table: Offsets { tensors: header.tensors },
            data,
            graph,
        })
    }

    /// The fp16 blob to upload, verbatim.
    pub fn data(&self) -> &[u8] {
        &self.data
    }

    /// A [`Weights`] that is nothing but a data section, for the device-parity fixtures.
    ///
    /// [`crate::vulkan::run::Net::new`] reads no part of a [`Weights`] except [`data`] to *record*
    /// the plan, because the [`crate::nets::Plan`] it is handed already carries every resolved
    /// offset. It does read the table to **validate** each op's weight range against it, though —
    /// see `segment::tensor_end` — so the table cannot be empty or every op is rejected as reading
    /// outside all zero of the tensors the file describes.
    ///
    /// One tensor spanning the whole blob is what a fixture can honestly assert: its plan was built
    /// against a [`WeightSource`] whose own offsets are already consistent, so the property left to
    /// check on the device is that nothing reads off the end of the data. Assembling a faithful
    /// per-tensor table here would duplicate the fixture's source and check the fixture rather than
    /// the shader.
    ///
    /// [`data`]: Weights::data
    #[cfg(test)]
    pub(crate) fn from_data(data: Vec<u8>) -> Weights {
        let table = Offsets {
            tensors: vec![Tensor {
                rank: 1,
                dims: [(data.len() / 2) as u32, 0, 0, 0],
                offset: 0,
                len: (data.len() / 2) as u32,
                dtype: Dtype::F16,
            }],
        };
        Weights { graph_id: 0, source_sha256: [0u8; 32], table, data, graph: None }
    }

    /// The tensor table alone, for a caller that will rebuild a plan after this `Weights` is
    /// gone. See [`Offsets`].
    pub fn offsets(&self) -> Offsets {
        self.table.clone()
    }

    /// This file's table and bytes, for the tensors the host reads itself. See [`Reader`].
    pub fn reader(&self) -> Reader<'_> {
        Reader::new(&self.table, self)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }

    /// Tensor `index`, or an error naming the index — which is what a mismatch
    /// between the Rust forward pass and the converter's ordering looks like.
    pub fn tensor(&self, index: usize) -> Result<Tensor, String> {
        self.table.tensor(index)
    }

    /// Tensor `index`, checked against the shape the caller expects.
    ///
    /// The net modules use this for every weight, so a table that is the right
    /// length but the wrong order fails on the first layer whose shape differs
    /// rather than silently convolving with someone else's kernel.
    pub fn shaped(&self, index: usize, dims: &[u32]) -> Result<Tensor, String> {
        self.table.shaped(index, dims)
    }

    /// The version-2 graph section, when the file carries one.
    ///
    /// `None` on every version-1 file and on version-2 files converted before the
    /// converter emitted graphs. Callers that need a topology keep their hand-written
    /// forward pass for those; the section is authoritative only when present *and*
    /// parsed, never a silent fallback.
    pub fn graph_section(&self) -> Option<&Graph> {
        self.graph.as_ref()
    }
}

impl Blob for Weights {
    fn data_len(&self) -> u64 {
        self.data.len() as u64
    }

    fn tensors(&self) -> &[Tensor] {
        &self.table.tensors
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let start = usize::try_from(offset).map_err(|_| "a data offset overflowed usize")?;
        let end = start
            .checked_add(into.len())
            .ok_or("a data range overflowed")?;
        let from = self
            .data
            .get(start..end)
            .ok_or_else(|| format!("{start}..{end} of a {}-byte data section", self.data.len()))?;
        into.copy_from_slice(from);
        Ok(())
    }
}

/// A `.maml` whose table is in memory and whose data section is still in a file.
///
/// The counterpart of [`Weights`], and the whole of what bundling Supertonic needs: its table is a
/// few kilobytes, and its data section is the ~105 MB that must not be resident three times over.
/// See [`Blob`] for why that mattered enough to add a second implementation.
///
/// # Not `mmap`
///
/// `memmap2` was deliberately removed from this repo, and nothing here needs it back. A mapping
/// would let the upload read the data as a slice, but the upload does not want a slice: it wants
/// to hand fixed-size pieces to a staging buffer, and a positional read does that with no
/// unsafety, no dependency, and no page-fault behaviour to reason about on an unknown filesystem.
///
/// # The offset, and why it is not always zero
///
/// An asset inside an APK is a *range* of the APK, so `AssetManager.openFd` returns a descriptor
/// alongside a `startOffset` and a `length` rather than a file of its own. This holds that range
/// and adds it to every read, so a bundled asset and a downloaded file are one code path. It also
/// means `openFd` must succeed, which is what `noCompress += "maml"` in the app's Gradle
/// configuration is load-bearing for: `openFd` throws for a deflated asset.
pub struct Streamed {
    /// Which network this file is for. See [`graph`].
    pub graph_id: u32,
    /// SHA-256 of the ONNX it was converted from, for tracing a shipped asset.
    pub source_sha256: [u8; 32],
    table: Offsets,
    file: File,
    /// Byte offset of the **data section** within `file`, i.e. the asset's own start plus the
    /// header and table. Every [`Blob::read_at`] adds it, so callers index the data section.
    data_at: u64,
    data_len: u64,
    /// Set by [`Streamed::map`]: host reads copy out of a mapping instead of calling pread.
    mapping: Option<Mapping>,
}

impl Streamed {
    /// Read the header and table of the `.maml` occupying `at..at + len` of `file`.
    ///
    /// Only the prefix is read — the header plus the tensor table, a few kilobytes — so this costs
    /// nothing whatever the size of the net. `file` is retained; the data section is read later, by
    /// the upload.
    ///
    /// `len` is what the caller was told the asset is, and is checked against the header rather
    /// than trusted: a `.maml` claiming a data section past the end of its own range would
    /// otherwise be caught only by the first read that ran off the end, or not at all if a
    /// neighbouring asset happened to follow it.
    pub fn open(file: File, at: u64, len: u64, expect_graph: u32) -> Result<Streamed, String> {
        // A generous prefix read: one page covers the header and a 128-tensor table, and the
        // largest net here has 605. Reading `min(len, PREFIX)` rather than exactly the table means
        // one syscall instead of two, since the table's size is in the header being read.
        const PREFIX: u64 = 64 * 1024;
        let want = usize::try_from(len.min(PREFIX)).map_err(|_| "a .maml prefix overflowed")?;
        let mut prefix = vec![0u8; want];
        read_exact_at(&file, &mut prefix, at)
            .map_err(|e| format!("reading a .maml header at {at}: {e}"))?;
        let header = parse_header(&prefix, expect_graph)?;
        let data_at = header.data_offset as u64;
        let end = data_at
            .checked_add(header.data_len as u64)
            .ok_or("data section overflows")?;
        if end > len {
            return Err(format!(
                "the data section ends at {end} but this .maml is only {len} bytes"
            ));
        }
        Ok(Streamed {
            graph_id: header.graph_id,
            source_sha256: header.source_sha256,
            table: Offsets { tensors: header.tensors },
            file,
            data_at: at + data_at,
            data_len: header.data_len as u64,
            mapping: None,
        })
    }

    /// The tensor table alone, for building and rebuilding plans. See [`Offsets`].
    pub fn offsets(&self) -> Offsets {
        self.table.clone()
    }

    /// This file's table and bytes, for the tensors the host reads itself. See [`Reader`].
    ///
    /// Each read is a positional read of a few kilobytes out of the APK, which is what makes this
    /// affordable: the sampler's host block is 0.44% of its 127 MB.
    pub fn reader(&self) -> Reader<'_> {
        Reader::new(&self.table, self)
    }

    /// How many tensors the table holds.
    pub fn len(&self) -> usize {
        self.table.len()
    }

    /// Whether the table is empty. Only ever true for a hand-made file.
    pub fn is_empty(&self) -> bool {
        self.table.is_empty()
    }
}

impl Blob for Streamed {
    fn data_len(&self) -> u64 {
        self.data_len
    }

    fn tensors(&self) -> &[Tensor] {
        &self.table.tensors
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let end = offset
            .checked_add(into.len() as u64)
            .ok_or("a data range overflowed")?;
        if end > self.data_len {
            return Err(format!(
                "{offset}..{end} of a {}-byte data section",
                self.data_len
            ));
        }
        if let Some(mapped) = self.mapping.as_ref().and_then(|m| m.read_at(offset, into)) {
            return Ok(mapped);
        }
        read_exact_at(&self.file, into, self.data_at + offset)
            .map_err(|e| format!("reading {} bytes at {offset}: {e}", into.len()))
    }
}

/// A tensor table and the bytes it indexes, for the reads the host does itself.
///
/// A handful of Supertonic's tensors never reach a shader: the sampler's timestep MLP depends on
/// the step number, its rotary `theta` on the sequence length, and its folded style keys are
/// inputs to other nets. `post::supertonic` reads those on the host, and needed a [`Weights`] to
/// do it — which is exactly the 105 MB allocation the bundled path exists to avoid.
///
/// So it takes one of these instead. Both halves come from the same file either way; keeping them
/// as one argument rather than two means a caller cannot pair one net's table with another's bytes.
#[derive(Clone, Copy)]
pub struct Reader<'a> {
    table: &'a Offsets,
    data: &'a dyn Blob,
}
