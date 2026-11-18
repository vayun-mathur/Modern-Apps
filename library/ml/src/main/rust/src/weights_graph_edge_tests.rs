//! Graph-section edge tests: count ceilings, `pad_edge`, quant-code
//! and activation validation arms not covered by the main graph tests.

use super::{
    Graph, GraphNode, Offsets, Tensor, Dtype, NO_TENSOR, TAG_ADD, TAG_CONV, TAG_CONV_INT8,
    TAG_LAYER_NORM,
};
use crate::nets::tests::Shapes;
use crate::nets::{Act, Builder, Id, Node, Quant, Recorded, Shape};

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

fn tensor(rank: u32, dims: [u32; 4], len: u32) -> Tensor {
    Tensor { rank, dims, offset: 0, len, dtype: Dtype::F16 }
}

#[test]
fn count_ceilings_are_refused_before_any_entry_is_read() {
    let add = (TAG_ADD, vec![0, 1, 2]);
    // Computed-table count past the ceiling.
    let mut bytes = Vec::new();
    bytes.extend_from_slice(&1u32.to_le_bytes());
    bytes.push(TAG_ADD);
    for v in [0u32, 1, 2] {
        bytes.extend_from_slice(&v.to_le_bytes());
    }
    bytes.extend_from_slice(&65_537u32.to_le_bytes());
    let err = Graph::parse(&bytes, &[]).expect_err("computed ceiling");
    assert!(err.contains("claims 65537 computed tensors"), "{err}");

    // Host-table count past the ceiling (structure otherwise valid).
    let mut bytes = section(&[add], &[[1, 1, 2], [1, 1, 2], [1, 1, 2]], &[0], &[2], &[]);
    // Host count is the final u32 of an otherwise empty host list;
    // overwrite it in place.
    let n = bytes.len();
    bytes[n - 4..].copy_from_slice(&65_537u32.to_le_bytes());
    let err = Graph::parse(&bytes, &[]).expect_err("host ceiling");
    assert!(err.contains("claims 65537 host tensors"), "{err}");
}

#[test]
fn pad_edge_one_and_a_missing_prelu_slope_are_handled() {
    let kernel_bias = vec![tensor(4, [2, 2, 1, 1], 4), tensor(1, [2, 0, 0, 0], 2)];
    let shapes = &[[2, 1, 2], [2, 1, 2]];
    // pad_edge = 1 is a valid edge mode.
    let payload = vec![0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, NO_TENSOR, 1];
    let bytes = section(&[(TAG_CONV, payload)], shapes, &[0], &[1], &[]);
    assert_eq!(Graph::parse(&bytes, &kernel_bias).expect("pad_edge parses").nodes.len(), 1);
    // A pad_edge other than 0 or 1 is refused at the tag itself.
    let payload = vec![0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, NO_TENSOR, 2];
    let bytes = section(&[(TAG_CONV, payload)], shapes, &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &kernel_bias).expect_err("pad_edge 2");
    assert!(err.contains("pad_edge 2, not 0 or 1"), "{err}");
    // PReLU (act 4) naming no slope tensor is refused.
    let payload = vec![0, 1, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 4, NO_TENSOR, 0];
    let bytes = section(&[(TAG_CONV, payload)], shapes, &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &kernel_bias).expect_err("slope missing");
    assert!(err.contains("PReLU with no slope tensor"), "{err}");
}

#[test]
fn quantised_conv_validation_names_each_fault() {
    let shapes = &[[32, 1, 1], [1, 1, 1]];
    let base = vec![0, 1, 0, 1, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 1, 0, 0];
    // A quant code other than 0/1 never reaches shape validation.
    let mut payload = base.clone();
    payload[17] = 2;
    let bytes = section(&[(TAG_CONV_INT8, payload)], shapes, &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &[]).expect_err("bad quant");
    assert!(err.contains("quant 2, not 0 or 1"), "{err}");
    // A quantised conv cannot carry a PReLU.
    let mut payload = base.clone();
    payload[16] = 4;
    let bytes = section(&[(TAG_CONV_INT8, payload)], shapes, &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &[]).expect_err("int8 prelu");
    assert!(err.contains("cannot carry a PReLU"), "{err}");
    // 32 channels do not split into 3 groups.
    let mut payload = base.clone();
    payload[15] = 3;
    let bytes = section(&[(TAG_CONV_INT8, payload)], shapes, &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &[]).expect_err("bad groups");
    assert!(err.contains("bad groups for 32"), "{err}");
    // Stated output geometry the 1x1 kernel does not produce.
    let table = vec![tensor(4, [1, 32, 1, 1], 32), tensor(1, [1, 0, 0, 0], 1), tensor(1, [1, 0, 0, 0], 1)];
    let bytes = section(&[(TAG_CONV_INT8, base)], &[[32, 2, 2], [1, 3, 3]], &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &table).expect_err("geometry");
    assert!(err.contains("mismatches the geometry"), "{err}");
}

#[test]
fn norm_shape_and_host_shape_mismatches_are_refused() {
    // LayerNorm whose output shape differs from its input.
    let payload = vec![0, 1, 0, 1, 0.0f32.to_bits()];
    let bytes = section(&[(TAG_LAYER_NORM, payload)], &[[2, 1, 2], [2, 1, 3]], &[0], &[1], &[]);
    let err = Graph::parse(&bytes, &[]).expect_err("norm mismatch");
    assert!(err.contains("norm of [2, 1, 2] into [2, 1, 3]"), "{err}");
    // A host tensor whose stated dims are not the table tensor's.
    let bytes = section(
        &[(TAG_ADD, vec![0, 1, 2])],
        &[[1, 1, 2], [1, 1, 2], [1, 1, 2]],
        &[0],
        &[2],
        &[(0, 1, [9, 0, 0, 0])],
    );
    let table = vec![tensor(1, [2, 0, 0, 0], 2)];
    let err = Graph::parse(&bytes, &table).expect_err("host mismatch");
    assert!(err.contains("not [9]"), "{err}");
}

fn manual(nodes: Vec<GraphNode>, computed: Vec<[u32; 3]>, inputs: Vec<u32>, outputs: Vec<u32>) -> Graph {
    Graph { nodes, computed, inputs, outputs, host: Vec::new() }
}

fn lower(graph: &Graph, table: &Offsets) -> Result<Vec<crate::nets::Id>, String> {
    let source = Shapes::new(8);
    let mut builder = Builder::new(&source);
    graph.lower(&mut builder, table)
}

fn conv_node(act: u32, weight: u32, out: u32) -> GraphNode {
    GraphNode::Conv {
        input: 0, out, weight, bias: 1, kernel: (1, 1), stride: (1, 1),
        dilation: (1, 1), pads: (0, 0, 0, 0), group: 1, act,
        act_weight: NO_TENSOR, pad_edge: false,
    }
}

fn conv_int8_node(act: u32, quant: u32, weight: u32, out: u32) -> GraphNode {
    GraphNode::ConvInt8 {
        input: 0, out, weight, scale: 1, bias: 2, kernel: (1, 1), stride: (1, 1),
        dilation: (1, 1), pads: (0, 0, 0, 0), group: 1, act, quant,
    }
}

#[test]
fn lowering_refuses_indices_that_do_not_resolve() {
    let empty = Offsets { tensors: Vec::new() };
    // An input naming a computed tensor that does not exist.
    let graph = manual(Vec::new(), vec![[1, 1, 1]], vec![7], vec![0]);
    assert!(lower(&graph, &empty).expect_err("input range").contains("input names computed tensor 7"));
    // A conv whose stated output is past the computed table.
    let graph = manual(vec![conv_node(0, 0, 9)], vec![[2, 1, 2]], vec![0], vec![0]);
    assert!(lower(&graph, &empty).expect_err("conv out range").contains("computed tensor 9 out of range"));
    // A conv whose kernel is past the file table.
    let graph = manual(vec![conv_node(0, 9, 1)], vec![[2, 1, 2], [2, 1, 2]], vec![0], vec![1]);
    assert!(lower(&graph, &empty).expect_err("kernel range").contains("file tensor 9 of 0"));
    // A quantised conv whose kernel is past the file table.
    let graph = manual(vec![conv_int8_node(0, 0, 9, 1)], vec![[32, 1, 1], [1, 1, 1]], vec![0], vec![1]);
    assert!(lower(&graph, &empty).expect_err("int8 kernel range").contains("file tensor 9 of 0"));
    // Pool and reshape outputs past the computed table.
    let graph = manual(
        vec![GraphNode::GlobalAvgPool { input: 0, out: 9 }],
        vec![[2, 2, 2]], vec![0], vec![0],
    );
    assert!(lower(&graph, &empty).expect_err("pool range").contains("computed tensor 9 out of range"));
    let graph = manual(
        vec![GraphNode::Reshape { input: 0, out: 9 }],
        vec![[2, 2, 2]], vec![0], vec![0],
    );
    assert!(lower(&graph, &empty).expect_err("reshape range").contains("computed tensor 9 out of range"));
}

#[test]
fn lowering_accepts_hardswish_and_q2k_and_refuses_a_section_prelu() {
    let table = Offsets {
        tensors: vec![
            tensor(4, [1, 32, 1, 1], 32),
            tensor(1, [1, 0, 0, 0], 1),
            tensor(1, [1, 0, 0, 0], 1),
        ],
    };
    // Activation code 2 (HardSwish) on a plain conv.
    let conv_table = Offsets {
        tensors: vec![tensor(4, [2, 2, 1, 1], 4), tensor(1, [2, 0, 0, 0], 2), tensor(1, [1, 0, 0, 0], 1)],
    };
    let graph = manual(vec![conv_node(2, 0, 1)], vec![[2, 1, 2], [2, 1, 2]], vec![0], vec![1]);
    assert_eq!(lower(&graph, &conv_table).expect("hardswish lowers").len(), 1);
    // Quant code 2 (Q2_K) on a quantised conv.
    let graph = manual(vec![conv_int8_node(0, 2, 0, 1)], vec![[32, 1, 1], [1, 1, 1]], vec![0], vec![1]);
    assert_eq!(lower(&graph, &table).expect("q2k lowers").len(), 1);
    // Activation code 4 (PReLU) cannot be reconstructed from the code alone.
    let graph = manual(vec![conv_int8_node(4, 0, 0, 1)], vec![[32, 1, 1], [1, 1, 1]], vec![0], vec![1]);
    let err = lower(&graph, &table).expect_err("section prelu");
    assert!(err.contains("section PReLU"), "{err}");
    // A pool whose lowered shape disagrees with the section's statement.
    let graph = manual(
        vec![GraphNode::GlobalAvgPool { input: 0, out: 1 }],
        vec![[2, 2, 2], [2, 1, 2]], vec![0], vec![1],
    );
    let err = lower(&graph, &table).expect_err("pool shape");
    assert!(err.contains("global pool lowered to"), "{err}");
}

#[test]
fn emitting_convs_serialises_each_activation_code() {
    // Kernel at byte 0 (2 elements), bias at byte 2 (1 element), as
    // the shape stub's element offsets lay them out.
    let table = vec![
        Tensor { offset: 0, ..tensor(4, [1, 2, 1, 1], 2) },
        Tensor { offset: 2, ..tensor(1, [1, 0, 0, 0], 1) },
    ];
    let offsets = Offsets { tensors: table.clone() };
    for act in [Act::HardSwish, Act::Sigmoid, Act::Clip01, Act::Swish, Act::Relu6, Act::Gelu] {
        let source = Shapes::new(2);
        let mut builder = Builder::new(&source);
        let a = builder.input(Shape::new(2, 1, 1));
        let out = builder.conv(a, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act);
        let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
        let bytes = Graph::emit(&recorded, &offsets, &[out]).expect("emits");
        assert_eq!(Graph::parse(&bytes, &table).expect("parses back").nodes.len(), 1, "{act:?}");
    }
}

#[test]
fn emitting_a_fused_residual_conv_is_refused() {
    // conv -> add(input, conv) fuses the add into the conv's store at
    // record time; the emitter must refuse rather than serialise a
    // graph whose add has vanished.
    let source = Shapes::new(2);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(1, 1, 2));
    let conv = builder.conv(a, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None);
    let out = builder.add(a, conv);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let err = Graph::emit(&recorded, &Offsets::empty(), &[out]).expect_err("fused addend");
    assert!(err.contains("fused addend"), "{err}");
}
