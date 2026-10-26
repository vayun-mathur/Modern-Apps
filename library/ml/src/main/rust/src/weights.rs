//! The `.maml` reader.
//!
//! `.maml` is a **weights container only** — ordered tensors, no operators and no
//! topology — because each network's forward pass is hardcoded in [`crate::nets`]
//! rather than interpreted. Format version 2 appends an optional *graph section*
//! describing that forward pass symbolically (see [`Graph`]); version 1 files have
//! none, and behave exactly as before. See `scripts/ml/maml_convert.py`, which writes
//! it, for the byte layout; this is the other half of that contract.
//!
//! Two consequences worth stating plainly:
//!
//! * Tensor **order** is the whole interface. A reordering loads cleanly and infers
//!   nonsense, which is why the converter pins a SHA-256 over the ordered layer
//!   table and why [`Weights::graph_id`] is checked before a net will touch a file.
//! * Nothing here allocates or copies the tensor data. The whole file is uploaded to
//!   one `VkBuffer` verbatim, so a [`Tensor`]'s offset is simultaneously its offset
//!   in the file and its offset in device memory. That is the reason the format is
//!   one contiguous blob.

use std::fs::File;

use crate::preprocess::f16_to_f32;

/// `b"MAML"`, little-endian, at offset 0.
const MAGIC: [u8; 4] = *b"MAML";
/// Bumped when the layout below changes incompatibly.
///
/// Version 1 is header + table + blob. Version 2 appends a graph section after the blob
/// (see [`Graph`]); a v1 reader rejects a v2 file here, loudly, rather than running a
/// topology it cannot see.
const FORMAT_VERSION: u32 = 1;
/// The newest version this reader accepts. Kept distinct from [`FORMAT_VERSION`] — the
/// version *written* by the converter — so emitting v2 files is a separate, deliberate
/// step from reading them.
const FORMAT_READ_VERSION: u32 = 2;
const HEADER_BYTES: usize = 64;
const TENSOR_ENTRY_BYTES: usize = 32;
/// fp16 tensor encoding. `pub(crate)` for the fusion tests' hand-made header; the
/// format's dtypes are otherwise an internal detail of the parser.
pub(crate) const DTYPE_F16: u32 = 0;

/// Signed 8-bit, with a **separate** scale tensor beside it.
///
/// Used only where the weights dominate the download and fp16 would double it: SMaLL-100 is
/// 330 million parameters, which is 660 MB at fp16 and 330 MB here.
///
/// The quantisation is symmetric — zero point 0 — and the scale is per output channel, both read
/// from the export rather than assumed. So a value is `int8 * scale`, and because the scale
/// applies to a whole output row it multiplies the *accumulator* once rather than every tap. The
/// scale lives in its own fp16 tensor because a table entry is already 32 bytes with no room
/// for it, and a companion tensor needs no format version bump.
///
/// Activations stay fp16. The export quantises them too, dynamically, per tensor; not doing
/// that is both simpler and strictly more accurate, and it saves nothing to copy since the
/// weights are what take the space.
const DTYPE_I8: u32 = 1;

/// Signed 4-bit, two per byte, with a **per-block** scale tensor beside it.
///
/// Where [`DTYPE_I8`] carries one scale per output row, this carries one per block of
/// [`I4_BLOCK`] taps along the contraction axis, so the scale tensor is rank 2:
/// `(out_channels, ceil(taps / I4_BLOCK))`. Four bits cannot represent a row's dynamic range
/// well enough for a single scale; a block of 32 can.
///
/// Nibbles are packed **two per byte along the contraction axis**, low nibble first, so a block
/// is contiguous and the inner loop reads it without striding. A tensor's byte length is
/// therefore `len.div_ceil(2)` and not `len * stride` - the only dtype whose stride is
/// fractional, which is why [`Dtype::bytes`] exists rather than a stride constant.
const DTYPE_I4: u32 = 2;

/// Taps one [`DTYPE_I4`] scale covers.
///
/// 32 rather than 64: at 4.5 bits per weight against 4.25 the difference is 3% of a download,
/// and `MIN_INT8_COSINE` is a strict gate that the wider block is more likely to miss.
pub const I4_BLOCK: u32 = 32;

/// GGUF Q2_K superblocks, carried verbatim: 256 taps per 84-byte block (16 scale bytes +
/// 64 quant bytes + `d` + `dmin` as fp16). The per-superblock `(d, dmin)` pair lives in
/// the fp16 tensor beside it, two values per block, in the slot a per-channel scale would
/// occupy — so a Q2_K layer is the same kernel + scale + bias triple as int8, with a
/// rank-1 scale of `2` values per block.
///
/// A tensor's byte length is `len.div_ceil(256) * 84` — the second fractional-stride
/// dtype after [`DTYPE_I4`]. Reader and writer must agree on that rounding.
const DTYPE_Q2K: u32 = 3;

/// Taps one [`DTYPE_Q2K`] superblock covers. Fixed by the GGUF format, not a tuning choice.
pub const Q2K_BLOCK: u32 = 256;

/// Bytes one [`DTYPE_Q2K`] superblock occupies.
pub const Q2K_BYTES: u32 = 84;

/// Taps one [`DTYPE_Q2K`] lane covers: a superblock is 16 lanes of 16 taps sharing one
/// `(d, dmin)` pair and one scale byte each.
pub const Q2K_LANE: u32 = 16;

/// How a tensor's payload is encoded.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dtype {
    /// Half-precision float, two bytes an element.
    F16,
    /// Signed 8-bit with a per-output-channel scale. See [`DTYPE_I8`].
    I8,
    /// Signed 4-bit with a per-block scale. See [`DTYPE_I4`].
    I4,
    /// GGUF Q2_K superblocks with a per-block `(d, dmin)` scale. See [`DTYPE_Q2K`].
    Q2K,
}

impl Dtype {
    /// Bytes `len` elements of this dtype occupy.
    ///
    /// Not a stride: four-bit elements are half a byte each, so an odd length rounds up and the
    /// final nibble is padding; Q2_K elements are 84 bytes per 256 taps, so a short final
    /// block rounds up to the whole superblock.
    pub const fn bytes(self, len: u64) -> u64 {
        match self {
            Dtype::F16 => len * 2,
            Dtype::I8 => len,
            Dtype::I4 => len.div_ceil(2),
            Dtype::Q2K => len.div_ceil(Q2K_BLOCK as u64) * Q2K_BYTES as u64,
        }
    }

    /// Whether the payload is integer, and so read through the 32-bit word view with a scale.
    pub const fn is_quantised(self) -> bool {
        !matches!(self, Dtype::F16)
    }
}
/// Every tensor starts on this boundary, so an offset is a valid fp16 index too.
const ALIGNMENT: u32 = 16;

/// Graph ids, shared with `GRAPHS` in `scripts/ml/maml_convert.py`.
pub mod graph {
    /// MediaPipe Selfie Segmentation, 256x256.
    pub const SELFIE: u32 = 1;
    /// U^2-Net portable, 320x320.
    pub const U2NETP: u32 = 2;
    /// SCRFD 500M face detection, 640 on the long side.
    pub const SCRFD: u32 = 3;
    /// MobileFaceNet face embedding, 112x112 in, 512-d out.
    pub const MOBILEFACENET: u32 = 4;
    /// PP-OCRv5 mobile text detection, 960 on the long side.
    pub const PPOCR_DET: u32 = 5;
    /// PP-OCRv5 mobile text recognition, 48 tall.
    pub const PPOCR_REC: u32 = 6;
    // 7..10 were Piper's four VITS graphs, deleted when Supertonic replaced it. The numbers are
    // **not** reused: an id identifies a forward pass, and a `.maml` built for the old vocoder
    // must be rejected rather than loaded as whatever took its slot.
    /// Supertonic 3's ConvNeXt vocoder. See [`crate::nets::supertonic_vocoder`].
    pub const SUPERTONIC_VOC: u32 = 11;
    /// Supertonic 3's duration predictor. See [`crate::nets::supertonic_duration`].
    pub const SUPERTONIC_DP: u32 = 12;
    /// Supertonic 3's text encoder. See [`crate::nets::supertonic_text`].
    pub const SUPERTONIC_TTL: u32 = 13;
    /// Supertonic 3's flow-matching sampler. See [`crate::nets::supertonic_sampler`].
    pub const SUPERTONIC_VE: u32 = 14;
    // 15 was SMaLL-100's graph, deleted when NLLB replaced it. The number is **not** reused: an
    // id identifies a forward pass, and a `.maml` built for the old net must be rejected rather
    // than loaded as whatever took its slot.
    /// TinyCLIP-ViT-8M/16 Text-3M, both towers in one file. See [`crate::nets::tinyclip`].
    ///
    /// One graph rather than two because the towers share a file and a [`super::Weights`] upload,
    /// even though they share no weights: `Mode::Image` and `Mode::Text` are two plans over one net.
    pub const TINYCLIP: u32 = 16;
    /// whisper-base speech recognition, encoder and decoder in one file. See
    /// [`crate::nets::whisper`].
    ///
    /// One graph rather than two because the 51,865-row embedding is **tied**: it is the decoder's
    /// input table and the logits kernel, so two files would upload 26.6 MB of it twice.
    pub const WHISPER: u32 = 17;
    /// NLLB-200-distilled-600M translation, encoder and decoder in one file. See
    /// [`crate::nets::nllb`].
    ///
    /// One graph rather than two because the 256,206-row embedding is **tied**: it is the
    /// encoder's input table, the decoder's input table and the logits kernel, and two files would
    /// upload ~250 MiB of it twice.
    ///
    /// Agreed three-ways with model-eng and app-eng (team `nllb-translate`): the next free id
    /// after whisper's 17, with 7..10 staying retired. `maml_convert.py` has `GRAPHS["nllb600"]`
    /// at the same number.
    pub const NLLB: u32 = 18;
    /// Maia3-5M human-move prediction, one forward pass per move. See [`crate::nets::maia`].
    ///
    /// The next free id after NLLB's 18, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["maia"]` at the same number.
    pub const MAIA: u32 = 19;

    /// Gemma 4 E2B instruction-tuned, the text decoder. See [`crate::nets::gemma4`].
    ///
    /// The next free id after Maia's 19, with 7..10 and 15 staying retired. **Not 19**: the port
    /// plan originally claimed that number, which Maia already holds - a collision would make a
    /// `.maml` parse as the wrong net rather than be refused.
    /// `maml_convert.py` has `GRAPHS["gemma4_text"]` at the same number.
    pub const GEMMA4_TEXT: u32 = 20;

    /// Gemma 4's two embedding tables, gathered on the host. See [`crate::nets::gemma4`].
    ///
    /// Its own file rather than tensors in [`GEMMA4_TEXT`] because nothing on the device reads
    /// it: a decode step needs one row of each table, and binding 4.7 GB to gather 1536 values
    /// would be absurd. Splitting it also lets the text model load while the embedding streams.
    pub const GEMMA4_EMBED: u32 = 21;

    /// Gemma 4's vision tower. See [`crate::nets::gemma4_vision`].
    ///
    /// Its own file because it is optional: a device that never sends an image never downloads
    /// 99 MB of encoder.
    pub const GEMMA4_VISION: u32 = 22;

    /// The Now Playing audio fingerprinter, 415 ms of log-mel in and a 64-d embedding out. See
    /// [`crate::nets::nnfp`].
    ///
    /// The next free id after Gemma 4's vision tower at 22, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["nnfp"]` at the same number.
    ///
    /// Ported from a **TFLite** flatbuffer rather than an ONNX export or a PyTorch checkpoint,
    /// which is a third input kind for the converter; see `nnfp_layers` there.
    ///
    /// The music/not-music gate that runs in front of this on the DSP is a separate 8.2K-
    /// parameter network. It **is** ported, as [`crate::gate`], but it holds no id here and
    /// never will: it runs on the CPU with its 9,772 bytes of weights embedded by
    /// `include_bytes!`, so there is no `.maml` for an id to identify. An id names a forward
    /// pass the Vulkan runtime executes, and the gate is not one.
    ///
    /// So 23 has no successor reserved. 24 went to Gemma 4's audio encoder, which is the next
    /// thing that actually needed a graph.
    pub const NNFP: u32 = 23;

    /// Gemma 4's audio tower. See [`crate::nets::gemma4_audio`].
    ///
    /// The next free id after the fingerprinter at 23, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["gemma4_audio"]` at the same number.
    pub const GEMMA4_AUDIO: u32 = 24;

    /// MADLAD400-3B-MT translation, encoder and decoder in one file. See
    /// [`crate::nets::madlad`].
    ///
    /// Two untied tables rather than one tied embedding: the input table and the logits
    /// kernel are separate 256,000-row tables, each split four ways. The next free id
    /// after Gemma 4's audio tower at 24, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["madlad400"]` at the same number.
    pub const MADLAD: u32 = 25;

    /// Gemma 4's GPU logits head: the 16 int8 head chunks alone in their own file.
    ///
    /// The next free id after MADLAD's 25, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["gemma4_head"]` at the same number. See
    /// [`crate::nets::gemma4_head`].
    ///
    /// Its own file because the head `Net` uploads its whole `Blob` verbatim:
    /// with the chunks inside EMBED that upload was 3.6 GB (TEXT 1.1 GB +
    /// EMBED 3.6 GB = OOM-reboot on a Pixel 8). The head file is ~402 MB.
    pub const GEMMA4_HEAD: u32 = 26;

    /// MODNet photographic-portrait matting, 512x512 in, 512x512 alpha out. See
    /// [`crate::nets::modnet`].
    ///
    /// The next free id after Gemma 4's head at 26, with 7..10 and 15 staying retired.
    /// `maml_convert.py` has `GRAPHS["modnet"]` at the same number; the file is
    /// produced by `scripts/ml/modnet_fold.py` rather than `maml_convert.py` directly.
    pub const MODNET: u32 = 27;
}

/// One tensor's entry in the table: where it is and what shape it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Tensor {
    /// 1..=4 significant entries of [`Tensor::dims`].
    pub rank: u32,
    /// Trailing entries beyond `rank` are zero.
    pub dims: [u32; 4],
    /// Byte offset from the start of the data section.
    pub offset: u32,
    /// Elements, not bytes.
    pub len: u32,
    /// How the payload is encoded, which decides how many bytes [`Tensor::len`] occupies.
    pub dtype: Dtype,
}

impl Tensor {
    /// The offset as an fp16 **element** index, which is how the shaders address it.
    pub fn elem_offset(&self) -> u32 {
        self.offset / 2
    }

    /// The offset as a 32-bit **word** index, which is how an int8 tensor is addressed.
    ///
    /// Int8 weights are read through a `uint` view of the same buffer and unpacked four at a
    /// time, because a byte view would need `VK_KHR_8bit_storage` — an extension on top of the
    /// fp16 one this runtime already requires, and every extra requirement narrows the fleet.
    /// `ALIGNMENT` is 16, so a tensor always starts on a word boundary.
    pub fn word_offset(&self) -> u32 {
        self.offset / 4
    }
}

/// A `.maml`'s data section, readable a piece at a time.
///
/// [`crate::vulkan::run::Net::new`] needs the data section and nothing else: the [`Plan`] it is
/// handed already carries every resolved offset. It does *not* need the section in one host
/// allocation, and for a bundled Supertonic it must not — the current path allocates the model
/// three times over, as a Java `byte[]`, as the `Vec<u8>` JNI hands Rust, and as [`Weights`]'s own
/// copy. At the ~105 MB an int8 bundle comes to, that is ~300 MB of transient heap and an
/// out-of-memory kill on a low-RAM device.
///
/// So the upload asks for ranges instead, and two implementations answer: [`Weights`], which has
/// the bytes already, and [`Streamed`], which leaves them in the APK and reads them positionally.
/// Neither is faster than the other in device time — both end up doing the same
/// `cmd_copy_buffer`s — and the second has a peak host cost of one chunk.
///
/// [`Plan`]: crate::nets::Plan
pub trait Blob {
    /// Bytes in the data section.
    fn data_len(&self) -> u64;

    /// The tensor table, so `vulkan::segment` can find the extent of every tensor an op reads.
    ///
    /// Nothing about *running* a net needs the table - a [`Plan`] carries every resolved offset,
    /// which is what makes [`Weights`] and [`Streamed`] interchangeable. Segmenting the weights
    /// buffer does: it has to know where each tensor ends to choose a boundary that does not fall
    /// inside one, and only the table says that.
    fn tensors(&self) -> &[Tensor];

    /// Fill `into` from `offset` bytes into the data section.
    ///
    /// A short read is an error rather than a partial fill: every caller here knows exactly how
    /// many bytes it wants, and silently uploading a half-read chunk would leave one tensor of a
    /// net holding whatever the buffer was allocated with.
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String>;
}

/// A `.maml` header and tensor table, without the data section.
///
/// Parsed by [`parse_header`] from the first `HEADER_BYTES + count * 32` bytes of a file, which is
/// a few kilobytes for even the largest net here. Both [`Weights::parse`] and [`Streamed::open`]
/// go through it, so there is one implementation of the format's bounds checks rather than two
/// that have to agree.
struct Header {
    graph_id: u32,
    source_sha256: [u8; 32],
    tensors: Vec<Tensor>,
    data_offset: usize,
    data_len: usize,
    /// Byte offset of the version-2 graph section, or `None` on a version-1 file.
    ///
    /// Read from the header's reserved bytes 56..64 (offset then length). Zero length means
    /// absent even on a v2 file — the section is optional per file, not per version.
    graph: Option<(usize, usize)>,
}

/// Parse the header and tensor table out of `bytes`, which must reach at least the table's end.
///
/// Every offset and length in the table is bounds-checked here rather than at use, so a truncated
/// or hand-edited asset fails at load with a message instead of dispatching a shader that reads
/// past the end of a device buffer — where the symptom would be a driver reset, not an error.
///
/// The one thing this does *not* check is that the file ends where the data section does: a
/// [`Streamed`] read only has the prefix in hand, and an asset inside an APK is followed by the
/// next asset. [`Weights::parse`] checks it separately, because there the whole file is the slice
/// and a length mismatch means a truncated download.
fn parse_header(bytes: &[u8], expect_graph: u32) -> Result<Header, String> {
    if bytes.len() < HEADER_BYTES {
        return Err(format!("{} bytes is shorter than a .maml header", bytes.len()));
    }
    if bytes[0..4] != MAGIC {
        return Err("not a .maml file (bad magic)".into());
    }
    let version = u32(bytes, 4);
    if version == 0 || version > FORMAT_READ_VERSION {
        return Err(format!("format version {version}, expected 1 or {FORMAT_READ_VERSION}"));
    }
    let graph_id = u32(bytes, 8);
    if graph_id != expect_graph {
        return Err(format!(
            "this file is for graph {graph_id}, but graph {expect_graph} asked for it"
        ));
    }
    let count = u32(bytes, 12) as usize;
    let mut source_sha256 = [0u8; 32];
    source_sha256.copy_from_slice(&bytes[16..48]);
    let data_offset = u32(bytes, 48) as usize;
    let data_len = u32(bytes, 52) as usize;
    // Version-2 graph section: byte offset and length in the reserved tail. Zero length
    // is absent, which is how a v2 file without an emitted graph reads — and how every
    // v1 file reads, since those bytes are zero there.
    let graph_at = u32(bytes, 56) as usize;
    let graph_len = u32(bytes, 60) as usize;
    let graph = if graph_len == 0 {
        None
    } else if version < 2 {
        return Err("a version-1 file naming a graph section it cannot hold".into());
    } else {
        Some((graph_at, graph_len))
    };

    let table_bytes = count
        .checked_mul(TENSOR_ENTRY_BYTES)
        .ok_or_else(|| format!("{count} tensors overflows the table size"))?;
    if data_offset != HEADER_BYTES + table_bytes {
        return Err(format!(
            "data starts at {data_offset}, but {count} tensors put it at {}",
            HEADER_BYTES + table_bytes
        ));
    }
    data_offset
        .checked_add(data_len)
        .ok_or_else(|| "data section overflows".to_string())?;
    if bytes.len() < data_offset {
        return Err(format!(
            "{} bytes does not reach the end of a {count}-tensor table at {data_offset}",
            bytes.len()
        ));
    }

    let mut tensors = Vec::with_capacity(count);
    for i in 0..count {
        let at = HEADER_BYTES + i * TENSOR_ENTRY_BYTES;
        let rank = u32(bytes, at);
        if rank == 0 || rank > 4 {
            return Err(format!("tensor {i} has rank {rank}"));
        }
        let dims =
            [u32(bytes, at + 4), u32(bytes, at + 8), u32(bytes, at + 12), u32(bytes, at + 16)];
        let dtype = u32(bytes, at + 20);
        let dtype = match dtype {
            DTYPE_F16 => Dtype::F16,
            DTYPE_I8 => Dtype::I8,
            DTYPE_I4 => Dtype::I4,
            DTYPE_Q2K => Dtype::Q2K,
            other => {
                return Err(format!(
                    "tensor {i} has dtype {other}, expected fp16, int8, int4 or q2k"
                ))
            }
        };
        let offset = u32(bytes, at + 24);
        let len = u32(bytes, at + 28);

        let expected: u64 = dims[..rank as usize].iter().map(|&d| d as u64).product();
        if expected != len as u64 {
            return Err(format!("tensor {i} has dims {dims:?} but len {len}"));
        }
        if !offset.is_multiple_of(ALIGNMENT) {
            return Err(format!("tensor {i} is at {offset}, not {ALIGNMENT}-aligned"));
        }
        let end = (offset as u64) + dtype.bytes(len as u64);
        if end > data_len as u64 {
            return Err(format!(
                "tensor {i} spans {offset}..{end} of a {data_len}-byte data section"
            ));
        }
        tensors.push(Tensor { rank, dims, offset, len, dtype });
    }
    // The graph section, when named, must sit exactly where the blob ends and end where
    // the lengths say. A section overlapping the blob would alias weights as topology;
    // one running past the declared end is truncated.
    if let Some((at, len)) = graph {
        let blob_end =
            data_offset.checked_add(data_len).ok_or_else(|| "data section overflows".to_string())?;
        if at != blob_end {
            return Err(format!(
                "graph section at {at}, but the data section ends at {blob_end}"
            ));
        }
        at.checked_add(len).ok_or_else(|| "graph section overflows".to_string())?;
    }
    Ok(Header { graph_id, source_sha256, tensors, data_offset, data_len, graph })
}

/// The tensor table on its own, without the data section.
///
/// [`crate::vulkan::run::Net::rebuild`] re-records a net at a new shape, and that means building a
/// fresh [`crate::nets::Plan`] — which needs a [`crate::nets::WeightSource`]. It does not need the
/// blob: a builder only ever consults offsets and shapes, and the blob is already in device memory
/// by then. Holding a whole [`Weights`] alive to rebuild from would keep 127 MB of host RSS for the
/// Supertonic sampler alone, beside the copy the device has, where the table is a few kilobytes.
///
/// So a caller that means to rebuild takes one of these first and lets the `Weights` go.
#[derive(Clone, Debug)]
pub struct Offsets {
    tensors: Vec<Tensor>,
}

include!("weights_part1.rs");
include!("weights_part2.rs");
include!("weights_part3.rs");
include!("weights_part4.rs");
include!("weights_part5.rs");
include!("weights_part6.rs");
include!("weights_part7.rs");
include!("weights_part8.rs");
include!("weights_part9.rs");