use super::FLOATS_PER_VERTEX;

/// The unit normal

/// The unit normal of the triangle `a, b, c`, or straight up for a degenerate (zero-area)
/// triangle so a sliver never emits a NaN normal.
pub(crate) fn face_normal(a: [f32; 3], b: [f32; 3], c: [f32; 3]) -> [f32; 3] {
    let u = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
    let v = [c[0] - a[0], c[1] - a[1], c[2] - a[2]];
    let n = [
        u[1] * v[2] - u[2] * v[1],
        u[2] * v[0] - u[0] * v[2],
        u[0] * v[1] - u[1] * v[0],
    ];
    let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
    if len <= f32::EPSILON {
        return [0.0, 0.0, 1.0];
    }
    [n[0] / len, n[1] / len, n[2] / len]
}

/// Orient a roof face normal to face outward: up for a sloped face, away from the roof centre for
/// a near-vertical one (a gable or hip end), so shading never lights a roof from inside.
pub(crate) fn orient(mut normal: [f32; 3], face: [f32; 3], centre: [f32; 3]) -> [f32; 3] {
    if normal[2].abs() > 0.1 {
        if normal[2] < 0.0 {
            normal = [-normal[0], -normal[1], -normal[2]];
        }
    } else {
        let d = normal[0] * (face[0] - centre[0])
            + normal[1] * (face[1] - centre[1])
            + normal[2] * (face[2] - centre[2]);
        if d < 0.0 {
            normal = [-normal[0], -normal[1], -normal[2]];
        }
    }
    normal
}

/// Append one triangle: three vertices sharing `normal` and `rgba`, three fresh indices. Vertices
/// are not shared between triangles, which is what makes the shading flat per face.
pub(crate) fn push_tri(
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    normal: [f32; 3],
    rgba: u32,
) {
    let base = (out_v.len() / FLOATS_PER_VERTEX) as u32;
    for pt in [a, b, c] {
        out_v.extend_from_slice(&[
            pt[0],
            pt[1],
            pt[2],
            normal[0],
            normal[1],
            normal[2],
            f32::from_bits(rgba),
        ]);
    }
    out_i.extend_from_slice(&[base, base + 1, base + 2]);
}
