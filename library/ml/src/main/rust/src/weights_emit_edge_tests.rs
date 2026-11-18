//! Emitter edge tests: hand-assembled recordings the builder
//! itself never produces (incomplete shapes, PReLU and fused
//! quantised nodes), the Q2_K code seam, and unknown code refusal
//! on the lowering side. Split from `weights_graph_edge_tests.rs`
//! to keep both files under the repository's line limit.

use super::{Graph, GraphNode, Offsets, Tensor, Dtype, NO_TENSOR};
use crate::nets::tests::Shapes;
use crate::nets::{Act, Builder, Id, Node, Quant, Recorded, Shape};

fn tensor(rank: u32, dims: [u32; 4], len: u32) -> Tensor {
    Tensor { rank, dims, offset: 0, len, dtype: Dtype::F16 }
}

fn manual(nodes: Vec<GraphNode>, computed: Vec<[u32; 3]>, inputs: Vec<u32>, outputs: Vec<u32>) -> Graph {
    Graph { nodes, computed, inputs, outputs, host: Vec::new() }
}

fn lower(graph: &Graph, table: &Offsets) -> Result<Vec<Id>, String> {
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

/// A hand-assembled recording, for the emitter states the builder
/// itself never produces (its shapes are always complete).
fn recorded(nodes: Vec<Node>, shapes: Vec<Shape>, inputs: Vec<Id>) -> Recorded {
    let source = Shapes::new(0);
    let mut builder = Builder::new(&source);
    let x = builder.input(Shape::new(1, 1, 1));
    let plan = builder.finish(&[x]).expect("a plan to carry");
    Recorded { plan, nodes, shapes, inputs, read: Vec::new() }
}

fn conv_node_record(input: Id, out: Id) -> Node {
    Node::Conv {
        input, out, weight: 0, bias: 0, kernel: (1, 1), stride: (1, 1),
        dilation: (1, 1), pad: (0, 0), group: 1, act: Act::None,
        act_weight: 0, transpose: false, pad_edge: false, res: None, shift: None,
    }
}

#[test]
fn emitting_a_recording_with_missing_shapes_names_the_tensor() {
    let one = Shape::new(1, 1, 1);
    // A conv output with no recorded shape.
    let rec = recorded(vec![conv_node_record(Id(0), Id(9))], vec![one], vec![Id(0)]);
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(9)]).expect_err("out shape");
    assert!(err.contains("output 9 has no shape"), "{err}");
    // A conv input with no recorded shape.
    let rec = recorded(vec![conv_node_record(Id(9), Id(0))], vec![one], vec![Id(0)]);
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(0)]).expect_err("in shape");
    assert!(err.contains("input 9 has no shape"), "{err}");
    // A quantised conv input with no recorded shape.
    let rec = recorded(
        vec![Node::ConvInt8 {
            input: Id(9), out: Id(0), weight: 0, scale: 0, bias: 0, kernel: (1, 1),
            stride: (1, 1), dilation: (1, 1), pad: (0, 0), group: 1, act: Act::None,
            quant: Quant::I8, res: None, shift: None, quant_scale: None, staged: None,
        }],
        vec![one],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(0)]).expect_err("int8 in shape");
    assert!(err.contains("input 9 has no shape"), "{err}");
    // A quantised conv output with no recorded shape.
    let rec = recorded(
        vec![Node::ConvInt8 {
            input: Id(0), out: Id(9), weight: 0, scale: 0, bias: 0, kernel: (1, 1),
            stride: (1, 1), dilation: (1, 1), pad: (0, 0), group: 1, act: Act::None,
            quant: Quant::I8, res: None, shift: None, quant_scale: None, staged: None,
        }],
        vec![one],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(9)]).expect_err("int8 out shape");
    assert!(err.contains("output 9 has no shape"), "{err}");
    // A norm output with no recorded shape.
    let rec = recorded(
        vec![Node::LayerNorm { input: Id(0), out: Id(9), gamma: 0, beta: 0, epsilon: 0.0 }],
        vec![one],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(9)]).expect_err("norm shape");
    assert!(err.contains("output 9 has no shape"), "{err}");
    // A pooled tensor the computed table cannot describe.
    let rec = recorded(
        vec![Node::GlobalAvgPool { input: Id(0), out: Id(1) }],
        vec![Shape::new(2, 2, 2)],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(1)]).expect_err("computed shape");
    assert!(err.contains("tensor 1 has no shape"), "{err}");
}

#[test]
fn emitting_prelu_and_fused_quantised_nodes_is_refused() {
    // A plain conv carrying a PReLU: kernel and bias resolve through
    // the table, then the slope check refuses.
    let table = vec![
        Tensor { offset: 0, ..tensor(4, [1, 2, 1, 1], 2) },
        Tensor { offset: 2, ..tensor(1, [1, 0, 0, 0], 1) },
    ];
    let offsets = Offsets { tensors: table };
    let mut node = conv_node_record(Id(0), Id(1));
    if let Node::Conv { act, act_weight, bias, .. } = &mut node {
        *act = Act::PRelu(0);
        *act_weight = 5;
        *bias = 1;
    }
    let rec = recorded(
        vec![node],
        vec![Shape::new(2, 1, 1), Shape::new(1, 1, 1)],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &offsets, &[Id(1)]).expect_err("conv prelu");
    assert!(err.contains("is a PReLU"), "{err}");
    // The same conv with a slope but no PReLU.
    let mut node = conv_node_record(Id(0), Id(1));
    if let Node::Conv { act_weight, bias, .. } = &mut node {
        *act_weight = 5;
        *bias = 1;
    }
    let rec = recorded(
        vec![node],
        vec![Shape::new(2, 1, 1), Shape::new(1, 1, 1)],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &offsets, &[Id(1)]).expect_err("slope without prelu");
    assert!(err.contains("a slope without a PReLU"), "{err}");
    // A quantised conv carrying a PReLU, and one carrying a fused
    // addend: both refused before any shape is consulted.
    let int8 = |act: Act, res: Option<Id>| Node::ConvInt8 {
        input: Id(0), out: Id(1), weight: 0, scale: 1, bias: 2, kernel: (1, 1),
        stride: (1, 1), dilation: (1, 1), pad: (0, 0), group: 1, act,
        quant: Quant::I8, res, shift: None, quant_scale: None, staged: None,
    };
    let shapes = vec![Shape::new(32, 1, 1), Shape::new(1, 1, 1)];
    let rec = recorded(vec![int8(Act::PRelu(0), None)], shapes.clone(), vec![Id(0)]);
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(1)]).expect_err("int8 prelu");
    assert!(err.contains("is a PReLU"), "{err}");
    let rec = recorded(vec![int8(Act::None, Some(Id(0)))], shapes, vec![Id(0)]);
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(1)]).expect_err("int8 fused");
    assert!(err.contains("fused addend"), "{err}");
    // A kind phase 1 does not serialise at all.
    let rec = recorded(
        vec![Node::MaxPool { input: Id(0), out: Id(1), kernel: (2, 2), stride: (2, 2) }],
        vec![Shape::new(1, 2, 2), Shape::new(1, 1, 1)],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(1)]).expect_err("maxpool");
    assert!(err.contains("not in phase 1"), "{err}");
}

#[test]
fn emitting_a_q2k_conv_writes_quant_code_two() {
    // The emitter's code table maps Q2_K to 2 — which the section
    // parser then refuses ("quant 2, not 0 or 1"): the format carries
    // no Q2_K today, and both sides of that seam are pinned here.
    let table = vec![
        Tensor { offset: 0, ..tensor(4, [1, 256, 1, 1], 256) },
        Tensor { offset: 2, ..tensor(1, [1, 0, 0, 0], 1) },
        Tensor { offset: 4, ..tensor(1, [1, 0, 0, 0], 1) },
    ];
    let offsets = Offsets { tensors: table.clone() };
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let a = builder.input(Shape::new(256, 1, 1));
    let out = builder.conv_q2k(a, 0, 1, Act::None);
    let recorded = builder.record(&[out], &Offsets::empty()).expect("records");
    let bytes = Graph::emit(&recorded, &offsets, &[out]).expect("emits");
    let err = Graph::parse(&bytes, &table).expect_err("the parser carries no Q2_K");
    assert!(err.contains("quant 2, not 0 or 1"), "{err}");
}

#[test]
fn lowering_refuses_unknown_activation_and_quant_codes() {
    let table = Offsets {
        tensors: vec![
            tensor(4, [1, 32, 1, 1], 32),
            tensor(1, [1, 0, 0, 0], 1),
            tensor(1, [1, 0, 0, 0], 1),
        ],
    };
    // Activation code 3 (Sigmoid) lowers fine.
    let graph = manual(vec![conv_node(3, 0, 1)], vec![[2, 1, 2], [2, 1, 2]], vec![0], vec![1]);
    let conv_table = Offsets {
        tensors: vec![tensor(4, [2, 2, 1, 1], 4), tensor(1, [2, 0, 0, 0], 2), tensor(1, [1, 0, 0, 0], 1)],
    };
    assert_eq!(lower(&graph, &conv_table).expect("sigmoid lowers").len(), 1);
    // An activation code with no meaning.
    let graph = manual(vec![conv_int8_node(9, 0, 0, 1)], vec![[32, 1, 1], [1, 1, 1]], vec![0], vec![1]);
    let err = lower(&graph, &table).expect_err("bad activation");
    assert!(err.contains("activation code 9"), "{err}");
    // A quant code with no meaning.
    let graph = manual(vec![conv_int8_node(0, 7, 0, 1)], vec![[32, 1, 1], [1, 1, 1]], vec![0], vec![1]);
    let err = lower(&graph, &table).expect_err("bad quant");
    assert!(err.contains("quant code 7"), "{err}");
    // A quantised conv whose stated output is past the computed table.
    let graph = manual(vec![conv_int8_node(0, 0, 0, 9)], vec![[32, 1, 1]], vec![0], vec![0]);
    let err = lower(&graph, &table).expect_err("int8 out range");
    assert!(err.contains("computed tensor 9 out of range"), "{err}");
}

#[test]
fn emitting_a_quantised_conv_with_a_fused_quantize_is_refused() {
    let rec = recorded(
        vec![Node::ConvInt8 {
            input: Id(0), out: Id(1), weight: 0, scale: 0, bias: 0, kernel: (1, 1),
            stride: (1, 1), dilation: (1, 1), pad: (0, 0), group: 1, act: Act::None,
            quant: Quant::I8, res: None, shift: None, quant_scale: Some(0.5), staged: None,
        }],
        vec![Shape::new(32, 1, 1), Shape::new(1, 1, 1)],
        vec![Id(0)],
    );
    let err = Graph::emit(&rec, &Offsets::empty(), &[Id(1)]).expect_err("fused quantize");
    assert!(err.contains("fused quantize"), "{err}");
}

#[test]
fn lowering_an_int4_spatial_conv_is_refused_and_a_padded_q2k_lowers_generic() {
    let table = Offsets {
        tensors: vec![
            tensor(4, [1, 32, 3, 3], 288),
            tensor(2, [1, 9, 0, 0], 9),
            tensor(1, [1, 0, 0, 0], 1),
        ],
    };
    // int4 with a 3x3 kernel: only the two 1x1 lowerings exist.
    let mut node = conv_int8_node(0, 1, 0, 1);
    if let GraphNode::ConvInt8 { kernel, .. } = &mut node {
        *kernel = (3, 3);
    }
    let graph = manual(vec![node], vec![[32, 4, 4], [1, 2, 2]], vec![0], vec![1]);
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let outs = graph.lower(&mut builder, &table).expect("lowers");
    let err = builder.finish(&outs).expect_err("spatial int4");
    assert!(err.contains("only the"), "{err}");

    // Q2_K with padding: not tiled, not a vector case, so the plan
    // carries the generic ConvQ2K kind.
    let mut node = conv_int8_node(0, 2, 0, 1);
    if let GraphNode::ConvInt8 { pads, .. } = &mut node {
        *pads = (1, 0, 0, 0);
    }
    let table = Offsets {
        tensors: vec![
            tensor(4, [1, 256, 1, 1], 256),
            tensor(1, [1, 0, 0, 0], 1),
            tensor(1, [1, 0, 0, 0], 1),
        ],
    };
    let graph = manual(vec![node], vec![[256, 1, 1], [1, 2, 2]], vec![0], vec![1]);
    let source = Shapes::new(3);
    let mut builder = Builder::new(&source);
    let outs = graph.lower(&mut builder, &table).expect("lowers");
    let plan = builder.finish(&outs).expect("builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvQ2K, .. }
        )),
        "the generic Q2_K kind: {:?}",
        plan.ops
    );
}
