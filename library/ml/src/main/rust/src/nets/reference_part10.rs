
    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_scrfd_net_produces_usable_detection_maps() {
        let Some(bytes) = asset("photos/src/main/assets/scrfd_500m.maml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::SCRFD)
            .expect("the shipped scrfd asset parses");
        // 128x128 rather than the 640 it runs at on device: the plan lowers at any
        // multiple of 32, and a 1/25th-area run exercises every one of the 60 layers,
        // both nearest upsamples and all nine heads for a twenty-fifth of the arithmetic.
        let plan = scrfd::build(&weights, 128, 128).expect("scrfd builds at 128x128");
        let shape = plan.input().expect("one input").shape;
        let got = run_multi(&plan, weights.data(), &[&blob(shape)]).expect("scrfd runs");

        assert_eq!(got.len(), 9);
        for (group, stride) in got.chunks_exact(3).zip(scrfd::STRIDES) {
            let [score, bbox, keypoints] = match group {
                [a, b, c] => [a, b, c],
                other => panic!("stride {stride}: {} maps", other.len()),
            };
            // Scores come through a sigmoid, so they are probabilities. Boxes and
            // keypoints are raw distances in stride units and only have to be finite.
            for (i, &value) in score.iter().enumerate() {
                assert!((0.0..=1.0).contains(&value), "stride {stride} score {i} is {value}");
            }
            for (name, map) in [("box", bbox), ("keypoint", keypoints)] {
                for (i, &value) in map.iter().enumerate() {
                    assert!(value.is_finite(), "stride {stride} {name} {i} is {value}");
                }
            }
            let peak = score.iter().fold(0.0f32, |a, &b| a.max(b));
            let spread = bbox.iter().fold(0.0f32, |a, &b| a.max(b.abs()));
            println!("scrfd/{stride}: peak score {peak:.4} largest box distance {spread:.3}");
            // A box map that is identically zero means the regression branch never ran,
            // which is what reading the wrong tensor index for a head produces.
            assert!(spread > 1e-3, "stride {stride} predicts no box extent at all");
        }
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_mobilefacenet_net_embeds_two_faces_differently() {
        let Some(bytes) = asset("photos/src/main/assets/w600k_mbf.maml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::MOBILEFACENET)
            .expect("the shipped mobilefacenet asset parses");
        let plan = mobilefacenet::build(&weights).expect("mobilefacenet builds");
        let shape = plan.input().expect("one input").shape;
        assert_eq!(
            plan.output().expect("one output").shape,
            Shape::new(mobilefacenet::EMBEDDING, 1, 1)
        );

        let first = run(&plan, weights.data(), &ramp(shape)).expect("embeds a ramp");
        let second = run(&plan, weights.data(), &blob(shape)).expect("embeds a blob");
        for (label, embedding) in [("ramp", &first), ("blob", &second)] {
            assert_eq!(embedding.len(), mobilefacenet::EMBEDDING as usize);
            for (i, &value) in embedding.iter().enumerate() {
                assert!(value.is_finite(), "{label} component {i} is {value}");
            }
            let norm = embedding.iter().map(|v| v * v).sum::<f32>().sqrt();
            println!("mobilefacenet/{label}: L2 norm {norm:.4}");
            // Unnormalised on purpose — `FaceRecognizer` L2-normalises in Kotlin — so
            // this only has to be a vector rather than the origin. A collapsed net,
            // which is what a wrong PReLU slope index produces, lands at zero.
            assert!(norm > 1e-2, "{label} embeds to the origin");
        }

        // Cosine similarity, which is exactly what the clustering compares. Two inputs
        // this unlike must not map to the same direction; a net whose final `Gemm` was
        // reshaped wrongly tends to return near-identical vectors for everything.
        let dot: f32 = first.iter().zip(&second).map(|(a, b)| a * b).sum();
        let magnitude = |v: &[f32]| v.iter().map(|x| x * x).sum::<f32>().sqrt();
        let cosine = dot / (magnitude(&first) * magnitude(&second));
        println!("mobilefacenet: cosine between the two {cosine:.4}");
        assert!(cosine < 0.99, "two unlike inputs embed to the same direction");
    }

    #[test]
    #[ignore = "runs the full shipped nets; minutes in a debug build"]
    fn the_shipped_ppocr_det_net_produces_a_usable_probability_map() {
        let Some(bytes) = asset("library/ocr/src/main/assets/ppocr_det.maml") else {
            return;
        };
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::PPOCR_DET)
            .expect("the shipped ppocr_det asset parses");
        // 128x128 rather than 960: the plan lowers at any multiple of 32, and this
        // exercises all 62 convolutions, both squeeze-excite kinds, the one surviving
        // affine, all six nearest upsamples and both transposed convolutions for a
        // fraction of the arithmetic.
        let plan = ppocr_det::build(&weights, 128, 128).expect("builds at 128x128");
        assert_eq!(plan.output().expect("one output").shape, Shape::new(1, 128, 128));
        // The output is a per-pixel text probability at full resolution, so it is a mask
        // in exactly the sense `assert_usable_mask` means — including that a synthetic
        // image contains no text, which makes a near-empty map the honest answer.
        assert_usable_mask("ppocr_det", &plan, weights.data());
    }

    /// The whole Supertonic pipeline, on the shipped assets, to a waveform on disk.
    ///
    /// Every parity check in this repo is *per net*: `onnx_parity.py` compares one graph against
    /// onnxruntime, and `vulkan::parity` compares one plan against this interpreter. Nothing
    /// compares the **composition** - four nets, a sixteen-step sampler loop and the marshalling
    /// between them - against anything, and that is where the engine's shipped bugs lived: the
    /// duration predictor's second output, and the vocoder's two missing permutations.
    ///
    /// So this runs `post::supertonic::synthesise` exactly as `bridge.rs` does, with the noise read
    /// from a file so an ONNX driver can be given the same draw, and writes the samples out for it
    /// to correlate against. Driven by `PARITY_DIR`, like `dump_reference_output`.
    #[test]
    #[ignore = "runs all four shipped supertonic nets through the interpreter; minutes"]
    fn dump_supertonic_utterance() {
        let Ok(dir) = std::env::var("SUPERTONIC_DIR") else {
            return;
        };
        let dir = std::path::PathBuf::from(dir);
        let text = std::env::var("SUPERTONIC_TEXT").expect("SUPERTONIC_TEXT");
        let Some(root) = repo_root() else {
            return;
        };
        let assets = root.join("speech/src/main/assets/supertonic");
        let load = |name: &str, id: u32| {
            let bytes = std::fs::read(assets.join(name)).unwrap_or_else(|e| panic!("{name}: {e}"));
            crate::weights::Weights::parse(&bytes, id).unwrap_or_else(|e| panic!("{name}: {e}"))
        };
        use crate::post::supertonic as post;
        use crate::weights::graph;
        let dp = load("supertonic_dp.maml", graph::SUPERTONIC_DP);
        let ttl = load("supertonic_ttl.maml", graph::SUPERTONIC_TTL);
        let ve = load("supertonic_ve.maml", graph::SUPERTONIC_VE);
        let voc = load("supertonic_voc.maml", graph::SUPERTONIC_VOC);
        let indexer = std::fs::read(assets.join("unicode_indexer.bin")).expect("the indexer");
        let style = std::fs::read(assets.join("style_F1.bin")).expect("the voice");
        let voice = post::Voice::read(&style).expect("the voice parses");
        let conditioning = post::Conditioning::read(ve.reader()).expect("the conditioning");

        /// The four nets through the interpreter, rebuilding a plan per shape as `Reshaped` does.
        struct Interpreted<'a> {
            dp: &'a crate::weights::Weights,
            ttl: &'a crate::weights::Weights,
            ve: &'a crate::weights::Weights,
            voc: &'a crate::weights::Weights,
        }
        impl post::Stages for Interpreted<'_> {
            fn duration(&mut self, lanes: &[f32], style: &[f32]) -> Result<f32, String> {
                let chars = (lanes.len() / 2 - 1) as u32;
                let plan = supertonic_duration::build(self.dp, chars)?;
                let out = run_multi(&plan, self.dp.data(), &[lanes, style])?;
                Ok(out[1][0])
            }
            fn text(&mut self, lanes: &[f32], style: &[f32]) -> Result<Vec<f32>, String> {
                let chars = (lanes.len() / 2) as u32;
                let plan = supertonic_text::build(self.ttl, chars)?;
                Ok(run_multi(&plan, self.ttl.data(), &[lanes, style])?.remove(0))
            }
            #[allow(clippy::too_many_arguments)]
            fn sampler(
                &mut self,
                latent: &[f32],
                text: &[f32],
                keys: &[f32],
                style: &[f32],
                shifts: &[f32],
                query_angles: &[f32],
                key_angles: &[f32],
            ) -> Result<Vec<f32>, String> {
                let frames = (latent.len() / 144) as u32;
                let chars = (text.len() / 256) as u32;
                let plan = supertonic_sampler::build(self.ve, frames, chars)?;
                let inputs: &[&[f32]] =
                    &[latent, text, keys, style, shifts, query_angles, key_angles];
                Ok(run_multi(&plan, self.ve.data(), inputs)?.remove(0))
            }
            #[allow(clippy::too_many_arguments)]
            fn sampler_both(
                &mut self,
                latent: &[f32],
                conditional_text: &[f32],
                conditional_keys: &[f32],
                conditional_style: &[f32],
                unconditional_text: &[f32],
                unconditional_keys: &[f32],
                unconditional_style: &[f32],
                shifts: &[f32],
                query_angles: &[f32],
                key_angles: &[f32],
            ) -> Result<[Vec<f32>; 2], String> {
                // The parity harness takes the same path as production: one dual plan,
                // one interpreter run, two velocities out. A dual plan that diverged
                // from two single plans would show here before it shows on a device.
                let frames = (latent.len() / 144) as u32;
                let chars = (conditional_text.len() / 256) as u32;
                let plan = supertonic_sampler::build_dual(self.ve, frames, chars)?;
                let inputs: &[&[f32]] = &[
                    latent,
                    conditional_text,
                    conditional_keys,
                    conditional_style,
                    shifts,
                    query_angles,
                    key_angles,
                    latent,
                    unconditional_text,
                    unconditional_keys,
                    unconditional_style,
                    shifts,
                    query_angles,
                    key_angles,
                ];
                let out = run_multi(&plan, self.ve.data(), inputs)?;
                let [conditional, unconditional] =
                    <[Vec<f32>; 2]>::try_from(out).map_err(|other| {
                        format!("the dual sampler returned {} tensors, not two", other.len())
                    })?;
                Ok([conditional, unconditional])
            }
            fn vocoder(&mut self, latent: &[f32], frames: u32) -> Result<Vec<f32>, String> {
                let plan = supertonic_vocoder::build(self.voc, frames)?;
                // Statistics of the latent the sampler converged to. A flow model that stayed on
                // the data manifold lands in the same distribution as the export's, even when the
                // trajectory differs; one that drifted off it does not, and that shows here long
                // before it shows in a waveform correlation.
                let n = latent.len() as f64;
                let mean = latent.iter().map(|&v| f64::from(v)).sum::<f64>() / n;
                let var =
                    latent.iter().map(|&v| (f64::from(v) - mean).powi(2)).sum::<f64>() / n;
                let absmax = latent.iter().fold(0.0f32, |t, &v| t.max(v.abs()));
                println!(
                    "final latent: {} values, absmax {absmax:.4}, mean {mean:.5}, sd {:.5}",
                    latent.len(),
                    var.sqrt()
                );
                let unpacked = supertonic_vocoder::unpack_latent(latent, frames as usize)?;
                let channelled = run_multi(&plan, self.voc.data(), &[&unpacked])?.remove(0);
                Ok(supertonic_vocoder::interleave(&channelled))
            }
        }

        let raw = std::fs::read(dir.join("noise.f32")).expect("the noise");
        let drawn: Vec<f32> = raw
            .chunks_exact(4)
            .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
            .collect();
        let mut nets = Interpreted { dp: &dp, ttl: &ttl, ve: &ve, voc: &voc };
        let language = std::env::var("SUPERTONIC_LANG").unwrap_or_else(|_| "en".into());
        let draw = |count: usize| {
            assert_eq!(count, drawn.len(), "the driver drew a latent of a different size");
            drawn.clone()
        };
        let samples =
            post::synthesise(&mut nets, &conditioning, &indexer, &voice, &text, &language, &draw)
                .expect("the utterance synthesises");
        println!("supertonic utterance: {} samples", samples.len());
        write(&dir.join("ours.f32"), &samples);
    }

    /// The repo root, or `None` when the assets are not checked out beside this crate.
    fn repo_root() -> Option<std::path::PathBuf> {
        let mut dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        while !dir.join("settings.gradle.kts").is_file() {
            dir = dir.parent()?.to_path_buf();
        }
        Some(dir)
    }

    #[test]
    fn repo_root_finds_the_workspace_marker() {
        let root = repo_root().expect("this crate is checked out in the workspace");
        assert!(root.join("settings.gradle.kts").is_file());
        assert!(root.join("library/ml/src/main/rust").is_dir());
    }