#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::tests::Shapes;

    #[test]
    fn the_layout_matches_the_converter() {
        let source = Shapes::new(TENSORS);
        declare_shared(&source).expect("the shared tensors");
        for index in 0..LAYERS {
            declare_layer(&source, index).expect("a layer");
        }
        assert_eq!(layer_at(LAYERS), TENSORS);
        // Every index exactly once, which is what a positional table means and what a cursor
        // that drifted by one inside a layer would break without changing the total.
        let mut seen: Vec<usize> = source.asked.borrow().iter().map(|(at, _)| *at).collect();
        seen.sort_unstable();
        assert_eq!(seen, (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_layer_span_is_what_the_export_counts() {
        // Each of these was counted off audio_encoder_fp16.onnx rather than derived, and each
        // would shift every tensor after it if the export were re-exported differently:
        // 122 MatMul/Gemm named node_linear (12 * 10 + input and embedding projections),
        // 109 SimplifiedLayerNormalization (12 * 9 + the embedder's), 216 Clip (12 * 18, none
        // outside a layer), 14 Conv (2 + 12), 12 tables of [8, 128, 13].
        assert_eq!(NORMS_PER_LAYER * LAYERS + 1, 109);
        assert_eq!(CLIPS_PER_LAYER * LAYERS, 216);
        assert_eq!(PROJECTIONS_PER_LAYER * LAYERS + 2, 122);
        assert_eq!(LAYER_TENSORS, 61);
        assert_eq!(SHARED_TENSORS, 15);
        assert_eq!(TENSORS, 747);
    }

    #[test]
    fn the_two_ends_and_the_front_end_are_not_quantised() {
        // The mixed precision is the contract with `collect_gemma4_audio`, and a converter that
        // wrote a scale here would shift every index after it.
        assert_eq!(SSCP_NORM0, SSCP_CONV0 + 2);
        assert_eq!(INPUT_PROJECTION, SSCP_NORM1 + 2);
        assert_eq!(OUT_PROJECTION, INPUT_PROJECTION + 2);
        assert_eq!(EMBED_NORM, OUT_PROJECTION + 2);
        assert_eq!(EMBED_PROJECTION, EMBED_NORM + 1);
    }

    #[test]
    fn a_query_reads_itself_and_eleven_past_and_nothing_else() {
        // The worked examples from the contract, and the ones that separate the sliding window
        // from the block-diagonal reading it is easy to mistake it for. `q = 12` is the case
        // that decides it: under a block-diagonal mask it would read only itself.
        let reads = |q: u32, upto: u32| (0..upto).filter(|k| attends(q, *k)).collect::<Vec<_>>();
        assert_eq!(reads(0, 64), vec![0]);
        assert_eq!(reads(11, 64), (0..=11).collect::<Vec<_>>());
        assert_eq!(reads(12, 64), (1..=12).collect::<Vec<_>>());
        assert_eq!(reads(30, 64), (19..=30).collect::<Vec<_>>());
        // Nothing ahead, ever, and never more than the span.
        for q in 0..64_u32 {
            assert!(!attends(q, q + 1), "query {q} reads the future");
            assert_eq!(reads(q, 200).len() as u32, (q + 1).min(ATTEND_SPAN));
        }
    }

    #[test]
    fn the_relative_table_is_thirteen_wide_and_twelve_live() {
        // The fencepost. Column 12 is a query against itself and column 1 is the oldest key it
        // may read; column 0 is `d = 12`, which the mask never admits.
        assert_eq!(rel_column(0), 12);
        assert_eq!(rel_column(11), 1);
        assert_eq!(rel_column(ATTEND_SPAN), 0);
        let live: Vec<u32> = (0..REL_OFFSETS).filter(|d| attends(*d, 0)).map(rel_column).collect();
        assert_eq!(live, (1..=12).rev().collect::<Vec<_>>());
        assert_eq!(live.len() as u32, ATTEND_SPAN, "twelve of thirteen columns");
        assert!(!live.contains(&0), "column 0 is computed and thrown away");
    }

    #[test]
    fn the_band_agrees_with_the_mask_and_with_the_relative_column() {
        // The two index maps the banded scores op relies on, checked against each other rather
        // than each against my arithmetic. `band_key` decides which key a slot reads and
        // `rel_column` decides which table column weights it; a fencepost in either lands on a
        // plausible value, so the check is that they agree for every (q, j) at once.
        for q in 0..40_u32 {
            let mut live = 0;
            for j in 0..ATTEND_SPAN {
                match band_key(q, j) {
                    Some(k) => {
                        live += 1;
                        assert!(attends(q, k), "q {q} slot {j} reads {k}, which is not attended");
                        assert_eq!(q - k, ATTEND_SPAN - 1 - j, "displacement at q {q} slot {j}");
                        // The collapse that removes the skew: the column is j + 1, whatever q is.
                        assert_eq!(rel_column(q - k), j + 1, "column at q {q} slot {j}");
                    }
                    None => assert!(j < ATTEND_SPAN - 1 - q.min(ATTEND_SPAN - 1)),
                }
            }
            // Never a dead row: the last slot is the diagonal, always live and always in range.
            assert_eq!(band_key(q, ATTEND_SPAN - 1), Some(q));
            assert_eq!(live, (q + 1).min(ATTEND_SPAN), "live slots at q {q}");
            // Every key the mask admits is reachable from some slot, so the band loses nothing.
            let banded: Vec<u32> = (0..ATTEND_SPAN).filter_map(|j| band_key(q, j)).collect();
            let masked: Vec<u32> = (0..=q).filter(|k| attends(q, *k)).collect();
            assert_eq!(banded, masked, "the band and the mask disagree at q {q}");
        }
        assert_eq!(rel_column(0), ATTEND_SPAN, "the diagonal reads the last column");
        // The start edge, counted: 11 rows with dead slots, 66 in total.
        let dead: u32 = (0..40_u32)
            .map(|q| (0..ATTEND_SPAN).filter(|j| band_key(q, *j).is_none()).count() as u32)
            .sum();
        assert_eq!(dead, 66, "dead slots across the whole sequence");
    }

    #[test]
    fn the_additive_guard_is_the_only_rearrangement_that_holds() {
        // The team burned an afternoon on this expression, so it is pinned rather than trusted.
        // The signed condition is `q - (ATTEND_SPAN - 1) + j >= 0`, evaluated here in i64 where
        // it cannot wrap, and every candidate guard is checked against it over the whole
        // sequence and the whole band - the REACHABLE domain only, since a guard is not required
        // to be correct for queries past the end of the sequence.
        let span = i64::from(ATTEND_SPAN);
        let mut additive = 0;
        let mut subtractive = 0;
        for q in 0..MAX_TOKENS {
            for j in 0..ATTEND_SPAN {
                let signed = i64::from(q) - (span - 1) + i64::from(j);
                let want = signed >= 0;
                // The mandated form. No subtraction, so nothing to wrap or panic.
                if (j + q + 1 >= ATTEND_SPAN) != want {
                    additive += 1;
                }
                // The retracted one, `j >= 11 - q`, evaluated as u32 would evaluate it. Rust
                // would panic in debug rather than wrap, so the wrap is modelled explicitly -
                // GLSL is where it silently empties the band.
                let threshold = (ATTEND_SPAN - 1).wrapping_sub(q);
                if (j >= threshold) != want {
                    subtractive += 1;
                }
                assert_eq!(band_key(q, j).is_some(), want, "band_key at q {q} slot {j}");
            }
        }
        assert_eq!(additive, 0, "`j + q + 1 >= band` must match the signed condition everywhere");
        // Right on the twelve rows q = 0..=11, wrong on every row after them.
        assert_eq!(
            subtractive,
            (MAX_TOKENS - ATTEND_SPAN) * ATTEND_SPAN,
            "`j >= band - 1 - q` should be wrong on every query past the edge"
        );
    }

    #[test]
    fn the_band_never_reaches_past_the_last_query() {
        // `band_key` guards the LOWER bound only, and that is sufficient because right context is
        // zero: k <= q < T, so the upper bound is free. This asserts that property rather than
        // trusting it, because if the window ever reaches forward the helper keeps returning
        // Some(k) for an out-of-range k and a caller treating Some as "safe to index" reads past
        // the end. A comment would not fail; this does.
        let mut highest = 0;
        for q in 0..MAX_TOKENS {
            for j in 0..ATTEND_SPAN {
                if let Some(k) = band_key(q, j) {
                    assert!(k <= q, "q {q} slot {j} reads {k}, which is ahead of the query");
                    highest = highest.max(k);
                }
            }
        }
        assert_eq!(highest, MAX_TOKENS - 1, "the largest key any query reads");
        // The same statement in the form the shader needs: right context is zero.
        assert_eq!(band_key(0, ATTEND_SPAN - 1), Some(0));
        assert_eq!(band_key(MAX_TOKENS - 1, ATTEND_SPAN - 1), Some(MAX_TOKENS - 1));
        assert_eq!(band_key(MAX_TOKENS - 1, 0), Some(MAX_TOKENS - ATTEND_SPAN));
        // Past the band is not a slot at all, whatever the guard would say about it.
        assert_eq!(band_key(100, ATTEND_SPAN), None, "slot 12 is outside a 12-wide band");
    }

    #[test]
    fn the_fill_is_the_most_negative_finite_half() {
        // Not -1e9. The arena is fp16, where -1e9 saturates to -inf and a fully masked row would
        // give exp(NaN). -65504 underflows to zero against any live peak and stays finite.
        assert_eq!(MASK_FILL, -65504.0);
        assert!(MASK_FILL.is_finite());
        assert!(f64::from(MASK_FILL) < -f64::from(LOGIT_CAP) * 1000.0, "must swamp the softcap");
        // The reason the cap is fused rather than a separate pass: capping the sentinel would
        // bring it back to -50, which is a perfectly ordinary logit.
        let capped = (MASK_FILL / LOGIT_CAP).tanh() * LOGIT_CAP;
        assert!((capped + LOGIT_CAP).abs() < 1e-3, "a capped sentinel is {capped}, not -inf");
    }

    #[test]
    fn the_token_count_matches_the_reference_framing() {
        // From the contract's table, which was derived from the framing arithmetic rather than
        // from `ceil(ms / 40)`: the two agree only because the semicausal 160-sample prepend
        // makes them.
        for (seconds, frames, want) in [(1, 99, 25), (10, 999, 250), (30, 2999, 750)] {
            let samples = seconds * crate::logmel::SAMPLE_RATE as usize;
            assert_eq!(crate::logmel::frame_count(samples), frames, "{seconds}s frames");
            assert_eq!(tokens(frames as u32), want, "{seconds}s tokens");
            assert_eq!(want, seconds as u32 * 1000 / 40, "{seconds}s against 40 ms a token");
        }
        assert_eq!(crate::logmel::frame_count(MAX_SAMPLES), 2999);
        assert_eq!(tokens(2999), MAX_TOKENS);
        // The mel axis reduces the same way, which is where `input_proj`'s square shape comes
        // from: `(128 // 4) * 32`.
        assert_eq!(MELS_SUBSAMPLED, 32);
        assert_eq!(MELS_SUBSAMPLED * SSCP_CHANNELS[1], D_MODEL);
        assert_eq!(tokens(0), 0);
        assert_eq!(tokens(1), 1);
    }

    #[test]
    fn the_head_geometry_is_ordinary_multi_head() {
        assert_eq!(HEADS * HEAD_DIM, D_MODEL);
        assert_eq!(FFN, 4 * D_MODEL);
        assert_eq!(LCONV_GATE, 2 * D_MODEL);
        assert_eq!(CONV_LEFT_PAD, 4, "causal: four left, none right");
    }

    #[test]
    // The two literals are the export's constants at full `f64` precision;
    // rounding them would defeat the comparison against the folded scales.
    #[allow(clippy::excessive_precision)]
    fn the_folded_scales_are_the_constants_the_export_holds() {
        // Measured in the graph as val_279 and val_280. The `/ ln 2` in the query scale is the
        // part nobody would guess, and reproducing `HEAD_DIM ** -0.5` alone would be off by
        // 1.44x with no shape to catch it.
        let q = (f64::from(HEAD_DIM).powf(-0.5) / 2.0_f64.ln()) as f32;
        let k = ((1.0 + 1.0_f64.exp()).ln() / 2.0_f64.ln()) as f32;
        assert!((q - Q_SCALE).abs() < 1e-7, "{q} against {Q_SCALE}");
        assert!((k - K_SCALE).abs() < 1e-6, "{k} against {K_SCALE}");
        assert!((q - 0.127_517_431_974_411).abs() < 1e-7);
        assert!((k - 1.894_636_154_174_804_7).abs() < 1e-6);
    }

    #[test]
    fn the_parameter_total_is_within_reach_of_the_published_size() {
        // 12 layers of two 4x feed-forwards, four attention projections, and a 2x-gated conv
        // module, plus the three end projections. This is not an estimate: it reproduces the
        // 294,387,712 quantisable elements counted off audio_encoder_fp16.onnx exactly, which
        // is 99.8% of the 589,840,640-byte .onnx_data - the remainder being the norms, the
        // clips, the relative tables and the two subsampling kernels.
        //
        // The tower is bigger than the vision one, 24.5 M parameters a layer against 18.9 M,
        // and that is a real budget line rather than a rounding error.
        let per_layer = 2 * (2 * D_MODEL * FFN)
            + 4 * D_MODEL * D_MODEL
            + D_MODEL * LCONV_GATE
            + D_MODEL * D_MODEL;
        let ends = D_MODEL * D_MODEL + OUT_DIM * D_MODEL + OUT_DIM * OUT_DIM;
        let total = u64::from(LAYERS as u32 * per_layer + ends);
        assert_eq!(total, 294_387_712, "against the export's initializer census");
        let megabytes = total as f64 * 2.0 / 1e6;
        assert!(
            (585.0..592.0).contains(&megabytes),
            "{megabytes:.0} MB against the export's 590 MB of fp16 weights"
        );
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // The layout, restated in the export's own convention and compared against shapes
        // transcribed by hand from audio_encoder_fp16.onnx. `declare_layer` and the converter
        // could drift together and the other tests would not see it, because both sides are
        // this module's convention; these numbers come from the file.
        let exported = |declare: &dyn Fn(&Shapes) -> Result<(), String>| -> Vec<Vec<u32>> {
            let source = Shapes::new(TENSORS);
            declare(&source).expect("the declaration");
            let asked = source.asked.borrow();
            asked.iter().map(|(_, d)| as_exported(d)).collect()
        };
        let count = |shapes: &[Vec<u32>], want: &[u32]| shapes.iter().filter(|s| *s == want).count();

        let layer = exported(&|s| declare_layer(s, 0));
        assert_eq!(layer.len(), LAYER_TENSORS);
        // The ten int4 projections, in the export's [in, out] order.
        assert_eq!(count(&layer, &[D_MODEL, FFN]), 2, "ffw1.up and ffw2.up: {layer:?}");
        assert_eq!(count(&layer, &[FFN, D_MODEL]), 2, "the two downs");
        assert_eq!(count(&layer, &[D_MODEL, D_MODEL]), 5, "q, k, v, post and lconv.exit");
        assert_eq!(count(&layer, &[D_MODEL, LCONV_GATE]), 1, "lconv.gate");
        // The depthwise loses the unit height it gains here.
        assert_eq!(count(&layer, &[D_MODEL, 1, CONV_KERNEL]), 1, "the depthwise kernel");
        // The relative table keeps the converter's transposed order; there is no [in, out] to
        // restate, and the export's own [8, 128, 13] is a different thing.
        assert_eq!(count(&layer, &[HEADS, REL_OFFSETS, HEAD_DIM]), 1, "the relative table");

        let shared = exported(&|s| declare_shared(s));
        assert_eq!(shared.len(), SHARED_TENSORS);
        assert_eq!(count(&shared, &[D_MODEL, D_MODEL]), 1, "input_projection: {shared:?}");
        assert_eq!(count(&shared, &[D_MODEL, OUT_DIM]), 1, "output_projection");
        assert_eq!(count(&shared, &[OUT_DIM, OUT_DIM]), 1, "embedder.projection");
        // Square kernels, so the converter's spatial swap moves values and not the shape.
        assert_eq!(count(&shared, &[SSCP_CHANNELS[0], 1, SSCP_KERNEL, SSCP_KERNEL]), 1);
        assert_eq!(
            count(&shared, &[SSCP_CHANNELS[1], SSCP_CHANNELS[0], SSCP_KERNEL, SSCP_KERNEL]),
            1
        );
        // A 1x1 projection is the only thing that transposes; everything else is left alone.
        assert_eq!(as_exported(&[2]), vec![2], "a clip pair");
        assert_eq!(as_exported(&[D_MODEL]), vec![D_MODEL], "a norm gain");
    }

    #[test]
    fn a_mode_carries_its_shape_and_its_token_count() {
        // `Reshaped` keys its re-record on this, so the derives are load-bearing: a Mode that
        // did not compare by value would re-record every call, and one that was not Copy would
        // not fit the `fn(&Offsets, S)` plan pointer at all.
        let clip = Mode::Clip { frames: 2999 };
        assert_eq!(clip.frames(), 2999);
        assert_eq!(clip.tokens(), MAX_TOKENS);
        assert_eq!(Mode::Trace { frames: 2999, layers: 0 }.tokens(), MAX_TOKENS);
        assert_eq!(Mode::Sscp { frames: 99 }.tokens(), 25);
        // Distinct keys, or a trace sweep would silently reuse layer 0's recording.
        assert_ne!(
            Mode::Trace { frames: 99, layers: 3 },
            Mode::Trace { frames: 99, layers: 4 }
        );
        assert_ne!(Mode::Clip { frames: 99 }, Mode::Sscp { frames: 99 });
        assert_eq!(Mode::Clip { frames: 99 }, Mode::Clip { frames: 99 });
        assert_eq!(INPUTS, 1, "the mel is the whole input");
    }

    #[test]
    fn every_mode_records_and_reads_every_tensor() {
        // `Builder::finish` refuses a file with an unread tensor, which is what a pass that lost
        // a layer looks like from the outside. The early-stopping modes therefore have to name
        // what they skip, and this is the test that they do - against the stub, so it runs
        // without the 166 MiB artefact.
        let frames = 999_u32;
        for mode in [
            Mode::Clip { frames },
            Mode::Sscp { frames },
            Mode::Trace { frames, layers: 0 },
            Mode::Trace { frames, layers: 1 },
            Mode::Trace { frames, layers: LAYERS },
        ] {
            let source = Shapes::new(TENSORS);
            let plan = build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"));
            assert!(!plan.ops.is_empty(), "{mode:?} recorded nothing");
            let mut seen: Vec<usize> = source.asked.borrow().iter().map(|(i, _)| *i).collect();
            seen.sort_unstable();
            seen.dedup();
            assert_eq!(seen, (0..TENSORS).collect::<Vec<_>>(), "{mode:?} left a tensor unread");
        }
    }

    #[test]
    fn a_clip_outside_the_recordable_range_is_refused() {
        // The band is the trained model's, not the clip's: narrowing it to T would read relative
        // offsets 1..=T where the displacements are 0..=T-1, i.e. the wrong table columns. So a
        // clip under MIN_TOKENS is refused rather than quietly mis-indexed.
        assert_eq!(tokens(44), 11, "44 frames is one token short of the band");
        let refused =
            build(&Shapes::new(TENSORS), Mode::Clip { frames: 44 }).expect_err("a 0.44 s clip");
        assert!(refused.contains("under the 12"), "{refused}");
        assert_eq!(tokens(45), MIN_TOKENS, "45 frames is exactly the band");
        assert!(build(&Shapes::new(TENSORS), Mode::Clip { frames: 45 }).is_ok());
        assert!(build(&Shapes::new(TENSORS), Mode::Clip { frames: 0 }).is_err());
        assert!(build(&Shapes::new(TENSORS), Mode::Clip { frames: 6000 }).is_err(), "past the cap");
        assert!(
            build(&Shapes::new(TENSORS), Mode::Trace { frames: 999, layers: LAYERS + 1 }).is_err()
        );
    }

    #[test]
    fn a_clip_past_the_thirty_second_cap_is_refused_by_prepare() {
        // Every arena figure was approved against T <= 750. Nothing downstream re-checks it, so
        // a caller that forgot to truncate would get twice the approved arena and no complaint.
        let over = crate::logmel::frame_count(2 * MAX_SAMPLES);
        assert!(tokens(over as u32) > MAX_TOKENS, "{over} frames should be over the cap");
        let mel = vec![0.0_f32; over * MELS as usize];
        let refused = prepare(&mel, over as u32).expect_err("a 60-second clip");
        assert!(refused.contains("past the 750"), "{refused}");
        // The cap itself is fine.
        let at_cap = crate::logmel::frame_count(MAX_SAMPLES);
        assert_eq!(tokens(at_cap as u32), MAX_TOKENS);
        assert!(prepare(&vec![0.0; at_cap * MELS as usize], at_cap as u32).is_ok());
        assert!(prepare(&[], 0).is_err(), "an empty clip");
    }

    #[test]
    fn prepare_lays_a_mel_bin_out_where_the_plan_reads_it() {
        // Row-major frames in, mel-major out. Getting this the wrong way round is a transpose
        // that changes no shape when the frame count happens to equal 128.
        let frames = 5_u32;
        let mut mel = vec![0.0_f32; frames as usize * MELS as usize];
        mel[3 * MELS as usize + 7] = 1.0; // frame 3, bin 7
        let laid = prepare(&mel, frames).expect("a spectrogram");
        assert_eq!(laid.len(), mel.len());
        assert_eq!(laid[7 * frames as usize + 3], 1.0);
        assert_eq!(laid.iter().filter(|v| **v != 0.0).count(), 1);
        assert_eq!(input_shape(frames), Shape::new(1, MELS, frames));
        assert!(prepare(&mel, frames + 1).is_err(), "a short spectrogram");
    }

    /// The one check that compares this layout against a real converter output rather than
    /// against a stub, which is what every other test here does.
    ///
    /// Ignored and a no-op without `GEMMA4_AUDIO_MAML`, because the file is a 166 MiB build
    /// artefact rather than a committed asset - unlike the nets in `tests/assets.rs`, whose
    /// `.maml`s are small enough to live in the tree. **Both the variable and an ignore-bypass
    /// flag are needed**; setting the variable alone leaves it skipped, silently:
    ///
    /// ```text
    /// GEMMA4_AUDIO_MAML=.../gemma4_audio.maml \
    ///   cargo test -p modelrunner --lib -- --include-ignored the_converter_output_matches
    /// ```
    ///
    /// `--ignored` in place of `--include-ignored` also works and runs only the ignored tests.
    ///
    /// `Shapes` records the dims a pass asks for and returns `Ok` without comparing them to
    /// anything, so the other tests prove this module is self-consistent and prove nothing about
    /// whether `collect_gemma4_audio` agrees with it. This is the test that closes that gap.
    #[test]
    #[ignore = "needs GEMMA4_AUDIO_MAML; the file is a build artefact, not a committed asset"]
    fn the_converter_output_matches_this_layout() {
        let Ok(path) = std::env::var("GEMMA4_AUDIO_MAML") else {
            println!("SKIPPED the_converter_output_matches_this_layout: GEMMA4_AUDIO_MAML unset");
            return;
        };
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::GEMMA4_AUDIO)
            .unwrap_or_else(|e| panic!("{path} is not a usable gemma4_audio .maml: {e}"));

        assert_eq!(weights.len(), TENSORS, "{path} holds the wrong number of tensors");
        declare_shared(&weights).expect("the shared tensors match the file");
        for index in 0..LAYERS {
            declare_layer(&weights, index)
                .unwrap_or_else(|e| panic!("layer {index} does not match the file: {e}"));
        }

        // The precision split is the contract with `collect_gemma4_audio.projection` and with
        // the parity harness's int4 control: quantise something we ship at fp16 and the device
        // looks better than it is, leave something fp16 that we ship at int4 and it looks worse.
        let quantised = (0..TENSORS)
            .filter(|i| weights.tensor(*i).expect("a tensor").dtype.is_quantised())
            .count();
        assert_eq!(quantised, LAYERS * PROJECTIONS_PER_LAYER, "int4 tensors");
        assert_eq!(TENSORS - quantised, 627, "fp16 tensors");

        // And the pass itself against the real file: every mode records and reads everything.
        let frames = 2999_u32;
        let clip = build(&weights, Mode::Clip { frames }).expect("the clip pass");
        assert_eq!(clip.outputs.len(), 1);
        assert!(!clip.ops.is_empty());
        println!(
            "gemma4_audio Clip at {frames} frames ({} tokens): {} ops, {} arena elems = {:.1} MiB",
            tokens(frames),
            clip.ops.len(),
            clip.arena_elems,
            clip.arena_elems as f64 * 2.0 / (1024.0 * 1024.0)
        );
        assert_eq!(build(&weights, Mode::Trace { frames, layers: 6 }).expect("t").outputs.len(), 2);
        assert_eq!(build(&weights, Mode::Sscp { frames }).expect("s").outputs.len(), 2);
        assert_eq!(
            build(&weights, Mode::Trace { frames, layers: 0 }).expect("t0").outputs.len(),
            2
        );
    }
}