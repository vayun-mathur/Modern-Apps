#[test]
#[ignore = "needs a Vulkan device"]
fn the_banded_ops_agree_with_the_cpu_arm_at_the_towers_own_span() {
    // Device parity for both banded ops at the span, head count and table shape the tower
    // actually uses, over the same fixture the host value tests pin. The band-3 pair above is
    // the general case; this is the one that ships.
    //
    // The comparison is written out rather than handed to `matches`, and that is not fussiness.
    // `matches` scales its tolerance by the largest magnitude in the tensor, and a banded score
    // map contains MASK_FILL - so its scale is 65504 and `TOLERANCE * 65504` is about 262.
    // Every live score here is under 8. Run through `matches`, this test would pass with every
    // live slot on the device replaced by zero, which is exactly the "shader wrote nothing"
    // failure it is supposed to catch. Live slots are therefore compared on their own scale and
    // dead slots pinned to the sentinel exactly.
    use crate::nets::gemma4_audio::{band_key_in, ATTEND_SPAN, MASK_FILL};
    let offsets = ATTEND_SPAN + 1;
    let (q, k, table) = banded_score_fixture(offsets);
    let channels = FIXTURE_HEADS * FIXTURE_HEAD_DIM;
    let tensors = [(vec![FIXTURE_HEADS, offsets, FIXTURE_HEAD_DIM], table)];
    let given = Given::new(&tensors).expect("the banded fixture tensors lay out");
    let shapes = [Shape::new(channels, 1, FIXTURE_T), Shape::new(channels, 1, FIXTURE_T)];
    let plan = build(&given, &shapes, |b, ids| {
        b.attn_scores_banded(
            ids[0],
            ids[1],
            crate::nets::BandedScores { heads: FIXTURE_HEADS, band: ATTEND_SPAN, table: 0, offsets, scale: 1.0, cap: FIXTURE_CAP },
        )
    });
    let data = given.data().to_vec();
    let host = run_multi(&plan, &data, &[&q, &k]).expect("the interpreter runs the banded scores");
    let got = on_device(plan, data, &[&q, &k]);
    let (host, got) = (&host[0], &got[0]);
    assert_eq!(got.len(), host.len(), "banded scores: output length");
    let mut live = 0;
    for head in 0..FIXTURE_HEADS {
        for query in 0..FIXTURE_T {
            for column in 0..ATTEND_SPAN {
                let at = ((head * FIXTURE_T + query) * ATTEND_SPAN + column) as usize;
                let what = format!("banded scores head {head} query {query} column {column}");
                if band_key_in(ATTEND_SPAN, query, column).is_some() {
                    live += 1;
                    // On the live scale, not the tensor's.
                    let tolerance = host[at].abs().max(1.0) * TOLERANCE;
                    assert!(
                        (host[at] - got[at]).abs() <= tolerance,
                        "{what}: {} on the device, {} on the host",
                        got[at],
                        host[at]
                    );
                } else {
                    // Both sides must have written the sentinel. A device that left the arena
                    // alone would show up here as whatever the previous op left.
                    for (side, value) in [("host", host[at]), ("device", got[at])] {
                        assert!(
                            (value - MASK_FILL).abs() < 1.0,
                            "{what} is dead but the {side} has {value}"
                        );
                    }
                }
            }
        }
    }
    // The fixture has to contain both kinds of slot or the loop above proves only one thing.
    // Per head: 14 queries of a 12-wide band is 168 slots, of which 11 + 10 + ... + 1 = 66 are
    // dead, so 102 are live. Two heads doubles both.
    assert_eq!(live, 204, "live slots over two heads of 14 queries of a 12-wide band");
    assert_eq!(host.len() - live, 132, "66 dead slots a head, one per column off the start");

    // The value half, over the band the scores op just produced. Ordinary `agrees`: nothing in a
    // value mix is a sentinel, so the tensor's own scale is the right one.
    let probs = spread((FIXTURE_HEADS * FIXTURE_T * ATTEND_SPAN) as usize, 0.11);
    let v = spread((channels * FIXTURE_T) as usize, 0.29);
    agrees_invented(
        "a banded value mix at the tower's span",
        0,
        &[
            Shape::new(FIXTURE_HEADS, FIXTURE_T, ATTEND_SPAN),
            Shape::new(channels, 1, FIXTURE_T),
        ],
        &[&probs, &v],
        |b, ids| b.attn_apply_banded(ids[0], ids[1], FIXTURE_HEADS, ATTEND_SPAN),
    );
}

#[test]
fn a_banded_score_map_has_the_right_values_where_the_band_hangs_off_the_start() {
    // The test the brief calls unusual, and the reason it is unusual is the failure mode. A
    // column whose key underflowed does not crash and does not produce a NaN: unsigned wrap
    // gives about 4.29e9, and even where a bound catches it the *unguarded* form has already
    // read live arena belonging to a neighbouring tensor. What comes back is finite, of a
    // plausible magnitude, and wrong. So "it ran and the numbers look sane" is precisely the
    // shape of the bug, and nothing short of asserting the value catches it.
    //
    // Every one of the 336 slots is pinned: the live ones to the key `band_key` says they read,
    // the dead ones to `MASK_FILL` exactly. `q = 0, 1, 11` are called out afterwards because
    // they are the rows the brief names, but they are not special-cased here - the sweep already
    // covers them and a failure anywhere else is just as interesting.
    use crate::nets::gemma4_audio::{ATTEND_SPAN, MASK_FILL};
    let got = banded_scores(ATTEND_SPAN, ATTEND_SPAN + 1);
    assert_eq!(got.len(), (FIXTURE_HEADS * FIXTURE_T * ATTEND_SPAN) as usize);
    for head in 0..FIXTURE_HEADS {
        for query in 0..FIXTURE_T {
            for column in 0..ATTEND_SPAN {
                let at = ((head * FIXTURE_T + query) * ATTEND_SPAN + column) as usize;
                let what = format!("head {head} query {query} column {column}");
                match banded_score_want(ATTEND_SPAN, head, query, column) {
                    Some(want) => pinned(&what, got[at], want),
                    // Exactly the sentinel. Not "very negative": a wrapped read that happened to
                    // land on a large negative value would satisfy that and nothing else.
                    None => pinned(&format!("{what} is dead"), got[at], MASK_FILL),
                }
            }
        }
    }

    // The three rows the brief names, restated as the keys they must have read, so a failure
    // reads as "query 1 column 10 took the wrong key" rather than as an index into a flat array.
    for (query, live) in [(0u32, 1usize), (1, 2), (ATTEND_SPAN - 1, ATTEND_SPAN as usize)] {
        let row: Vec<f32> = (0..ATTEND_SPAN)
            .map(|column| got[((query) * ATTEND_SPAN + column) as usize])
            .collect();
        let dead = row.iter().filter(|v| **v < MASK_FILL / 2.0).count();
        assert_eq!(
            ATTEND_SPAN as usize - dead,
            live,
            "query {query} should have {live} live columns, row {row:?}"
        );
        // The last column is the diagonal in every row, including `q = 0`, which is what makes a
        // fully masked row impossible and `0/0` unreachable in the softmax that follows.
        pinned(
            &format!("query {query} reads itself in the last column"),
            row[(ATTEND_SPAN - 1) as usize],
            banded_score_want(ATTEND_SPAN, 0, query, ATTEND_SPAN - 1)
                .expect("the diagonal is always live"),
        );
    }

    // The negative control, and the reason the sweep above runs past the ragged rows rather than
    // stopping at `q = 11`.
    //
    // `j >= band - 1 - q` is the rearrangement that looks like the safe one. On the twelve ragged
    // rows it is correct, so a fixture that only checked `q = 0, 1, 11` would pass under it. From
    // `q = 12` on, `band - 1 - q` underflows to about 4.29e9, the guard is false for every column
    // and the whole row goes dead - 738 of 750 queries in the real tower, silently.
    //
    // `T` is deliberately two past the band so that `q = 12` and `q = 13` exist and are fully
    // live. Asserting that no column of them is the sentinel is what excludes that form.
    for query in ATTEND_SPAN..FIXTURE_T {
        for column in 0..ATTEND_SPAN {
            let at = ((query * ATTEND_SPAN) + column) as usize;
            assert!(
                got[at] > MASK_FILL / 2.0,
                "query {query} column {column} is dead, but every column past the band's width \
                 is live - this is what `j >= band - 1 - q` does once the subtraction underflows"
            );
        }
    }

    // The two remaining wrong forms cannot be caught here, and pretending otherwise would be
    // worse than saying so. Gating the write instead of the read, and keeping `k < T` as the only
    // bound, both produce byte-identical output to the correct code *in GLSL*, where the wrap is
    // defined and a wrapped key fails `< T` on its own. What catches those is this arm being
    // Rust: `q - (band - 1)` on a u32 panics in a debug build, and every parity run is one.
}

#[test]
fn the_banded_span_is_twelve_measured_from_the_op_and_not_from_the_constant() {
    // A test that FAILS if the span is 11 or 13, measured from what the op emitted rather than
    // from `ATTEND_SPAN` - `the_span_is_twelve_and_a_neighbouring_span_would_not_pass` already
    // pins the constant and `band_key`, and would keep passing if the op ignored both.
    //
    // The discriminator is where the ragged rows stop. A query is full when the whole window
    // fits, which is at `q = span - 1`:
    //
    //   span 11 -> q = 10 is the first full row
    //   span 12 -> q = 10 has one dead column and q = 11 is the first full row
    //   span 13 -> q = 11 still has one dead column
    //
    // so asserting both halves of that pair excludes each neighbour from a different side.
    // Neither neighbour is a shape error and neither would fail a parity run, because the CPU
    // arm reads the same `Push::kh`.
    use crate::nets::gemma4_audio::{ATTEND_SPAN, MASK_FILL};
    let got = banded_scores(ATTEND_SPAN, ATTEND_SPAN + 1);
    let dead_in = |query: u32| {
        (0..ATTEND_SPAN)
            .filter(|column| got[((query * ATTEND_SPAN) + column) as usize] < MASK_FILL / 2.0)
            .count()
    };
    assert_eq!(dead_in(ATTEND_SPAN - 2), 1, "a span of 11 would make query 10 full");
    assert_eq!(dead_in(ATTEND_SPAN - 1), 0, "a span of 13 would leave query 11 ragged");
    // And the width itself, which is the other thing a wrong span changes.
    assert_eq!(got.len(), (FIXTURE_HEADS * FIXTURE_T * 12) as usize, "twelve columns a row");
    // The oldest key a full row reaches is `q - 11`. At a span of 13 it would be `q - 12`.
    let full = FIXTURE_T - 1;
    pinned(
        "the oldest key of a full row",
        got[(full * ATTEND_SPAN) as usize],
        banded_score_want(ATTEND_SPAN, 0, full, 0).expect("a full row has no dead columns"),
    );
    assert_eq!(
        crate::nets::gemma4_audio::band_key(full, 0),
        Some(full - (ATTEND_SPAN - 1)),
        "the oldest key is q - 11"
    );
}

#[test]
fn the_banded_cap_is_applied_to_the_sum_and_not_to_the_fill() {
    // Two things at once, because they are the same ordering question.
    //
    // The cap must bend the fixture, or every assertion above would pass against a shader that
    // never applied it. And it must be applied BEFORE the sentinel goes in, not after: a
    // separate `softcap.comp` pass over a finished band would map `-65504` to
    // `tanh(-65504 / 50) * 50 = -50`, a perfectly finite weight the softmax would then include,
    // and the masked keys would quietly get real attention. Fusing the cap into this op is what
    // makes that unrepresentable, and this is the test that says so.
    use crate::nets::gemma4_audio::{ATTEND_SPAN, MASK_FILL};
    let got = banded_scores(ATTEND_SPAN, ATTEND_SPAN + 1);
    // A full row of head 0, whose scores are the largest the fixture makes.
    let full = FIXTURE_T - 1;
    let uncapped = {
        let key = crate::nets::gemma4_audio::band_key(full, ATTEND_SPAN - 1).expect("live");
        (key + 1) as f32 * 0.5 + ATTEND_SPAN as f32 * 0.25
    };
    let capped = got[((full * ATTEND_SPAN) + ATTEND_SPAN - 1) as usize];
    assert!(
        (uncapped - capped).abs() > 1.0,
        "the fixture must straddle the cap or it proves nothing: uncapped {uncapped}, got {capped}"
    );
    pinned("the capped diagonal", capped, (uncapped / FIXTURE_CAP).tanh() * FIXTURE_CAP);
    // The sentinel survived the op uncapped. `tanh(-65504 / 8) * 8` would be -8.
    let dead = got[0];
    pinned("a dead slot is the raw sentinel", dead, MASK_FILL);
    assert!(dead < -1000.0, "a capped sentinel would be {}", -FIXTURE_CAP);
}

#[test]
fn a_banded_value_mix_reads_the_key_its_column_names() {
    // The value half's version of the same hazard. `attn_apply_banded` indexes V by a key it
    // derives from the column, so an underflowed column reads a live value from somewhere else
    // in the arena and mixes it in with a perfectly ordinary weight. The output stays finite and
    // the sequence stays fluent.
    //
    // V is distinct per (channel, key) and the weights are distinct per (head, column), so every
    // term of every sum is uniquely identifiable: no two wrong pairings produce the same total.
    use crate::nets::gemma4_audio::{band_key, ATTEND_SPAN};
    let channels = FIXTURE_HEADS * FIXTURE_HEAD_DIM;
    // Multiples of 1/4 and 1/128, both exact in fp16.
    let value = |c: u32, k: u32| (c + 1) as f32 + (k + 1) as f32 * 0.25;
    let weight = |h: u32, j: u32| (h + 1) as f32 * (j + 1) as f32 * 0.0078125;
    let mut probs = vec![0.0; (FIXTURE_HEADS * FIXTURE_T * ATTEND_SPAN) as usize];
    for head in 0..FIXTURE_HEADS {
        for query in 0..FIXTURE_T {
            for column in 0..ATTEND_SPAN {
                probs[((head * FIXTURE_T + query) * ATTEND_SPAN + column) as usize] =
                    weight(head, column);
            }
        }
    }
    let mut v = vec![0.0; (channels * FIXTURE_T) as usize];
    for c in 0..channels {
        for t in 0..FIXTURE_T {
            v[(c * FIXTURE_T + t) as usize] = value(c, t);
        }
    }

    let given = Given::new(&[]).expect("no tensors");
    let shapes =
        [Shape::new(FIXTURE_HEADS, FIXTURE_T, ATTEND_SPAN), Shape::new(channels, 1, FIXTURE_T)];
    let plan = build(&given, &shapes, |b, ids| {
        b.attn_apply_banded(ids[0], ids[1], FIXTURE_HEADS, ATTEND_SPAN)
    });
    let outputs = run_multi(&plan, given.data(), &[&probs, &v]).expect("the banded mix runs");
    let got = outputs.first().expect("one output");
    assert_eq!(got.len(), (channels * FIXTURE_T) as usize);

    for c in 0..channels {
        for query in 0..FIXTURE_T {
            // The head that owns this channel, which is what picks the row of `probs`.
            let head = c / FIXTURE_HEAD_DIM;
            let want: f32 = (0..ATTEND_SPAN)
                .filter_map(|column| {
                    // Dead columns contribute nothing because they are never read - not because
                    // their weight happens to be zero. Here it is emphatically not zero.
                    band_key(query, column).map(|key| weight(head, column) * value(c, key))
                })
                .sum();
            pinned(&format!("channel {c} query {query}"), got[(c * FIXTURE_T + query) as usize], want);
        }
    }

    // Query 0 has exactly one live column, so its output is one product and nothing else. This
    // is the strongest single statement in the file: if the eleven dead columns had been read,
    // their weights are 1/128 upward and the answer could not still be this.
    for c in 0..channels {
        let head = c / FIXTURE_HEAD_DIM;
        pinned(
            &format!("channel {c} at query 0 is a single term"),
            got[(c * FIXTURE_T) as usize],
            weight(head, ATTEND_SPAN - 1) * value(c, 0),
        );
    }
}

#[test]
fn the_band_edge_is_dead_only_where_the_window_hangs_off_the_start() {
    // The property the whole layout rests on, checked against `gemma4_audio::band_key` at the
    // three queries that matter: q = 0 has one live column, q = 1 two, and q = band - 1 is the
    // first fully populated row. Asserting the KEYS rather than merely that something is live,
    // because a wrapped read returns a finite plausible index rather than an error.
    use crate::nets::gemma4_audio::{band_key, ATTEND_SPAN};
    let live = |q: u32| (0..ATTEND_SPAN).filter(|j| band_key(q, *j).is_some()).count();
    assert_eq!(live(0), 1, "query 0 sees only itself");
    assert_eq!(live(1), 2);
    assert_eq!(live(ATTEND_SPAN - 1), ATTEND_SPAN as usize, "the first full row");
    assert_eq!(band_key(0, ATTEND_SPAN - 1), Some(0), "the diagonal is always live");
    for q in [0, 1, ATTEND_SPAN - 1, 100] {
        assert_eq!(band_key(q, ATTEND_SPAN - 1), Some(q), "the last column is the diagonal");
        for j in 0..ATTEND_SPAN {
            if let Some(k) = band_key(q, j) {
                assert!(k <= q, "q {q} column {j} reached forward to {k}");
                assert!(q - k < ATTEND_SPAN, "q {q} column {j} reached back past the span");
            }
        }
    }
}

#[test]
fn the_span_is_twelve_and_a_neighbouring_span_would_not_pass() {
    // A test that fails if the span is 11 or 13. The measured mask is `0 <= q - k <= 11`, so the
    // window is twelve wide; both neighbours were live hypotheses during the trace and neither
    // produces a shape error. `ATTEND_SPAN` is what the shader reads through `Push::kh`.
    use crate::nets::gemma4_audio::{band_key, ATTEND_SPAN, REL_OFFSETS};
    assert_eq!(ATTEND_SPAN, 12, "the export's mask admits twelve keys per query");
    assert_eq!(REL_OFFSETS, 13, "thirteen offsets, of which the mask reaches twelve");
    // At a query well clear of the start the window is exactly `ATTEND_SPAN` keys wide, so a
    // span of 11 or 13 changes this count and the assertion catches it.
    let keys: Vec<u32> = (0..ATTEND_SPAN).filter_map(|j| band_key(64, j)).collect();
    assert_eq!(keys.len(), 12, "a span of 11 or 13 would give 11 or 13 here");
    assert_eq!(keys.first(), Some(&53), "oldest key is q - 11");
    assert_eq!(keys.last(), Some(&64), "newest key is q itself");
}

#[test]
fn the_oracle_honours_the_ops_band_rather_than_the_constant() {
    // shader-smith caught this on the real ops: the reference arms looped over `Push::kh` but
    // computed their keys with the `ATTEND_SPAN` form, so they agreed with the shaders only at
    // band 12 and diverged silently everywhere else. At band 3 query 0 came back entirely dead
    // and query 13 read keys 2, 3, 4 instead of 11, 12, 13.
    //
    // The band is a parameter of the op, so anything deriving a key must take it as one.
    use crate::nets::gemma4_audio::{band_key, band_key_in, ATTEND_SPAN};
    // At the tower's own span the two spellings must be the same function.
    for q in [0, 1, 11, 12, 100] {
        for j in 0..ATTEND_SPAN {
            assert_eq!(band_key_in(ATTEND_SPAN, q, j), band_key(q, j), "q {q} column {j}");
        }
    }
    // At any other band they must not be, and the fixed form is the wrong one.
    assert_eq!(band_key_in(3, 0, 2), Some(0), "at band 3 query 0's last column is key 0");
    assert_eq!(band_key(0, 2), None, "the ATTEND_SPAN form calls that dead - the old bug");
    assert_eq!(
        (0..3).filter_map(|j| band_key_in(3, 13, j)).collect::<Vec<_>>(),
        vec![11, 12, 13],
        "at band 3 query 13 reads keys 11, 12, 13"
    );
    // The window is `band` wide once clear of the start, whatever the band is.
    for band in [1, 2, 3, 5, 12, 24] {
        let keys: Vec<u32> = (0..band).filter_map(|j| band_key_in(band, 100, j)).collect();
        assert_eq!(keys.len(), band as usize, "band {band} should give {band} keys");
        assert_eq!(keys.last(), Some(&100), "the last column is always the diagonal");
        assert_eq!(keys.first(), Some(&(100 - (band - 1))), "the first is q - (band - 1)");
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_softcap_saturates_the_logits_it_is_given() {
    // `final_logit_softcapping = 30`. The values below deliberately straddle the cap, because
    // the op is nearly the identity well inside it - a fixture that stayed in the linear region
    // would pass against no softcap at all.
    let input: Vec<f32> = (0..64)
        .map(|i| (i as f32 - 32.0) * 4.0)
        .collect();
    assert!(input.iter().any(|v| v.abs() > 30.0), "the fixture must exceed the cap");
    agrees_invented("a softcap", 0, &[Shape::new(64, 1, 1)], &[&input], |b, ids| {
        b.softcap(ids[0], 30.0)
    });
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_standalone_activation_matches_a_folded_one() {
    // `Kind::Activate` exists for a gated feed-forward, where half a fused projection must stay
    // linear. Checked against the same activation folded into a convolution: the two paths run
    // different shaders and must agree, or a gated MLP would differ from an ungated one for no
    // reason the shapes could show.
    let input = spread(48, 0.35);
    agrees_invented("a standalone gelu", 0, &[Shape::new(48, 1, 1)], &[&input], |b, ids| {
        b.activate(ids[0], Act::Gelu)
    });
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_two_axis_rotary_rotates_each_half_by_its_own_position() {
    // Gemma 4's vision tower rotates the first half of a 64-wide head by the patch's column
    // and the second half by its row. The failure this guards is not a shape error: rotating the head
    // as one block pairs a row channel with a column channel, and produces an encoder that is
    // subtly position-blind rather than one that crashes.
    //
    // The two halves are given deliberately different angles, so a shader that used one block's
    // table for both cannot pass.
    let heads = 3u32;
    let head_dim = 64u32;
    let positions = 5u32;
    let input = spread((heads * head_dim * positions) as usize, 0.31);
    // `[head_dim, 1, W]`: block 0 is cos(col) then sin(col), block 1 cos(row) then sin(row).
    let mut angles = vec![0f32; (head_dim * positions) as usize];
    for position in 0..positions {
        for frequency in 0..16u32 {
            let column = 0.11 * (position + 1) as f32 * (frequency + 1) as f32;
            let row = 0.37 * (position + 2) as f32 * (frequency + 1) as f32;
            let at = |channel: u32| (channel * positions + position) as usize;
            angles[at(frequency)] = column.cos();
            angles[at(16 + frequency)] = column.sin();
            angles[at(32 + frequency)] = row.cos();
            angles[at(48 + frequency)] = row.sin();
        }
    }
    agrees_invented(
        "a two-axis rotary",
        0,
        &[Shape::new(heads * head_dim, 1, positions), Shape::new(head_dim, 1, positions)],
        &[&input, &angles],
        |b, ids| b.rotary_axes(ids[0], ids[1], heads, 2),
    );
}
