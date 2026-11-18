
    /// Run a shipped net on an input from disk and write its output back, for
    /// `scripts/ml/onnx_parity.py` to compare against onnxruntime.
    ///
    /// Ignored, and a no-op without `PARITY_DIR`: it exists to be driven by that script,
    /// which needs the export's ONNX and an `onnxruntime` install that CI does not have.
    /// See the script's header for what the comparison is worth and what it has caught.
    #[test]
    #[ignore = "driven by scripts/ml/onnx_parity.py"]
    fn dump_reference_output() {
        let Ok(dir) = std::env::var("PARITY_DIR") else {
            return;
        };
        let dir = std::path::PathBuf::from(dir);
        let graph = std::env::var("PARITY_GRAPH").expect("PARITY_GRAPH");
        let width: u32 = std::env::var("PARITY_WIDTH")
            .expect("PARITY_WIDTH")
            .parse()
            .expect("a width");
        let raw = std::fs::read(dir.join("input.f32")).expect("the input");
        let input: Vec<f32> = raw
            .chunks_exact(4)
            .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
            .collect();

        // A voice is a runtime download rather than a bundled asset, so the vocoder's
        // `.maml` is given by path instead of being looked up in the tree.
        if graph == "supertonic_voc" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights =
                crate::weights::Weights::parse(&bytes, crate::weights::graph::SUPERTONIC_VOC)
                    .expect("the vocoder asset parses");
            let plan = supertonic_vocoder::build(&weights, width).expect("the vocoder builds");
            // The latent arrives `[144, L]` and the plan wants `[24, 6L]`, and that is NOT a flat
            // reinterpretation: the export reshapes to `[24, 6, L]`, transposes the last two axes
            // and flattens, so position `p` of channel `c` is `latent[c * 6 + p % 6][p / 6]`.
            // Assuming a plain reshape here produced audio that correlated with the reference at
            // 0.009 — structurally wrong rather than merely imprecise.
            let unpacked = supertonic_vocoder::unpack_latent(&input, width as usize)
                .expect("the latent unpacks");
            let channelled = run(&plan, weights.data(), &unpacked).expect("the vocoder runs");
            // And the plan emits `[512, 1, T]` while the waveform is time-major. Both marshalling
            // steps are the host's job in production; it is copying into an audio buffer anyway.
            let samples = supertonic_vocoder::interleave(&channelled);
            write(&dir.join("reference.f32"), &samples);
            println!("supertonic_voc at {width} frames: wrote {} values", samples.len());
            return;
        }
        if graph == "supertonic_ve" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights =
                crate::weights::Weights::parse(&bytes, crate::weights::graph::SUPERTONIC_VE)
                    .expect("the sampler asset parses");
            let chars: u32 = std::env::var("PARITY_CHARS")
                .expect("PARITY_CHARS")
                .parse()
                .expect("a character count");
            let plan = supertonic_sampler::build(&weights, width, chars).expect("the sampler builds");
            let read = |name: &str| -> Vec<f32> {
                let raw = std::fs::read(dir.join(name)).unwrap_or_else(|e| panic!("{name}: {e}"));
                raw.chunks_exact(4)
                    .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                    .collect()
            };
            let text = read("text.f32");
            let style = read("style.f32");
            use crate::post::supertonic as post;
            let (current, total) = (5u32, 16u32);
            let conditioning =
                post::Conditioning::read(weights.reader()).expect("the conditioning");
            let shifts =
                post::time_shifts(weights.reader(), current, total).expect("the timestep shifts");
            let query_angles =
                post::rotary_angles(&conditioning.theta, width).expect("the query angles");
            let key_angles =
                post::rotary_angles(&conditioning.theta, chars).expect("the key angles");
            // The export tiles its batch to two: the real conditioning, and two learned
            // unconditional tokens. Two runs of one plan here, combined below.
            let unconditional_text = post::unconditional_text(&conditioning.text_token, chars)
                .expect("the unconditional text");
            let run_branch = |text: &[f32], keys: &[f32], style: &[f32]| -> Vec<f32> {
                let outputs = run_multi(
                    &plan,
                    weights.data(),
                    &[&input, text, keys, style, &shifts, &query_angles, &key_angles],
                )
                .expect("the sampler runs");
                outputs.into_iter().next().expect("the velocity")
            };
            let conditional = run_branch(&text, &conditioning.conditional_keys, &style);
            let unconditional = run_branch(
                &unconditional_text,
                &conditioning.unconditional_keys,
                &conditioning.unconditional_style,
            );
            let denoised = post::step(&input, &conditional, &unconditional, total)
                .expect("the Euler step");
            write(&dir.join("reference.f32"), &denoised);
            println!(
                "supertonic_ve at {width} frames, {chars} chars: wrote {} values",
                denoised.len()
            );
            return;
        }
        if graph == "supertonic_ttl" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights =
                crate::weights::Weights::parse(&bytes, crate::weights::graph::SUPERTONIC_TTL)
                    .expect("the text encoder asset parses");
            let plan = supertonic_text::build(&weights, width).expect("the text encoder builds");
            let raw = std::fs::read(dir.join("style.f32")).expect("the style");
            let style: Vec<f32> = raw
                .chunks_exact(4)
                .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect();
            let ids: Vec<u32> = input.iter().map(|&v| v as u32).collect();
            let lanes = super::super::embed_lanes(&ids);
            let outputs = run_multi(&plan, weights.data(), &[&lanes, &style])
                .expect("the text encoder runs");
            let emb = outputs.first().expect("the conditioning output");
            write(&dir.join("reference.f32"), emb);
            println!("supertonic_ttl at {width} chars: wrote {} values", emb.len());
            return;
        }
        if graph == "whisper" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::WHISPER)
                .expect("the whisper asset parses");
            let plan = whisper::build(&weights, whisper::Mode::Encode).expect("the encoder builds");
            let outputs = run_multi(&plan, weights.data(), &[&input]).expect("the encoder runs");
            // The one output is the hidden states; the cross-attention caches stay on the device.
            let hidden = outputs.first().expect("the hidden states");
            write(&dir.join("reference.f32"), hidden);
            println!("whisper encoder: wrote {} values", hidden.len());
            return;
        }
        if graph == "modnet" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::MODNET)
                .expect("the modnet asset parses");
            let plan = modnet::build(&weights).expect("modnet builds");
            let matte = run(&plan, weights.data(), &input).expect("modnet runs");
            write(&dir.join("reference.f32"), &matte);
            println!("modnet at 512: wrote {} values", matte.len());
            return;
        }
        if graph == "maia" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::MAIA)
                .expect("the maia asset parses");
            // The script writes the 12 board planes followed by the two elos, rather than a
            // built input: the history repeat and the elo blend are host code in
            // `nets::maia`, and running them here is what puts them under the comparison.
            let planes = (maia::PLANES * maia::SQUARES) as usize;
            let (board, elos) = input.split_at(planes);
            let self_elo = maia::elo_embedding(weights.reader(), elos[0]).expect("self elo");
            let oppo_elo = maia::elo_embedding(weights.reader(), elos[1]).expect("oppo elo");
            let tokens = maia::tokens(board, &self_elo, &oppo_elo).expect("the input builds");
            let plan = maia::build(&weights).expect("the forward pass builds");
            let outputs = run_multi(&plan, weights.data(), &[&tokens]).expect("maia runs");
            let [scores, promo] = outputs.as_slice() else {
                panic!("{} outputs, not 2", outputs.len());
            };
            // The comparison is the assembled move vector, not the raw tensors, so
            // `post::maia`'s indexing is inside the bound rather than beside it.
            let logits = crate::post::maia::logits(scores, promo).expect("the logits assemble");
            write(&dir.join("reference.f32"), &logits);
            println!(
                "maia at self {} oppo {}: wrote {} logits",
                elos[0],
                elos[1],
                logits.len()
            );
            return;
        }
        if graph == "tinyclip" || graph == "tinyclip_text" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights =
                crate::weights::Weights::parse(&bytes, crate::weights::graph::TINYCLIP)
                    .expect("the tinyclip asset parses");
            // Both towers project every position and pool one on the host, so the comparable
            // vector is one column of the plan's output. Which column is the whole of what
            // `nets::tinyclip`'s module docs warn about, and it differs per tower.
            let column = |out: &[f32], at: usize, len: usize| -> Vec<f32> {
                (0..tinyclip::PROJECTION as usize)
                    .map(|channel| out[channel * len + at])
                    .collect()
            };
            let pooled = if graph == "tinyclip" {
                let plan =
                    tinyclip::build(&weights, tinyclip::Mode::Image).expect("the image tower");
                let out = run(&plan, weights.data(), &input).expect("the image tower runs");
                // The class token, position 0.
                column(&out, 0, tinyclip::VISION_POSITIONS as usize)
            } else {
                // The ids arrive as f32, exactly as the caller's tokenizer hands them over.
                let ids: Vec<u32> = input.iter().map(|&v| v as u32).collect();
                let embedded = tinyclip::embed_positions(weights.reader(), &ids)
                    .expect("the host embedding gather");
                let len = ids.len();
                let plan = tinyclip::build(&weights, tinyclip::Mode::Text { len: len as u32 })
                    .expect("the text tower");
                let out = run(&plan, weights.data(), &embedded).expect("the text tower runs");
                // The end-of-text position, which is the last one because the caller passed
                // `len = eot + 1` and the causal mask makes that exact.
                column(&out, len - 1, len)
            };
            write(&dir.join("reference.f32"), &pooled);
            println!("{graph}: wrote {} values", pooled.len());
            return;
        }
        if graph == "supertonic_dp" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights =
                crate::weights::Weights::parse(&bytes, crate::weights::graph::SUPERTONIC_DP)
                    .expect("the duration asset parses");
            let plan = supertonic_duration::build(&weights, width).expect("the predictor builds");
            let raw = std::fs::read(dir.join("style.f32")).expect("the style");
            let style: Vec<f32> = raw
                .chunks_exact(4)
                .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
                .collect();
            // The sentence token leads the sequence, and the ids go past 2048 so they travel as
            // two lanes. Both are the caller's job in production too.
            let mut ids = vec![supertonic_duration::SENTENCE_TOKEN];
            ids.extend(input.iter().map(|&v| v as u32));
            let lanes = super::super::embed_lanes(&ids);
            let outputs =
                run_multi(&plan, weights.data(), &[&lanes, &style]).expect("the predictor runs");
            let encoded = outputs.first().expect("the encoder output");
            write(&dir.join("reference.f32"), encoded);
            let log_seconds = outputs[1][0];
            println!(
                "supertonic_dp at {width} chars: wrote {} values, {:.6} seconds ({} frames)",
                encoded.len(),
                supertonic_duration::seconds(log_seconds),
                supertonic_duration::latent_frames(supertonic_duration::seconds(log_seconds)),
            );
            return;
        }

        let (path, id) = match graph.as_str() {
            "ppocr_rec" => (
                "library/ocr/src/main/assets/ppocr_rec.maml",
                crate::weights::graph::PPOCR_REC,
            ),
            "ppocr_det" => (
                "library/ocr/src/main/assets/ppocr_det.maml",
                crate::weights::graph::PPOCR_DET,
            ),
            other => panic!("no parity probe for {other}"),
        };
        let bytes = asset(path).unwrap_or_else(|| panic!("{path} is not checked out"));
        let weights = crate::weights::Weights::parse(&bytes, id).expect("the asset parses");
        // Detection's own output is a saturated probability map, so the probe is its
        // backbone output — where all fourteen of its surviving affines are.
        let plan = match graph.as_str() {
            "ppocr_rec" => ppocr_rec::build(&weights, width).expect("ppocr_rec builds"),
            _ => ppocr_det::build_with_backbone(&weights, width, width).expect("det builds"),
        };
        let outputs = run_multi(&plan, weights.data(), &[&input]).expect("the net runs");
        let probe = match graph.as_str() {
            "ppocr_rec" => outputs.first(),
            _ => outputs.get(1),
        }
        .expect("a probe output");
        write(&dir.join("reference.f32"), probe);
        println!("{graph} at {width}: wrote {} values", probe.len());
    }

    /// `values` as little-endian `f32`, for the parity script to read back.
    fn write(path: &std::path::Path, values: &[f32]) {
        let mut out = Vec::with_capacity(values.len() * 4);
        for value in values {
            out.extend_from_slice(&value.to_le_bytes());
        }
        std::fs::write(path, out).expect("writes");
    }

    /// The shipped `.maml` for `name`, or `None` if it is not checked out.
    fn asset(name: &str) -> Option<Vec<u8>> {
        let mut dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        while !dir.join("settings.gradle.kts").is_file() {
            dir = dir.parent()?.to_path_buf();
        }
        std::fs::read(dir.join(name)).ok()
    }

    /// A gradient: monotonic in both axes, and containing nothing a segmentation net
    /// should find.
    fn ramp(shape: Shape) -> Vec<f32> {
        let mut values = Vec::with_capacity(shape.len() as usize);
        for c in 0..shape.c {
            for y in 0..shape.h {
                for x in 0..shape.w {
                    let across = x as f32 / shape.w as f32;
                    let down = y as f32 / shape.h as f32;
                    values.push((across + down + c as f32 * 0.1) / 2.2);
                }
            }
        }
        values
    }

    /// A bright centred blob on a dark field — the crudest possible stand-in for a
    /// subject, and structurally nothing like [`ramp`].
    fn blob(shape: Shape) -> Vec<f32> {
        let mut values = Vec::with_capacity(shape.len() as usize);
        let (cy, cx) = (shape.h as f32 / 2.0, shape.w as f32 / 2.0);
        let radius = shape.h.min(shape.w) as f32 / 3.0;
        for c in 0..shape.c {
            for y in 0..shape.h {
                for x in 0..shape.w {
                    let dy = y as f32 - cy;
                    let dx = x as f32 - cx;
                    let inside = (dy * dy + dx * dx).sqrt() < radius;
                    values.push(if inside { 0.9 - c as f32 * 0.2 } else { 0.05 });
                }
            }
        }
        values
    }

    /// Run `plan` on two unlike inputs and check that both give a usable mask.
    ///
    /// # What is and is not asserted
    ///
    /// Finiteness and the `0..1` range are the cheap part, and they do catch the
    /// failure these nets are most prone to: fp16 saturating somewhere down a hundred
    /// layers and coming back as an infinity or a NaN.
    ///
    /// Flatness deliberately is **not** asserted. A synthetic image contains no
    /// subject, so a near-uniform mask is the honest answer and demanding variance
    /// would only be demanding that the net hallucinate. What is asserted instead is
    /// that the mask *depends on its input*: a forward pass whose weights were
    /// misindexed into a constant, whose activations had all died, or whose arena
    /// aliased itself would answer the same thing for both of these, and that is a
    /// property a real photograph is not needed to check.
    ///
    /// The distribution is printed rather than asserted, for the same reason
    /// `tests/assets.rs` prints its memory figures: the numbers are what a reviewer
    /// wants to see, and pinning them would pin this runtime's fp16 rounding.
    fn assert_usable_mask(name: &str, plan: &Plan, weights: &[u8]) {
        let shape = plan.input().expect("one input").shape;
        let inputs = [("ramp", ramp(shape)), ("blob", blob(shape))];
        let mut masks = Vec::new();
        for (label, input) in &inputs {
            let mask = run(plan, weights, input).unwrap_or_else(|e| panic!("{name}/{label}: {e}"));
            assert_eq!(mask.len(), plan.output().expect("one output").shape.len() as usize);
            for (i, &value) in mask.iter().enumerate() {
                assert!(
                    value.is_finite() && (0.0..=1.0).contains(&value),
                    "{name}/{label} pixel {i} is {value}",
                );
            }
            let low = mask.iter().fold(f32::MAX, |a, &b| a.min(b));
            let high = mask.iter().fold(f32::MIN, |a, &b| a.max(b));
            let mean = mask.iter().sum::<f32>() / mask.len() as f32;
            println!("{name}/{label}: min {low:.4} mean {mean:.4} max {high:.4}");
            masks.push(mask);
        }

        let (first, second) = match (masks.first(), masks.get(1)) {
            (Some(a), Some(b)) => (a, b),
            _ => panic!("{name}: two runs"),
        };
        // The largest per-pixel change, not the mean one. Both of these nets answer a
        // *question* about the image — "is this pixel a person", "is it the salient
        // object" — so a correct mask is zero across almost all of a synthetic frame
        // and any mean is dominated by that zero. The maximum is what distinguishes a
        // net that responded somewhere from one that cannot respond at all.
        let response = first
            .iter()
            .zip(second)
            .map(|(a, b)| (a - b).abs())
            .fold(0.0f32, f32::max);
        println!("{name}: peak response to the input {response:.4}");
        assert!(response > 0.01, "{name} answers {response} regardless of its input");
    }

    /// `cargo test --release -p modelrunner --lib -- --ignored --nocapture`, and expect
    /// about a minute for the pair.
    ///
    /// Ignored by default because these are the real graphs at their real sizes —
    /// roughly 0.5 and 2.2 GMAC — which a debug build does not get through quickly.
    /// The per-op fixtures above are what run on every commit; this is the check that
    /// the whole shipped forward pass, against the shipped weights, holds together.
    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_selfie_net_produces_a_usable_mask() {
        let Some(bytes) = asset("camera/src/main/assets/selfie_segmentation.maml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::SELFIE)
            .expect("the shipped selfie asset parses");
        let plan = selfie::build(&weights).expect("selfie builds");
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(1, selfie::SIZE, selfie::SIZE)
        );
        assert_usable_mask("selfie", &plan, weights.data());
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_u2netp_net_produces_a_usable_mask() {
        let Some(bytes) = asset("photos/src/main/assets/u2netp.maml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::U2NETP)
            .expect("the shipped u2netp asset parses");
        let plan = u2netp::build(&weights).expect("u2netp builds");
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(1, u2netp::SIZE, u2netp::SIZE)
        );
        assert_usable_mask("u2netp", &plan, weights.data());
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_modnet_net_produces_a_usable_mask() {
        let Some(bytes) = asset("camera/src/main/assets/modnet.maml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::MODNET)
            .expect("the shipped modnet asset parses");
        let plan = modnet::build(&weights).expect("modnet builds");
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(1, modnet::SIZE, modnet::SIZE)
        );
        assert_usable_mask("modnet", &plan, weights.data());
    }

    #[test]
    fn a_synthetic_mask_net_passes_the_usable_mask_check() {
        // The shipped-net assertion, exercised on a one-layer stand-in:
        // a 1x1 identity conv under a sigmoid is a usable mask (finite,
        // in 0..1) and answers its two synthetic inputs differently.
        let given = crate::nets::reference::Given::new(&[
            (vec![1, 1, 1, 1], vec![1.0]),
            (vec![1], vec![0.0]),
        ])
        .expect("tensors");
        let mut builder = crate::nets::Builder::new(&given);
        let x = builder.input(Shape::new(1, 4, 4));
        let conv = builder.conv(x, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, crate::nets::Act::None);
        let out = builder.activate(conv, crate::nets::Act::Sigmoid);
        let plan = builder.finish(&[out]).expect("builds");
        assert_usable_mask("synthetic", &plan, given.data());
        // The asset walk finds the workspace root beside this crate.
        assert!(asset("settings.gradle.kts").is_some());
        assert!(asset("no/such/asset.bin").is_none());
    }

    #[test]
    fn synthetic_ramp_and_blob_have_the_documented_values() {
        let ramped = ramp(Shape::new(2, 2, 2));
        let want = [
            0.0, 0.5 / 2.2, 0.5 / 2.2, 1.0 / 2.2,
            0.1 / 2.2, 0.6 / 2.2, 0.6 / 2.2, 1.1 / 2.2,
        ];
        assert_eq!(ramped.len(), want.len());
        for (got, want) in ramped.iter().zip(want) {
            assert!((got - want).abs() < 1e-6, "ramp {ramped:?}");
        }

        let blobbed = blob(Shape::new(1, 4, 4));
        assert_eq!(blobbed.iter().filter(|&&v| (v - 0.9).abs() < 1e-6).count(), 5);
        assert_eq!(blobbed.iter().filter(|&&v| (v - 0.05).abs() < 1e-6).count(), 11);
        assert!((blobbed[2 * 4 + 2] - 0.9).abs() < 1e-6, "centre is inside");
        assert!((blobbed[0] - 0.05).abs() < 1e-6, "corner is outside");
    }

    #[test]
    fn written_values_are_little_endian_f32() {
        let path = std::env::temp_dir()
            .join(format!("modelrunner-reference-write-{}.f32", std::process::id()));
        write(&path, &[1.0, -2.5]);
        let bytes = std::fs::read(&path).expect("the written file");
        std::fs::remove_file(&path).expect("cleanup");
        let mut want = Vec::new();
        want.extend_from_slice(&1.0f32.to_le_bytes());
        want.extend_from_slice(&(-2.5f32).to_le_bytes());
        assert_eq!(bytes, want);
    }