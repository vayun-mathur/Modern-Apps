//! JBIG2 decoding using hayro-jbig2 (pure Rust, memory-safe).
//! Handles embedded organization with optional JBIG2Globals, with size guards.

use hayro_jbig2::{Decoder as Jbig2Trait, Image};

struct SimpleRgbaDecoder {
    width: usize,
    height: usize,
    rgba: Vec<u8>,
    current_row: usize,
    col_pos: usize,
}

impl SimpleRgbaDecoder {
    fn new(width: usize, height: usize) -> Self {
        Self {
            width,
            height,
            rgba: vec![0u8; width * height * 4],
            current_row: 0,
            col_pos: 0,
        }
    }

    fn set_pixel_at(&mut self, col: usize, black: bool) {
        if self.current_row >= self.height || col >= self.width {
            return;
        }
        let idx = (self.current_row * self.width + col) * 4;
        let v = if black { 0 } else { 255 };
        self.rgba[idx] = v;
        self.rgba[idx + 1] = v;
        self.rgba[idx + 2] = v;
        self.rgba[idx + 3] = 255;
    }
}

impl Jbig2Trait for SimpleRgbaDecoder {
    fn push_pixel(&mut self, black: bool) {
        if self.col_pos >= self.width {
            return;
        }
        self.set_pixel_at(self.col_pos, black);
        self.col_pos += 1;
    }

    fn push_pixel_chunk(&mut self, black: bool, chunk_count: u32) {
        let count = (chunk_count as usize * 8).min(self.width.saturating_sub(self.col_pos));
        for i in 0..count {
            self.set_pixel_at(self.col_pos + i, black);
        }
        self.col_pos += count;
    }

    fn next_line(&mut self) {
        while self.col_pos < self.width {
            self.set_pixel_at(self.col_pos, false);
            self.col_pos += 1;
        }
        self.current_row += 1;
        self.col_pos = 0;
    }
}

/// Decode JBIG2 data (embedded organization) with optional globals.
/// Returns (w,h,rgba) if successful.
pub fn decode_jbig2(data: &[u8], globals: Option<&[u8]>, _max_w: u32, _max_h: u32) -> Option<(u32, u32, Vec<u8>)> {
    if data.is_empty() {
        return None;
    }

    // Try variants while keeping owning buffer alive for lifetime.
    // Ordered: embedded with globals, standalone, embedded without globals, combined globals+data as standalone file.

    // Helper to attempt decode from Image reference that borrows from provided slice(s) alive in this closure.
    let try_decode_image = |img: &Image| -> Option<(u32, u32, Vec<u8>)> {
        let w = img.width();
        let h = img.height();
        if w == 0 || h == 0 || w > 20000 || h > 20000 {
            return None;
        }
        if (w as usize).checked_mul(h as usize).unwrap_or(usize::MAX) > 16 * 1024 * 1024 {
            return None;
        }
        let mut decoder = SimpleRgbaDecoder::new(w as usize, h as usize);
        if img.decode(&mut decoder).is_err() {
            return None;
        }
        Some((w, h, decoder.rgba))
    };

    if let Some(g) = globals {
        // 1) embedded using globals
        if let Ok(img) = Image::new_embedded(data, Some(g)) {
            if let Some(res) = try_decode_image(&img) {
                return Some(res);
            }
        }
        // 2) standalone file in data itself (ignores globals)
        if let Ok(img) = Image::new(data) {
            if let Some(res) = try_decode_image(&img) {
                return Some(res);
            }
        }
        // 3) try combined buffer as if globals stream prepended to data and entire thing is a single file
        let mut combined = Vec::with_capacity(g.len() + data.len());
        combined.extend_from_slice(g);
        combined.extend_from_slice(data);
        if let Ok(img) = Image::new(&combined) {
            if let Some(res) = try_decode_image(&img) {
                return Some(res);
            }
        }
        // 4) embedded without globals (data may already contain required dicts)
        if let Ok(img) = Image::new_embedded(data, None) {
            if let Some(res) = try_decode_image(&img) {
                return Some(res);
            }
        }
        None
    } else {
        if let Ok(img) = Image::new(data) {
            if let Some(res) = try_decode_image(&img) {
                return Some(res);
            }
        }
        if let Ok(img) = Image::new_embedded(data, None) {
            if let Some(res) = try_decode_image(&img) {
                return Some(res);
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn empty_fails() {
        assert!(decode_jbig2(b"", None, 100, 100).is_none());
    }
    #[test]
    fn invalid_fails() {
        assert!(decode_jbig2(b"not jbig2", None, 100, 100).is_none());
    }
}
