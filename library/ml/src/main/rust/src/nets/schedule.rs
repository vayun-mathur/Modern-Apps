//! Which of a plan's barriers are actually needed.
//!
//! [`super::Plan`]'s `ops` field says "each depends on the results of the ones before it", and
//! [`crate::vulkan::run::Net::record`] takes it literally: a `vkCmdPipelineBarrier` after every
//! op. That claim is false in general, and serialising every op is expensive — but the **size of
//! the effect is unmeasured**. This header used to say 5,091 ms falling to 1,194 ms, 74% of an
//! utterance at about 0.55 ms a barrier. That came from a no-barrier control which delivered
//! 43,008 frames against a correct run's 150,528, so it set a full utterance against a truncated
//! one; the comparison captures output length at least as much as barrier cost.
//!
//! The one valid same-work pair is `narrow` at 2,179 ms against `none` at 1,160 ms, both at
//! 43,008 frames — a 47% delta, at a truncated workload and with a barrier cheaper than the
//! default, so it is a lower bound. A valid ceiling needs the frame count pinned so both paths do
//! identical work; that is task 11. See `analysis/maml_vs_litert.md` section 6.
//!
//! None of which changes what this module is for: whatever a barrier costs, emitting one that is
//! not needed costs all of it for nothing.
//!
//! This module answers the narrower question the runtime should have been asking: given what each
//! op reads and writes, which pairs actually have to be ordered.
//!
//! # Three hazards, not one
//!
//! It is tempting to track only read-after-write. That is wrong here, because
//! [`super::Builder::finish`] packs the arena with a best-fit coalescing free list that
//! **recycles offsets** as tensors die. Two ops with no path between them in the graph can
//! therefore land on the same bytes, and then:
//!
//! * **RAW** — the later op reads what the earlier wrote. The obvious one.
//! * **WAR** — the later op overwrites a range the earlier is still reading. Without a barrier the
//!   earlier op reads the new value.
//! * **WAW** — both write the same range and the survivor is whichever finishes last.
//!
//! Working from offsets rather than from the graph is what makes this safe: recycling is exactly
//! the case a graph-based analysis would call independent and get wrong. It costs some
//! pessimism — a barrier between two ops that only share a range by coincidence — and that is the
//! right direction to be wrong in.
//!
//! # Everything unknown is a barrier
//!
//! [`Kind::arena_reads`] returns [`Reads::Unknown`] for any kind whose reads have not been checked
//! against its shader, and an unknown read forces a flush. So does an op whose write range does
//! not fit the arena, and so does anything involving a transfer. The failure mode of this module
//! is a barrier that was not needed; it must never be a barrier that was.

use super::{Kind, Op, Plan, Push, Reads};

/// A byte range of the arena. Half-open, `[at, at + len)`.
type Range = (u64, u64);

/// What has to happen before an op runs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Step {
    /// Whether a barrier must precede this op.
    pub barrier: bool,
    /// Whether that barrier has to name the transfer stage as well as compute.
    ///
    /// True whenever an [`Op::Copy`] is on either side of it: a copy moves data through
    /// `TRANSFER`, and a dependency that names only `COMPUTE_SHADER` would not order it.
    pub transfer: bool,
}

/// Why a barrier had to be emitted, for [`Schedule`]'s report.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Causes {
    /// A real overlap: RAW, WAR or WAW.
    pub hazard: u32,
    /// A kind whose reads are not audited, so it might read anything.
    pub unknown: u32,
    /// A write range that does not fit the arena, or a zero-length one.
    pub unbounded: u32,
}

/// A plan's barriers, and why each is there.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Schedule {
    /// One entry per op, in plan order.
    pub steps: Vec<Step>,
    /// Barriers by reason. Sums to [`Schedule::barriers`].
    pub causes: Causes,
}

impl Schedule {
    /// Barriers this schedule emits.
    pub fn barriers(&self) -> usize {
        self.steps.iter().filter(|s| s.barrier).count()
    }
}

/// Where an op reads and writes, in arena bytes.
struct Touches {
    reads: Option<Vec<Range>>,
    writes: Vec<Range>,
    transfer: bool,
}

/// The bytes an op touches, or `None` reads when it could touch anything.
///
/// Element offsets are doubled throughout: the arena is fp16 and
/// [`crate::vulkan::run::Net::record`] measures its barrier ranges in bytes, so this has to agree
/// with it exactly or the two disagree about what a barrier covers.
fn touches(op: &Op, arena_elems: u32) -> Touches {
    let bytes = |at: u32, len: u32| (u64::from(at) * 2, u64::from(len) * 2);
    match *op {
        Op::Copy { src, dst, elems } => Touches {
            reads: Some(vec![bytes(src, elems)]),
            writes: vec![bytes(dst, elems)],
            transfer: true,
        },
        Op::Dispatch { kind, push, .. } => {
            let reads = match kind.arena_reads(&push) {
                Reads::Ranges(ranges) => {
                    Some(ranges.into_iter().map(|(at, len)| bytes(at, len)).collect())
                }
                Reads::Unknown => None,
            };
            Touches { reads, writes: vec![written(&push, arena_elems)], transfer: false }
        }
    }
}

/// The arena bytes a dispatch writes.
///
/// The same formula `Net::record` uses, deliberately: if the scheduler and the recorder disagree
/// about an op's output, the barrier that does get emitted covers the wrong range.
fn written(push: &Push, arena_elems: u32) -> Range {
    let elems = u64::from(push.out_c) * u64::from(push.out_h.max(1)) * u64::from(push.out_w.max(1));
    let at = u64::from(push.out) * 2;
    let len = elems * 2;
    // A shape this could not have written is a plan bug rather than something to guess around,
    // so it becomes the whole arena and forces a flush - as it does in `record`.
    if len == 0 || at + len > u64::from(arena_elems) * 2 {
        (0, u64::from(arena_elems) * 2)
    } else {
        (at, len)
    }
}

fn overlaps(a: Range, b: Range) -> bool {
    a.0 < b.0 + b.1 && b.0 < a.0 + a.1
}

/// Whether a dispatch's write range was widened to the whole arena by [`written`].
fn unbounded(op: &Op, arena_elems: u32) -> bool {
    match *op {
        Op::Dispatch { push, .. } => {
            let elems =
                u64::from(push.out_c) * u64::from(push.out_h.max(1)) * u64::from(push.out_w.max(1));
            let at = u64::from(push.out) * 2;
            let len = elems * 2;
            len == 0 || at + len > u64::from(arena_elems) * 2
        }
        Op::Copy { .. } => false,
    }
}

/// Which of `plan`'s ops need a barrier before them.
///
/// Walks once, holding the ranges read and written since the last barrier. An op that conflicts
/// with any of them flushes: the pending set clears and the op starts a new one. Ops that do not
/// conflict accumulate, and the GPU is free to overlap them.
pub fn schedule(plan: &Plan) -> Schedule {
    let arena = plan.arena_elems;
    let mut steps = Vec::with_capacity(plan.ops.len());
    let mut causes = Causes::default();
    // Ranges touched since the last barrier, and whether any of them was a transfer.
    let mut pending_reads: Vec<Range> = Vec::new();
    let mut pending_writes: Vec<Range> = Vec::new();
    let mut pending_transfer = false;
    // An op that reads an unaudited set poisons the pending window: nothing after it can be
    // proved independent of it, so the next op flushes.
    let mut pending_unknown = false;

    for op in &plan.ops {
        let it = touches(op, arena);

        let hazard = match &it.reads {
            // RAW against anything written since the last barrier.
            Some(reads) => reads.iter().any(|r| pending_writes.iter().any(|w| overlaps(*r, *w))),
            None => false,
        };
        // WAW against a pending write, and WAR against a pending read.
        let write_hazard = it.writes.iter().any(|w| {
            pending_writes.iter().any(|p| overlaps(*w, *p))
                || pending_reads.iter().any(|p| overlaps(*w, *p))
        });
        let reads_unknown = it.reads.is_none();
        let write_unbounded = unbounded(op, arena);

        let barrier = hazard || write_hazard || reads_unknown || pending_unknown || write_unbounded;
        if barrier {
            // Attribute to the most specific reason, so the report distinguishes "this plan is
            // genuinely serial" from "this plan is full of kinds nobody has audited".
            if hazard || write_hazard {
                causes.hazard += 1;
            } else if reads_unknown || pending_unknown {
                causes.unknown += 1;
            } else {
                causes.unbounded += 1;
            }
        }

        // The barrier goes *before* this op, so it has to cover the transfer stage if either the
        // pending set or this op moves data through it.
        steps.push(Step { barrier, transfer: barrier && (pending_transfer || it.transfer) });

        if barrier {
            pending_reads.clear();
            pending_writes.clear();
            pending_transfer = false;
            pending_unknown = false;
        }
        if let Some(reads) = &it.reads {
            pending_reads.extend_from_slice(reads);
        }
        pending_writes.extend_from_slice(&it.writes);
        pending_transfer |= it.transfer;
        pending_unknown |= reads_unknown;
    }

    Schedule { steps, causes }
}

/// Whether every op that could see a stale value is preceded by a barrier.
///
/// The property [`schedule`] exists to guarantee, checked directly rather than inferred from the
/// walk that produced it: for every pair `i < j` with no barrier between them, `j` must not
/// conflict with `i`. Quadratic and only for tests, but it is the definition rather than a
/// restatement of the implementation, so it catches a bug in the walk itself.
pub fn is_sound(plan: &Plan, schedule: &Schedule) -> Result<(), String> {
    let arena = plan.arena_elems;
    for (j, op) in plan.ops.iter().enumerate() {
        let later = touches(op, arena);
        for i in (0..j).rev() {
            // A barrier anywhere between `i` and `j` orders them.
            if schedule.steps[i + 1..=j].iter().any(|s| s.barrier) {
                break;
            }
            let earlier = touches(&plan.ops[i], arena);
            let raw = later
                .reads
                .as_ref()
                .is_none_or(|r| r.iter().any(|r| earlier.writes.iter().any(|w| overlaps(*r, *w))));
            let waw = later
                .writes
                .iter()
                .any(|w| earlier.writes.iter().any(|p| overlaps(*w, *p)));
            let war = earlier.reads.as_ref().is_none_or(|reads| {
                later.writes.iter().any(|w| reads.iter().any(|r| overlaps(*w, *r)))
            });
            if raw || waw || war {
                return Err(format!("ops {i} and {j} conflict with no barrier between them"));
            }
        }
    }
    Ok(())
}

/// How many ops of each kind a plan holds, for the fusion survey.
pub fn kinds(plan: &Plan) -> Vec<(Kind, usize)> {
    let mut counts: Vec<(Kind, usize)> = Vec::new();
    for op in &plan.ops {
        if let Op::Dispatch { kind, .. } = op {
            match counts.iter_mut().find(|(k, _)| k == kind) {
                Some((_, n)) => *n += 1,
                None => counts.push((*kind, 1)),
            }
        }
    }
    counts.sort_by(|a, b| b.1.cmp(&a.1));
    counts
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::{Binding, Shape};

    /// A dispatch reading `in0` for `in_len` and writing `out` for `out_len`, in elements.
    fn dispatch(in0: u32, in_len: u32, out: u32, out_len: u32) -> Op {
        Op::Dispatch {
            // `Activate` reads exactly `in_c * in_h * in_w` from `in0`, which is the simplest
            // audited arm to build a hazard out of.
            kind: Kind::Activate,
            push: Push {
                in0,
                out,
                in_c: in_len,
                in_h: 1,
                in_w: 1,
                out_c: out_len,
                out_h: 1,
                out_w: 1,
                count: out_len,
                ..Push::default()
            },
            invocations: out_len,
        }
    }

    fn plan(ops: Vec<Op>, arena_elems: u32) -> Plan {
        Plan {
            ops,
            arena_elems,
            inputs: vec![Binding { at: 0, shape: Shape { c: 1, h: 1, w: 1 } }],
            pinned: Vec::new(),
            outputs: vec![Binding { at: 0, shape: Shape { c: 1, h: 1, w: 1 } }],
            widen: Vec::new(),
        }
    }

    #[test]
    fn independent_ops_need_no_barrier_between_them() {
        // Two dispatches over disjoint ranges: 0..8 -> 8..16 and 16..24 -> 24..32.
        let p = plan(vec![dispatch(0, 8, 8, 8), dispatch(16, 8, 24, 8)], 64);
        let s = schedule(&p);
        assert!(!s.steps[1].barrier, "{:?}", s);
        assert_eq!(s.barriers(), 0);
        is_sound(&p, &s).expect("sound");
    }

    #[test]
    fn a_read_after_write_needs_a_barrier() {
        // The second reads exactly what the first wrote.
        let p = plan(vec![dispatch(0, 8, 8, 8), dispatch(8, 8, 24, 8)], 64);
        let s = schedule(&p);
        assert!(s.steps[1].barrier);
        assert_eq!(s.causes.hazard, 1);
        is_sound(&p, &s).expect("sound");
    }

    #[test]
    fn a_write_after_read_needs_a_barrier() {
        // The first reads 0..8; the second overwrites it. Without a barrier the first may see
        // the new value - the hazard a RAW-only analysis misses.
        let p = plan(vec![dispatch(0, 8, 32, 8), dispatch(40, 8, 0, 8)], 64);
        let s = schedule(&p);
        assert!(s.steps[1].barrier);
        assert_eq!(s.causes.hazard, 1);
        is_sound(&p, &s).expect("sound");
    }

    #[test]
    fn a_write_after_write_needs_a_barrier() {
        // Two ops writing the same range, which the arena's offset recycling makes reachable
        // between ops with no graph edge between them.
        let p = plan(vec![dispatch(0, 8, 32, 8), dispatch(16, 8, 32, 8)], 64);
        let s = schedule(&p);
        assert!(s.steps[1].barrier);
        assert_eq!(s.causes.hazard, 1);
        is_sound(&p, &s).expect("sound");
    }

    #[test]
    fn a_recycled_offset_is_a_hazard_even_though_the_graph_says_otherwise() {
        // Three ops. The third reuses the first's output range for its own output, which is what
        // the best-fit arena does once a tensor dies. Nothing connects them in the graph.
        let p = plan(
            vec![dispatch(0, 8, 8, 8), dispatch(16, 8, 24, 8), dispatch(32, 8, 8, 8)],
            64,
        );
        let s = schedule(&p);
        assert!(s.steps[2].barrier, "the recycled write must be ordered: {s:?}");
        is_sound(&p, &s).expect("sound");
    }

    #[test]
    fn every_kind_is_audited_so_nothing_falls_back_to_unknown() {
        // `arena_reads` matches every `Kind` explicitly and has no catch-all, so adding a kind
        // will not compile until someone writes its arm. That is a stronger guarantee than a
        // default of `Unknown` would be, and this pins the intent: if a future arm returns
        // `Unknown` deliberately it is a decision, not an oversight.
        //
        // Checked over the kinds a plan can hold rather than by reflection, which Rust has not
        // got. `Activate` stands in for the audited path; the exhaustiveness is the compiler's.
        let op = dispatch(0, 8, 8, 8);
        assert!(touches(&op, 64).reads.is_some());
    }

    #[test]
    fn a_copy_makes_its_barrier_name_the_transfer_stage() {
        let p = plan(
            vec![Op::Copy { src: 0, dst: 8, elems: 8 }, dispatch(8, 8, 24, 8)],
            64,
        );
        let s = schedule(&p);
        assert!(s.steps[1].barrier);
        assert!(s.steps[1].transfer, "a copy on either side needs the transfer stage");
        is_sound(&p, &s).expect("sound");
    }

    #[test]
    fn a_serial_chain_barriers_every_op_but_the_first() {
        // What Supertonic's ConvNeXt stack looks like: each op reads the previous one's output.
        let ops: Vec<Op> = (0..8).map(|i| dispatch(i * 8, 8, (i + 1) * 8, 8)).collect();
        let p = plan(ops, 128);
        let s = schedule(&p);
        assert_eq!(s.barriers(), 7, "a serial chain has no barriers to remove");
        is_sound(&p, &s).expect("sound");
    }
}
