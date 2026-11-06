use super::nms::Face;

/// Sort by score and drop any proposal overlapping a better one by more than `threshold`.
///
/// Greedy and quadratic in the number of proposals, which after a 0.5 score threshold is
/// a handful even on a group photo.
pub fn suppress(faces: &mut Vec<Face>, threshold: f32) {
    // Stable, unlike the reference's quicksort — see the module docs. `total_cmp` rather
    // than `partial_cmp`: a NaN score would otherwise make the ordering inconsistent and
    // the sort's behaviour unspecified.
    faces.sort_by(|a, b| b.score.total_cmp(&a.score));

    let mut kept: Vec<Face> = Vec::new();
    for face in faces.iter() {
        let overlaps = kept.iter().any(|other| {
            let intersection = face.intersection(other);
            let union = face.area() + other.area() - intersection;
            // Strictly greater, and a zero union cannot suppress: two degenerate boxes
            // would otherwise divide by zero and compare as NaN, which is `false` here
            // but only by accident.
            union > 0.0 && intersection / union > threshold
        });
        if !overlaps {
            kept.push(*face);
        }
    }
    *faces = kept;
}

/// Floats per face in [`flatten`]'s output, which is the JNI wire format.
pub const FACE_FLOATS: usize = 15;

/// Pack faces for JNI: bounds, both eyes, score, then nose and the two mouth corners.
///
/// Keypoints keep SCRFD's order (left eye, right eye, nose, mouth left, mouth right); the
/// Kotlin side aligns on all five, so none are dropped.
pub fn flatten(faces: &[Face]) -> Vec<f32> {
    let mut flat = Vec::with_capacity(faces.len() * FACE_FLOATS);
    for face in faces {
        let [left_eye, right_eye, nose, mouth_left, mouth_right] = face.keypoints;
        flat.extend_from_slice(&face.bounds);
        flat.extend_from_slice(&[left_eye.0, left_eye.1, right_eye.0, right_eye.1, face.score]);
        flat.extend_from_slice(&[nose.0, nose.1, mouth_left.0, mouth_left.1, mouth_right.0, mouth_right.1]);
    }
    flat
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flatten_packs_bounds_eyes_score_then_nose_and_mouth() {
        let face = Face {
            score: 0.9,
            bounds: [0.1, 0.2, 0.3, 0.4],
            keypoints: [(1.0, 2.0), (3.0, 4.0), (5.0, 6.0), (7.0, 8.0), (9.0, 10.0)],
        };
        let flat = flatten(&[face, face]);
        assert_eq!(flat.len(), 2 * FACE_FLOATS);
        assert_eq!(
            &flat[..FACE_FLOATS],
            &[0.1, 0.2, 0.3, 0.4, 1.0, 2.0, 3.0, 4.0, 0.9, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
        );
    }
}
