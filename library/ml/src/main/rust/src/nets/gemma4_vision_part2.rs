#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::tests::Shapes;

    #[test]
    fn the_layout_matches_the_converter() {
        let source = Shapes::new(TENSORS);
        declare_shared(&source).expect("the shared tensors");
        for index in 0..LAYERS {
            declare_layer(&source, index).expect("a layer");
        }
        assert_eq!(layer_at(LAYERS), TENSORS);
    }

    #[test]
    fn the_two_ends_are_not_quantised() {
        // The mixed precision is the contract with `collect_gemma4_vision`, and a converter that
        // wrote three tensors here instead of two would shift every index after it.
        assert_eq!(FINAL_NORM, PATCH_PROJECTION + 2);
        assert_eq!(COLUMN_POSITIONS, OUT_PROJECTION + 2);
        assert_eq!(SHARED_TENSORS, 11);
        assert_eq!(TENSORS, SHARED_TENSORS + LAYERS * LAYER_TENSORS);
    }

    #[test]
    fn the_parameter_total_is_within_reach_of_the_published_size() {
        // 16 layers of (4 * 768 * 768 attention + 3 * 768 * 3072 feed-forward), plus the patch
        // and output projections. The tower is ~300M parameters, which at fp16 is the 337 MB the
        // export weighs - the check is that this layout accounts for essentially all of it.
        let per_layer = 4 * D_MODEL * D_MODEL + 3 * D_MODEL * FFN;
        let total = LAYERS as u32 * per_layer + D_MODEL * D_MODEL + OUT_DIM * D_MODEL;
        let megabytes = f64::from(total) * 2.0 / 1e6;
        assert!(
            (300.0..350.0).contains(&megabytes),
            "{megabytes:.0} MB against the export's 337 MB"
        );
    }

    #[test]
    fn the_head_splits_into_two_rotary_blocks() {
        // The one thing about this tower that is not the decoder's shape.
        assert_eq!(HEAD_DIM % ROPE_AXES, 0);
        assert_eq!((HEAD_DIM / ROPE_AXES) % 2, 0, "each block still rotates 2-planes");
        assert_eq!(HEADS * HEAD_DIM, D_MODEL, "multi-head, so q is the full width");
    }

    #[test]
    fn the_inverse_frequencies_are_the_constant_the_export_holds() {
        // The export folds these into a `[1, 16, 1]` initializer whose values are the powers of
        // 0.75. That is `100^(-i/16)` to four figures, and this is the check that the theta and
        // the block width in this file reproduce it rather than merely being plausible.
        let got = inv_freq();
        assert_eq!(got.len(), 16);
        for (i, value) in got.iter().enumerate() {
            let want = 0.75_f64.powi(i as i32);
            assert!(
                (f64::from(*value) - want).abs() < 2e-3,
                "frequency {i} is {value}, not {want}"
            );
        }
    }

    #[test]
    fn a_square_image_resizes_to_the_grid_the_reference_processor_picks() {
        // 2520 patches of 16 pixels is 645120 pixels, whose square side is 803.2; rounded down to
        // a multiple of 48 that is 768, which is 48 patches and so 16x16 = 256 soft tokens.
        let grid = Grid::for_image(1000, 1000, DEFAULT_SOFT_TOKENS).expect("a square image");
        assert_eq!((grid.rows, grid.cols), (48, 48));
        assert_eq!(grid.pixels(), (768, 768));
        assert_eq!(grid.soft_tokens(), 256);
    }

    #[test]
    fn every_grid_stays_inside_its_budget_and_divides_by_the_pooling_kernel() {
        // The budget is the reason the resize rounds down, and the divisibility is what lets
        // `pool` be an average rather than the export's masked matrix. Both hold or neither the
        // plan nor the pooling is valid, so sweep a range of shapes rather than trusting one.
        for soft_tokens in SOFT_TOKEN_BUDGETS {
            for width in [1_u32, 17, 64, 640, 1920, 4000] {
                for height in [1_u32, 17, 64, 480, 1080, 4000] {
                    let grid = Grid::for_image(width, height, soft_tokens)
                        .unwrap_or_else(|e| panic!("{width}x{height} at {soft_tokens}: {e}"));
                    assert!(
                        grid.patches() <= max_patches(soft_tokens),
                        "{width}x{height} at {soft_tokens} gives {} patches",
                        grid.patches()
                    );
                    assert!(grid.soft_tokens() <= soft_tokens, "{width}x{height}");
                    assert_eq!(grid.rows % POOL, 0, "{width}x{height} rows");
                    assert_eq!(grid.cols % POOL, 0, "{width}x{height} columns");
                    // Every coordinate has to index the learned position table.
                    assert!(grid.rows <= POSITIONS && grid.cols <= POSITIONS);
                }
            }
        }
        assert!(Grid::for_image(64, 64, 99).is_err(), "an unsupported budget");
    }

    #[test]
    fn the_aspect_ratio_survives_the_resize() {
        // Rounding to a multiple of 48 pixels cannot preserve a ratio exactly, but it must not
        // transpose or squash one: a 2:1 image stays about 2:1.
        let grid = Grid::for_image(2000, 1000, DEFAULT_SOFT_TOKENS).expect("a wide image");
        let ratio = f64::from(grid.cols) / f64::from(grid.rows);
        assert!((ratio - 2.0).abs() < 0.15, "a 2:1 image became {ratio:.2}:1");
        assert!(grid.cols > grid.rows, "a wide image must stay wide");
    }

    #[test]
    fn a_grid_the_pooling_cannot_tile_is_refused() {
        assert!(Grid::new(16, 16).is_err(), "16 does not divide by 3");
        assert!(Grid::new(0, 3).is_err(), "an empty grid");
        assert!(Grid::new(3000, 3000).is_err(), "past the largest budget");
        assert!(Grid::new(48, 48).is_ok());
    }

    #[test]
    fn patchify_lays_a_pixel_out_where_the_export_reads_it() {
        // A 3x3-patch grid, so 48x48 pixels, with one pixel set to a known colour. The check is
        // that it lands at the channel and the position the export's patchify would put it: patch
        // `row * cols + col`, and within the patch `(y * 16 + x) * 3 + channel`.
        let grid = Grid::new(3, 3).expect("a grid");
        let (width, height) = grid.pixels();
        assert_eq!((width, height), (48, 48));
        let mut pixels = vec![0xff00_0000_u32 as i32; (width * height) as usize];
        // Red, at pixel (y = 20, x = 35): patch row 1, column 2, and (4, 3) inside it.
        pixels[20 * width as usize + 35] = 0xffff_0000_u32 as i32;

        let values = patchify(grid, &pixels).expect("patches");
        let patches = grid.patches() as usize;
        assert_eq!(values.len(), D_MODEL as usize * patches);
        let patch = 3 + 2;
        let within = (4 * 16 + 3) * 3;
        assert_eq!(values[within * patches + patch], 1.0, "red is saturated");
        assert_eq!(values[(within + 1) * patches + patch], -1.0, "green is off");
        assert_eq!(values[(within + 2) * patches + patch], -1.0, "blue is off");
        // Black elsewhere, which after the shift is -1 rather than 0.
        assert_eq!(values[within * patches], -1.0);
        assert!(patchify(grid, &pixels[1..]).is_err(), "a short buffer");
    }

    #[test]
    fn the_angles_rotate_the_first_block_by_the_column_and_the_second_by_the_row() {
        // The trap `rotary_axes` exists to avoid: if the two blocks were swapped, or if a block
        // held sines before cosines, every shape would still agree.
        let grid = Grid::new(3, 6).expect("a grid");
        let angles = rotary_angles(grid);
        let patches = grid.patches() as usize;
        assert_eq!(angles.len(), HEAD_DIM as usize * patches);
        let frequencies = inv_freq();
        let half = frequencies.len();

        // Patch 8 is row 1, column 2 of a 3-row, 6-column grid.
        let at = 6 + 2;
        for (i, frequency) in frequencies.iter().enumerate() {
            let column = 2.0 * frequency;
            let row = 1.0 * frequency;
            let close = |got: f32, want: f32, what: &str| {
                assert!((got - want).abs() < 1e-6, "{what} at {i} is {got}, not {want}");
            };
            close(angles[i * patches + at], column.cos(), "block 0 cosine");
            close(angles[(half + i) * patches + at], column.sin(), "block 0 sine");
            close(angles[(2 * half + i) * patches + at], row.cos(), "block 1 cosine");
            close(angles[(3 * half + i) * patches + at], row.sin(), "block 1 sine");
        }
        // Frequency 0 is 1.0, so the first channel of each block is the raw coordinate.
        assert!((angles[at] - 2.0_f32.cos()).abs() < 1e-6);
        assert!((angles[2 * half * patches + at] - 1.0_f32.cos()).abs() < 1e-6);
    }
}
