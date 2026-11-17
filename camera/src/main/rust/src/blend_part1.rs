pub fn multiband_blend(tiles: &[WarpedTile], masks: &[Vec<u8>], gain_maps: &[Vec<f32>]) -> Option<Rgba> {
    let num = tiles.len();
    if num == 0 {
        return None;
    }
    // Global canvas bounds
    let mut gx0 = i32::MAX;
    let mut gy0 = i32::MAX;
    let mut gx1 = i32::MIN;
    let mut gy1 = i32::MIN;
    for t in tiles {
        gx0 = gx0.min(t.corner_x);
        gy0 = gy0.min(t.corner_y);
        gx1 = gx1.max(t.corner_x + t.img.w as i32);
        gy1 = gy1.max(t.corner_y + t.img.h as i32);
    }
    let cw = (gx1 - gx0) as usize;
    let ch = (gy1 - gy0) as usize;
    if cw == 0 || ch == 0 || cw > 20000 || ch > 20000 {
        return None;
    }

    // OpenCV MultiBandBlender prepare logic
    let max_len = cw.max(ch) as f64;
    let mut nb = if max_len > 1.0 { max_len.log2().ceil() as usize } else { 1 };
    nb = nb.clamp(1, 5); // actual_num_bands=5
    let num_bands = nb;

    // Padded size divisible by 1<<num_bands
    let div = 1usize << num_bands;
    let cw_padded = cw + (div - cw % div) % div;
    let ch_padded = ch + (div - ch % div) % div;
    if cw_padded > 20000 || ch_padded > 20000 {
        // Too large for pyramid – fallback to feather
        return feather_blend(tiles, masks, gain_maps, gx0, gy0, cw, ch);
    }

    // Global pyramid dimensions
    let mut global_ws = Vec::with_capacity(num_bands + 1);
    let mut global_hs = Vec::with_capacity(num_bands + 1);
    let mut bw = cw_padded;
    let mut bh = ch_padded;
    global_ws.push(bw);
    global_hs.push(bh);
    for _ in 0..num_bands {
        bw = bw.div_ceil(2);
        bh = bh.div_ceil(2);
        global_ws.push(bw);
        global_hs.push(bh);
    }

    // Allocate global laplacian and weight pyramids zero
    let mut global_lap: Vec<Img3> = (0..=num_bands).map(|l| Img3 { w: global_ws[l], h: global_hs[l], data: vec![0f32; global_ws[l] * global_hs[l] * 3] }).collect();
    let mut global_wmap: Vec<WMap> = (0..=num_bands).map(|l| WMap { w: global_ws[l], h: global_hs[l], data: vec![0f32; global_ws[l] * global_hs[l]] }).collect();

    // For each tile, build its image and weight, with gain applied
    // Gap handling: OpenCV uses gap=3*(1<<bands) and BORDER_REFLECT for image
    let gap = 3 * div;
    for (ti, tile) in tiles.iter().enumerate() {
        let tw0 = tile.img.w;
        let th0 = tile.img.h;
        let gain = &gain_maps[ti];
        let mask = &masks[ti];

        // Base image f32 with gain
        let mut base_img = Img3 { w: tw0, h: th0, data: vec![0f32; tw0 * th0 * 3] };
        let mut base_weight = WMap { w: tw0, h: th0, data: vec![0f32; tw0 * th0] };
        for y in 0..th0 {
            for x in 0..tw0 {
                let li = y * tw0 + x;
                let c = tile.img.get(x, y);
                if c[3] == 0 {
                    continue;
                }
                let g = gain.get(li).copied().unwrap_or(1.0);
                let base = li * 3;
                base_img.data[base] = (c[0] as f32 * g).min(255.0);
                base_img.data[base + 1] = (c[1] as f32 * g).min(255.0);
                base_img.data[base + 2] = (c[2] as f32 * g).min(255.0);
                if mask.get(li).copied().unwrap_or(0) != 0 {
                    base_weight.data[li] = 1.0; // mask/255 CV_32F – our mask 0/1 => 1.0
                }
            }
        }

        // Compute tl in padded canvas (relative to gx0,gy0)
        let tl_x = (tile.corner_x - gx0) as usize;
        let tl_y = (tile.corner_y - gy0) as usize;

        // Expand with gap for pyramid border handling (simplified – we expand base before pyramid but still place at tl)
        // For simplicity we keep expansion only for laplacian border reflect: we build bordered image larger
        // by gap where possible, with reflect for image and constant 0 for weight.
        // This matches OpenCV's copyMakeBorder logic.

        let tl_new_x_raw = tl_x.saturating_sub(gap);
        let tl_new_y_raw = tl_y.saturating_sub(gap);
        let br_new_x_raw = (tl_x + tw0 + gap).min(cw_padded);
        let br_new_y_raw = (tl_y + th0 + gap).min(ch_padded);

        // Align tl_new to multiple of div (1<<bands) – OpenCV: tl_new = dst_roi + ((tl_new - dst_roi)>>bands <<bands)
        let mut tl_new_x = (tl_new_x_raw >> num_bands) << num_bands;
        let mut tl_new_y = (tl_new_y_raw >> num_bands) << num_bands;
        let mut br_new_x = br_new_x_raw;
        let mut br_new_y = br_new_y_raw;
        // Make br-tl divisible by div
        let w_gap = br_new_x - tl_new_x;
        let h_gap = br_new_y - tl_new_y;
        br_new_x += (div - w_gap % div) % div;
        br_new_y += (div - h_gap % div) % div;
        // Clamp to padded size, shift if needed
        if br_new_x > cw_padded {
            let dx = br_new_x - cw_padded;
            if tl_new_x >= dx {
                tl_new_x -= dx;
                br_new_x -= dx;
            } else {
                br_new_x = cw_padded;
            }
        }
        if br_new_y > ch_padded {
            let dy = br_new_y - ch_padded;
            if tl_new_y >= dy {
                tl_new_y -= dy;
                br_new_y -= dy;
            } else {
                br_new_y = ch_padded;
            }
        }
        let bw_big = br_new_x - tl_new_x;
        let bh_big = br_new_y - tl_new_y;
        if bw_big == 0 || bh_big == 0 {
            continue;
        }
        let left = tl_x - tl_new_x;
        let top = tl_y - tl_new_y;
        // Build big image with reflect border for img, constant 0 for weight
        let mut big_img = Img3 { w: bw_big, h: bh_big, data: vec![0f32; bw_big * bh_big * 3] };
        let mut big_weight = WMap { w: bw_big, h: bh_big, data: vec![0f32; bw_big * bh_big] };
        for y in 0..bh_big {
            for x in 0..bw_big {
                // source coord in base image
                let sx = x as isize - left as isize;
                let sy = y as isize - top as isize;
                let d_idx3 = (y * bw_big + x) * 3;
                let d_idx1 = y * bw_big + x;
                if sx >= 0 && sy >= 0 && (sx as usize) < tw0 && (sy as usize) < th0 {
                    let s_idx3 = ((sy as usize) * tw0 + sx as usize) * 3;
                    big_img.data[d_idx3] = base_img.data[s_idx3];
                    big_img.data[d_idx3 + 1] = base_img.data[s_idx3 + 1];
                    big_img.data[d_idx3 + 2] = base_img.data[s_idx3 + 2];
                    big_weight.data[d_idx1] = base_weight.data[sy as usize * tw0 + sx as usize];
                } else {
                    // Image: BORDER_REFLECT_101 over the WHOLE gap (as
                    // OpenCV copyMakeBorder does). Reflecting only a 1px
                    // ring and leaving the rest black put a 120->0 step
                    // just outside each tile; pyr_down blur spread it
                    // into coarse Laplacian bands inside the tile, so a
                    // uniform overlap reconstructed at 98..135 instead
                    // of 120. reflect_101 handles far indices. Weight
                    // stays 0 (BORDER_CONSTANT).
                    if tw0 > 0 && th0 > 0 {
                        let rx = reflect_101(sx, tw0);
                        let ry = reflect_101(sy, th0);
                        let s_idx3 = (ry * tw0 + rx) * 3;
                        big_img.data[d_idx3] = base_img.data[s_idx3];
                        big_img.data[d_idx3 + 1] = base_img.data[s_idx3 + 1];
                        big_img.data[d_idx3 + 2] = base_img.data[s_idx3 + 2];
                    }
                }
            }
        }

        // Build pyramids for this big tile
        let mut pyr_img: Vec<Img3> = Vec::with_capacity(num_bands + 1);
        let mut pyr_weight: Vec<WMap> = Vec::with_capacity(num_bands + 1);
        pyr_img.push(big_img);
        pyr_weight.push(big_weight);
        for l in 0..num_bands {
            let down_i = pyr_down_3ch(&pyr_img[l]);
            let down_w = pyr_down_1ch(&pyr_weight[l]);
            pyr_img.push(down_i);
            pyr_weight.push(down_w);
        }
        // Laplacian pyramid: lap[l] = gauss[l] - up(gauss[l+1])
        let mut lap_pyr: Vec<Img3> = Vec::with_capacity(num_bands + 1);
        for l in 0..num_bands {
            let up = pyr_up_3ch(&pyr_img[l + 1], pyr_img[l].w, pyr_img[l].h);
            let mut lap = Img3 { w: pyr_img[l].w, h: pyr_img[l].h, data: vec![0f32; pyr_img[l].w * pyr_img[l].h * 3] };
            for i in 0..pyr_img[l].data.len() {
                lap.data[i] = pyr_img[l].data[i] - up.data[i];
            }
            lap_pyr.push(lap);
        }
        lap_pyr.push(pyr_img[num_bands].clone()); // low-pass

        // Accumulate into global pyramids at tl_new position
        // Global tl per level: divide tl_new by 2^l
        let mut gx = tl_new_x;
        let mut gy = tl_new_y;
        // Keep track of tile dimensions per level as in pyr
        for l in 0..=num_bands {
            let gw = global_ws[l];
            let gh = global_hs[l];
            let tile_w = lap_pyr[l].w;
            let tile_h = lap_pyr[l].h;
            let tile_wm = pyr_weight[l].w; // same as lap w
            // Accumulate
            for y in 0..tile_h.min(gh.saturating_sub(gy)) {
                let gy_glob = gy + y;
                if gy_glob >= gh {
                    break;
                }
                for x in 0..tile_w.min(gw.saturating_sub(gx)) {
                    let gx_glob = gx + x;
                    if gx_glob >= gw {
                        break;
                    }
                    let wgt = if x < tile_wm && y < pyr_weight[l].h {
                        pyr_weight[l].data[y * tile_wm + x]
                    } else {
                        0.0
                    };
                    if wgt <= 1e-6 {
                        continue;
                    }
                    let g_w_idx = gy_glob * gw + gx_glob;
                    global_wmap[l].data[g_w_idx] += wgt;
                    let s_idx = (y * tile_w + x) * 3;
                    let d_idx = (gy_glob * gw + gx_glob) * 3;
                    global_lap[l].data[d_idx] += lap_pyr[l].data[s_idx] * wgt;
                    global_lap[l].data[d_idx + 1] += lap_pyr[l].data[s_idx + 1] * wgt;
                    global_lap[l].data[d_idx + 2] += lap_pyr[l].data[s_idx + 2] * wgt;
                }
            }
            gx /= 2;
            gy /= 2;
        }
    }

    // Normalize per level by weight EPS – matches OpenCV normalizeUsingWeightMap
    const WEIGHT_EPS: f32 = 1e-5;
    for l in 0..=num_bands {
        let gw = global_ws[l];
        let gh = global_hs[l];
        let lap = &mut global_lap[l];
        let wmap = &global_wmap[l];
        for y in 0..gh {
            for x in 0..gw {
                let wi = y * gw + x;
                let wgt = wmap.data[wi];
                let base = wi * 3;
                if wgt > WEIGHT_EPS {
                    lap.data[base] /= wgt + WEIGHT_EPS;
                    lap.data[base + 1] /= wgt + WEIGHT_EPS;
                    lap.data[base + 2] /= wgt + WEIGHT_EPS;
                } else {
                    lap.data[base] = 0.0;
                    lap.data[base + 1] = 0.0;
                    lap.data[base + 2] = 0.0;
                }
            }
        }
    }

    // Restore image from Laplacian pyramid – matches OpenCV restoreImageFromLaplacePyr
    for l in (1..=num_bands).rev() {
        let up = pyr_up_3ch(&global_lap[l], global_ws[l - 1], global_hs[l - 1]);
        let dst = &mut global_lap[l - 1];
        for i in 0..dst.data.len().min(up.data.len()) {
            dst.data[i] += up.data[i];
        }
    }

    // Convert level 0 to Rgba, with alpha from level0 weight
    let final_lap = &global_lap[0];
    let final_w = &global_wmap[0];
    let mut big_rgba = Rgba::new(cw_padded, ch_padded);
    for y in 0..ch_padded {
        for x in 0..cw_padded {
            let wi = y * cw_padded + x;
            if wi >= final_w.data.len() {
                continue;
            }
            if final_w.data[wi] <= WEIGHT_EPS {
                // leave alpha 0
                continue;
            }
            let base = wi * 3;
            if base + 2 >= final_lap.data.len() {
                continue;
            }
            let r = final_lap.data[base].round().clamp(0.0, 255.0) as u8;
            let g = final_lap.data[base + 1].round().clamp(0.0, 255.0) as u8;
            let b = final_lap.data[base + 2].round().clamp(0.0, 255.0) as u8;
            let idx = wi * 4;
            big_rgba.px[idx] = r;
            big_rgba.px[idx + 1] = g;
            big_rgba.px[idx + 2] = b;
            big_rgba.px[idx + 3] = 255;
        }
    }

    // Crop to original cw,ch (dst_roi_final) – matches MultiBandBlender::blend
    let mut cropped = Rgba::new(cw, ch);
    for y in 0..ch {
        for x in 0..cw {
            let s = (y * cw_padded + x) * 4;
            let d = (y * cw + x) * 4;
            if s + 3 < big_rgba.px.len() && d + 3 < cropped.px.len() {
                cropped.px[d..d + 4].copy_from_slice(&big_rgba.px[s..s + 4]);
            }
        }
    }

    // If blended result is mostly empty (e.g., weights zero), fallback to feather
    let mut opaque = 0usize;
    for i in 0..cw * ch {
        if cropped.px[i * 4 + 3] == 255 {
            opaque += 1;
        }
    }
    if opaque == 0 {
        return feather_blend(tiles, masks, gain_maps, gx0, gy0, cw, ch);
    }

    Some(crop_to_content(cropped))
}

/// Crop rectangle = maximal all-opaque rectangle. With the coverage a proper
/// partition of the frames (no interior holes), this is the large landscape
/// rectangle spanning all frames, with the black curved borders removed.
fn content_rect(valid: &[bool], w: usize, h: usize) -> (usize, usize, usize, usize) {
    let mut heights = vec![0usize; w];
    let mut best = (0usize, 0usize, 0usize, 0usize);
    let mut best_area = 0usize;
    for y in 0..h {
        for x in 0..w {
            heights[x] = if valid[y * w + x] { heights[x] + 1 } else { 0 };
        }
        let mut stack: Vec<usize> = Vec::new();
        let mut x = 0usize;
        while x <= w {
            let cur = if x == w { 0 } else { heights[x] };
            if stack.is_empty() || cur >= heights[*stack.last().unwrap()] {
                stack.push(x);
                x += 1;
            } else {
                let top = stack.pop().unwrap();
                let height = heights[top];
                let left = if stack.is_empty() { 0 } else { *stack.last().unwrap() + 1 };
                let width = x - left;
                let area = height * width;
                if area > best_area {
                    best_area = area;
                    best = (left, y + 1 - height, width, height);
                }
            }
        }
    }
    best
}

fn crop_to_content(img: Rgba) -> Rgba {
    let valid: Vec<bool> = (0..img.w * img.h).map(|i| img.px[i * 4 + 3] == 255).collect();
    let (rx, ry, rw, rh) = content_rect(&valid, img.w, img.h);
    if rw == 0 || rh == 0 {
        return img;
    }
    let mut out = Rgba::new(rw, rh);
    for y in 0..rh {
        for x in 0..rw {
            let s = ((ry + y) * img.w + (rx + x)) * 4;
            let d = (y * rw + x) * 4;
            out.px[d..d + 4].copy_from_slice(&img.px[s..s + 4]);
        }
    }
    out
}
