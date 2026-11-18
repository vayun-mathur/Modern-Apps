//! Schedule tests beyond the inline hazard cases: the `kinds`
//! survey over a built plan, `is_sound` catching a hand-made
//! barrier-free schedule that conflicts, and an out-of-arena write
//! widening to a full flush.

use super::{is_sound, kinds, schedule, Causes, Schedule, Step};
use crate::nets::tests::Shapes;
use crate::nets::{Binding, Builder, Kind, Op, Plan, Push, Shape};

fn dispatch(in0: u32, out: u32) -> Op {
    Op::Dispatch {
        kind: Kind::Softmax,
        push: Push {
            in0,
            out,
            in_c: 8,
            in_h: 1,
            in_w: 1,
            out_c: 8,
            out_h: 1,
            out_w: 1,
            count: 8,
            ..Push::default()
        },
        invocations: 8,
    }
}

fn plan(ops: Vec<Op>, arena_elems: u32) -> Plan {
    Plan {
        ops,
        arena_elems,
        inputs: vec![Binding { at: 0, shape: Shape::new(1, 1, 1) }],
        pinned: Vec::new(),
        outputs: vec![Binding { at: 0, shape: Shape::new(1, 1, 1) }],
        widen: Vec::new(),
    }
}

#[test]
fn kinds_counts_dispatches_by_kind_most_common_first() {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 4));
    let b = builder.input(Shape::new(1, 1, 4));
    let sum = builder.add(a, b);
    let again = builder.add(sum, b);
    let soft = builder.softmax(again);
    let built = builder.finish(&[soft]).expect("builds");
    assert_eq!(kinds(&built), vec![(Kind::Add, 2), (Kind::Softmax, 1)]);
    // A plan of copies only has no dispatch kinds to count.
    assert!(kinds(&plan(vec![Op::Copy { src: 0, dst: 8, elems: 4 }], 64)).is_empty());
}

#[test]
fn is_sound_rejects_a_barrier_free_schedule_over_a_conflict() {
    // Both ops touch 0..16 bytes in elements 0..8: the second reads
    // what the first wrote, so a schedule with no barrier is unsound.
    let p = plan(vec![dispatch(0, 8), dispatch(8, 24)], 64);
    let unsound = Schedule {
        steps: vec![
            Step { barrier: false, transfer: false },
            Step { barrier: false, transfer: false },
        ],
        causes: Causes::default(),
    };
    let err = is_sound(&p, &unsound).expect_err("the conflict must be reported");
    assert!(err.contains("conflict"), "{err}");
    // The computed schedule inserts the barrier and is sound.
    let computed = schedule(&p);
    assert!(computed.steps[1].barrier);
    is_sound(&p, &computed).expect("the computed schedule is sound");
}

#[test]
fn a_write_outside_the_arena_forces_a_full_flush() {
    // The output claims to land far past the arena: the scheduler
    // cannot bound it, so it counts as unbounded and barriers.
    let p = plan(vec![dispatch(0, 10_000)], 64);
    let s = schedule(&p);
    assert_eq!(s.causes.unbounded, 1);
    assert!(s.steps[0].barrier);
    is_sound(&p, &s).expect("sound");
}

#[test]
fn an_empty_plan_schedules_to_nothing() {
    let p = plan(Vec::new(), 64);
    let s = schedule(&p);
    assert!(s.steps.is_empty());
    assert_eq!(s.barriers(), 0);
    is_sound(&p, &s).expect("vacuously sound");
}
