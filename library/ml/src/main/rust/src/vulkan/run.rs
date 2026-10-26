//! Recording a [`Plan`] into one command buffer, and running it.
//!
//! # Recorded once, submitted per inference
//!
//! The whole network — 350 dispatches for U^2-Netp, 150 for the selfie net, with a barrier
//! between each — is recorded into a single primary command buffer at [`Net::new`] and
//! never re-recorded. An inference is then exactly:
//!
//! 1. preprocess the bitmap into the staging buffer on the CPU,
//! 2. one `vkQueueSubmit`,
//! 3. one `vkWaitForFences`,
//! 4. read the mask back out of the staging buffer.
//!
//! Nothing is allocated, no descriptor is written and no command is recorded per frame.
//! That is what makes this viable on `:camera`'s ~15 fps preview path, where the recording
//! cost — hundreds of `vkCmd` calls — would otherwise be paid 15 times a second while the
//! UI is also using the GPU.
//!
//! The input copy is *inside* the recorded buffer, so the host writes only to the staging
//! buffer and the GPU does the transfer into device-local memory itself.
//!
//! # Barriers
//!
//! One full barrier between every op, covering both the compute and transfer stages in
//! each direction. Every layer reads what the one before it wrote, into the same
//! `VkBuffer`, so there is no dependency to skip: the arena is a single buffer and a
//! finer-grained barrier would have to name byte ranges that the ops already overlap by
//! design. About 350 barriers per inference is the cost of that simplicity, and it is the
//! first thing to look at if U^2-Netp is slower than it should be.

use std::sync::{Arc, OnceLock};

use ash::vk;

use crate::nets::{Op, Plan};
use crate::preprocess::{self, Normalise};
use crate::weights::{Blob, Dtype, Tensor};

use super::buffers::Buffer;
use super::context::Context;
use super::pipeline::{Pipelines, MAX_WORKGROUPS_PER_DIM, WORKGROUP};
use super::segment::Segments;

use crate::timing;

/// How [`Net::barrier_over`] spells the dependency between two ops.
///
/// Selected by `MODELRUNNER_BARRIER` or `debug.modelrunner.barrier`, defaulting to
/// [`Formulation::Range`], which is what this runtime has always done.
///
/// # Only four of these are correct
///
/// [`Formulation::Range`], [`Formulation::Whole`], [`Formulation::Global`] and
/// [`Formulation::AllCommands`] all compute right answers and differ only in how much the driver
/// is told. [`Formulation::Narrow`], [`Formulation::GlobalNarrow`] and [`Formulation::None`] do
/// not: [`Formulation::selected`] will not return them outside a `debug_assertions` build.
///
/// An earlier version of this comment claimed every variant but [`Formulation::None`] was correct.
/// A sweep of all seven showed otherwise — see `analysis/maml_vs_litert.md` section 6.
///
/// # Why it is a run-time knob
///
/// Nothing in the Vulkan spec says which spelling of the same dependency a given driver makes
/// cheap, so it has to be measured — and measured *within one process*, because comparing two
/// builds is how an earlier attempt attributed a shader regression to the barrier.
///
/// The sweep it was built for found nothing to choose between them: 5,073 / 5,091 / 5,095 /
/// 5,096 ms across the four correct variants on a Tensor G4, a spread of 0.45% against a noise
/// floor of 2%. Cost is also not proportional to the byte range — [`Formulation::Global`] names no
/// range at all and [`Formulation::Whole`] names the entire arena, and they land 4 ms apart. The
/// knob is kept so the sweep can be re-run on a new device, not because a winner is expected.
/// Whether per-op timestamp queries are recorded, read once.
///
/// `MODELRUNNER_TIMESTAMPS` or `debug.modelrunner.timestamps`, defaulting to
/// off. When on, `record` brackets every dispatch with timestamp writes and
/// [`Net::op_times`] reports each op's device time; when off, no pool exists
/// and the recording is byte-identical to what it always was. Same run-time
/// knob rationale as [`Formulation::selected`]: comparing two builds is how an
/// earlier attempt attributed a shader regression to the barrier.
fn timestamps_enabled() -> bool {
    static ON: OnceLock<bool> = OnceLock::new();
    *ON.get_or_init(|| crate::knobs::is_set("timestamps"))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Formulation {
    /// A buffer barrier over the op's own output range. The default and the historical behaviour.
    Range,
    /// A buffer barrier over the whole arena. What this used to do before `barrier_over` narrowed
    /// it; worth re-measuring now that the arena is 540 KB for Supertonic rather than Gemma's
    /// 303 MB.
    Whole,
    /// A global memory barrier: no buffer, no range for the driver to walk.
    Global,
    /// **Incorrect on this runtime**, and debug-only. [`Formulation::Global`] with only the
    /// compute-to-compute dependency named. See [`Formulation::Narrow`] for why naming less here
    /// is wrong rather than merely leaner.
    GlobalNarrow,
    /// **Incorrect on this runtime**, and debug-only. A buffer barrier over the op's range naming
    /// only `SHADER_WRITE` to `SHADER_READ` — the compute-to-compute dependency and nothing else.
    ///
    /// That is not enough, because **a copy is also an op here**. The input upload and the output
    /// readback are `TRANSFER` work recorded into the same command buffer as the dispatches, so
    /// dropping the transfer stage and its two accesses from the mask leaves them unordered
    /// against the dispatches either side. The dependency this omits is a real one, not a
    /// redundant one the spec lets you elide.
    ///
    /// It surfaced as a short utterance — 43,008 frames delivered instead of 150,528, because the
    /// duration model reads an unsynchronised upload and asks for 14 latent frames instead of 49
    /// — but that is the symptom. The missing transfer edge is the fault, and it would show up
    /// somewhere else entirely on a net that uploads different things.
    Narrow,
    /// `ALL_COMMANDS` on both sides. The bluntest correct answer, as a control.
    AllCommands,
    /// **No barrier at all. Produces wrong results.** Debug-only.
    ///
    /// Here so the ceiling can be measured in the same process as the variants being compared
    /// against it, which is the only way to know the ceiling has not moved under them. Never
    /// select this outside a measurement.
    ///
    /// It is not currently a *valid* ceiling: like [`Formulation::Narrow`] it corrupts the
    /// duration prediction, so it computes 14 latent frames where a correct run computes 49 and
    /// its 1,160 ms is 3.5x less work rather than the same work without barriers. Any figure for
    /// what the barrier costs needs this path pinned to the known-good frame count first.
    None,
}

impl Formulation {
    /// The selected variant, read once.
    ///
    /// Cached because this is called once per op inside `record`, where a property lookup per
    /// call would be a meaningful share of what is being measured.
    pub fn selected() -> Formulation {
        static CHOICE: OnceLock<Formulation> = OnceLock::new();
        *CHOICE.get_or_init(|| {
            let name = crate::knobs::get("barrier").unwrap_or_default();
            let chosen = match name.trim() {
                "" | "range" => Formulation::Range,
                "whole" => Formulation::Whole,
                "global" => Formulation::Global,
                "all-commands" => Formulation::AllCommands,
                // The three that compute wrong answers are measurement tools, and a shipped
                // build has no business selecting one. Gated at the point of selection rather
                // than on the variants so the enum has one shape in both profiles and callers
                // never need a `cfg` to match on it.
                #[cfg(debug_assertions)]
                "global-narrow" => Formulation::GlobalNarrow,
                #[cfg(debug_assertions)]
                "narrow" => Formulation::Narrow,
                #[cfg(debug_assertions)]
                "none" => Formulation::None,
                #[cfg(not(debug_assertions))]
                wrong @ ("global-narrow" | "narrow" | "none") => {
                    timing!("barrier formulation {wrong:?} computes wrong output and is \
                             debug-only, using range");
                    Formulation::Range
                }
                other => {
                    timing!("unknown barrier formulation {other:?}, using range");
                    Formulation::Range
                }
            };
            if chosen != Formulation::Range {
                timing!("barrier formulation {chosen:?}");
            }
            chosen
        })
    }

    /// The source and destination pipeline stages.
    fn stages(self) -> (vk::PipelineStageFlags, vk::PipelineStageFlags) {
        let compute = vk::PipelineStageFlags::COMPUTE_SHADER;
        let both = compute | vk::PipelineStageFlags::TRANSFER;
        match self {
            Formulation::Narrow | Formulation::GlobalNarrow => (compute, compute),
            Formulation::AllCommands => {
                (vk::PipelineStageFlags::ALL_COMMANDS, vk::PipelineStageFlags::ALL_COMMANDS)
            }
            _ => (both, both),
        }
    }

    /// The source and destination access masks.
    fn accesses(self) -> (vk::AccessFlags, vk::AccessFlags) {
        match self {
            // Exactly the dependency a dispatch has on the dispatch before it, and nothing else.
            Formulation::Narrow | Formulation::GlobalNarrow => (
                vk::AccessFlags::SHADER_WRITE,
                vk::AccessFlags::SHADER_READ | vk::AccessFlags::SHADER_WRITE,
            ),
            Formulation::AllCommands => {
                (vk::AccessFlags::MEMORY_WRITE, vk::AccessFlags::MEMORY_READ | vk::AccessFlags::MEMORY_WRITE)
            }
            // The historical masks: a copy is also an op here, so transfer is named on both sides.
            _ => (
                vk::AccessFlags::SHADER_WRITE | vk::AccessFlags::TRANSFER_WRITE,
                vk::AccessFlags::SHADER_READ
                    | vk::AccessFlags::SHADER_WRITE
                    | vk::AccessFlags::TRANSFER_READ
                    | vk::AccessFlags::TRANSFER_WRITE,
            ),
        }
    }
}

/// Values a recorded command buffer reads from memory rather than from its own recording.
///
/// # Why this exists
///
/// A decode step attends over every position decoded so far, so the loop bound grows by one each
/// token. That bound lived in the [`Plan`] — in a push constant and in the dispatch's group count
/// — and both are *baked into the recording*, so growing it meant re-recording: a
/// `device_wait_idle` under the queue lock, a fresh plan, and every dispatch emitted again, per
/// token. See [`super::reshape::Reshaped`].
///
/// A storage buffer is the one thing a recorded command buffer reads late. Putting the bound here
/// lets the same recording serve every step, and a step becomes a memcpy of a few bytes.
///
/// # Layout
///
/// `repr(C)` and all `u32`, matching the `Params` block in `shaders/common.glsl` field for field.
/// `std430` lays a struct of `uint`s out with no padding, so the two agree without alignment
/// rules having to be restated on either side.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct StepParams {
    /// Cache positions already written, and so the index the current step writes at.
    ///
    /// A cached-attention op attends over `prefix + 1` keys: the prefix plus this step's own.
    pub prefix: u32,
    /// First position a sliding-window attention may attend to.
    ///
    /// Zero means attend from the start, which is every model the runtime has today. Gemma 3n
    /// alternates local and global layers, which is what this is for.
    pub window_start: u32,
}

impl StepParams {
    /// Bytes to allocate for the params buffer.
    ///
    /// Rounded well past `size_of::<StepParams>()` so that adding a field, or appending the
    /// `VkDispatchIndirectCommand`s an indirect dispatch reads, does not change the allocation
    /// and cannot silently overrun a buffer sized to an older layout.
    pub const BYTES: vk::DeviceSize = 256;

    /// The struct as the bytes the buffer holds.
    fn as_bytes(&self) -> &[u8] {
        // SAFETY: `repr(C)` over two `u32`s has no padding and no invalid bit patterns, so every
        // byte of it is initialised and readable as `u8`. The slice borrows `self`.
        unsafe {
            std::slice::from_raw_parts(
                std::ptr::from_ref(self).cast::<u8>(),
                std::mem::size_of::<StepParams>(),
            )
        }
    }
}

/// A compiled, recorded network ready to run.
pub struct Net {
    context: Arc<Context>,
    plan: Plan,
    normalise: Normalise,
    weights: Buffer,
    arena: Buffer,
    staging: Buffer,
    /// Values the shaders read that change per step without a re-record.
    ///
    /// See [`StepParams`] and [`Buffer::step_params`].
    params: Buffer,
    pipelines: Pipelines,
    /// Which descriptor set each op's weights are visible through.
    ///
    /// Almost always one window over the whole file. See [`super::segment`].
    segments: Segments,
    /// The `.maml` tensors inside the uploaded window, offsets relative to its start, for
    /// [`Segments::for_op`].
    tensors: Vec<crate::weights::Tensor>,
    /// fp16 copies of int8 weights, past the window in the weights buffer. See [`Widened`].
    widened: Vec<Widened>,
    /// The `[lo, hi)` data-section bytes in the weights buffer. Byte 0 of the buffer is file
    /// byte `lo`; a plan reading outside the window is refused at record time.
    window: (u64, u64),
    /// This net's own command pool, not the context's.
    ///
    /// A `VkCommandPool` is externally synchronised across *recording* as well as across
    /// allocation — every `vkBeginCommandBuffer` and `vkCmd*` counts — so a pool shared between
    /// nets would have to be locked for the whole of [`Net::record`]. One pool each removes the
    /// question: `infer` takes `&mut self`, so a net is already exclusive to one thread at a
    /// time, and nothing else can reach this pool.
    command_pool: vk::CommandPool,
    command_buffer: vk::CommandBuffer,
    /// One-shot transfers (arena copies, persistent-tensor writes and reads), kept apart from
    /// `command_buffer` so they never disturb the plan's recording. See [`Net::run_once`].
    transfer: vk::CommandBuffer,
    /// Other plans' recordings, oldest first, each still valid against the current arena,
    /// staging buffer and descriptor sets. A rebuild into one of these plans swaps buffers
    /// instead of re-recording.
    ///
    /// Recording is not cheap on Mali: the first time a plan with shared-memory dispatches is
    /// recorded into a command buffer the driver spends ~1.7 ms per such dispatch (NLLB's first
    /// DecodeStep record took 166 ms, every later switch 10-40 ms).
    recorded: Vec<(Plan, vk::CommandBuffer)>,
    /// Command buffers holding no valid recording, kept because a buffer the driver has already
    /// grown records faster than a fresh one.
    spare: Vec<vk::CommandBuffer>,
    /// Whether any plan installed so far pinned tensors, so the arena holds state a growth
    /// must carry over.
    pinned_seen: bool,
    fence: vk::Fence,
    /// Timestamp query pool for per-op timings, or null when disabled.
    ///
    /// Created in [`Net::new`] and [`Net::rebuild`] when [`timestamps_enabled`]
    /// holds and the device reports timestamp bits; sized at two queries per
    /// dispatch op. `record` writes a timestamp before and after every dispatch
    /// into it, and [`Net::op_times`] reads the pairs back after a submit.
    /// Null means no timestamps were recorded and `op_times` refuses.
    query_pool: vk::QueryPool,
    /// Dispatch ops the pool was sized for. A rebuild with a different plan
    /// recreates the pool; anything else reuses it.
    query_count: u32,
    /// Set when a submission was left pending, or a recording left part-written, after which
    /// this net is unusable.
    ///
    /// A `wait_for_fences` timeout returns an error while the submission is **still in
    /// flight**. Nothing can cancel it, so resetting the fence, resubmitting the command
    /// buffer or rewriting the staging buffer would all be illegal — and since the
    /// five-second timeout exists precisely so a hung GPU does not block forever, this is
    /// an anticipated path rather than a theoretical one. Once poisoned, every later
    /// `infer` fails immediately without touching Vulkan, and only `Drop` — which waits
    /// for the device to go idle first — cleans up. [`Net::rebuild`] sets it for the other
    /// reason: a command buffer it failed to finish recording must never be submitted.
    poisoned: bool,
    /// Scratch for the fp16 input and the fp16 output, so an inference allocates nothing.
    input_scratch: Vec<u16>,
    output_scratch: Vec<u16>,
    /// [`Net::stage_inputs`]' last f32 bits and their fp16, for skipping unchanged inputs.
    input_seen: Vec<u32>,
    input_cache: Vec<u16>,
}

include!("run_part1.rs");
include!("run_part6.rs");
include!("run_part2.rs");
include!("run_part7.rs");
include!("run_part3.rs");
include!("run_part4.rs");
include!("run_part5.rs");
include!("run_part8.rs");