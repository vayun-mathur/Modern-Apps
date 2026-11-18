//! Graph-section tests beyond the minimal conv in `graph_tests`:
//! the phase-1 node kinds (add, channel add, layer norm, global pool,
//! reshape) parsed, validated, lowered to a `Builder` pass and run
//! through the CPU reference; the section's structural refusals; and
//! an emit -> parse round trip for a recorded add.

use super::{
    Graph, Offsets, Tensor, Dtype, NO_TENSOR, TAG_ADD, TAG_ADD_BROADCAST, TAG_CONV,
    TAG_GLOBAL_AVG_POOL, TAG_LAYER_NORM, TAG_RESHAPE,
};
use crate::nets::reference::run_multi;
use crate::nets::tests::Shapes;
use crate::nets::{Builder, Shape};
use crate::weights::{graph, write_mixed, Fixture, Weights};

/// Serialise a section from `(tag, payload u32s)` nodes.
fn section(
    nodes: &[(u8, Vec<u32>)],
    computed: &[[u32; 3]],
    inputs: &[u32],
    outputs: &[u32],
    hosts: &[(u32, u32, [u32; 4])],
) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&(nodes.len() as u32).to_le_bytes());
    for (tag, payload) in nodes {
        bytes.push(*tag);
        for v in payload {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
    }
    bytes.extend_from_slice(&(computed.len() as u32).to_le_bytes());
    for shape in computed {
        for v in shape {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
    }
    for list in [inputs, outputs] {
        bytes.extend_from_slice(&(list.len() as u32).to_le_bytes());
        for v in list {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
    }
    bytes.extend_from_slice(&(hosts.len() as u32).to_le_bytes());
    for (index, rank, dims) in hosts {
        bytes.extend_from_slice(&index.to_le_bytes());
        bytes.extend_from_slice(&rank.to_le_bytes());
        for v in dims {
            bytes.extend_from_slice(&v.to_le_bytes());
        }
    }
    bytes
}

fn close(got: &[f32], want: &[f32]) {
    assert_eq!(got.len(), want.len(), "got {got:?}, want {want:?}");
    for (g, w) in got.iter().zip(want) {
        assert!((g - w).abs() < 1e-3, "got {got:?}, want {want:?}");
    }
}

/// Parse `bytes`, lower onto a fresh builder over `source`, finish, run.
fn lower_and_run(
    bytes: &[u8],
    table: &[Tensor],
    offsets: &Offsets,
    source: &dyn crate::nets::WeightSource,
    inputs: &[&[f32]],
) -> Vec<Vec<f32>> {
    let graph = Graph::parse(bytes, table).expect("the section parses");
    let mut builder = Builder::new(source);
    let outs = graph.lower(&mut builder, offsets).expect("the section lowers");
    let plan = builder.finish(&outs).expect("the lowered pass builds");
    run_multi(&plan, &[], inputs).expect("the lowered pass runs")
}

#[test]
fn an_add_section_lowers_and_adds_positionally() {
    let bytes = section(
        &[(TAG_ADD, vec![0, 1, 2])],
        &[[1, 1, 2], [1, 1, 2], [1, 1, 2]],
        &[0, 1],
        &[2],
        &[],
    );
    let out = lower_and_run(&bytes, &[], &Offsets::empty(), &Shapes::new(0), &[&[1.0, 2.0], &[10.0, 20.0]]);
    close(&out[0], &[11.0, 22.0]);
}

#[test]
fn an_add_broadcast_section_adds_per_channel() {
    let bytes = section(
        &[(TAG_ADD_BROADCAST, vec![0, 1, 2])],
        &[[2, 1, 2], [2, 1, 1], [2, 1, 2]],
        &[0, 1],
        &[2],
        &[],
    );
    let out = lower_and_run(
        &bytes,
        &[],
        &Offsets::empty(),
        &Shapes::new(0),
        &[&[1.0, 2.0, 3.0, 4.0], &[10.0, 20.0]],
    );
    close(&out[0], &[11.0, 12.0, 23.0, 24.0]);
}

#[test]
fn a_pool_then_reshape_section_means_each_channel() {
    let bytes = section(
        &[(TAG_GLOBAL_AVG_POOL, vec![0, 1]), (TAG_RESHAPE, vec![1, 2])],
        &[[2, 2, 2], [2, 1, 1], [1, 1, 2]],
        &[0],
        &[2],
        &[],
    );
    let out = lower_and_run(
        &bytes,
        &[],
        &Offsets::empty(),
        &Shapes::new(0),
        &[&[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0]],
    );
    // Channel means 2.5 and 25, reshaped to one channel of two positions.
    close(&out[0], &[2.5, 25.0]);
}

#[test]
fn a_layer_norm_section_applies_gamma_and_beta_per_channel() {
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::F16(vec![2], vec![1.0, 2.0]),
            Fixture::F16(vec![2], vec![0.0, 0.5]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let table: Vec<Tensor> = weights.offsets().tensors.clone();
    let bytes = section(
        &[(TAG_LAYER_NORM, vec![0, 1, 0, 1, 0.0f32.to_bits()])],
        &[[2, 1, 2], [2, 1, 2]],
        &[0],
        &[1],
        &[],
    );
    let graph = Graph::parse(&bytes, &table).expect("parses");
    let mut builder = Builder::new(&weights);
    let outs = graph.lower(&mut builder, &weights.offsets()).expect("lowers");
    let plan = builder.finish(&outs).expect("builds");
    let out = run_multi(&plan, weights.data(), &[&[1.0, 2.0, 3.0, 4.0]]).expect("runs");
    // Each position's channel pair has mean mid and unit spread:
    // [1,3] -> [-1,1], [2,4] -> [-1,1]; then * gamma + beta.
    close(&out[0], &[-1.0, -1.0, 2.5, 2.5]);
}

#[test]
fn structural_refusals_name_the_fault() {
    let table: Vec<Tensor> = Vec::new();
    // No nodes at all.
    assert!(Graph::parse(&section(&[], &[[1, 1, 1]], &[0], &[0], &[]), &table).is_err());
    // No inputs / no outputs.
    let add = &[(TAG_ADD, vec![0, 1, 2])];
    let shapes = &[[1, 1, 2], [1, 1, 2], [1, 1, 2]];
    assert!(Graph::parse(&section(add, shapes, &[], &[2], &[]), &table).is_err());
    assert!(Graph::parse(&section(add, shapes, &[0], &[], &[]), &table).is_err());
    // A binding past the computed table.
    assert!(Graph::parse(&section(add, shapes, &[9], &[2], &[]), &table).is_err());
    // A truncated node payload.
    let mut short = section(add, shapes, &[0], &[2], &[]);
    short.truncate(6);
    assert!(Graph::parse(&short, &table).is_err());
    // A host tensor with an impossible rank.
    let host = &[(0u32, 5u32, [1, 0, 0, 0])];
    assert!(Graph::parse(&section(add, shapes, &[0], &[2], host), &table).is_err());
}

#[test]
fn shape_refusals_name_the_node() {
    let table: Vec<Tensor> = Vec::new();
    // Add of mismatched shapes.
    let bytes = section(&[(TAG_ADD, vec![0, 1, 2])], &[[1, 1, 2], [1, 1, 3], [1, 1, 2]], &[0], &[2], &[]);
    assert!(Graph::parse(&bytes, &table).expect_err("mismatched add").contains("add of"));
    // Channel-add whose second operand is not per-channel.
    let bytes = section(
        &[(TAG_ADD_BROADCAST, vec![0, 1, 2])],
        &[[2, 1, 2], [2, 1, 2], [2, 1, 2]],
        &[0],
        &[2],
        &[],
    );
    assert!(Graph::parse(&bytes, &table).is_err());
    // Pool into a non-singleton plane.
    let bytes = section(&[(TAG_GLOBAL_AVG_POOL, vec![0, 1])], &[[1, 2, 2], [1, 2, 1]], &[0], &[1], &[]);
    assert!(Graph::parse(&bytes, &table).is_err());
    // Reshape that changes the element count.
    let bytes = section(&[(TAG_RESHAPE, vec![0, 1])], &[[1, 2, 2], [1, 1, 3]], &[0], &[1], &[]);
    assert!(Graph::parse(&bytes, &table).is_err());
}

#[test]
fn a_conv_section_with_a_slope_is_refused_at_lowering() {
    // One 1x1 conv, act = PReLU (4) with a slope tensor, which the
    // validator accepts and phase-1 lowering refuses.
    let mut payload = vec![0u32, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 4, 2];
    payload.push(0); // pad_edge = false; payload is 18 u32s in all
    let bytes = section(
        &[(TAG_CONV, payload)],
        &[[2, 1, 2], [2, 1, 2]],
        &[0],
        &[1],
        &[],
    );
    let table = vec![
        Tensor { rank: 4, dims: [2, 2, 1, 1], offset: 0, len: 4, dtype: Dtype::F16 },
        Tensor { rank: 1, dims: [2, 0, 0, 0], offset: 16, len: 2, dtype: Dtype::F16 },
        Tensor { rank: 3, dims: [2, 1, 1, 0], offset: 32, len: 2, dtype: Dtype::F16 },
    ];
    let graph = Graph::parse(&bytes, &table).expect("the validator accepts a sloped PReLU");
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let offsets = Offsets { tensors: table.clone() };
    let err = graph.lower(&mut builder, &offsets).expect_err("phase 1 cannot lower a PReLU");
    assert!(err.contains("PReLU"), "{err}");
    let _ = NO_TENSOR;
}

#[test]
fn lowering_refuses_a_read_before_any_write() {
    // Add reads computed 2, which is neither an input nor produced.
    let bytes = section(
        &[(TAG_ADD, vec![0, 2, 1])],
        &[[1, 1, 2], [1, 1, 2], [1, 1, 2]],
        &[0],
        &[1],
        &[],
    );
    let graph = Graph::parse(&bytes, &[]).expect("shapes alone parse");
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let err = graph.lower(&mut builder, &Offsets::empty()).expect_err("unwritten read");
    assert!(err.contains("read before it is written"), "{err}");
}

#[test]
fn a_recorded_add_emits_and_parses_back() {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 2));
    let b = builder.input(Shape::new(1, 1, 2));
    let out = builder.add(a, b);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &Offsets::empty(), &[out]).expect("emits");
    let graph = Graph::parse(&bytes, &[]).expect("the emission parses back");
    assert_eq!(graph.nodes.len(), 1);
    assert_eq!(graph.inputs.len(), 2);
    assert_eq!(graph.outputs.len(), 1);
    assert_eq!(graph.computed.len(), 3);
}

#[test]
fn emitting_a_transposed_conv_is_refused() {
    let source = Shapes::new(2);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 2, 2));
    let out = builder.conv_transpose(a, 0, 1, (1, 1), (1, 1), (0, 0, 0, 0), crate::nets::Act::None);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let err = Graph::emit(&recorded, &Offsets::empty(), &[out]).expect_err("transposed is not phase 1");
    assert!(err.contains("transposed"), "{err}");
}

fn tensor(rank: u32, dims: [u32; 4], offset: u32, len: u32) -> Tensor {
    Tensor { rank, dims, offset, len, dtype: Dtype::F16 }
}

#[test]
fn a_recorded_conv_with_relu_emits_and_parses_back() {
    let source = Shapes::new(2);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 1, 1));
    let out = builder.conv(a, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, crate::nets::Act::Relu);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    // Shapes answers tensor i at element offset i: kernel at byte 0
    // (2 elements), bias at byte 2 (1 element).
    let table = vec![tensor(4, [1, 2, 1, 1], 0, 2), tensor(1, [1, 0, 0, 0], 2, 1)];
    let offsets = Offsets { tensors: table.clone() };
    let bytes = Graph::emit(&recorded, &offsets, &[out]).expect("emits");
    let graph = Graph::parse(&bytes, &table).expect("the emission parses back");
    assert_eq!(graph.nodes.len(), 1);
    // The same emission against an empty table names no tensor.
    assert!(Graph::emit(&recorded, &Offsets::empty(), &[out]).is_err());
}

#[test]
fn recorded_quantised_convs_emit_their_quant_codes() {
    // int8: kernel (word offset 0), scale (byte 2), bias (byte 4).
    let table = vec![
        tensor(4, [1, 2, 1, 1], 0, 2),
        tensor(1, [1, 0, 0, 0], 2, 1),
        tensor(1, [1, 0, 0, 0], 4, 1),
    ];
    let offsets = Offsets { tensors: table.clone() };
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 1, 1));
    let out = builder.conv_int8(a, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, crate::nets::Act::None);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &offsets, &[out]).expect("emits int8");
    assert_eq!(Graph::parse(&bytes, &table).expect("parses").nodes.len(), 1);

    // int4: same layout, and the emitter must stamp quant code 1,
    // which the parser then validates against a [out, blocks] scale.
    let table4 = vec![
        tensor(4, [1, 2, 1, 1], 0, 2),
        tensor(2, [1, 1, 0, 0], 2, 1),
        tensor(1, [1, 0, 0, 0], 4, 1),
    ];
    let offsets4 = Offsets { tensors: table4.clone() };
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 1, 1));
    let out = builder.conv_int4(a, 0, 1, crate::nets::Act::None);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &offsets4, &[out]).expect("emits int4");
    assert_eq!(Graph::parse(&bytes, &table4).expect("parses").nodes.len(), 1);
}

#[test]
fn recorded_norm_pool_reshape_and_channel_add_emit_their_tags() {
    // LayerNorm: gamma at byte 0, beta at byte 2, both [2].
    let table = vec![tensor(1, [2, 0, 0, 0], 0, 2), tensor(1, [2, 0, 0, 0], 2, 2)];
    let offsets = Offsets { tensors: table.clone() };
    let source = Shapes::new(2);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 1, 2));
    let out = builder.layer_norm(a, 0, 0.0);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &offsets, &[out]).expect("emits norm");
    assert_eq!(Graph::parse(&bytes, &table).expect("parses").nodes.len(), 1);

    // GlobalAvgPool then a single-part reshaped copy: both tags, no weights.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 2, 2));
    let pooled = builder.global_avg_pool(a);
    let out = builder.reshaped(pooled, Shape::new(1, 1, 2));
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &Offsets::empty(), &[out]).expect("emits pool+reshape");
    assert_eq!(Graph::parse(&bytes, &[]).expect("parses").nodes.len(), 2);

    // A channel add emits the broadcast tag.
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(2, 1, 2));
    let b = builder.input(Shape::new(2, 1, 1));
    let out = builder.add_channel(a, b);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &Offsets::empty(), &[out]).expect("emits channel add");
    assert_eq!(Graph::parse(&bytes, &[]).expect("parses").nodes.len(), 1);
}

#[test]
fn emitting_a_plain_mul_is_refused_as_not_phase_1() {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 2));
    let b = builder.input(Shape::new(1, 1, 2));
    let out = builder.mul(a, b);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let err = Graph::emit(&recorded, &Offsets::empty(), &[out]).expect_err("mul is not phase 1");
    assert!(err.contains("not in phase 1"), "{err}");
}

#[test]
fn a_conv_section_lowers_and_runs_with_its_activation() {
    // Identity 1x1 conv over two channels, act = Relu (code 1).
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::F16(vec![2, 2, 1, 1], vec![1.0, 0.0, 0.0, 1.0]),
            Fixture::F16(vec![2], vec![0.0, 0.0]),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("parses");
    let table: Vec<Tensor> = weights.offsets().tensors.clone();
    let payload = vec![0u32, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1, NO_TENSOR, 0];
    let bytes = section(&[(TAG_CONV, payload)], &[[2, 1, 2], [2, 1, 2]], &[0], &[1], &[]);
    let graph = Graph::parse(&bytes, &table).expect("parses");
    let mut builder = Builder::new(&weights);
    let outs = graph.lower(&mut builder, &weights.offsets()).expect("lowers");
    let plan = builder.finish(&outs).expect("builds");
    let out = run_multi(&plan, weights.data(), &[&[3.0, -4.0, -1.0, 2.0]]).expect("runs");
    // Identity kernel, then Relu per channel: negatives clamp to 0.
    close(&out[0], &[3.0, 0.0, 0.0, 2.0]);
}

#[test]
fn a_quantised_conv_section_lowers_for_both_quant_codes() {
    use super::TAG_CONV_INT8;
    for quant in [0u32, 1] {
        // in 32 channels (one int4 block), one output, 1x1.
        let payload = vec![
            0, 1, 0, 1, 2, // input, out, weight, scale, bias
            1, 1, 1, 1, 1, 1, // kernel, stride, dilation
            0, 0, 0, 0, // pads
            1, 0, quant, // group, act, quant
        ];
        assert_eq!(payload.len(), 18);
        let bytes = section(
            &[(TAG_CONV_INT8, payload)],
            &[[32, 1, 1], [1, 1, 1]],
            &[0],
            &[1],
            &[],
        );
        let table = vec![
            tensor(4, [1, 32, 1, 1], 0, 32),
            if quant == 0 { tensor(1, [1, 0, 0, 0], 64, 1) } else { tensor(2, [1, 1, 0, 0], 64, 1) },
            tensor(1, [1, 0, 0, 0], 66, 1),
        ];
        let graph = Graph::parse(&bytes, &table).expect("parses");
        let source = Shapes::new(3);
        let mut builder = Builder::new(&source);
        let offsets = Offsets { tensors: table.clone() };
        let outs = graph.lower(&mut builder, &offsets).expect("lowers");
        builder.finish(&outs).expect("the lowered quantised conv builds");
    }
}

#[test]
fn lowering_refuses_an_output_nothing_produced() {
    let bytes = section(
        &[(TAG_ADD, vec![0, 1, 2])],
        &[[1, 1, 2], [1, 1, 2], [1, 1, 2], [1, 1, 2]],
        &[0, 1],
        &[3],
        &[],
    );
    let graph = Graph::parse(&bytes, &[]).expect("parses");
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    assert!(graph.lower(&mut builder, &Offsets::empty()).is_err());
}

#[test]
fn conv_validation_refusals_name_the_fault() {
    // Group 0, and a group that does not divide the channels.
    for group in [0u32, 3] {
        let payload = vec![0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, group, 0, NO_TENSOR, 0];
        let bytes = section(&[(TAG_CONV, payload)], &[[2, 1, 2], [2, 1, 2]], &[0], &[1], &[]);
        let table = vec![tensor(4, [2, 2, 1, 1], 0, 4), tensor(1, [2, 0, 0, 0], 16, 2)];
        assert!(Graph::parse(&bytes, &table).is_err(), "group {group}");
    }
    // A stated output shape the geometry does not produce (2x2 in, 1x1
    // kernel, stated 9 wide out).
    let payload = vec![0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, NO_TENSOR, 0];
    let bytes = section(&[(TAG_CONV, payload)], &[[2, 2, 2], [2, 1, 9]], &[0], &[1], &[]);
    let table = vec![tensor(4, [2, 2, 1, 1], 0, 4), tensor(1, [2, 0, 0, 0], 16, 2)];
    assert!(Graph::parse(&bytes, &table).is_err(), "shape mismatch");
    // A slope without a PReLU, and a host tensor past the table.
    let payload = vec![0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, 1, 0];
    let bytes = section(&[(TAG_CONV, payload)], &[[2, 1, 2], [2, 1, 2]], &[0], &[1], &[]);
    assert!(Graph::parse(&bytes, &table).is_err(), "slope without PReLU");
    let bytes = section(
        &[(TAG_ADD, vec![0, 1, 2])],
        &[[1, 1, 2], [1, 1, 2], [1, 1, 2]],
        &[0],
        &[2],
        &[(7, 1, [1, 0, 0, 0])],
    );
    assert!(Graph::parse(&bytes, &table).is_err(), "host past the table");
}
