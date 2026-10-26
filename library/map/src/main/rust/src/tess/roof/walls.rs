use super::geom::push_tri;

/// A wall quad per footprint edge, from `base` to `wall_top`, with an outward-facing normal.
///
/// Both heights are lifted by the tile-normalised ground offset sampled per corner, so each
/// wall spans the full structural height while its bottom edge sits on the slope — on flat
/// ground the two samples agree and the quad is the level rectangle it always was.
///
/// `skirt` hangs the bottom edge below the sampled ground: the closure samples the rendered
/// triangle plane exactly at the corners, but between corners the straight wall bottom and
/// the folded terrain surface part — up to half a cell's relief on a saddle — so the terrain
/// would poke through the wall, or daylight under it. Dropping the base by a few metres
/// buries the seam. Zero keeps the un-skirted mesh bit-for-bit.
#[allow(clippy::too_many_arguments)]
pub(crate) fn emit_walls(
    local: &[Vec<(f32, f32)>],
    base: f32,
    wall_top: f32,
    rgba: u32,
    skirt: f32,
    ground: &dyn Fn(f32, f32) -> f32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    if wall_top <= base {
        return; // A floating part flush with its base has no wall to draw.
    }
    for (ring_index, ring) in local.iter().enumerate() {
        let n = ring.len();
        // Which side is outward follows from the ring's winding, not from a centroid (which can
        // sit outside an L-shaped or courtyard footprint): a positive shoelace area puts the
        // ring's interior to the left of each edge, i.e. towards (-ey, ex). The exterior ring
        // (first) faces away from its interior; a hole's walls face into the courtyard.
        let twice_area: f32 = (0..n)
            .map(|i| {
                let (x0, y0) = ring[i];
                let (x1, y1) = ring[(i + 1) % n];
                x0 * y1 - x1 * y0
            })
            .sum();
        let outward_right = (ring_index == 0) == (twice_area > 0.0);
        for i in 0..n {
            let (x0, y0) = ring[i];
            let (x1, y1) = ring[(i + 1) % n];
            let (ex, ey) = (x1 - x0, y1 - y0);
            if ex == 0.0 && ey == 0.0 {
                continue;
            }
            // Cull is off in the pipeline, so this only decides shading, never visibility.
            let (nx, ny) = if outward_right { (ey, -ex) } else { (-ey, ex) };
            let inv = 1.0 / (nx * nx + ny * ny).sqrt();
            let normal = [nx * inv, ny * inv, 0.0];

            let base0 = [x0, y0, base + ground(x0, y0) - skirt];
            let base1 = [x1, y1, base + ground(x1, y1) - skirt];
            let top1 = [x1, y1, wall_top + ground(x1, y1)];
            let top0 = [x0, y0, wall_top + ground(x0, y0)];
            push_tri(out_v, out_i, base0, base1, top1, normal, rgba);
            push_tri(out_v, out_i, base0, top1, top0, normal, rgba);
        }
    }
}
