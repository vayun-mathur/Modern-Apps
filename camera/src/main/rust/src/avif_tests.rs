use super::{avif_dimensions, avif_exif_item, avif_exif_tiff, encode_avif, encode_avif_with_exif, has_avif_magic};
use crate::avif_mux::mux_avif_exif;

    use super::{avif_dimensions, avif_exif_item, avif_exif_tiff, encode_avif, encode_avif_with_exif, has_avif_magic};

    /// Minimal little-endian TIFF: IFD0 with one Orientation(=1) entry,
    /// then a marker tail so content assertions can find the payload.
    fn test_tiff() -> Vec<u8> {
        let mut tiff = Vec::new();
        tiff.extend_from_slice(b"II\x2a\0");
        tiff.extend_from_slice(&8u32.to_le_bytes()); // IFD0 at offset 8
        tiff.extend_from_slice(&1u16.to_le_bytes()); // one entry
        tiff.extend_from_slice(&0x0112u16.to_le_bytes()); // Orientation
        tiff.extend_from_slice(&3u16.to_le_bytes()); // SHORT
        tiff.extend_from_slice(&1u32.to_le_bytes()); // count 1
        tiff.extend_from_slice(&1u16.to_le_bytes()); // value 1 (Normal)
        tiff.extend_from_slice(&[0, 0]);
        tiff.extend_from_slice(&0u32.to_le_bytes()); // no next IFD
        tiff.extend_from_slice(b"GPSMARK");
        tiff
    }

    fn gradient(width: usize, height: usize) -> Vec<u8> {
        let mut px = Vec::with_capacity(width * height * 4);
        for y in 0..height {
            for x in 0..width {
                px.extend_from_slice(&[
                    (x % 256) as u8,
                    (y % 256) as u8,
                    ((x + y) % 256) as u8,
                    255,
                ]);
            }
        }
        px
    }

    #[test]
    fn encodes_synthetic_rgba_with_avif_header_and_dimensions() {
        let rgba = gradient(64, 48);
        let bytes = encode_avif(&rgba, 64, 48, 80.0, 10).expect("encode must succeed");
        assert!(has_avif_magic(&bytes), "missing ftypavif magic");
        assert_eq!(avif_dimensions(&bytes), Some((64, 48)));
    }

    #[test]
    fn exif_item_round_trips_through_isobmff_boxes() {
        let rgba = gradient(64, 48);
        let tiff = test_tiff();
        let bytes = encode_avif_with_exif(&rgba, 64, 48, 80.0, 10, Some(&tiff))
            .expect("encode with exif must succeed");
        assert!(has_avif_magic(&bytes));
        assert_eq!(avif_dimensions(&bytes), Some((64, 48)));
        // Item framing: [u32 offset=0][TIFF], located via iinf+iloc.
        let item = avif_exif_item(&bytes).expect("Exif item must be present via iinf/iloc");
        assert_eq!(&item[..4], &[0, 0, 0, 0]);
        let parsed = avif_exif_tiff(&bytes).expect("TIFF payload must parse back");
        assert_eq!(parsed, tiff);
        assert!(parsed.starts_with(b"II\x2a\0"));
        assert!(parsed.windows(7).any(|w| w == b"GPSMARK"));
    }

    #[test]
    fn mux_splices_exif_into_encoded_avif() {
        let rgba = gradient(64, 48);
        let tiff = test_tiff();
        let bare = encode_avif(&rgba, 64, 48, 80.0, 10).expect("encode must succeed");
        assert!(avif_exif_item(&bare).is_none());
        let muxed = mux_avif_exif(&bare, &tiff).expect("mux must succeed");
        if let Ok(dir) = std::env::var("AVIF_BENCH_DIR") {
            let _ = std::fs::write(format!("{dir}/muxed64.avif"), &muxed);
        }
        assert!(has_avif_magic(&muxed));
        assert_eq!(avif_dimensions(&muxed), Some((64, 48)));
        let parsed = avif_exif_tiff(&muxed).expect("muxed TIFF must parse back via iinf/iloc");
        assert_eq!(parsed, tiff);
        // A second mux (file already has an Exif item) and bad inputs refuse.
        assert!(mux_avif_exif(&muxed, &tiff).is_none());
        assert!(mux_avif_exif(&bare, b"not a tiff").is_none());
        assert!(mux_avif_exif(b"not an avif", &tiff).is_none());
    }

    #[test]
    fn no_exif_item_when_no_payload_given() {
        let rgba = gradient(32, 32);
        let bytes = encode_avif(&rgba, 32, 32, 80.0, 10).expect("encode must succeed");
        assert!(avif_exif_item(&bytes).is_none());
    }

    #[test]
    fn non_tiff_exif_is_rejected_so_kotlin_falls_back_to_jpeg() {
        let rgba = gradient(32, 32);
        assert!(encode_avif_with_exif(&rgba, 32, 32, 80.0, 10, Some(b"not a tiff")).is_none());
        assert!(encode_avif_with_exif(&rgba, 32, 32, 80.0, 10, Some(b"")).is_none());
    }

    #[test]
    fn rejects_mismatched_and_oversized_input() {
        assert!(encode_avif(&[0u8; 10], 64, 48, 80.0, 10).is_none());
        assert!(encode_avif(&[], 0, 0, 80.0, 10).is_none());
        assert!(encode_avif(&[0u8; 4], 20_001, 1, 80.0, 10).is_none());
    }

    /// Photo-like 12 MP frame (4080x3072): smooth gradients + sinusoidal
    /// detail + deterministic sensor-like noise, so encode times are not
    /// flattered by a flat/gradient-only synthetic.
    fn photo_like(width: usize, height: usize) -> Vec<u8> {
        let mut px = Vec::with_capacity(width * height * 4);
        let mut state: u32 = 0x9E37_79B9;
        let mut noise = move || {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            (state >> 24) as i32 - 128
        };
        for y in 0..height {
            for x in 0..width {
                let fx = x as f32 / width as f32;
                let fy = y as f32 / height as f32;
                let wave = ((x as f32 * 0.11).sin() * 18.0
                    + (y as f32 * 0.043 + x as f32 * 0.017).sin() * 26.0
                    + ((x + y) as f32 * 0.31).sin() * 8.0) as i32;
                let n = noise() / 10;
                let r = (40.0 + fx * 180.0) as i32 + wave + n;
                let g = (60.0 + fy * 150.0) as i32 + wave / 2 + n;
                let b = (90.0 + (1.0 - fx) * 110.0) as i32 - wave / 3 + n;
                px.extend_from_slice(&[
                    r.clamp(0, 255) as u8,
                    g.clamp(0, 255) as u8,
                    b.clamp(0, 255) as u8,
                    255,
                ]);
            }
        }
        px
    }

    /// Host-side encode-time probe for a 12 MP frame. Ignored by default
    /// (it takes seconds); run with
    /// `cargo test --release -p camera_stitch bench_12mp -- --ignored --nocapture`
    /// to reproduce the latency figures in the change report. Writes each
    /// speed's .avif to $AVIF_BENCH_DIR (default /tmp/avif_bench) so an
    /// external AV1 decoder (ffmpeg/dav1d) can score PSNR vs the source.
    #[test]
    #[ignore]
    fn bench_12mp_encode_time() {
        let (w, h) = (4080usize, 3072usize);
        let rgba = photo_like(w, h);
        let dir = std::env::var("AVIF_BENCH_DIR").unwrap_or_else(|_| "/tmp/avif_bench".into());
        let _ = std::fs::create_dir_all(&dir);
        // Source dump for PSNR scoring (raw RGBA).
        let _ = std::fs::write(format!("{dir}/source_{w}x{h}.rgba"), &rgba);
        for speed in [4u8, 6, 8, 9, 10] {
            let start = std::time::Instant::now();
            let bytes = encode_avif(&rgba, w, h, 80.0, speed).expect("encode must succeed");
            let ms = start.elapsed().as_millis();
            let _ = std::fs::write(format!("{dir}/q80_s{speed}.avif"), &bytes);
            println!("12MP AVIF q80 speed={speed} threads=default: {ms} ms -> {} bytes", bytes.len());
            assert!(has_avif_magic(&bytes));
        }
        // Thread scaling at the production-adjacent speeds, via ravif directly.
        let pixels: Vec<ravif::RGBA8> = rgba
            .chunks_exact(4)
            .map(|p| ravif::RGBA8::new(p[0], p[1], p[2], p[3]))
            .collect();
        for speed in [8u8, 10] {
            for threads in [Some(1usize), Some(4), Some(8), Some(16)] {
                let img = ravif::Img::new(pixels.as_slice(), w, h);
                let start = std::time::Instant::now();
                let enc = ravif::Encoder::new()
                    .with_quality(80.0)
                    .with_speed(speed)
                    .with_bit_depth(ravif::BitDepth::Eight)
                    .with_num_threads(threads)
                    .encode_rgba(img)
                    .expect("encode must succeed");
                println!(
                    "12MP AVIF q80 speed={speed} threads={threads:?}: {} ms -> {} bytes",
                    start.elapsed().as_millis(),
                    enc.avif_file.len()
                );
            }
        }
    }
