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
