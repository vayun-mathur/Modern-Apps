//! Builds both forward passes against the `.maml` files actually committed to
//! `camera/` and `photos/`.
//!
//! Everything in `src/nets` is unit-tested against a stub weight source, which proves
//! the two passes are self-consistent but not that they agree with the converter. This
//! is the test that closes that: it parses the shipped assets and walks all 348 tensors
//! through `Weights::shaped`, so a converter that reordered a layer, a `.maml` rebuilt
//! from a moved upstream, or a miscounted RSU block fails here — naming the tensor.
//!
//! Given that nothing checks the *numbers* this runtime produces (correctness is
//! confirmed visually on device), agreement about the tensor table is the strongest
//! automatic guarantee available, and it is worth reaching across modules for.

use std::path::{Path, PathBuf};

use modelrunner::nets::{
    maia, mobilefacenet, ppocr_det, ppocr_rec, scrfd, selfie, supertonic_duration,
    supertonic_sampler, supertonic_text, supertonic_vocoder, tinyclip, u2netp, whisper,
};
use modelrunner::post::ctc;
use modelrunner::weights::{graph, Weights};

/// The repo root, found by walking up for `settings.gradle.kts` rather than by counting
/// `..` — so moving this crate cannot silently turn the test into a no-op.
fn repo_root() -> PathBuf {
    let mut dir: &Path = &PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    loop {
        if dir.join("settings.gradle.kts").is_file() {
            return dir.to_path_buf();
        }
        dir = dir.parent().expect("this crate is inside the repo");
    }
}

fn load(relative: &str, graph_id: u32) -> Weights {
    let path = repo_root().join(relative);
    let bytes = std::fs::read(&path).unwrap_or_else(|e| {
        panic!(
            "cannot read {}: {e}. Run ./scripts/ml/fetch_and_convert.sh to rebuild it.",
            path.display()
        )
    });
    Weights::parse(&bytes, graph_id)
        .unwrap_or_else(|e| panic!("{} is not a usable .maml: {e}", path.display()))
}

#[test]
fn the_shipped_selfie_asset_builds_the_selfie_forward_pass() {
    let weights = load("camera/src/main/assets/selfie_segmentation.maml", graph::SELFIE);
    assert_eq!(weights.len(), selfie::TENSORS);
    let plan = selfie::build(&weights).expect("the shipped asset matches nets::selfie");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_u2netp_asset_builds_the_u2netp_forward_pass() {
    let weights = load("photos/src/main/assets/u2netp.maml", graph::U2NETP);
    assert_eq!(weights.len(), u2netp::TENSORS);
    let plan = u2netp::build(&weights).expect("the shipped asset matches nets::u2netp");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_mobilefacenet_asset_builds_the_mobilefacenet_forward_pass() {
    let weights = load("photos/src/main/assets/w600k_mbf.maml", graph::MOBILEFACENET);
    assert_eq!(weights.len(), mobilefacenet::TENSORS);
    let plan =
        mobilefacenet::build(&weights).expect("the shipped asset matches nets::mobilefacenet");
    assert!(!plan.ops.is_empty());
}

#[test]
fn the_shipped_scrfd_asset_builds_the_scrfd_forward_pass() {
    let weights = load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD);
    assert_eq!(weights.len(), scrfd::TENSORS);
    let plan = scrfd::build(&weights, scrfd::LONG_SIDE, scrfd::LONG_SIDE)
        .expect("the shipped asset matches nets::scrfd");
    assert_eq!(plan.outputs.len(), 9);
}

#[test]
fn the_scrfd_pass_builds_at_every_letterbox_shape_a_photo_can_produce() {
    // The detector is compiled per shape, so the whole family has to lower — not just
    // the square case. These are the short sides a 640-long-side letterbox rounds to for
    // aspect ratios from 1:1 to about 3:1, which covers any real photo.
    let weights = load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD);
    for short in (scrfd::EXTENT_MULTIPLE..=scrfd::LONG_SIDE).step_by(32) {
        for (height, width) in [(short, scrfd::LONG_SIDE), (scrfd::LONG_SIDE, short)] {
            let plan = scrfd::build(&weights, height, width)
                .unwrap_or_else(|e| panic!("{width}x{height}: {e}"));
            assert_eq!(plan.outputs.len(), 9, "{width}x{height}");
        }
    }
}

#[test]
fn the_shipped_ppocr_det_asset_builds_the_ppocr_det_forward_pass() {
    let weights = load("library/ocr/src/main/assets/ppocr_det.maml", graph::PPOCR_DET);
    assert_eq!(weights.len(), ppocr_det::TENSORS);
    let plan = ppocr_det::build(&weights, ppocr_det::LONG_SIDE, ppocr_det::LONG_SIDE)
        .expect("the shipped asset matches nets::ppocr_det");
    // Full-resolution probability map, which is what DBNet thresholds.
    assert_eq!(
        plan.output().expect("one output").shape.h,
        ppocr_det::LONG_SIDE
    );
}

#[test]
fn the_shipped_ppocr_rec_asset_builds_the_ppocr_rec_forward_pass() {
    let weights = load("library/ocr/src/main/assets/ppocr_rec.maml", graph::PPOCR_REC);
    assert_eq!(weights.len(), ppocr_rec::TENSORS);
    // Building against the real file checks every one of the 112 tensors' shapes, because
    // `Builder::weight` asks for each by index *and* dimensions — so a converter that
    // transposed a linear, split the fused QKV projection the wrong way, or emitted a
    // layer norm's gamma and beta in the wrong order fails here rather than on device.
    let plan = ppocr_rec::build(&weights, 320)
        .expect("the shipped asset matches nets::ppocr_rec");
    assert_eq!(
        plan.output().expect("one output").shape,
        modelrunner::nets::Shape::new(ppocr_rec::LOGITS, 1, 40)
    );
}

#[test]
fn the_shipped_ppocr_rec_asset_builds_at_every_padded_line_width() {
    // A line crop is resized to 48 tall and padded to a multiple of 8, so the plan has to
    // lower at whatever width the batcher lands on.
    let weights = load("library/ocr/src/main/assets/ppocr_rec.maml", graph::PPOCR_REC);
    for width in [8u32, 64, 160, 320, 640, 1024] {
        let plan = ppocr_rec::build(&weights, width)
            .unwrap_or_else(|e| panic!("at width {width}: {e}"));
        assert_eq!(plan.output().expect("one output").shape.w, width / 8);
    }
}

#[test]
fn the_shipped_character_dictionary_matches_the_recogniser_label_space() {
    // The model's final layer emits 838 logits positionally, so the dictionary's *length*
    // is a hard constraint and its *order* is the whole decode. A file that lost a line
    // to an editor would shift every character above it.
    let path = repo_root().join("library/ocr/src/main/assets/ppocr_keys.txt");
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let dictionary = ctc::Dictionary::parse(&text).expect("the shipped dictionary parses");
    assert_eq!(dictionary.labels(), ctc::LOGITS);
    // Spot-check the ends against the export's `inference.yml`: digits first, and the
    // appended space last.
    assert_eq!(dictionary.label(0), None, "label 0 is the CTC blank");
    assert_eq!(dictionary.label(1), Some("0"));
    assert_eq!(dictionary.label(11), Some("A"));
    assert_eq!(dictionary.label(ctc::LOGITS - 1), Some(" "));
}

#[test]
fn the_shipped_maia_asset_builds_the_maia_forward_pass() {
    // Against the real 259-tensor file, which is what checks `collect_maia`'s emission order
    // against `nets::maia`'s layout constants: `Builder::conv_int8` asks for every tensor by
    // index *and* dimensions, so a transposed projection, the fused q/k/v split done the wrong
    // way, an RMS norm emitted with a beta it does not have, or a scale and a bias swapped all
    // fail here rather than on device.
    let weights = load("games/chess/src/main/assets/maia3-5m.maml", graph::MAIA);
    assert_eq!(weights.len(), maia::TENSORS);

    let plan = maia::build(&weights).expect("the shipped asset matches nets::maia");
    let shapes: Vec<modelrunner::nets::Shape> =
        plan.outputs.iter().map(|out| out.shape).collect();
    assert_eq!(
        shapes,
        vec![
            modelrunner::nets::Shape::new(1, maia::SQUARES, maia::SQUARES),
            modelrunner::nets::Shape::new(maia::PROMOTIONS, 1, maia::SQUARES),
        ]
    );
}

#[test]
fn the_shipped_maia_asset_blends_the_two_elo_embeddings_on_the_host() {
    // The host blend, against the real file. `elo_embedding` is the only reader of either
    // table, and upstream's naming is inverted — `weight_low` is `elo / 5000` and multiplies
    // `elo_embedding_low` — so at elo 0 the vector is entirely the *high* embedding. A blend
    // computed the intuitive way round produces a perfectly plausible vector, which is why what
    // is pinned here is the relation between the three points rather than any one of them.
    let weights = load("games/chess/src/main/assets/maia3-5m.maml", graph::MAIA);
    let table = weights.offsets();

    let low = maia::elo_embedding(modelrunner::weights::Reader::new(&table, &weights), 0.0)
        .expect("the blend at elo 0");
    let high = maia::elo_embedding(
        modelrunner::weights::Reader::new(&table, &weights),
        maia::ELO_MAX,
    )
    .expect("the blend at elo 5000");
    let middle = maia::elo_embedding(
        modelrunner::weights::Reader::new(&table, &weights),
        maia::ELO_MAX / 2.0,
    )
    .expect("the blend at elo 2500");

    assert_eq!(low.len(), maia::ELO_DIM as usize);
    assert!(low.iter().any(|&v| v != 0.0), "the blend produced zeros");
    // The two ends are different vectors, which is what makes the difficulty setting mean
    // anything at all.
    assert!(low.iter().zip(&high).any(|(a, b)| a != b), "both ends are the same vector");
    // And the midpoint is exactly halfway, because the blend is linear.
    for index in 0..maia::ELO_DIM as usize {
        let want = (low[index] + high[index]) / 2.0;
        assert!(
            (middle[index] - want).abs() < 1e-4,
            "channel {index}: {} against {want}",
            middle[index]
        );
    }

    // Past either end it clamps rather than extrapolating, as `torch.clamp` does.
    let beyond = maia::elo_embedding(modelrunner::weights::Reader::new(&table, &weights), 9000.0)
        .expect("the blend past the top");
    assert_eq!(beyond, high);
}

#[test]
fn the_shipped_tinyclip_asset_builds_both_towers() {
    // Both passes against the real 306-tensor file, which is what checks the converter's emission
    // order against `nets::tinyclip`'s layout constants: `Builder::weight` asks for every tensor by
    // index *and* dimensions, so a transposed projection, a position table emitted the wrong way
    // round, or a tower emitted in the export's interleaved order fails here rather than on device.
    let weights = load("photos/src/main/assets/clip/tinyclip.maml", graph::TINYCLIP);
    assert_eq!(weights.len(), tinyclip::TENSORS);

    let image = tinyclip::build(&weights, tinyclip::Mode::Image)
        .expect("the shipped asset matches nets::tinyclip's vision tower");
    assert_eq!(
        image.output().expect("one output").shape,
        modelrunner::nets::Shape::new(tinyclip::PROJECTION, 1, tinyclip::VISION_POSITIONS)
    );

    // Every length a query can be, since the pass is compiled per length: 1 through the padded 77.
    for len in [1u32, 2, 5, 32, tinyclip::CONTEXT] {
        let text = tinyclip::build(&weights, tinyclip::Mode::Text { len })
            .unwrap_or_else(|e| panic!("at {len} tokens: {e}"));
        assert_eq!(
            text.output().expect("one output").shape,
            modelrunner::nets::Shape::new(tinyclip::PROJECTION, 1, len)
        );
    }
}

#[test]
fn the_shipped_tinyclip_asset_embeds_a_token_on_the_host() {
    // The host gather, against the real file: the token embedding is int8 with a per-row scale and
    // the position table is fp16, and `embed_positions` is the only reader of either. A wrong row
    // stride or a transposed position table produces a plausible vector, so what is pinned here is
    // the one property that has no fixture — the same token at two positions must differ by exactly
    // the two position rows' difference.
    let weights = load("photos/src/main/assets/clip/tinyclip.maml", graph::TINYCLIP);
    let table = weights.offsets();
    let reader = modelrunner::weights::Reader::new(&table, &weights);

    // `<|startoftext|>`, "dog", `<|endoftext|>`.
    let ids = [49_406u32, 1_929, 49_407];
    let out = tinyclip::embed_positions(reader, &ids).expect("the gather succeeds");
    assert_eq!(out.len(), tinyclip::WIDTH as usize * ids.len());
    assert!(out.iter().any(|&v| v != 0.0), "the gather produced zeros");

    // Channel-major: `out[c * len + t]`. A position-major layout would put one token's 256 values
    // contiguously instead, so this also pins the transpose.
    let twice = tinyclip::embed_positions(reader, &[1_929, 1_929]).expect("the gather succeeds");
    let single = tinyclip::embed_positions(reader, &[1_929]).expect("the gather succeeds");
    for channel in 0..tinyclip::WIDTH as usize {
        let first = twice[channel * 2];
        // The same token at position 0, alone, must be exactly the same value.
        assert!((first - single[channel]).abs() < 1e-6, "channel {channel}");
        // And the two positions must differ, or the position table was never added.
        assert!(first.is_finite() && twice[channel * 2 + 1].is_finite(), "channel {channel}");
    }
    let moved = (0..tinyclip::WIDTH as usize)
        .filter(|&c| (twice[c * 2] - twice[c * 2 + 1]).abs() > 1e-4)
        .count();
    assert!(moved > tinyclip::WIDTH as usize / 2, "only {moved} channels differ between positions");
}

#[test]
fn the_shipped_whisper_asset_builds_both_passes() {
    // Both passes against the real 363-tensor file, which is what checks the converter's emission
    // order against `nets::whisper`'s layout constants: `Builder::weight` asks for every tensor by
    // index *and* dimensions, so a transposed projection, a position table emitted the wrong way
    // round, or the two passes disagreeing about which cross-attention projection belongs to which
    // fails here rather than on device.
    let weights = load("speech/src/main/assets/whisper-base/whisper_base.maml", graph::WHISPER);
    assert_eq!(weights.len(), whisper::TENSORS);

    let encode = whisper::build(&weights, whisper::Mode::Encode)
        .expect("the shipped asset matches nets::whisper's encoder");
    // The hidden states are the only output: the twelve cross-attention
    // caches stay on the device as the first pinned tensors, where the
    // decode step's plan declares them at the same offsets.
    assert_eq!(encode.outputs.len(), 1);
    assert_eq!(
        encode.outputs[0].shape,
        modelrunner::nets::Shape::new(whisper::D_MODEL, 1, whisper::SOURCE_POSITIONS)
    );
    let caches = whisper::DECODER_LAYERS * 2;
    assert!(encode.pinned.len() >= caches, "the cross caches are pinned");
    for binding in &encode.pinned[..caches] {
        assert_eq!(
            binding.shape,
            modelrunner::nets::Shape::new(whisper::D_MODEL, 1, whisper::SOURCE_POSITIONS)
        );
    }

    // The decode plan is recorded once at `MAX_POSITIONS` and the step supplies how much of the
    // cache is live, so there is one plan per transcript rather than one per token.
    let step = whisper::build(&weights, whisper::Mode::DecodeStep).expect("the decode step builds");
    assert_eq!(
        step.outputs[0].shape,
        modelrunner::nets::Shape::new(whisper::VOCAB, 1, 1),
    );
    // The logits and nothing else: the self-attention caches stay on the device.
    assert_eq!(step.outputs.len(), 1);
}

#[test]
fn the_shipped_whisper_asset_embeds_a_token_on_the_host() {
    // The host gather, against the real file: the tied embedding is int8 with a per-row scale and
    // the decoder position table is fp16, and `embed_positions` is the only reader of the table. What
    // is pinned here is the property that has no fixture — the same token at two positions must
    // differ by exactly the two position rows' difference, which fails if `past` is applied wrongly.
    let weights = load("speech/src/main/assets/whisper-base/whisper_base.maml", graph::WHISPER);
    let table = weights.offsets();
    let reader = modelrunner::weights::Reader::new(&table, &weights);

    // `<|startoftranscript|>`, `<|en|>`, `<|transcribe|>`, `<|notimestamps|>` — the real prompt.
    let prompt = [50_258u32, 50_259, 50_359, 50_363];
    let out = whisper::embed_positions(reader, &prompt, 0).expect("the gather succeeds");
    assert_eq!(out.len(), whisper::D_MODEL as usize * prompt.len());
    assert!(out.iter().any(|&v| v != 0.0), "the gather produced zeros");

    // Channel-major: `out[c * len + t]`. A position-major layout would put one token's 512 values
    // contiguously instead, so this also pins the transpose.
    let at_zero = whisper::embed_positions(reader, &[50_258], 0).expect("the gather succeeds");
    let at_one = whisper::embed_positions(reader, &[50_258], 1).expect("the gather succeeds");
    let moved = (0..whisper::D_MODEL as usize)
        .filter(|&c| (at_zero[c] - at_one[c]).abs() > 1e-4)
        .count();
    assert!(moved > whisper::D_MODEL as usize / 2, "only {moved} channels move with the position");
    // And position 0 of the four-token gather must be exactly the single-token gather at 0.
    for channel in 0..whisper::D_MODEL as usize {
        let from_block = out[channel * prompt.len()];
        assert!(
            (from_block - at_zero[channel]).abs() < 1e-6,
            "channel {channel}: {from_block} against {}",
            at_zero[channel]
        );
    }

    // Past the 448-position table is refused rather than clamped: a transcript that ran over would
    // otherwise wrap onto position 0 and start repeating.
    let error = whisper::embed_positions(reader, &[50_258], whisper::MAX_POSITIONS)
        .expect_err("past the table");
    assert!(error.contains("past the model"), "{error}");
}

#[test]
fn neither_asset_will_load_as_the_other_network() {
    // The `graph_id` in the header exists for exactly this: the two files are both
    // ordered fp16 tensor tables, so without it a swapped asset would parse.
    let selfie = repo_root().join("camera/src/main/assets/selfie_segmentation.maml");
    let bytes = std::fs::read(selfie).expect("the selfie asset");
    assert!(Weights::parse(&bytes, graph::U2NETP).is_err());
}

#[test]
fn report_the_device_memory_each_net_needs() {
    // Not an assertion so much as the number a reviewer wants to see, printed by
    // `cargo test -- --nocapture`. Both are well inside what a phone will give a
    // background allocation, which is the point.
    for (name, weights, arena) in [
        (
            "selfie",
            load("camera/src/main/assets/selfie_segmentation.maml", graph::SELFIE),
            selfie::build(&load(
                "camera/src/main/assets/selfie_segmentation.maml",
                graph::SELFIE,
            ))
            .expect("selfie")
            .arena_elems,
        ),
        (
            "u2netp",
            load("photos/src/main/assets/u2netp.maml", graph::U2NETP),
            u2netp::build(&load("photos/src/main/assets/u2netp.maml", graph::U2NETP))
                .expect("u2netp")
                .arena_elems,
        ),
        (
            "mobilefacenet",
            load("photos/src/main/assets/w600k_mbf.maml", graph::MOBILEFACENET),
            mobilefacenet::build(&load(
                "photos/src/main/assets/w600k_mbf.maml",
                graph::MOBILEFACENET,
            ))
            .expect("mobilefacenet")
            .arena_elems,
        ),
        (
            "scrfd@640",
            load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD),
            scrfd::build(
                &load("photos/src/main/assets/scrfd_500m.maml", graph::SCRFD),
                scrfd::LONG_SIDE,
                scrfd::LONG_SIDE,
            )
            .expect("scrfd")
            .arena_elems,
        ),
    ] {
        let weight_bytes = weights.data().len();
        let arena_bytes = arena as usize * 2;
        println!(
            "{name}: {} KiB weights + {} KiB arena = {} KiB",
            weight_bytes / 1024,
            arena_bytes / 1024,
            (weight_bytes + arena_bytes) / 1024,
        );
        assert!(weight_bytes + arena_bytes < 128 * 1024 * 1024);
    }
}

/// One of Supertonic's four `.maml`, or `None` when the bundle has not been built.
///
/// Unlike every other asset here, Supertonic's is **not committed**: the four nets come to 189 MiB
/// at fp16, which is more than belongs in git history for something a pinned script can rebuild
/// byte for byte. So a fresh checkout does not have it, and this returns `None` rather than
/// panicking — the test below then reports that it was skipped instead of failing for the one
/// reason that is not a bug.
///
/// A file that is *present but wrong* is still a hard failure. That is the case worth catching:
/// a bundle rebuilt from a moved upstream, or converted by a `supertonic_fold.py` whose layer
/// ordering has drifted from the Rust.
fn supertonic(name: &str, graph_id: u32) -> Option<Weights> {
    let path = repo_root().join("speech/src/main/assets/supertonic").join(name);
    let bytes = std::fs::read(&path).ok()?;
    Some(
        Weights::parse(&bytes, graph_id)
            .unwrap_or_else(|e| panic!("{} is not a usable .maml: {e}", path.display())),
    )
}

#[test]
fn the_bundled_supertonic_assets_build_all_four_forward_passes() {
    // The four hardcoded passes against the four converted files, which is the strongest automatic
    // check that the bundle `:speech` ships is the one the Rust was written for: `Builder::finish`
    // refuses a plan that does not read every tensor in its file, and each `build` walks its
    // tensors through `Weights::shaped`, so a reordered or re-shaped layer fails here naming the
    // tensor rather than producing silent nonsense on a phone.
    //
    // Widths are arbitrary but non-trivial: every Supertonic plan is utterance-shaped, so a build
    // at one length exercises the shape arithmetic that `Net::rebuild` repeats per sentence.
    let (frames, chars) = (32u32, 16u32);

    let Some(duration) = supertonic("supertonic_dp.maml", graph::SUPERTONIC_DP) else {
        println!(
            "skipped: no bundle in speech/src/main/assets/supertonic. \
             Run python scripts/ml/fetch_supertonic.py to build it."
        );
        return;
    };
    assert_eq!(duration.len(), supertonic_duration::TENSORS);
    assert!(!supertonic_duration::build(&duration, chars).expect("the duration pass").ops.is_empty());

    let text = supertonic("supertonic_ttl.maml", graph::SUPERTONIC_TTL).expect("the text encoder");
    assert_eq!(text.len(), supertonic_text::TENSORS);
    assert!(!supertonic_text::build(&text, chars).expect("the text pass").ops.is_empty());

    let sampler = supertonic("supertonic_ve.maml", graph::SUPERTONIC_VE).expect("the sampler");
    assert_eq!(sampler.len(), supertonic_sampler::TENSORS);
    assert!(
        !supertonic_sampler::build(&sampler, frames, chars).expect("the sampler pass").ops.is_empty()
    );

    let vocoder = supertonic("supertonic_voc.maml", graph::SUPERTONIC_VOC).expect("the vocoder");
    assert_eq!(vocoder.len(), supertonic_vocoder::TENSORS);
    assert!(!supertonic_vocoder::build(&vocoder, frames).expect("the vocoder pass").ops.is_empty());

    let total: usize = [&duration, &text, &sampler, &vocoder].iter().map(|w| w.data().len()).sum();
    println!("supertonic: {} MiB of weights across four nets", total / (1 << 20));
}

/// The six files `SupertonicBundle.isPresent` requires before `:speech` will advertise a voice.
///
/// Kotlin's check is a directory listing, so it cannot notice a file that is the right name and the
/// wrong size. This is the counterpart that does: the codepoint table is a fixed 65,536 `int16` and
/// each voice style a fixed 12,928 fp16, both fixed by the architecture rather than by the export,
/// so a length check here is as strong as a digest would be.
#[test]
fn the_bundled_supertonic_inputs_are_the_sizes_the_runtime_assumes() {
    let dir = repo_root().join("speech/src/main/assets/supertonic");
    let Ok(indexer) = std::fs::read(dir.join("unicode_indexer.bin")) else {
        println!("skipped: no bundle. Run python scripts/ml/fetch_supertonic.py to build it.");
        return;
    };
    // `post::supertonic::INDEXER_ENTRIES` int16, one per BMP codepoint.
    assert_eq!(indexer.len(), 65_536 * 2, "the codepoint table");

    // `style_ttl` transposed to [256, 50] then `style_dp` flattened to 128, as fp16.
    let style_elems = 256 * 50 + 128;
    for voice in ["F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"] {
        let name = format!("style_{voice}.bin");
        let bytes = std::fs::read(dir.join(&name)).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(bytes.len(), style_elems * 2, "{name}");
    }
}
