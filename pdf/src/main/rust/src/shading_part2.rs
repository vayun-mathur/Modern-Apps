#[cfg(test)]
mod tests {
    use super::*;

    /// Collect the triangles a streaming parser emits, for tests that want to make
    /// assertions about the decoded mesh rather than the raster.
    fn collect(f: impl FnOnce(Emit)) -> Vec<(Vertex, Vertex, Vertex)> {
        let mut out = Vec::new();
        let mut sink = |a: &Vertex, b: &Vertex, c: &Vertex| {
            out.push((a.clone(), b.clone(), c.clone()));
        };
        f(&mut sink);
        out
    }

    /// Build a minimal Type 4 free-form Gouraud triangle: one triangle with
    /// three distinct RGB corner colors, packed at 8bpp coords/comps.
    fn type4_one_triangle() -> Vec<u8> {
        // Decode: x 0..1, y 0..1, r/g/b 0..1. 8 bits each. flag=8 bits.
        // Vertex layout: flag(1) x(1) y(1) r(1) g(1) b(1) = 6 bytes.
        let verts: [(u8, u8, u8, [u8; 3]); 3] = [
            (0, 0, 0, [255, 0, 0]),
            (0, 255, 0, [0, 255, 0]),
            (0, 0, 255, [0, 0, 255]),
        ];
        let mut out = Vec::new();
        for (flag, x, y, c) in verts {
            out.push(flag);
            out.push(x);
            out.push(y);
            out.extend_from_slice(&c);
        }
        out
    }

    #[test]
    fn type4_produces_varied_pixels() {
        let data = type4_one_triangle();
        let decode = [0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0];
        let tris = collect(|e| parse_type4(&data, 8, 8, 8, 3, &decode, e));
        assert_eq!(tris.len(), 1);
        let bounds = [0.0, 0.0, 1.0, 1.0];
        let (w, h) = (32usize, 32usize);
        let mut rgba = vec![0u8; w * h * 4];
        let (v0, v1, v2) = &tris[0];
        fill_tri(
            &mut rgba,
            w,
            h,
            &bounds,
            (v0, v1, v2),
            (0xFFFF0000, 0xFF00FF00, 0xFF0000FF),
        );
        // Expect a mix of colors, not a single flat value.
        let mut seen = std::collections::HashSet::new();
        for px in rgba.chunks(4) {
            if px[3] == 255 {
                seen.insert((px[0], px[1], px[2]));
            }
        }
        assert!(seen.len() > 3, "gouraud triangle should have varied colors");
    }

    #[test]
    fn bit_reader_reads_big_endian() {
        let mut br = BitReader::new(&[0b1010_0000, 0b1100_0000]);
        assert_eq!(br.read(3), Some(0b101));
        assert_eq!(br.read(5), Some(0b00000));
        assert_eq!(br.read(2), Some(0b11));
    }

    // ISO 32000-1 8.7.4.5.5: each Type 4 vertex occupies a whole number of bytes
    // and trailing padding bits are ignored. Here flag(8) + 2*coord(8) + 1*comp(4)
    // = 28 bits, so every vertex is padded to 32. Without the per-vertex align the
    // second and third vertices decode from the padding and yield garbage
    // coordinates, so this pins the alignment behaviour rather than just the count.
    #[test]
    fn type4_pads_each_vertex_to_a_byte_boundary() {
        // Per vertex: [flag, x, y, colour<<4 | padding].
        let data: Vec<u8> = vec![
            0, 0, 0, 0x00, // v0 (0,0)   colour 0/15
            0, 255, 0, 0xF0, // v1 (255,0) colour 15/15
            0, 0, 255, 0x80, // v2 (0,255) colour 8/15
        ];
        // x 0..255, y 0..255, colour 0..1 — coords map through as identity.
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type4(&data, 8, 8, 4, 1, &decode, e));
        assert_eq!(tris.len(), 1, "three flag-0 vertices form exactly one triangle");
        let (v0, v1, v2) = &tris[0];
        assert_eq!((v0.x, v0.y), (0.0, 0.0));
        assert_eq!((v1.x, v1.y), (255.0, 0.0));
        assert_eq!((v2.x, v2.y), (0.0, 255.0));
        assert!((v1.color[0] - 1.0).abs() < 1e-9, "v1 colour is the full 4-bit range");
    }

    // A Type 7 tensor patch whose interior control points are displaced must
    // produce a different surface than the equivalent Type 6 Coons patch (which
    // has no interior points). This guards against silently dropping p13..p16.
    fn tensor_patch_bytes(interior: [(u8, u8); 4]) -> Vec<u8> {
        // 12 boundary points forming a [0,100] square, then 4 interior points.
        let boundary: [(u8, u8); 12] = [
            (0, 0), (0, 33), (0, 66), (0, 100),
            (33, 100), (66, 100), (100, 100),
            (100, 66), (100, 33), (100, 0),
            (66, 0), (33, 0),
        ];
        let mut out = vec![0u8]; // flag = 0 (full patch), 8-bit
        for (x, y) in boundary { out.push(x); out.push(y); }
        for (x, y) in interior { out.push(x); out.push(y); }
        out.extend_from_slice(&[0, 0, 0, 0]); // 4 single-component colors
        out
    }

    #[test]
    fn tensor_interior_changes_surface() {
        // Coord decode maps 0..255 -> 0..255 (identity); one color component.
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        // Flat/interpolated interior vs interior pulled hard to the corners.
        let flat = tensor_patch_bytes([(33, 33), (33, 66), (66, 66), (66, 33)]);
        let bulged = tensor_patch_bytes([(0, 0), (0, 100), (100, 100), (100, 0)]);
        let a = collect(|e| parse_type6_7(&flat, 7, (8, 8, 8), 1, &decode, e));
        let b = collect(|e| parse_type6_7(&bulged, 7, (8, 8, 8), 1, &decode, e));
        assert!(!a.is_empty() && !b.is_empty(), "both patches should tessellate");
        let verts = |tris: &[(Vertex, Vertex, Vertex)]| -> Vec<(i64, i64)> {
            tris.iter().flat_map(|(p, q, r)| {
                [p, q, r].map(|v| ((v.x * 100.0) as i64, (v.y * 100.0) as i64))
            }).collect()
        };
        assert_ne!(verts(&a), verts(&b), "displaced interior must alter geometry");
    }

    // ISO 32000-1 8.7.4.5.5, Table 80: an edge flag of 1 continues the previous
    // triangle with (vb, vc, new) and a flag of 2 with (va, vc, new). Swapping them
    // (or reusing the wrong pair) still produces the right NUMBER of triangles, so a
    // count assertion cannot see it — the mesh just folds back on itself.
    #[test]
    fn type4_edge_flags_pick_the_right_previous_vertices() {
        // Per vertex: flag(8) x(8) y(8) colour(8). Distinct coords identify vertices.
        let data: Vec<u8> = vec![
            0, 10, 11, 0, // va
            0, 20, 21, 0, // vb
            0, 30, 31, 0, // vc  -> triangle 0 = (va, vb, vc)
            1, 40, 41, 0, // flag 1 -> (vb, vc, vd)
            2, 50, 51, 0, // flag 2 -> (vb, vd, ve)  [va of the PREVIOUS triangle is vb]
        ];
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type4(&data, 8, 8, 8, 1, &decode, e));
        assert_eq!(tris.len(), 3);
        let xy = |v: &Vertex| (v.x as i32, v.y as i32);
        assert_eq!(
            (xy(&tris[0].0), xy(&tris[0].1), xy(&tris[0].2)),
            ((10, 11), (20, 21), (30, 31))
        );
        // flag 1: drop the previous triangle's FIRST vertex.
        assert_eq!(
            (xy(&tris[1].0), xy(&tris[1].1), xy(&tris[1].2)),
            ((20, 21), (30, 31), (40, 41))
        );
        // flag 2: drop the previous triangle's SECOND vertex.
        assert_eq!(
            (xy(&tris[2].0), xy(&tris[2].1), xy(&tris[2].2)),
            ((20, 21), (40, 41), (50, 51))
        );
    }

    // ISO 32000-1 8.7.4.5.7 Table 85: a flag-1 continuation patch inherits the
    // previous patch's p4..p7 as its own p1..p4 AND its c2,c3 as c1,c2. Getting the
    // colour half wrong is invisible in the geometry but rotates the gradient inside
    // every continued patch.
    #[test]
    fn coons_continuation_inherits_edge_and_corner_colours() {
        // Patch 1: flag 0, 12 boundary points, 4 one-component colours.
        let boundary: [(u8, u8); 12] = [
            (0, 0), (0, 33), (0, 66), (0, 100),
            (33, 100), (66, 100), (100, 100),
            (100, 66), (100, 33), (100, 0),
            (66, 0), (33, 0),
        ];
        let mut data = vec![0u8];
        for (x, y) in boundary { data.push(x); data.push(y); }
        data.extend_from_slice(&[10, 20, 30, 40]); // c1..c4
        // Patch 2: flag 1, 8 new boundary points, 2 new colours (c3, c4).
        data.push(1);
        for i in 0..8u8 { data.push(200 + i); data.push(200 + i); }
        data.extend_from_slice(&[50, 60]);

        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 255.0];
        let tris = collect(|e| parse_type6_7(&data, 6, (8, 8, 8), 1, &decode, e));
        assert!(!tris.is_empty());

        // The second patch's tessellation starts after the first's (2*8*8 triangles).
        let per_patch = 2 * 8 * 8;
        assert_eq!(tris.len(), 2 * per_patch, "two patches tessellated");

        // grid[0][0] of a patch is its C00 corner = p1 with colour c1. For patch 2
        // that inherits patch 1's p4 = (0,100) and patch 1's c2 = 20.
        let corner = &tris[per_patch].0;
        assert_eq!((corner.x as i32, corner.y as i32), (0, 100), "p1 = previous p4");
        assert!((corner.color[0] - 20.0).abs() < 1e-6, "c1 = previous c2");
    }

    // §8.7.4.5.6: a lattice mesh is row-major with `/VerticesPerRow` vertices per row,
    // and each cell splits into two triangles. This pins the triangulation and the row
    // pairing directly, since `parse_type5` now streams a row at a time rather than
    // materialising the whole lattice.
    #[test]
    fn type5_lattice_pairs_adjacent_rows() {
        // Vertex = x(8) y(8) colour(8). Two rows of two.
        let data: Vec<u8> = vec![
            0, 0, 0, 10, 0, 0, // row 0: (0,0) (10,0)
            0, 10, 0, 10, 10, 0, // row 1: (0,10) (10,10)
        ];
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type5(&data, 2, 8, 8, 1, &decode, e));
        assert_eq!(tris.len(), 2, "one lattice cell = two triangles");
        let xy = |v: &Vertex| (v.x as i32, v.y as i32);
        assert_eq!(
            (xy(&tris[0].0), xy(&tris[0].1), xy(&tris[0].2)),
            ((0, 0), (10, 0), (0, 10))
        );
        assert_eq!(
            (xy(&tris[1].0), xy(&tris[1].1), xy(&tris[1].2)),
            ((0, 10), (10, 0), (10, 10))
        );

        // A trailing PARTIAL row contributes nothing rather than pairing with garbage.
        let mut ragged = data.clone();
        ragged.extend_from_slice(&[0, 20, 0]);
        assert_eq!(collect(|e| parse_type5(&ragged, 2, 8, 8, 1, &decode, e)).len(), 2);
        // And a single row alone yields no triangles at all.
        assert!(collect(|e| parse_type5(&data[..6], 2, 8, 8, 1, &decode, e)).is_empty());
    }

    // §8.7.4.5.7 (Table 85) / §8.7.4.5.8: "All of the data for a patch shall occupy a
    // whole number of bytes; if the total number of bits required is not divisible by
    // 8, the last data byte for each patch is padded at the end with extra bits, which
    // shall be ignored." The existing continuation test uses 8-bit everything, so every
    // record is accidentally aligned and it cannot see a missing `align()`. Here a Coons
    // patch is 8 + 24*8 + 4*1 = 204 bits, so each patch carries 4 padding bits; without
    // the align the second patch's flag is read out of the padding and the whole rest of
    // the stream decodes from the wrong bit offset — the "spectacular noise" failure.
    #[test]
    fn coons_patches_are_padded_to_a_byte_boundary() {
        let boundary: [(u8, u8); 12] = [
            (0, 0), (0, 33), (0, 66), (0, 100),
            (33, 100), (66, 100), (100, 100),
            (100, 66), (100, 33), (100, 0),
            (66, 0), (33, 0),
        ];
        let mut data = vec![0u8]; // patch 1: flag 0
        for (x, y) in boundary { data.push(x); data.push(y); }
        data.push(0b1010_0000); // c1..c4 = 1,0,1,0 as single bits, then 4 pad bits
        data.push(1u8); // patch 2: flag 1 — must start on a byte boundary
        for i in 0..8u8 { data.push(200 + i); data.push(200 + i); }
        data.push(0b0100_0000); // 2 new colours = 0,1, then 6 pad bits
        assert_eq!(data.len(), 44);

        // 1-bit components, so /Decode maps raw 0 -> 0.0 and raw 1 -> 1.0.
        let decode = [0.0, 255.0, 0.0, 255.0, 0.0, 1.0];
        let tris = collect(|e| parse_type6_7(&data, 6, (8, 8, 1), 1, &decode, e));
        let per_patch = 2 * 8 * 8;
        assert_eq!(tris.len(), 2 * per_patch, "both patches decode");

        // Patch 2 inherits patch 1's p4 = (0,100) and patch 1's c2 = 0 (Table 85).
        let corner = &tris[per_patch].0;
        assert_eq!((corner.x as i32, corner.y as i32), (0, 100), "p1 = previous p4");
        assert!(corner.color[0].abs() < 1e-9, "c1 = previous c2 = 0");
        // Patch 1's own C00 carries c1 = 1, so the colours are not all collapsing to 0.
        assert!((tris[0].0.color[0] - 1.0).abs() < 1e-9);
    }

    // §8.6.6.3: a colour value in an Indexed space is a SINGLE index, not `base_ncomp`
    // components. `cs_kind_ncomp` reports the base arity (3 over DeviceRGB), and using
    // that as the per-vertex component count made every vertex consume 3x the colour
    // bits, so the packed stream desynchronised after the first vertex.
    #[test]
    fn an_indexed_mesh_reads_one_index_per_vertex() {
        let mut doc = Document::with_version("1.7");
        let palette = doc.add_object(Stream::new(
            dictionary! {},
            vec![0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255],
        ));
        let dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => Object::Array(vec![
                Object::Name(b"Indexed".to_vec()),
                Object::Name(b"DeviceRGB".to_vec()),
                Object::Integer(3),
                Object::Reference(palette),
            ]),
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            // x 0..255, y 0..255, index 0..3.
            "Decode" => vec![0.into(), 255.into(), 0.into(), 255.into(), 0.into(), 3.into()],
        };
        // flag, x, y, index-byte. 85/255 * 3 == 1 -> palette entry 1 == red.
        let data: Vec<u8> = vec![
            0, 0, 0, 85,
            0, 255, 0, 85,
            0, 0, 255, 85,
        ];
        let (_ctm, w, h, rgba) =
            rasterize_shading_mesh(&doc, &dict, Some(&data), &IDENTITY, &HashMap::new(), 32)
                .expect("four bytes per vertex is exactly three vertices = one triangle");
        assert!(w > 0 && h > 0);
        let red = rgba
            .chunks_exact(4)
            .filter(|px| px[3] == 255)
            .collect::<Vec<_>>();
        assert!(!red.is_empty(), "the triangle painted something");
        assert!(
            red.iter().all(|px| px[0] == 255 && px[1] == 0 && px[2] == 0),
            "every covered pixel is palette entry 1 (red), not the 0xFF808080 fallback"
        );
    }

    // A /Decode extent of +/-infinity makes `dmin + (raw/max)*(dmax-dmin)` NaN for
    // raw == 0. NaN defeats every `< -1e-6` barycentric rejection in `fill_tri` and
    // the colour cast saturates to 0, so the shading used to paint an OPAQUE BLACK
    // rectangle over its whole area and hide the page content beneath it.
    #[test]
    fn non_finite_geometry_paints_nothing_rather_than_a_black_rectangle() {
        let inf_decode = [f64::NEG_INFINITY, f64::INFINITY, 0.0, 255.0, 0.0, 1.0];
        let data: Vec<u8> = vec![
            0, 0, 0, 0,
            0, 255, 0, 255,
            0, 0, 255, 128,
        ];
        let tris = collect(|e| parse_type4(&data, 8, 8, 8, 1, &inf_decode, e));
        assert_eq!(tris.len(), 1);
        assert!(tris[0].0.x.is_nan(), "the NaN this guards against is reachable");

        let (w, h) = (16usize, 16usize);
        let mut rgba = vec![0u8; w * h * 4];
        let (v0, v1, v2) = &tris[0];
        fill_tri(&mut rgba, w, h, &[0.0, 0.0, 1.0, 1.0], (v0, v1, v2), (0xFF00_0000, 0xFF00_0000, 0xFF00_0000));
        assert!(
            rgba.chunks(4).all(|px| px[3] == 0),
            "a triangle with non-finite vertices must leave every pixel transparent"
        );
    }

    // A flat white triangle must paint 255, not 254. `c` is computed as `1 - a - b`,
    // so a+b+c is 0.9999999999999999 for many interior pixels, the weighted sum is
    // 254.99999999999997, and truncating `as u8` darkened every flat mesh by 1/255 —
    // and every gradient by up to the same, systematically towards zero.
    #[test]
    fn flat_white_triangle_is_255_not_254() {
        let (w, h) = (32usize, 32usize);
        let mut rgba = vec![0u8; w * h * 4];
        let v = |x: f64, y: f64| Vertex { x, y, color: vec![1.0, 1.0, 1.0] };
        let (v0, v1, v2) = (v(0.05, 0.05), v(0.95, 0.05), v(0.5, 0.95));
        let white = 0xFFFF_FFFF;
        fill_tri(&mut rgba, w, h, &[0.0, 0.0, 1.0, 1.0], (&v0, &v1, &v2), (white, white, white));
        let painted: Vec<&[u8]> = rgba.chunks(4).filter(|px| px[3] == 255).collect();
        assert!(painted.len() > 100, "expected a filled triangle, got {} pixels", painted.len());
        assert!(
            painted.iter().all(|px| px[0] == 255 && px[1] == 255 && px[2] == 255),
            "a triangle white at all three corners must be white everywhere inside; \
             darkest pixel was {:?}",
            painted.iter().min_by_key(|px| px[0]).unwrap()
        );
    }

    // The same rounding, on a colour that is not a channel extreme, so the clamp
    // cannot be what rescues it: a flat (100,150,200) triangle truncated to
    // (99,149,199) on every interior pixel whose barycentric sum fell short of 1.
    #[test]
    fn flat_midtone_triangle_keeps_its_exact_colour() {
        let (w, h) = (32usize, 32usize);
        let mut rgba = vec![0u8; w * h * 4];
        let v = |x: f64, y: f64| Vertex { x, y, color: vec![0.4, 0.6, 0.8] };
        let (v0, v1, v2) = (v(0.05, 0.05), v(0.95, 0.05), v(0.5, 0.95));
        let c = 0xFF64_96C8; // 100, 150, 200
        fill_tri(&mut rgba, w, h, &[0.0, 0.0, 1.0, 1.0], (&v0, &v1, &v2), (c, c, c));
        let painted: Vec<&[u8]> = rgba.chunks(4).filter(|px| px[3] == 255).collect();
        assert!(!painted.is_empty(), "expected a filled triangle");
        assert!(
            painted.iter().all(|px| px[0] == 100 && px[1] == 150 && px[2] == 200),
            "flat triangle must keep its colour; saw {:?}",
            painted.iter().find(|px| px[0] != 100 || px[1] != 150 || px[2] != 200)
        );
    }

    // `rasterize_shading_mesh` returning None (rather than an all-transparent raster)
    // for a mesh it cannot parse is load-bearing beyond this file: images.rs now
    // applies a /Background post-pass over every alpha-0 pixel of whatever raster it
    // gets back, so an empty raster would be flooded edge to edge with the background
    // colour and become the opaque rectangle over page content that the no-triangles
    // branch exists to prevent. Table 78 scopes /Background to the area outside the
    // shading's bounds, and an unparseable mesh has no known bounds.
    #[test]
    fn unparseable_mesh_returns_none_rather_than_an_empty_raster() {
        let doc = Document::with_version("1.7");
        let dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            "Decode" => vec![0.into(), 255.into(), 0.into(), 255.into(),
                             0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
        };
        // Two vertices' worth of bytes: enough to decode, never enough for a triangle.
        let truncated = vec![0u8; 12];
        assert!(
            rasterize_shading_mesh(&doc, &dict, Some(&truncated), &IDENTITY, &HashMap::new(), 64)
                .is_none(),
            "a mesh that yields no triangles must produce no raster at all"
        );
        // And a non-finite /Decode extent, which makes every vertex NaN.
        let inf_dict = dictionary! {
            "ShadingType" => 4,
            "ColorSpace" => "DeviceRGB",
            "BitsPerCoordinate" => 8,
            "BitsPerComponent" => 8,
            "BitsPerFlag" => 8,
            "Decode" => vec![Object::Real(f32::NEG_INFINITY), Object::Real(f32::INFINITY),
                             0.into(), 255.into(),
                             0.into(), 1.into(), 0.into(), 1.into(), 0.into(), 1.into()],
        };
        let three_verts: Vec<u8> = vec![
            0, 0, 0, 255, 0, 0,
            0, 255, 0, 0, 255, 0,
            0, 0, 255, 0, 0, 255,
        ];
        assert!(
            rasterize_shading_mesh(&doc, &inf_dict, Some(&three_verts), &IDENTITY, &HashMap::new(), 64)
                .is_none(),
            "an infinite /Decode extent must be refused, not rasterized as NaN geometry"
        );
    }
}
