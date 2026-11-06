//! SCRFD's post-processing: anchor decode, non-maximum suppression, and undoing the
//! letterbox.
//!
//! # Why this is host code and not a shader
//!
//! The plan stops at [`crate::nets::scrfd`]'s nine convolution outputs. What follows is
//! a threshold, a sort and a quadratic pairwise comparison over however many proposals
//! survived — data-dependent control flow over a handful of candidates, which is the
//! shape of work a GPU is worst at and a CPU finishes in microseconds.
//!
//! # Ported from `scrfd.cpp`, including its quirks
//!
//! Every constant and every rounding decision below is `scrfd.cpp:144-224` and
//! `:389-443`. Three are worth calling out because they look like mistakes:
//!
//! * **Anchor centres are `j * stride`, with no half-cell offset.** The reference builds
//!   a table of anchor boxes and then uses only its row *count*: `distance2bbox` needs
//!   the centre, and the centre is `anchor[0] + j * stride + anchor_w / 2`, in which the
//!   `anchor_w / 2` cancels the `-anchor_w / 2` that `anchor[0]` is. So the box geometry
//!   is independent of the anchor sizes, and this port does not compute them at all.
//!   ([`ANCHORS`] is still 2, because the score map has two channels.)
//! * **Box extents carry an inclusive `+1`.** The reference sets
//!   `width = x1 - x0 + 1`, so a box is a pixel wider and taller than
//!   `distance2bbox` produced. That inflation is then *kept* through NMS and through the
//!   undo, so it changes which detections survive. It is preserved here.
//! * **The letterbox offset is subtracted as an integer.** `wpad / 2` is integer
//!   division in C++, and using the true half of an odd pad would shift every
//!   coordinate by half a pixel.
//!
//! # One deliberate difference
//!
//! The reference sorts with a quicksort, which is unstable, so proposals with equal
//! scores come out in an arbitrary order and NMS then keeps an arbitrary one of them.
//! [`suppress`] sorts stably, so equal scores keep the order they were decoded in. That
//! makes this port *more* deterministic than the thing it replaces, and it is the reason
//! a bit-identical comparison against ncnn can differ on a tie.

use crate::nets::Shape;
use crate::preprocess::Letterbox;

/// Anchors per cell, at every stride. See the note above on why this is all that is
/// needed of the anchor table.
pub const ANCHORS: usize = 2;
/// Keypoints SCRFD predicts: both eyes, the nose, both mouth corners.
pub const KEYPOINTS: usize = 5;

/// Score at or above which a proposal is kept. `scrfd.cpp`'s caller default.
pub const SCORE_THRESHOLD: f32 = 0.5;
/// IoU **strictly above** which an overlapping proposal is dropped.
pub const IOU_THRESHOLD: f32 = 0.45;

/// One detected face.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Face {
    /// The score map's value, already through a sigmoid in the graph.
    pub score: f32,
    /// `[x0, y0, x1, y1]`, a half-open box: `x1` is one past the last column.
    ///
    /// The `+1` that makes it half-open is the reference's, applied at decode and never
    /// removed — see the module docs.
    pub bounds: [f32; 4],
    /// `(x, y)` per keypoint, in the same space as [`Face::bounds`].
    pub keypoints: [(f32, f32); KEYPOINTS],
}

impl Face {
    pub(crate) fn area(&self) -> f32 {
        let [x0, y0, x1, y1] = self.bounds;
        (x1 - x0).max(0.0) * (y1 - y0).max(0.0)
    }

    pub(crate) fn intersection(&self, other: &Face) -> f32 {
        let [ax0, ay0, ax1, ay1] = self.bounds;
        let [bx0, by0, bx1, by1] = other.bounds;
        let width = ax1.min(bx1) - ax0.max(bx0);
        let height = ay1.min(by1) - ay0.max(by0);
        if width <= 0.0 || height <= 0.0 {
            return 0.0;
        }
        width * height
    }
}

/// One stride's three maps, as the plan hands them back.
pub struct Maps<'a> {
    /// `[anchors, h, w]`, post-sigmoid.
    pub score: &'a [f32],
    /// `[anchors * 4, h, w]`: left, top, right, bottom distances in stride units.
    pub bbox: &'a [f32],
    /// `[anchors * 10, h, w]`: five `(x, y)` offsets in stride units.
    pub keypoints: &'a [f32],
    /// The score map's shape, which the other two share spatially.
    pub shape: Shape,
}

/// Decode one stride's maps into proposals, appending to `out`.
///
/// Iterates anchor, then row, then column — the reference's order, which is what fixes
/// the order of equal-scoring proposals under the stable sort in [`suppress`].
pub fn decode(
    maps: &Maps,
    stride: u32,
    threshold: f32,
    out: &mut Vec<Face>,
) -> Result<(), String> {
    let (h, w) = (maps.shape.h, maps.shape.w);
    let cells = (h * w) as usize;
    if maps.shape.c as usize != ANCHORS {
        return Err(format!("a score map with {} channels, not {ANCHORS}", maps.shape.c));
    }
    for (name, map, channels) in [
        ("score", maps.score, ANCHORS),
        ("box", maps.bbox, ANCHORS * 4),
        ("keypoint", maps.keypoints, ANCHORS * KEYPOINTS * 2),
    ] {
        if map.len() != cells * channels {
            return Err(format!(
                "a {name} map of {} values, not {channels} x {h} x {w}",
                map.len()
            ));
        }
    }

    let at = |map: &[f32], channel: usize, index: usize| -> Result<f32, String> {
        map.get(channel * cells + index)
            .copied()
            .ok_or_else(|| format!("channel {channel} element {index} is out of range"))
    };

    for anchor in 0..ANCHORS {
        for row in 0..h {
            for column in 0..w {
                let index = (row * w + column) as usize;
                let score = at(maps.score, anchor, index)?;
                if score < threshold {
                    continue;
                }
                // The centre, with no half-cell offset. See the module docs.
                let cx = (column * stride) as f32;
                let cy = (row * stride) as f32;
                let distance = |k: usize| -> Result<f32, String> {
                    Ok(at(maps.bbox, anchor * 4 + k, index)? * stride as f32)
                };
                let (left, top) = (distance(0)?, distance(1)?);
                let (right, bottom) = (distance(2)?, distance(3)?);
                let (x0, y0) = (cx - left, cy - top);
                let (x1, y1) = (cx + right, cy + bottom);

                let mut keypoints = [(0.0, 0.0); KEYPOINTS];
                for (k, point) in keypoints.iter_mut().enumerate() {
                    let base = anchor * KEYPOINTS * 2 + k * 2;
                    *point = (
                        cx + at(maps.keypoints, base, index)? * stride as f32,
                        cy + at(maps.keypoints, base + 1, index)? * stride as f32,
                    );
                }

                out.push(Face {
                    score,
                    // `+ 1` on each extent: the reference's inclusive width and height.
                    bounds: [x0, y0, x1 + 1.0, y1 + 1.0],
                    keypoints,
                });
            }
        }
    }
    Ok(())
}

pub use super::nms_extra::{flatten, suppress, FACE_FLOATS};

/// Map coordinates out of the letterboxed space and back onto the source image.
///
/// Undoes the padding and the scale, clamps to `[0, extent - 1]`, and then normalises to
/// `0..1` of the source — which is the form `scrfd_jni.cpp:77-84` returned and therefore
/// what the Kotlin already expects.
pub fn to_source(faces: &mut [Face], fit: &Letterbox, width: u32, height: u32) {
    let (left, top) = (fit.offset.0 as f32, fit.offset.1 as f32);
    let (limit_x, limit_y) = ((width.max(1) - 1) as f32, (height.max(1) - 1) as f32);
    let scale = if fit.scale == 0.0 { 1.0 } else { fit.scale };
    let undo = |value: f32, pad: f32, limit: f32| ((value - pad) / scale).clamp(0.0, limit);

    for face in faces {
        let [x0, y0, x1, y1] = face.bounds;
        face.bounds = [
            undo(x0, left, limit_x) / width as f32,
            undo(y0, top, limit_y) / height as f32,
            undo(x1, left, limit_x) / width as f32,
            undo(y1, top, limit_y) / height as f32,
        ];
        for point in &mut face.keypoints {
            *point = (
                undo(point.0, left, limit_x) / width as f32,
                undo(point.1, top, limit_y) / height as f32,
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A `[channels, h, w]` map that is zero except for the values named.
    fn map(channels: usize, h: u32, w: u32, set: &[(usize, usize, f32)]) -> Vec<f32> {
        let mut values = vec![0.0; channels * (h * w) as usize];
        for &(channel, index, value) in set {
            if let Some(slot) = values.get_mut(channel * (h * w) as usize + index) {
                *slot = value;
            }
        }
        values
    }

    fn face(score: f32, bounds: [f32; 4]) -> Face {
        Face { score, bounds, keypoints: [(0.0, 0.0); KEYPOINTS] }
    }

    #[test]
    fn a_proposal_decodes_to_a_box_around_its_cell_centre() {
        // Anchor 0 at cell (1, 2) of a stride-8 map, all four distances 1.0 — so one
        // stride out in every direction from the centre at (16, 8).
        let shape = Shape::new(2, 4, 4);
        let (row, column) = (1usize, 2usize);
        let index = row * 4 + column;
        let score = map(2, 4, 4, &[(0, index, 0.9)]);
        let bbox = map(8, 4, 4, &[(0, index, 1.0), (1, index, 1.0), (2, index, 1.0), (3, index, 1.0)]);
        let keypoints = map(20, 4, 4, &[]);
        let mut out = Vec::new();
        decode(
            &Maps { score: &score, bbox: &bbox, keypoints: &keypoints, shape },
            8,
            0.5,
            &mut out,
        )
        .expect("decodes");

        assert_eq!(out.len(), 1);
        let got = out.first().copied().expect("one face");
        assert_eq!(got.score, 0.9);
        // Centre (16, 8), 8 out each way, then the inclusive `+1` on the far edges.
        assert_eq!(got.bounds, [8.0, 0.0, 25.0, 17.0]);
    }

    #[test]
    fn the_second_anchor_reads_its_own_slice_of_every_map() {
        // Anchor 1's box channels are 4..8 and its keypoints 10..20. Reading anchor 0's
        // by mistake gives a box at the same place with the wrong size, which is the
        // kind of error that halves recall and never crashes.
        let shape = Shape::new(2, 2, 2);
        let score = map(2, 2, 2, &[(1, 0, 0.8)]);
        let bbox = map(8, 2, 2, &[(4, 0, 2.0), (5, 0, 2.0), (6, 0, 3.0), (7, 0, 3.0)]);
        let keypoints = map(20, 2, 2, &[(10, 0, 1.0), (11, 0, -1.0)]);
        let mut out = Vec::new();
        decode(
            &Maps { score: &score, bbox: &bbox, keypoints: &keypoints, shape },
            16,
            0.5,
            &mut out,
        )
        .expect("decodes");

        let got = out.first().copied().expect("one face");
        // Cell (0, 0), so the centre is the origin: 2 strides left/up, 3 right/down.
        assert_eq!(got.bounds, [-32.0, -32.0, 49.0, 49.0]);
        assert_eq!(got.keypoints.first().copied(), Some((16.0, -16.0)));
    }

    #[test]
    fn keypoints_decode_as_offsets_from_the_centre_in_stride_units() {
        // Five distinct offsets, so a transposed x/y or a keypoint read at the wrong
        // channel pair is visible. Cell (0, 1) at stride 8, centre (8, 0).
        let shape = Shape::new(2, 1, 2);
        let score = map(2, 1, 2, &[(0, 1, 1.0)]);
        let bbox = map(8, 1, 2, &[]);
        let offsets: Vec<(usize, usize, f32)> = (0..10)
            .map(|k| (k, 1usize, (k as f32 + 1.0) / 8.0))
            .collect();
        let keypoints = map(20, 1, 2, &offsets);
        let mut out = Vec::new();
        decode(
            &Maps { score: &score, bbox: &bbox, keypoints: &keypoints, shape },
            8,
            0.5,
            &mut out,
        )
        .expect("decodes");

        let got = out.first().copied().expect("one face");
        // x gets 8 + (2k + 1), y gets 0 + (2k + 2).
        assert_eq!(
            got.keypoints,
            [(9.0, 2.0), (11.0, 4.0), (13.0, 6.0), (15.0, 8.0), (17.0, 10.0)]
        );
    }

    #[test]
    fn a_score_exactly_at_the_threshold_is_kept() {
        // The reference tests `prob >= prob_threshold`. Using `>` would silently drop
        // the boundary case, which on a quantised score map is not rare.
        let shape = Shape::new(2, 1, 1);
        let score = map(2, 1, 1, &[(0, 0, 0.5)]);
        let bbox = map(8, 1, 1, &[]);
        let keypoints = map(20, 1, 1, &[]);
        let mut out = Vec::new();
        decode(
            &Maps { score: &score, bbox: &bbox, keypoints: &keypoints, shape },
            8,
            0.5,
            &mut out,
        )
        .expect("decodes");
        assert_eq!(out.len(), 1);
    }

    #[test]
    fn suppression_keeps_the_best_of_an_overlapping_pair() {
        let mut faces = vec![
            face(0.6, [0.0, 0.0, 10.0, 10.0]),
            face(0.9, [1.0, 1.0, 11.0, 11.0]),
        ];
        suppress(&mut faces, 0.45);
        assert_eq!(faces.len(), 1);
        assert_eq!(faces.first().map(|f| f.score), Some(0.9));
    }

    #[test]
    fn suppression_keeps_two_faces_that_barely_overlap() {
        // 100-unit boxes sharing a 10x100 strip: IoU is 1000 / 19000, well under 0.45.
        let mut faces = vec![
            face(0.9, [0.0, 0.0, 100.0, 100.0]),
            face(0.8, [90.0, 0.0, 190.0, 100.0]),
        ];
        suppress(&mut faces, 0.45);
        assert_eq!(faces.len(), 2);
    }

    #[test]
    fn suppression_is_strictly_greater_than_the_threshold() {
        // Two boxes whose IoU is exactly 1/3. At a threshold of 1/3 the reference's
        // `>` keeps both; a `>=` would drop one.
        let mut faces =
            vec![face(0.9, [0.0, 0.0, 2.0, 1.0]), face(0.8, [1.0, 0.0, 3.0, 1.0])];
        let exactly = 1.0 / 3.0;
        suppress(&mut faces, exactly);
        assert_eq!(faces.len(), 2, "an IoU equal to the threshold must not suppress");
        suppress(&mut faces, exactly - 0.01);
        assert_eq!(faces.len(), 1);
    }

    #[test]
    fn suppression_compares_against_everything_already_kept() {
        // Three boxes in a row where the middle one overlaps both ends but the ends do
        // not overlap each other. A greedy pass that only compared with the immediately
        // preceding proposal would keep the middle one.
        let mut faces = vec![
            face(0.9, [0.0, 0.0, 10.0, 10.0]),
            face(0.7, [2.0, 0.0, 12.0, 10.0]),
            face(0.8, [40.0, 0.0, 50.0, 10.0]),
        ];
        suppress(&mut faces, 0.45);
        let scores: Vec<f32> = faces.iter().map(|f| f.score).collect();
        assert_eq!(scores, vec![0.9, 0.8]);
    }

    #[test]
    fn equal_scores_keep_their_decode_order() {
        // The one deliberate divergence from `scrfd.cpp`, whose quicksort leaves this
        // arbitrary. Pinned so a future change to the sort is a visible decision.
        let mut faces = vec![
            face(0.7, [0.0, 0.0, 1.0, 1.0]),
            face(0.7, [100.0, 0.0, 101.0, 1.0]),
            face(0.7, [200.0, 0.0, 201.0, 1.0]),
        ];
        suppress(&mut faces, 0.45);
        let lefts: Vec<f32> = faces.iter().map(|f| f.bounds[0]).collect();
        assert_eq!(lefts, vec![0.0, 100.0, 200.0]);
    }

    #[test]
    fn undoing_the_letterbox_recovers_the_original_fraction() {
        // A 1280x720 photo: scale 0.5, resized 640x360, padded to 640x384, so 12 rows of
        // padding above. A box filling the image maps back to the whole frame.
        let fit = Letterbox::new(1280, 720, 640, 32).expect("fits");
        assert_eq!(fit.resized, (640, 360));
        assert_eq!(fit.padded, (640, 384));
        assert_eq!(fit.offset, (0, 12));

        let mut faces = vec![face(0.9, [0.0, 12.0, 640.0, 372.0])];
        to_source(&mut faces, &fit, 1280, 720);
        let got = faces.first().copied().expect("one face");
        // Clamped to the last pixel, hence 1279/1280 rather than exactly 1.
        assert!((got.bounds[0] - 0.0).abs() < 1e-6, "{:?}", got.bounds);
        assert!((got.bounds[1] - 0.0).abs() < 1e-6, "{:?}", got.bounds);
        assert!((got.bounds[2] - 1279.0 / 1280.0).abs() < 1e-6, "{:?}", got.bounds);
        assert!((got.bounds[3] - 719.0 / 720.0).abs() < 1e-6, "{:?}", got.bounds);
    }

    #[test]
    fn a_coordinate_outside_the_image_clamps_rather_than_going_negative() {
        // Decode routinely produces boxes past the edge — the distances are unbounded —
        // and a negative normalised coordinate would index outside the bitmap when the
        // Kotlin crops.
        let fit = Letterbox::new(640, 640, 640, 32).expect("fits");
        let mut faces = vec![face(0.9, [-500.0, -500.0, 5000.0, 5000.0])];
        to_source(&mut faces, &fit, 640, 640);
        let got = faces.first().copied().expect("one face");
        for value in got.bounds {
            assert!((0.0..=1.0).contains(&value), "{:?}", got.bounds);
        }
    }

    #[test]
    fn a_portrait_photo_pads_on_the_left_and_right() {
        // 720x1280: the long side is the height, so the width scales to 360 and pads to
        // 384 — 12 columns on the left. The transposed case of the test above, which is
        // where a swapped width/height shows up.
        let fit = Letterbox::new(720, 1280, 640, 32).expect("fits");
        assert_eq!(fit.resized, (360, 640));
        assert_eq!(fit.padded, (384, 640));
        assert_eq!(fit.offset, (12, 0));
    }

    #[test]
    fn a_square_photo_needs_no_padding_at_all() {
        let fit = Letterbox::new(1000, 1000, 640, 32).expect("fits");
        assert_eq!(fit.resized, (640, 640));
        assert_eq!(fit.padded, (640, 640));
        assert_eq!(fit.offset, (0, 0));
    }

    #[test]
    fn the_short_side_truncates_rather_than_rounds() {
        // 1000x667 at scale 0.64 gives 426.88. The reference truncates to 426, which
        // pads to 448; rounding to 427 would also pad to 448 but shift the image one
        // row, and there are aspect ratios where it changes the padded size outright.
        let fit = Letterbox::new(1000, 667, 640, 32).expect("fits");
        assert_eq!(fit.resized.1, 426);
        assert_eq!(fit.padded.1, 448);
        // 22 rows of padding, 11 above: an even split here, but the odd case below is
        // the one that pins the integer division.
        assert_eq!(fit.offset.1, 11);
    }

    #[test]
    fn an_odd_pad_puts_the_extra_row_at_the_bottom() {
        // Any short side that pads by an odd number: `pad / 2` above and the rest below.
        let fit = Letterbox::new(640, 27, 640, 32).expect("fits");
        assert_eq!(fit.resized, (640, 27));
        assert_eq!(fit.padded, (640, 32));
        // 5 rows of padding: 2 above, 3 below.
        assert_eq!(fit.offset.1, 2);
    }

    #[test]
    fn every_letterbox_extent_is_a_multiple_of_the_requested_size() {
        // The net refuses anything else, so this is the property that keeps the two in
        // agreement across every aspect ratio a camera produces.
        for (width, height) in [
            (4032, 3024),
            (3024, 4032),
            (1920, 1080),
            (1080, 1920),
            (640, 480),
            (1, 4000),
            (4000, 1),
            (1, 1),
        ] {
            let fit = Letterbox::new(width, height, 640, 32).expect("fits");
            assert!(fit.padded.0.is_multiple_of(32), "{width}x{height}: {:?}", fit.padded);
            assert!(fit.padded.1.is_multiple_of(32), "{width}x{height}: {:?}", fit.padded);
            assert!(fit.padded.0 >= fit.resized.0 && fit.padded.1 >= fit.resized.1);
        }
    }

    #[test]
    fn a_zero_sized_image_is_refused() {
        assert!(Letterbox::new(0, 100, 640, 32).is_err());
        assert!(Letterbox::new(100, 0, 640, 32).is_err());
    }

    #[test]
    fn a_map_of_the_wrong_length_is_refused_rather_than_read_short() {
        let shape = Shape::new(2, 2, 2);
        let score = map(2, 2, 2, &[]);
        let bbox = map(4, 2, 2, &[]);
        let keypoints = map(20, 2, 2, &[]);
        let mut out = Vec::new();
        let error = decode(
            &Maps { score: &score, bbox: &bbox, keypoints: &keypoints, shape },
            8,
            0.5,
            &mut out,
        )
        .expect_err("a short box map");
        assert!(error.contains("box map"), "{error}");
    }
}
