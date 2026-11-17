//! Minimal image buffers and sampling helpers (kept dependency-free so the CV
//! code is fully under our control; the `image` crate is only used for the final
//! JPEG encode).

/// Interleaved 8-bit RGBA image.
#[derive(Clone)]
pub struct Rgba {
    pub w: usize,
    pub h: usize,
    pub px: Vec<u8>, // len = w*h*4
}

impl Rgba {
    pub fn new(w: usize, h: usize) -> Self {
        Rgba { w, h, px: vec![0u8; w * h * 4] }
    }

    pub fn from_bytes(w: usize, h: usize, px: Vec<u8>) -> Self {
        debug_assert_eq!(px.len(), w * h * 4);
        Rgba { w, h, px }
    }

    /// Decode JPEG/PNG bytes into an RGBA buffer.
    /// Previously used `image` crate which pulled moxcms, pxfm, bytemuck, etc for this single call.
    /// Now uses jpeg-decoder directly (armv8-only, minimal pure Rust, already in pdf_render).
    ///
    /// Behind the `jpeg` feature: consumers that only want the CV primitives (the
    /// measure app's VIO engine feeds raw camera luminance and never sees a JPEG)
    /// should not have to link a codec.
    #[cfg(feature = "jpeg")]
    pub fn from_jpeg(bytes: &[u8]) -> Option<Rgba> {
        use std::io::Cursor;
        let mut decoder = jpeg_decoder::Decoder::new(Cursor::new(bytes));
        let pixels = decoder.decode().ok()?;
        let info = decoder.info()?;
        let w = info.width as usize;
        let h = info.height as usize;
        if w == 0 || h == 0 || w > 20000 || h > 20000 {
            return None;
        }
        let rgba = match info.pixel_format {
            jpeg_decoder::PixelFormat::L8 => {
                // gray -> rgba
                let mut out = vec![0u8; w * h * 4];
                for i in 0..w * h {
                    let g = pixels.get(i).copied().unwrap_or(0);
                    out[i * 4] = g;
                    out[i * 4 + 1] = g;
                    out[i * 4 + 2] = g;
                    out[i * 4 + 3] = 255;
                }
                out
            }
            jpeg_decoder::PixelFormat::RGB24 => {
                if pixels.len() < w * h * 3 {
                    return None;
                }
                let mut out = vec![0u8; w * h * 4];
                for i in 0..w * h {
                    out[i * 4] = pixels[i * 3];
                    out[i * 4 + 1] = pixels[i * 3 + 1];
                    out[i * 4 + 2] = pixels[i * 3 + 2];
                    out[i * 4 + 3] = 255;
                }
                out
            }
            // jpeg-decoder rarely emits CMYK for plain camera JPEGs, but handle
            // inverted Adobe-marked path if present (same as pdf path)
            jpeg_decoder::PixelFormat::CMYK32 => {
                if pixels.len() < w * h * 4 {
                    return None;
                }
                let mut out = vec![0u8; w * h * 4];
                for i in 0..w * h {
                    let c = pixels[i * 4] as f32 / 255.0;
                    let m = pixels[i * 4 + 1] as f32 / 255.0;
                    let y = pixels[i * 4 + 2] as f32 / 255.0;
                    let k = pixels[i * 4 + 3] as f32 / 255.0;
                    out[i * 4] = ((1.0 - c) * (1.0 - k) * 255.0).round() as u8;
                    out[i * 4 + 1] = ((1.0 - m) * (1.0 - k) * 255.0).round() as u8;
                    out[i * 4 + 2] = ((1.0 - y) * (1.0 - k) * 255.0).round() as u8;
                    out[i * 4 + 3] = 255;
                }
                out
            }
            _ => return None,
        };
        Some(Rgba::from_bytes(w, h, rgba))
    }

    #[inline]
    pub fn get(&self, x: usize, y: usize) -> [u8; 4] {
        let i = (y * self.w + x) * 4;
        [self.px[i], self.px[i + 1], self.px[i + 2], self.px[i + 3]]
    }

    /// Bilinear-resampled copy at a new size. Used to run the (slow) registration
    /// stage — features, matching, bundle adjustment — at a reduced resolution.
    pub fn resized(&self, nw: usize, nh: usize) -> Rgba {
        let nw = nw.max(1);
        let nh = nh.max(1);
        let mut out = Rgba::new(nw, nh);
        let sx = self.w as f32 / nw as f32;
        let sy = self.h as f32 / nh as f32;
        for y in 0..nh {
            let fy = ((y as f32 + 0.5) * sy - 0.5).max(0.0);
            for x in 0..nw {
                let fx = ((x as f32 + 0.5) * sx - 0.5).max(0.0);
                let c = self.sample(fx, fy).unwrap_or([0.0, 0.0, 0.0, 0.0]);
                out.set(x, y, [
                    c[0].round().clamp(0.0, 255.0) as u8,
                    c[1].round().clamp(0.0, 255.0) as u8,
                    c[2].round().clamp(0.0, 255.0) as u8,
                    255,
                ]);
            }
        }
        out
    }

    #[inline]
    pub fn set(&mut self, x: usize, y: usize, c: [u8; 4]) {
        let i = (y * self.w + x) * 4;
        self.px[i] = c[0];
        self.px[i + 1] = c[1];
        self.px[i + 2] = c[2];
        self.px[i + 3] = c[3];
    }

    /// Bilinear RGBA sample; returns None if out of bounds or the sampled
    /// neighbourhood is fully transparent.
    #[inline]
    pub fn sample(&self, fx: f32, fy: f32) -> Option<[f32; 4]> {
        if fx < 0.0 || fy < 0.0 || fx > (self.w - 1) as f32 || fy > (self.h - 1) as f32 {
            return None;
        }
        let x0 = fx.floor() as usize;
        let y0 = fy.floor() as usize;
        let x1 = (x0 + 1).min(self.w - 1);
        let y1 = (y0 + 1).min(self.h - 1);
        let ax = fx - x0 as f32;
        let ay = fy - y0 as f32;
        let mut out = [0f32; 4];
        let c00 = self.get(x0, y0);
        let c10 = self.get(x1, y0);
        let c01 = self.get(x0, y1);
        let c11 = self.get(x1, y1);
        // If every corner is fully transparent, treat as no data.
        if c00[3] == 0 && c10[3] == 0 && c01[3] == 0 && c11[3] == 0 {
            return None;
        }
        for k in 0..4 {
            let top = c00[k] as f32 * (1.0 - ax) + c10[k] as f32 * ax;
            let bot = c01[k] as f32 * (1.0 - ax) + c11[k] as f32 * ax;
            out[k] = top * (1.0 - ay) + bot * ay;
        }
        Some(out)
    }
}

/// 8-bit single-channel image.
#[derive(Clone)]
pub struct Gray {
    pub w: usize,
    pub h: usize,
    pub px: Vec<u8>,
}

impl Gray {
    pub fn new(w: usize, h: usize) -> Self {
        Gray { w, h, px: vec![0u8; w * h] }
    }

    #[inline]
    pub fn at(&self, x: usize, y: usize) -> u8 {
        self.px[y * self.w + x]
    }

    pub fn resized(&self, nw: usize, nh: usize) -> Gray {
        let nw = nw.max(1);
        let nh = nh.max(1);
        let mut out = Gray::new(nw, nh);
        let sx = self.w as f32 / nw as f32;
        let sy = self.h as f32 / nh as f32;
        for y in 0..nh {
            let fy = ((y as f32 + 0.5) * sy - 0.5).max(0.0);
            let y0 = fy.floor() as usize;
            let y1 = (y0 + 1).min(self.h - 1);
            let ay = fy - y0 as f32;
            for x in 0..nw {
                let fx = ((x as f32 + 0.5) * sx - 0.5).max(0.0);
                let x0 = fx.floor() as usize;
                let x1 = (x0 + 1).min(self.w - 1);
                let ax = fx - x0 as f32;
                let c00 = self.px[y0 * self.w + x0] as f32;
                let c10 = self.px[y0 * self.w + x1] as f32;
                let c01 = self.px[y1 * self.w + x0] as f32;
                let c11 = self.px[y1 * self.w + x1] as f32;
                let top = c00 * (1.0 - ax) + c10 * ax;
                let bot = c01 * (1.0 - ax) + c11 * ax;
                out.px[y * nw + x] = (top * (1.0 - ay) + bot * ay).round().clamp(0.0, 255.0) as u8;
            }
        }
        out
    }

    /// Cheap 3x3 Gaussian blur (approx: center 4, edge 2, corner 1) for pyramid.
    pub fn gaussian_blur_3x3(&self) -> Gray {
        let (w, h) = (self.w, self.h);
        if w < 3 || h < 3 {
            return self.clone();
        }
        let mut out = Gray::new(w, h);
        // Keep borders unchanged
        for y in 0..h {
            for x in 0..w {
                if x == 0 || y == 0 || x + 1 == w || y + 1 == h {
                    out.px[y * w + x] = self.px[y * w + x];
                    continue;
                }
                let mut acc: u32 = 0;
                // 1 2 1 / 2 4 2 / 1 2 1  (sum 16)
                let cx = x;
                let cy = y;
                acc += self.px[(cy - 1) * w + (cx - 1)] as u32;
                acc += self.px[(cy - 1) * w + cx] as u32 * 2;
                acc += self.px[(cy - 1) * w + (cx + 1)] as u32;
                acc += self.px[cy * w + (cx - 1)] as u32 * 2;
                acc += self.px[cy * w + cx] as u32 * 4;
                acc += self.px[cy * w + (cx + 1)] as u32 * 2;
                acc += self.px[(cy + 1) * w + (cx - 1)] as u32;
                acc += self.px[(cy + 1) * w + cx] as u32 * 2;
                acc += self.px[(cy + 1) * w + (cx + 1)] as u32;
                out.px[y * w + x] = (acc / 16) as u8;
            }
        }
        out
    }
}

#[inline]
fn harris_response(g: &Gray, x: usize, y: usize) -> f32 {
    // 7x7 window Sobel approx Ix,Iy then Harris H = det(M) - K*trace^2  K=0.04
    // M = [ Ix2 IxIy ; IxIy Iy2 ] summed over window
    let mut ix2: i32 = 0;
    let mut iy2: i32 = 0;
    let mut ixy: i32 = 0;
    let r: i32 = 3;
    for dy in -r..=r {
        for dx in -r..=r {
            let xx = (x as i32 + dx).clamp(1, g.w as i32 - 2) as usize;
            let yy = (y as i32 + dy).clamp(1, g.h as i32 - 2) as usize;
            let xm = if xx > 0 { xx - 1 } else { xx };
            let xp = if xx + 1 < g.w { xx + 1 } else { xx };
            let ym = if yy > 0 { yy - 1 } else { yy };
            let yp = if yy + 1 < g.h { yy + 1 } else { yy };
            let dxv = g.px[yy * g.w + xp] as i32 - g.px[yy * g.w + xm] as i32;
            let dyv = g.px[yp * g.w + xx] as i32 - g.px[ym * g.w + xx] as i32;
            ix2 += dxv * dxv;
            iy2 += dyv * dyv;
            ixy += dxv * dyv;
        }
    }
    let det = ix2 as f64 * iy2 as f64 - ixy as f64 * ixy as f64;
    let trace = ix2 as f64 + iy2 as f64;
    (det - 0.04 * trace * trace) as f32
}

/// Compute Harris scores for a list of points
pub fn harris_scores(g: &Gray, pts: &[(i32, i32)]) -> Vec<f32> {
    pts.iter().map(|&(x, y)| harris_response(g, x as usize, y as usize)).collect()
}

/// Rec.601 luma from an RGBA image.
pub fn to_gray(img: &Rgba) -> Gray {
    let mut g = Gray::new(img.w, img.h);
    for i in 0..img.w * img.h {
        let r = img.px[i * 4] as u32;
        let gg = img.px[i * 4 + 1] as u32;
        let b = img.px[i * 4 + 2] as u32;
        g.px[i] = ((r * 77 + gg * 150 + b * 29) >> 8) as u8;
    }
    g
}

#[cfg(test)] #[path = "imgbuf_tests.rs"] mod imgbuf_tests;
