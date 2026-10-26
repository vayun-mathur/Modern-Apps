//! Detection, cropping, recognition and reading order — the whole OCR request.
//!
//! # Why this is not in `bridge.rs`
//!
//! Everything between the two networks is arithmetic on tens of candidates: threshold the
//! probability map, take each region's oriented rectangle, warp a strip out of the source,
//! collapse the logits, sort the results. None of it touches Vulkan, and all of it is the
//! part that used to live inside the ncnn fork's `ppocrv5.cpp` where it could not be read
//! or tested.
//!
//! So recognition arrives as a **closure**. [`lines`] takes a probability map and a way to
//! turn a crop into logits, which means the sequencing is exercised on the host against a
//! stub recogniser, and `bridge.rs` supplies the real one. The alternative — a function
//! that owns two `Net`s — would be untestable anywhere but a device.
//!
//! # The two fixed shapes
//!
//! Detection runs at a fixed 960x960 square and recognition at a fixed 48x[`REC_WIDTH`], so
//! one compiled plan serves every request. `vulkan::run::Net` records its command buffer
//! once at construction, so a per-image shape would mean compiling a plan and re-recording
//! per request; PaddleOCR pads to a batch's shape for the same reason.
//!
//! The cost is padding. A tall thin photograph wastes detection work on the letterbox, and
//! a short word wastes recognition work on the right of its strip — which is why
//! [`super::ctc::decode`] is told how many timesteps are real.

use super::crop;
use super::ctc::{self, Decoded, Dictionary};
use super::dbnet;
use crate::nets::ppocr_rec;
use crate::preprocess::Letterbox;

/// The width every recognition crop is padded to. 320 is the export's own
/// `image_shape [3, 48, 320]`, so a full-width line is neither squeezed nor stretched.
pub const REC_WIDTH: u32 = 320;

/// What the padding around a crop is filled with, as an 8-bit level.
///
/// PaddleOCR's `resize_norm_img` pads with **zero after normalisation**, and recognition's
/// normalisation is `(v / 255 - 0.5) / 0.5`, so its zero is the raw level 127.5. 128 is the
/// nearest byte — a 0.4% offset on the padded strip, against filling with black, which the
/// net would read as a stroke and turn into characters.
const PAD_LEVEL: u32 = 128;

/// One recognised line, in source-image pixels.
#[derive(Clone, Debug, PartialEq)]
pub struct Line {
    /// The text, already collapsed by [`ctc::decode`].
    pub text: String,
    /// Mean probability of the timesteps that produced it.
    pub confidence: f32,
    /// The region's four corners, `0 -> 1` along the text and `0 -> 3` across it.
    pub corners: [(f32, f32); 4],
    /// True when the run was read as vertical script.
    pub vertical: bool,
}

/// Detection's output: the probability map, its extent, and how the source was fitted into
/// it. Grouped like [`super::nms::Maps`], because the four travel together and mean nothing
/// apart.
pub struct Detection<'a> {
    /// The net's single channel, `width` x `height`, row-major, in `0..1`.
    pub probability: &'a [f32],
    /// The map's width.
    pub width: u32,
    /// The map's height.
    pub height: u32,
    /// How the source was letterboxed in, so the regions come back in source pixels.
    pub fit: &'a Letterbox,
}

/// The image the crops are cut from, as `ARGB_8888`.
pub struct Source<'a> {
    /// `width * height` entries of `0xAARRGGBB`, row-major.
    pub pixels: &'a [i32],
    /// The image's width.
    pub width: u32,
    /// The image's height.
    pub height: u32,
}

/// Detect, crop, recognise and order every line in one image.
///
/// `recognise` is handed a crop as `(pixels, width, height)` and must return the logit map
/// [`ctc::decode`] wants — class-major `[838, T]`. A crop it fails on is skipped rather
/// than abandoning the image: one unreadable region should not lose the other twenty.
pub fn lines(
    detection: &Detection,
    source: &Source,
    dictionary: &Dictionary,
    mut recognise: impl FnMut(&[i32], u32, u32) -> Result<Vec<f32>, String>,
) -> Result<Vec<Line>, String> {
    lines_batched(detection, source, dictionary, |strips| {
        strips.iter().map(|strip| recognise(strip, REC_WIDTH, ppocr_rec::HEIGHT)).collect()
    })
}

/// [`lines`] with every strip handed over at once, `REC_WIDTH x HEIGHT` each, so the device can
/// recognise a request's lines in a few submits instead of one per line. `recognise` returns one
/// result per strip, in order; a failed strip is skipped like a failed crop.
pub fn lines_batched(
    detection: &Detection,
    source: &Source,
    dictionary: &Dictionary,
    recognise: impl FnOnce(&[Vec<i32>]) -> Vec<Result<Vec<f32>, String>>,
) -> Result<Vec<Line>, String> {
    let regions = dbnet::regions(
        detection.probability,
        detection.width,
        detection.height,
        detection.fit,
        (source.width, source.height),
    )?;
    let mut pending = Vec::with_capacity(regions.len());
    let mut strips = Vec::with_capacity(regions.len());
    for region in &regions {
        let corners = crop::corners(&region.rect);
        let content =
            crop::crop_width(region, ppocr_rec::HEIGHT, ppocr_rec::WIDTH_MULTIPLE, REC_WIDTH);
        if let Ok(s) = strip(source, &corners, content) {
            strips.push(s);
            pending.push((corners, content, region.vertical));
        }
    }
    let mut lines = Vec::with_capacity(pending.len());
    for ((corners, content, vertical), logits) in pending.into_iter().zip(recognise(&strips)) {
        let Ok(logits) = logits else { continue };
        // Only the timesteps the crop actually filled; the rest is padding.
        let used = (content / ppocr_rec::WIDTH_MULTIPLE) as usize;
        let Decoded { text, confidence } = match ctc::decode(&logits, used, dictionary) {
            Ok(d) => d,
            Err(_) => continue,
        };
        let text = text.trim().to_string();
        if text.is_empty() {
            continue;
        }
        lines.push(Line { text, confidence, corners, vertical });
    }
    order(&mut lines);
    Ok(lines)
}

/// Warp `corners` into the left `content` columns of a [`REC_WIDTH`]-wide strip.
///
/// The strip is the recogniser's whole input, so the columns past `content` are the padding
/// described on [`PAD_LEVEL`]. Warping straight into the padded buffer rather than warping
/// and then copying is one pass and no intermediate.
fn strip(
    source: &Source,
    corners: &[(f32, f32); 4],
    content: u32,
) -> Result<Vec<i32>, String> {
    let content = content.clamp(ppocr_rec::WIDTH_MULTIPLE, REC_WIDTH);
    let cropped = crop::warp(
        source.pixels,
        source.width,
        source.height,
        corners,
        content,
        ppocr_rec::HEIGHT,
    )?;
    if content == REC_WIDTH {
        return Ok(cropped);
    }
    let pad = ((0xffu32 << 24) | (PAD_LEVEL << 16) | (PAD_LEVEL << 8) | PAD_LEVEL) as i32;
    let mut out = vec![pad; (REC_WIDTH as usize) * (ppocr_rec::HEIGHT as usize)];
    for row in 0..ppocr_rec::HEIGHT as usize {
        let from = row * content as usize;
        let to = row * REC_WIDTH as usize;
        let source = cropped
            .get(from..from + content as usize)
            .ok_or("the crop is shorter than its own width")?;
        let destination = out
            .get_mut(to..to + content as usize)
            .ok_or("the strip is shorter than the crop")?;
        destination.copy_from_slice(source);
    }
    Ok(out)
}

/// How many pixels of vertical drift still counts as the same row.
///
/// The same band the ncnn path used, so a two-column page keeps its columns rather than
/// interleaving them line by line.
const ROW_BAND: f32 = 16.0;

/// Sort into reading order: down the page, then across each row band.
///
/// Banding on the quad's *centre* rather than its top edge is what keeps a tilted line's
/// regions together — a 3-degree tilt across a page moves the top edge further than the
/// band is wide, and the line comes back shuffled.
fn order(lines: &mut [Line]) {
    let centre = |line: &Line| {
        let sum = line.corners.iter().fold((0.0f32, 0.0f32), |a, p| (a.0 + p.0, a.1 + p.1));
        (sum.0 / 4.0, sum.1 / 4.0)
    };
    lines.sort_by(|a, b| {
        let (ax, ay) = centre(a);
        let (bx, by) = centre(b);
        let band = |y: f32| (y / ROW_BAND).floor() as i64;
        band(ay)
            .cmp(&band(by))
            .then(ax.total_cmp(&bx))
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::post::ctc::LOGITS;

    /// A dictionary of `a`, `b`, `c`, ... at the real length.
    fn dictionary() -> Dictionary {
        let mut text = String::new();
        for index in 0..ctc::DICTIONARY_ENTRIES {
            text.push((b'a' + (index % 26) as u8) as char);
            text.push('\n');
        }
        Dictionary::parse(&text).expect("the fixture dictionary parses")
    }

    /// A logit map whose timestep `t` picks label `labels[t]`, class-major over `steps`.
    fn logits(labels: &[usize], steps: usize) -> Vec<f32> {
        let mut map = vec![0.0f32; LOGITS * steps];
        for (step, &label) in labels.iter().enumerate() {
            if let Some(slot) = map.get_mut(label * steps + step) {
                *slot = 20.0;
            }
        }
        map
    }

    /// A probability map with a filled rectangle, which `dbnet` reads as one region.
    fn blob(map: u32, left: u32, top: u32, right: u32, bottom: u32) -> Vec<f32> {
        let mut values = vec![0.0f32; (map * map) as usize];
        for y in top..bottom {
            for x in left..right {
                if let Some(slot) = values.get_mut((y * map + x) as usize) {
                    *slot = 1.0;
                }
            }
        }
        values
    }

    #[test]
    fn a_detected_region_is_cropped_recognised_and_returned() {
        let map = 64u32;
        let probability = blob(map, 8, 24, 56, 36);
        let fit = Letterbox::square(map, map, map).expect("a 1:1 letterbox");
        let pixels = vec![0xffff_ffffu32 as i32; (map * map) as usize];
        let dictionary = dictionary();

        let mut widths = Vec::new();
        let got = lines(
            &Detection { probability: &probability, width: map, height: map, fit: &fit },
            &Source { pixels: &pixels, width: map, height: map },
            &dictionary,
            |crop, w, h| {
                widths.push((w, h, crop.len()));
                Ok(logits(&[1, 2, 3], (REC_WIDTH / ppocr_rec::WIDTH_MULTIPLE) as usize))
            },
        )
        .expect("the pipeline runs");

        assert_eq!(got.len(), 1, "{got:?}");
        assert_eq!(got[0].text, "abc");
        assert!(!got[0].vertical);
        // Recognition always sees the one fixed shape, whatever the region's own size.
        assert_eq!(
            widths,
            vec![(
                REC_WIDTH,
                ppocr_rec::HEIGHT,
                (REC_WIDTH * ppocr_rec::HEIGHT) as usize
            )]
        );
    }

    #[test]
    fn a_crop_shorter_than_the_strip_is_padded_and_only_its_own_timesteps_decoded() {
        // A nearly square region wants far less than 320 columns, so most of the strip is
        // padding. Decoding it would put a character on the end that no pixel supports.
        let map = 64u32;
        let probability = blob(map, 24, 24, 40, 40);
        let fit = Letterbox::square(map, map, map).expect("a 1:1 letterbox");
        let pixels = vec![0xff00_0000u32 as i32; (map * map) as usize];
        let dictionary = dictionary();

        let steps = (REC_WIDTH / ppocr_rec::WIDTH_MULTIPLE) as usize;
        // Every timestep would decode to a distinct letter, so the answer's length is
        // exactly the number of timesteps the pipeline chose to read.
        let labels: Vec<usize> = (0..steps).map(|i| 1 + i % 26).collect();
        let mut seen = None;
        let got = lines(
            &Detection { probability: &probability, width: map, height: map, fit: &fit },
            &Source { pixels: &pixels, width: map, height: map },
            &dictionary,
            |crop, w, _| {
                // The right of the strip is the pad level, not the black source.
                let last = crop.get((w - 1) as usize).copied().unwrap_or(0) as u32;
                seen = Some(last & 0xff);
                Ok(logits(&labels, steps))
            },
        )
        .expect("the pipeline runs");

        assert_eq!(seen, Some(PAD_LEVEL), "the strip was not padded");
        assert_eq!(got.len(), 1, "{got:?}");
        assert!(
            got[0].text.chars().count() < steps,
            "decoded all {steps} timesteps: {:?}",
            got[0].text
        );
        assert!(!got[0].text.is_empty());
    }

    #[test]
    fn a_region_the_recogniser_fails_on_is_skipped_rather_than_losing_the_image() {
        let map = 64u32;
        // Two well-separated runs, so `dbnet` finds two components.
        let mut probability = blob(map, 4, 10, 60, 20);
        for (i, v) in blob(map, 4, 44, 60, 54).iter().enumerate() {
            if *v > 0.0 {
                probability[i] = *v;
            }
        }
        let fit = Letterbox::square(map, map, map).expect("a 1:1 letterbox");
        let pixels = vec![0xffff_ffffu32 as i32; (map * map) as usize];
        let dictionary = dictionary();
        let steps = (REC_WIDTH / ppocr_rec::WIDTH_MULTIPLE) as usize;

        let mut call = 0;
        let got = lines(
            &Detection { probability: &probability, width: map, height: map, fit: &fit },
            &Source { pixels: &pixels, width: map, height: map },
            &dictionary,
            |_, _, _| {
                call += 1;
                if call == 1 {
                    Err("this crop failed".to_string())
                } else {
                    Ok(logits(&[2], steps))
                }
            },
        )
        .expect("the pipeline runs");
        assert_eq!(call, 2, "the second region was not attempted");
        assert_eq!(got.len(), 1, "{got:?}");
        assert_eq!(got[0].text, "b");
    }

    #[test]
    fn a_blank_recognition_contributes_no_line() {
        // A region whose logits are all blank is a false positive from detection, not an
        // empty string in the output.
        let map = 64u32;
        let probability = blob(map, 8, 24, 56, 36);
        let fit = Letterbox::square(map, map, map).expect("a 1:1 letterbox");
        let pixels = vec![0xffff_ffffu32 as i32; (map * map) as usize];
        let steps = (REC_WIDTH / ppocr_rec::WIDTH_MULTIPLE) as usize;
        let got = lines(
            &Detection { probability: &probability, width: map, height: map, fit: &fit },
            &Source { pixels: &pixels, width: map, height: map },
            &dictionary(),
            |_, _, _| Ok(logits(&[0], steps)),
        )
        .expect("the pipeline runs");
        assert!(got.is_empty(), "{got:?}");
    }

    #[test]
    fn reading_order_goes_down_the_page_then_across_each_band() {
        let line = |text: &str, cx: f32, cy: f32| Line {
            text: text.to_string(),
            confidence: 1.0,
            corners: [
                (cx - 5.0, cy - 2.0),
                (cx + 5.0, cy - 2.0),
                (cx + 5.0, cy + 2.0),
                (cx - 5.0, cy + 2.0),
            ],
            vertical: false,
        };
        // Deliberately out of order, and with two regions in one band.
        let mut got = vec![
            line("second-right", 100.0, 8.0),
            line("third", 10.0, 200.0),
            line("second-left", 20.0, 8.0),
            line("first", 10.0, 1.0),
        ];
        order(&mut got);
        let texts: Vec<&str> = got.iter().map(|l| l.text.as_str()).collect();
        // Bands are 16 tall, so y = 1 and y = 8 share one and sort by x within it.
        assert_eq!(texts, vec!["first", "second-left", "second-right", "third"]);
    }

    #[test]
    fn banding_uses_the_quad_centre_so_a_tilted_line_stays_together() {
        // Two halves of one line across a page, tilted enough that their top edges land in
        // different 16-pixel bands while their centres do not. Banding on the top edge
        // returns them in the wrong order relative to the line below.
        let tilted = |text: &str, x: f32, y: f32, drop: f32| Line {
            text: text.to_string(),
            confidence: 1.0,
            corners: [(x, y), (x + 200.0, y + drop), (x + 200.0, y + drop + 20.0), (x, y + 20.0)],
            vertical: false,
        };
        let mut got = vec![
            tilted("below", 0.0, 60.0, 0.0),
            tilted("right", 200.0, 14.0, 0.0),
            tilted("left", 0.0, 0.0, 14.0),
        ];
        order(&mut got);
        let texts: Vec<&str> = got.iter().map(|l| l.text.as_str()).collect();
        assert_eq!(texts, vec!["left", "right", "below"]);
    }
}
