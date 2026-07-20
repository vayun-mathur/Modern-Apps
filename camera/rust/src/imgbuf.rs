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
    pub fn from_jpeg(bytes: &[u8]) -> Option<Rgba> {
        let img = image::load_from_memory(bytes).ok()?.to_rgba8();
        let (w, h) = img.dimensions();
        Some(Rgba::from_bytes(w as usize, h as usize, img.into_raw()))
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
