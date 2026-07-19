//! MultiBandBlender (blenders.cpp): Laplacian-pyramid blending. Built the OpenCV
//! way — each image's pyramid is created at its own ROI (padded so tl/br divide
//! 2^bands) and accumulated into the destination pyramids at the level offset,
//! rather than allocating a full-canvas pyramid per image.

use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

struct FImg {
    w: usize,
    h: usize,
    d: Vec<f32>, // w*h*3
}
impl FImg {
    fn new(w: usize, h: usize) -> Self {
        FImg { w, h, d: vec![0.0; w * h * 3] }
    }
}
struct Plane {
    w: usize,
    h: usize,
    d: Vec<f32>,
}
impl Plane {
    fn new(w: usize, h: usize) -> Self {
        Plane { w, h, d: vec![0.0; w * h] }
    }
}

const K: [f32; 5] = [1.0 / 16.0, 4.0 / 16.0, 6.0 / 16.0, 4.0 / 16.0, 1.0 / 16.0];

fn blur3(img: &FImg) -> FImg {
    let (w, h) = (img.w, img.h);
    let mut tmp = FImg::new(w, h);
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let mut s = 0.0;
                for k in 0..5 {
                    let xx = (x as i32 + k as i32 - 2).clamp(0, w as i32 - 1) as usize;
                    s += K[k] * img.d[(y * w + xx) * 3 + c];
                }
                tmp.d[(y * w + x) * 3 + c] = s;
            }
        }
    }
    let mut out = FImg::new(w, h);
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let mut s = 0.0;
                for k in 0..5 {
                    let yy = (y as i32 + k as i32 - 2).clamp(0, h as i32 - 1) as usize;
                    s += K[k] * tmp.d[(yy * w + x) * 3 + c];
                }
                out.d[(y * w + x) * 3 + c] = s;
            }
        }
    }
    out
}
fn blur1(p: &Plane) -> Plane {
    let (w, h) = (p.w, p.h);
    let mut tmp = Plane::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let mut s = 0.0;
            for k in 0..5 {
                let xx = (x as i32 + k as i32 - 2).clamp(0, w as i32 - 1) as usize;
                s += K[k] * p.d[y * w + xx];
            }
            tmp.d[y * w + x] = s;
        }
    }
    let mut out = Plane::new(w, h);
    for y in 0..h {
        for x in 0..w {
            let mut s = 0.0;
            for k in 0..5 {
                let yy = (y as i32 + k as i32 - 2).clamp(0, h as i32 - 1) as usize;
                s += K[k] * tmp.d[yy * w + x];
            }
            out.d[y * w + x] = s;
        }
    }
    out
}
fn down3(img: &FImg) -> FImg {
    let b = blur3(img);
    let dw = (img.w + 1) / 2;
    let dh = (img.h + 1) / 2;
    let mut out = FImg::new(dw, dh);
    for y in 0..dh {
        for x in 0..dw {
            for c in 0..3 {
                out.d[(y * dw + x) * 3 + c] = b.d[((2 * y).min(img.h - 1) * img.w + (2 * x).min(img.w - 1)) * 3 + c];
            }
        }
    }
    out
}
fn down1(p: &Plane) -> Plane {
    let b = blur1(p);
    let dw = (p.w + 1) / 2;
    let dh = (p.h + 1) / 2;
    let mut out = Plane::new(dw, dh);
    for y in 0..dh {
        for x in 0..dw {
            out.d[y * dw + x] = b.d[(2 * y).min(p.h - 1) * p.w + (2 * x).min(p.w - 1)];
        }
    }
    out
}
fn up3(src: &FImg, dw: usize, dh: usize) -> FImg {
    let mut spread = FImg::new(dw, dh);
    for y in 0..src.h {
        for x in 0..src.w {
            let dx = 2 * x;
            let dy = 2 * y;
            if dx < dw && dy < dh {
                for c in 0..3 {
                    spread.d[(dy * dw + dx) * 3 + c] = src.d[(y * src.w + x) * 3 + c] * 4.0;
                }
            }
        }
    }
    blur3(&spread)
}

/// Fill uncovered (black) pixels of a warped tile by clamp-extending the nearest
/// covered pixel: horizontally, then vertically. Prevents black edges from
/// contaminating the Laplacian pyramid near seams.
fn border_extend(img: &mut FImg, covered: &[bool], w: usize, h: usize) {
    let mut valid = covered.to_vec();
    // horizontal clamp
    for y in 0..h {
        let row = y * w;
        let mut first = None;
        let mut last = 0usize;
        for x in 0..w {
            if covered[row + x] {
                if first.is_none() {
                    first = Some(x);
                }
                last = x;
            }
        }
        if let Some(f) = first {
            for x in 0..f {
                for c in 0..3 {
                    img.d[(row + x) * 3 + c] = img.d[(row + f) * 3 + c];
                }
                valid[row + x] = true;
            }
            for x in (last + 1)..w {
                for c in 0..3 {
                    img.d[(row + x) * 3 + c] = img.d[(row + last) * 3 + c];
                }
                valid[row + x] = true;
            }
        }
    }
    // vertical clamp over remaining
    for x in 0..w {
        let mut first = None;
        let mut last = 0usize;
        for y in 0..h {
            if valid[y * w + x] {
                if first.is_none() {
                    first = Some(y);
                }
                last = y;
            }
        }
        if let Some(f) = first {
            for y in 0..f {
                for c in 0..3 {
                    img.d[(y * w + x) * 3 + c] = img.d[(f * w + x) * 3 + c];
                }
            }
            for y in (last + 1)..h {
                for c in 0..3 {
                    img.d[(y * w + x) * 3 + c] = img.d[(last * w + x) * 3 + c];
                }
            }
        }
    }
}

pub fn multiband_blend(tiles: &[WarpedTile], masks: &[Vec<u8>], gain_maps: &[Vec<f32>]) -> Option<Rgba> {
    let num = tiles.len();
    if num == 0 {
        return None;
    }
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
    let bands = {
        let maxlen = cw.max(ch) as f64;
        (maxlen.log2().ceil() as i32 - 1).clamp(1, 5) as usize
    };
    let div = 1usize << bands;
    let pw = ((cw + div - 1) / div) * div;
    let ph = ((ch + div - 1) / div) * div;

    let mut sizes = Vec::with_capacity(bands + 1);
    {
        let (mut w, mut h) = (pw, ph);
        for _ in 0..=bands {
            sizes.push((w, h));
            w /= 2;
            h /= 2;
        }
    }
    let mut dst_lap: Vec<FImg> = sizes.iter().map(|&(w, h)| FImg::new(w, h)).collect();
    let mut dst_w: Vec<Plane> = sizes.iter().map(|&(w, h)| Plane::new(w, h)).collect();

    for ti in 0..num {
        let t = &tiles[ti];
        let gmap = &gain_maps[ti];
        let tlx = (t.corner_x - gx0) as usize;
        let tly = (t.corner_y - gy0) as usize;
        // Padded ROI whose corners divide 2^bands.
        let x_tl = (tlx / div) * div;
        let y_tl = (tly / div) * div;
        let x_br = (((tlx + t.img.w) + div - 1) / div * div).min(pw);
        let y_br = (((tly + t.img.h) + div - 1) / div * div).min(ph);
        let rw = x_br - x_tl;
        let rh = y_br - y_tl;
        if rw == 0 || rh == 0 {
            continue;
        }
        let mut img = FImg::new(rw, rh);
        let mut wt = Plane::new(rw, rh);
        let mut covered = vec![false; rw * rh];
        for ly in 0..t.img.h {
            for lx in 0..t.img.w {
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue; // no coverage here
                }
                let rx = tlx + lx - x_tl;
                let ry = tly + ly - y_tl;
                let gain = gmap[ly * t.img.w + lx];
                let idx = (ry * rw + rx) * 3;
                // Place the full warped image everywhere it has coverage so the
                // Laplacian pyramid is smooth across the overlap (no hard edge at
                // the seam). The seam mask drives only the blend weight.
                img.d[idx] = (c[0] as f32 * gain).min(255.0);
                img.d[idx + 1] = (c[1] as f32 * gain).min(255.0);
                img.d[idx + 2] = (c[2] as f32 * gain).min(255.0);
                covered[ry * rw + rx] = true;
                wt.d[ry * rw + rx] = if masks[ti][ly * t.img.w + lx] != 0 { 1.0 } else { 0.0 };
            }
        }
        // Edge-extend the image into its black (uncovered) borders so the
        // Laplacian pyramid has no hard black edge that the coarse blurred
        // weights would drag into the blend (OpenCV builds pyramids with border
        // extension for the same reason).
        border_extend(&mut img, &covered, rw, rh);
        // Pyramids on the ROI.
        let mut gpyr: Vec<FImg> = Vec::with_capacity(bands + 1);
        gpyr.push(img);
        for b in 0..bands {
            gpyr.push(down3(&gpyr[b]));
        }
        let mut wpyr: Vec<Plane> = Vec::with_capacity(bands + 1);
        wpyr.push(wt);
        for b in 0..bands {
            wpyr.push(down1(&wpyr[b]));
        }
        for b in 0..=bands {
            let lw = gpyr[b].w;
            let lh = gpyr[b].h;
            let lap: FImg = if b == bands {
                FImg { w: lw, h: lh, d: gpyr[b].d.clone() }
            } else {
                let up = up3(&gpyr[b + 1], lw, lh);
                let mut l = FImg::new(lw, lh);
                for i in 0..lw * lh * 3 {
                    l.d[i] = gpyr[b].d[i] - up.d[i];
                }
                l
            };
            let (dw, _dh) = sizes[b];
            let offx = x_tl >> b;
            let offy = y_tl >> b;
            for yy in 0..lh {
                for xx in 0..lw {
                    let w = wpyr[b].d[yy * lw + xx];
                    let didx = (offy + yy) * dw + (offx + xx);
                    dst_w[b].d[didx] += w;
                    dst_lap[b].d[didx * 3] += lap.d[(yy * lw + xx) * 3] * w;
                    dst_lap[b].d[didx * 3 + 1] += lap.d[(yy * lw + xx) * 3 + 1] * w;
                    dst_lap[b].d[didx * 3 + 2] += lap.d[(yy * lw + xx) * 3 + 2] * w;
                }
            }
        }
    }

    // Normalize each band by weight.
    for b in 0..=bands {
        let (w, h) = sizes[b];
        for i in 0..w * h {
            let ww = dst_w[b].d[i];
            if ww > 1e-5 {
                dst_lap[b].d[i * 3] /= ww;
                dst_lap[b].d[i * 3 + 1] /= ww;
                dst_lap[b].d[i * 3 + 2] /= ww;
            }
        }
    }

    // Collapse pyramid.
    let mut res = FImg { w: sizes[bands].0, h: sizes[bands].1, d: dst_lap[bands].d.clone() };
    for b in (0..bands).rev() {
        let (w, h) = sizes[b];
        let up = up3(&res, w, h);
        let mut cur = FImg::new(w, h);
        for i in 0..w * h * 3 {
            cur.d[i] = up.d[i] + dst_lap[b].d[i];
        }
        res = cur;
    }

    // Crop to covered region (within the unpadded cw×ch, using base weight).
    let mut minx = cw;
    let mut miny = ch;
    let mut maxx = 0usize;
    let mut maxy = 0usize;
    let mut any = false;
    for y in 0..ch {
        for x in 0..cw {
            if dst_w[0].d[y * pw + x] > 1e-5 {
                any = true;
                minx = minx.min(x);
                miny = miny.min(y);
                maxx = maxx.max(x);
                maxy = maxy.max(y);
            }
        }
    }
    if !any {
        return None;
    }
    let ow = maxx - minx + 1;
    let oh = maxy - miny + 1;
    let mut out = Rgba::new(ow, oh);
    for y in 0..oh {
        for x in 0..ow {
            let sx = x + minx;
            let sy = y + miny;
            let didx = (y * ow + x) * 4;
            if dst_w[0].d[sy * pw + sx] > 1e-5 {
                let ridx = (sy * pw + sx) * 3;
                out.px[didx] = res.d[ridx].round().clamp(0.0, 255.0) as u8;
                out.px[didx + 1] = res.d[ridx + 1].round().clamp(0.0, 255.0) as u8;
                out.px[didx + 2] = res.d[ridx + 2].round().clamp(0.0, 255.0) as u8;
                out.px[didx + 3] = 255;
            } else {
                out.px[didx + 3] = 0;
            }
        }
    }
    Some(out)
}
