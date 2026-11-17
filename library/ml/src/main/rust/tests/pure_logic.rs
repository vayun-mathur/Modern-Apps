//! Host-only pure logic for `modelrunner`: no device, no `.maml` asset, no network.
//!
//! `tests/assets.rs` proves the shipped files build the shipped passes. This file proves the
//! pieces underneath them behave before any asset is read: the fp16 math preprocessing stands
//! on, the letterbox geometry the detector shares with its post-processing, the tokenizer and
//! CTC edges that would otherwise decode to fluent nonsense, and the input/output shapes the
//! Kotlin uploads and reads back. Everything here runs on the host with `cargo test`.

use modelrunner::nets::{
    mobilefacenet, modnet, ppocr_det, ppocr_rec, scrfd, selfie, u2netp, Shape, WeightSource,
};
use modelrunner::post::ctc::{decode, Dictionary, DICTIONARY_ENTRIES, LOGITS};
use modelrunner::post::sentencepiece::Table;
use modelrunner::post::sentencepiece_flavours::{FAIRSEQ, GEMMA};
use modelrunner::preprocess::{
    f16_to_f32, f32_to_f16, to_planar_f16, Letterbox, RESCALE_ONLY,
};

/// A weight source that knows only how many tensors the pass wants.
///
/// Hands back the tensor index as the offset, which is meaningless as an address but makes the
/// plan reproducible. The net modules check every shape through `shaped`, so a transcription
/// that asks for the wrong dims still fails here — just against the stub rather than a file.
struct Stub {
    count: usize,
}

impl WeightSource for Stub {
    fn shaped(&self, index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(index as u32)
    }

    fn shaped_words(&self, index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(index as u32)
    }

    fn count(&self) -> usize {
        self.count
    }
}

/// The smallest table `Table::parse` accepts under the fairseq convention.
fn fairseq_fixture() -> Vec<u8> {
    let mut out = b"SPM1".to_vec();
    let pieces = ["<s>", "<pad>", "</s>", "<unk>"];
    out.extend((pieces.len() as u32).to_le_bytes());
    for (index, piece) in pieces.iter().enumerate() {
        out.extend((-(index as i32)).to_le_bytes());
        out.extend((piece.len() as u16).to_le_bytes());
        out.extend(piece.as_bytes());
    }
    out
}

/// A CTC dictionary of `a` repeated to the real length, so `decode` accepts it.
fn ctc_fixture() -> Dictionary {
    let mut text = String::new();
    for _ in 0..DICTIONARY_ENTRIES {
        text.push('a');
        text.push('\n');
    }
    Dictionary::parse(&text).expect("the fixture dictionary parses")
}

#[test]
fn half_precision_encodings_match_the_bit_layout_preprocessing_relies_on() {
    // Exact codes rather than round trips: the input path writes these bits and the mask path
    // reads them back, so a conversion that rounded correctly but placed the fields wrongly
    // would still pass a tolerance check and fail on device.
    assert_eq!(f32_to_f16(0.0), 0x0000);
    assert_eq!(f32_to_f16(-0.0), 0x8000);
    assert_eq!(f32_to_f16(1.0), 0x3c00);
    assert_eq!(f32_to_f16(-1.0), 0xbc00);
    assert_eq!(f32_to_f16(f32::INFINITY), 0x7c00);
    assert_eq!(f32_to_f16(f32::NEG_INFINITY), 0xfc00);
    // The border preprocessing pads with: raw zero through the SCRFD affine is about -0.996,
    // which must survive the trip rather than flush to zero.
    let border = (0.0 - 127.5) / 128.0;
    let back = f16_to_f32(f32_to_f16(border));
    assert!((back - border).abs() < 1e-3, "{back} vs {border}");
    // Overflow saturates and NaN stays a NaN; underflow keeps its sign.
    assert_eq!(f16_to_f32(f32_to_f16(1e30)), f32::INFINITY);
    assert!(f16_to_f32(f32_to_f16(f32::NAN)).is_nan());
    assert!(f16_to_f32(f32_to_f16(-1e-30)).is_sign_negative());
}

#[test]
fn letterbox_geometry_stays_on_multiples_and_centres_the_image() {
    // A square photo needs no padding and reports the input shape Kotlin uploads.
    let square = Letterbox::new(1000, 1000, 640, 32).expect("fits");
    assert_eq!(square.resized, (640, 640));
    assert_eq!(square.padded, (640, 640));
    assert_eq!(square.offset, (0, 0));
    assert_eq!(square.shape(), Shape::new(3, 640, 640));
    // A portrait photo pads left and right; the transposed case of the landscape test in
    // `post::nms`, which is where a swapped width and height shows up.
    let portrait = Letterbox::new(720, 1280, 640, 32).expect("fits");
    assert_eq!(portrait.resized, (360, 640));
    assert_eq!(portrait.padded, (384, 640));
    assert_eq!(portrait.offset, (12, 0));
    // An odd pad splits 2 above and 3 below under integer division, so the extra row lands
    // at the bottom rather than shifting every coordinate by half a pixel.
    let odd = Letterbox::new(640, 27, 640, 32).expect("fits");
    assert_eq!(odd.padded, (640, 32));
    assert_eq!(odd.offset.1, 2);
    // Truncation, not rounding: 1000x667 at scale 0.64 gives 426.88, which truncates to 426.
    let truncated = Letterbox::new(1000, 667, 640, 32).expect("fits");
    assert_eq!(truncated.resized.1, 426);
    // Every camera aspect ratio pads to multiples of 32, which is what the detector refuses
    // anything else for.
    for (width, height) in [(4032, 3024), (3024, 4032), (1920, 1080), (640, 480), (1, 1)] {
        let fit = Letterbox::new(width, height, 640, 32).expect("fits");
        assert!(fit.padded.0.is_multiple_of(32), "{width}x{height}: {fit:?}");
        assert!(fit.padded.1.is_multiple_of(32), "{width}x{height}: {fit:?}");
    }
    // Zero-sized inputs are refused, and a buffer of the wrong length is refused with the
    // element count rather than written short.
    assert!(Letterbox::new(0, 100, 640, 32).is_err());
    let pixels = vec![0i32; 4];
    let mut out = vec![0u16; 2];
    let error = to_planar_f16(&pixels, 2, 2, Shape::new(3, 2, 2), &RESCALE_ONLY, &mut out)
        .expect_err("wrong length");
    assert!(error.contains("output elements"), "{error}");
}

#[test]
fn ctc_decoding_refuses_malformed_inputs_before_touching_the_net() {
    let dictionary = ctc_fixture();
    assert_eq!(dictionary.labels(), LOGITS);
    assert_eq!(dictionary.label(0), None);
    assert_eq!(dictionary.label(1), Some("a"));
    assert_eq!(dictionary.label(LOGITS - 1), Some(" "));
    // A short table would shift every character above the gap, so it is refused at load.
    let error = Dictionary::parse("a\nb\nc\n").expect_err("too short");
    assert!(error.contains("3 dictionary entries"), "{error}");
    // An empty map means the recogniser produced nothing, which must not read as blank text.
    let error = decode(&[], 0, &dictionary).expect_err("an empty map");
    assert!(error.contains("no logits"), "{error}");
    // A ragged array is not a whole number of timesteps and is refused rather than read short.
    let error = decode(&vec![0.0; LOGITS + 3], 1, &dictionary).expect_err("ragged");
    assert!(error.contains("not a whole number"), "{error}");
    // Asking for more timesteps than the map holds is refused rather than read past the end.
    let mut two_steps = vec![0.0; LOGITS * 2];
    if let Some(slot) = two_steps.get_mut(2) {
        *slot = 10.0;
    }
    let error = decode(&two_steps, 3, &dictionary).expect_err("three of two");
    assert!(error.contains("3 of 2 timesteps"), "{error}");
    // One winning timestep decodes class-major: label 1 of step 0 sits at index `1 * steps`.
    let mut one_step = vec![0.0; LOGITS];
    if let Some(slot) = one_step.get_mut(1) {
        *slot = 10.0;
    }
    let got = decode(&one_step, 1, &dictionary).expect("decodes");
    assert_eq!(got.text, "a");
    assert!(got.confidence > 0.9, "{}", got.confidence);
}

#[test]
fn sentencepiece_tables_reject_malformed_bytes_before_decoding() {
    let bytes = fairseq_fixture();
    let table = Table::parse(&bytes).expect("the minimal table parses");
    assert_eq!(table.len(), 4);
    assert!(!table.has_byte_fallback());
    assert_eq!(table.piece(0), Some("<s>".as_bytes()));
    // Empty text encodes to nothing and decodes back to nothing; an id past the table is
    // skipped rather than replacing the whole string.
    assert!(table.encode("").is_empty());
    assert_eq!(table.decode(&[]), "");
    assert_eq!(table.decode(&[999]), "");
    // Not a table at all, a header cut short, and a table with bytes trailing it.
    // (Table carries no Debug so neither expect_err nor unwrap_err is
    // available; match the Err arm out explicitly.)
    let error = match Table::parse(&[]) {
        Err(e) => e,
        Ok(_) => panic!("empty input must not parse"),
    };
    assert!(error.contains("SPM1"), "{error}");
    let error = match Table::parse(b"BAD!") {
        Err(e) => e,
        Ok(_) => panic!("bad magic must not parse"),
    };
    assert!(error.contains("SPM1"), "{error}");
    let cut = bytes.get(..10).unwrap_or(&[]).to_vec();
    assert!(Table::parse(&cut).is_err());
    let mut trailing = bytes.clone();
    trailing.push(0);
    let error = match Table::parse(&trailing) {
        Err(e) => e,
        Ok(_) => panic!("trailing byte must not parse"),
    };
    assert!(error.contains("bytes left"), "{error}");
    // The same bytes under the wrong convention are refused: id 0 is `<s>`, not `<pad>`.
    let error = match Table::parse_with(&bytes, GEMMA) {
        Err(e) => e,
        Ok(_) => panic!("wrong flavour must not parse"),
    };
    assert!(error.contains("not <pad>"), "{error}");
    // The flavour travelled with the parse, so callers read back what they asked for.
    assert_eq!(table.specials(), FAIRSEQ);
}

#[test]
fn vision_passes_advertise_the_shapes_the_kotlin_uploads() {
    // Fixed-shape nets: the input is what preprocessing writes and the output is what the
    // host reads back, so both are pinned without running anything on a device.
    let selfie = selfie::build(&Stub { count: selfie::TENSORS }).expect("selfie builds");
    assert_eq!(selfie.input().expect("one input").shape, Shape::new(3, selfie::SIZE, selfie::SIZE));
    assert_eq!(selfie.output().expect("one output").shape, Shape::new(1, selfie::SIZE, selfie::SIZE));
    let u2netp = u2netp::build(&Stub { count: u2netp::TENSORS }).expect("u2netp builds");
    assert_eq!(u2netp.input().expect("one input").shape, Shape::new(3, u2netp::SIZE, u2netp::SIZE));
    assert_eq!(u2netp.output().expect("one output").shape, Shape::new(1, u2netp::SIZE, u2netp::SIZE));
    let embed = mobilefacenet::build(&Stub { count: mobilefacenet::TENSORS }).expect("embedder builds");
    assert_eq!(embed.input().expect("one input").shape, Shape::new(3, mobilefacenet::SIZE, mobilefacenet::SIZE));
    assert_eq!(embed.output().expect("one output").shape, Shape::new(mobilefacenet::EMBEDDING, 1, 1));
    let matte = modnet::build(&Stub { count: modnet::TENSORS }).expect("modnet builds");
    assert_eq!(matte.input().expect("one input").shape, Shape::new(3, modnet::SIZE, modnet::SIZE));
    assert_eq!(matte.output().expect("one output").shape, Shape::new(1, modnet::SIZE, modnet::SIZE));
    // Dynamically shaped nets lower at the shape they are given: SCRFD groups nine maps by
    // stride, detection returns a full-resolution probability map, and recognition emits one
    // logit row per eighth of the width.
    let detect = scrfd::build(&Stub { count: scrfd::TENSORS }, 640, 640).expect("scrfd builds");
    assert_eq!(detect.outputs.len(), 9);
    assert_eq!(detect.outputs[0].shape, Shape::new(2, 80, 80));
    assert_eq!(detect.outputs[8].shape, Shape::new(20, 20, 20));
    let text = ppocr_det::build(&Stub { count: ppocr_det::TENSORS }, 960, 960).expect("detector builds");
    assert_eq!(text.output().expect("one output").shape.h, ppocr_det::LONG_SIDE);
    let line = ppocr_rec::build(&Stub { count: ppocr_rec::TENSORS }, 320).expect("recogniser builds");
    assert_eq!(line.output().expect("one output").shape, Shape::new(ppocr_rec::LOGITS, 1, 40));
    // Shapes the builders refuse rather than lower badly: a non-multiple SCRFD extent that
    // would break the neck, and a recognition width that would drop a column per stride.
    let error = scrfd::build(&Stub { count: scrfd::TENSORS }, 100, 640).expect_err("ragged extent");
    assert!(error.contains("multiple of 32"), "{error}");
    let error = ppocr_rec::build(&Stub { count: ppocr_rec::TENSORS }, 100).expect_err("ragged width");
    assert!(error.contains("multiple of 8"), "{error}");
}
