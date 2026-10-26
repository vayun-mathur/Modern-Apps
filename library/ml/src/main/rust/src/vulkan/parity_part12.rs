/// Build `record`'s plan and require every dispatch of `kind` to take the ragged tiled mode.
fn ragged_plan(source: &dyn WeightSource, shapes: &[Shape], kind: crate::nets::Kind, record: impl FnOnce(&mut Builder, &[Id]) -> Id) -> Plan {
    let plan = build(source, shapes, record);
    let ragged = plan.ops.iter().filter(|op| matches!(op, crate::nets::Op::Dispatch { kind: k, push, .. } if *k == kind && push.kw == 2)).count();
    assert!(ragged > 0, "no {kind:?} took the ragged tiled mode");
    plan
}

#[test]
#[ignore = "needs a Vulkan device"]
fn ragged_tiled_attention_agrees_with_the_reference() {
    // Lengths that are not multiples of four and at least 256 (the Gemma vision tower runs 594
    // patches), partial 32-wide tiles, queries != keys, and a grouped case.
    for (heads, kv_heads, head_dim, queries, keys) in [(2u32, 2u32, 8u32, 258u32, 261u32), (4, 2, 12, 262, 257), (2, 1, 64, 278, 278)] {
        let q = spread((heads * head_dim * queries) as usize, 0.31);
        let k = spread((kv_heads * head_dim * keys) as usize, -0.23);
        let source = Invented::new(0);
        let shapes = [Shape::new(heads * head_dim, 1, queries), Shape::new(kv_heads * head_dim, 1, keys)];
        let plan = ragged_plan(&source, &shapes, crate::nets::Kind::AttnScores, |b, ids| {
            b.attn_scores_grouped_prescaled(ids[0], ids[1], heads, kv_heads)
        });
        compare(&format!("ragged scores {heads}/{kv_heads} heads {queries}x{keys}"), plan, source.into_data(), &[&q, &k]);

        let probs = spread((heads * queries * keys) as usize, 0.17);
        let v = spread((kv_heads * head_dim * keys) as usize, 0.53);
        let source = Invented::new(0);
        let shapes = [Shape::new(heads, queries, keys), Shape::new(kv_heads * head_dim, 1, keys)];
        let plan = ragged_plan(&source, &shapes, crate::nets::Kind::AttnApply, |b, ids| {
            b.attn_apply_grouped(ids[0], ids[1], heads, kv_heads)
        });
        compare(&format!("ragged apply {heads}/{kv_heads} heads {queries}x{keys}"), plan, source.into_data(), &[&probs, &v]);
    }
}
#[test]
#[ignore = "needs a Vulkan device"]
fn key_bounded_softmax_and_crop_agree_with_the_reference() {
    // The vision tower's padding: rows over a padded key count normalise only the real keys
    // (per-row and row-mode shaders), and the padded positions are cropped off.
    for (heads, queries, width, keys) in [(3u32, 40u32, 40u32, 37u32), (2, 2, 1504, 1497), (2, 8, 600, 600)] {
        let scores = spread((heads * queries * width) as usize, 0.41);
        let source = Invented::new(0);
        let plan = build(&source, &[Shape::new(heads, queries, width)], |b, ids| {
            let probs = b.softmax_keys(ids[0], keys);
            b.crop_positions(probs, keys)
        });
        compare(&format!("softmax over {keys} of {width} keys, cropped"), plan, source.into_data(), &[&scores]);
        let source = Invented::new(0);
        let plan = build(&source, &[Shape::new(heads, queries, width)], |b, ids| b.softmax_keys(ids[0], keys));
        compare(&format!("softmax over {keys} of {width} keys, zero tail"), plan, source.into_data(), &[&scores]);
        let source = Invented::new(0);
        let plan = build(&source, &[Shape::new(heads, queries, keys)], |b, ids| b.pad_positions(ids[0], width));
        let real = spread((heads * queries * keys) as usize, 0.29);
        compare(&format!("{keys} positions padded to {width}"), plan, source.into_data(), &[&real]);
    }
}
#[test]
#[ignore = "needs a Vulkan device"]
fn a_tile_mode_rms_norm_agrees_with_the_reference() {
    // Gemma 4's prefill norms: the hidden state at 208 and a ragged 200 positions (tiles that end
    // past the last row), and the grouped QK-norm at 37 positions, where tiles straddle heads.
    for (channels, groups, positions) in [(1536u32, 1u32, 208u32), (1536, 1, 200), (2048, 8, 37)] {
        let per_group = channels / groups;
        let input: Vec<f32> = spread((channels * positions) as usize, 0.9)
            .iter()
            .enumerate()
            .map(|(i, v)| v * ((i / (per_group * positions) as usize) as f32 + 1.0))
            .collect();
        let given = Given::new(&[(vec![per_group], spread(per_group as usize, 2.5))])
            .expect("the fixture tensors are consistent");
        let plan = ragged_plan(&given, &[Shape::new(channels, 1, positions)], crate::nets::Kind::RmsNorm, |b, ids| {
            b.rms_norm_grouped(ids[0], 0, 1e-6, groups)
        });
        compare(&format!("a tile-mode rms norm, {channels}/{groups} x {positions}"), plan, given.data().to_vec(), &[&input]);
    }
}
#[test]
#[ignore = "needs a Vulkan device"]
fn causal_attention_skips_only_zero_probabilities() {
    // Gemma 4's fresh prefill: grouped queries over their own keys through a causal softmax, at a
    // width with a partial 32-query tile (200), and a ragged width on the scalar-load path (258).
    for (heads, kv_heads, head_dim, positions) in [(2u32, 1u32, 64u32, 200u32), (2, 1, 16, 258)] {
        let q = spread((heads * head_dim * positions) as usize, 0.31);
        let k = spread((kv_heads * head_dim * positions) as usize, -0.23);
        let v = spread((kv_heads * head_dim * positions) as usize, 0.53);
        let source = Invented::new(0);
        let shapes = [
            Shape::new(heads * head_dim, 1, positions),
            Shape::new(kv_heads * head_dim, 1, positions),
            Shape::new(kv_heads * head_dim, 1, positions),
        ];
        let plan = build(&source, &shapes, |b, ids| {
            let scores = b.attn_scores_grouped_prescaled(ids[0], ids[1], heads, kv_heads);
            let probs = b.softmax_causal(scores);
            b.attn_apply_grouped(probs, ids[2], heads, kv_heads)
        });
        let bounded = plan.ops.iter().any(|op| {
            matches!(op, crate::nets::Op::Dispatch { kind: crate::nets::Kind::AttnApply, push, .. } if push.kh == 1 && push.kw != 0)
        });
        assert!(bounded, "the causal apply at {positions} did not take the bounded tiled path");
        let skipped = plan.ops.iter().any(|op| {
            matches!(op, crate::nets::Op::Dispatch { kind: crate::nets::Kind::AttnScores, push, .. } if push.kh == 1 && push.kw != 0)
        });
        assert!(skipped, "the causal scores at {positions} do not skip the upper triangle");
        compare(&format!("causal attention {heads}/{kv_heads} heads x {positions}"), plan, source.into_data(), &[&q, &k, &v]);
    }
}
