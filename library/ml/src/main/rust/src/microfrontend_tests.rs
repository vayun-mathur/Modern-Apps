//! Microfrontend tests for the configuration refusals in the filter
//! bank and the front end constructor.

use super::{mel_breakpoints, Config, Frontend};

fn config() -> Config {
    Config {
        sample_rate: 16_000,
        window_samples: 400,
        hop_samples: 160,
        fft_size: 512,
        channels: 40,
        lower_band_limit: 125.0,
        upper_band_limit: 7_500.0,
        window_bits: 12,
        fft_gain: 1.0,
        log_correction_bits: 0,
        ceiling: f32::INFINITY,
    }
}

#[test]
fn the_filter_bank_refuses_degenerate_configs() {
    let mut bad = config();
    bad.fft_size = 0;
    assert!(mel_breakpoints(&bad).is_err(), "zero FFT");
    let mut bad = config();
    bad.channels = 0;
    assert!(mel_breakpoints(&bad).is_err(), "zero channels");
    // An empty mel band: upper at or below the lower edge.
    let mut bad = config();
    bad.upper_band_limit = bad.lower_band_limit;
    assert!(mel_breakpoints(&bad).is_err(), "empty band");
    // A band no FFT bin falls into: both edges above Nyquist's first bin.
    let mut bad = config();
    bad.lower_band_limit = 7_990.0;
    bad.upper_band_limit = 7_999.0;
    assert!(mel_breakpoints(&bad).is_err(), "no bin in band");
}

#[test]
fn the_frontend_refuses_bad_window_geometry_and_reports_its_channels() {
    let mut bad = config();
    bad.hop_samples = 0;
    assert!(Frontend::new(bad).is_err(), "zero hop");
    let mut bad = config();
    bad.window_samples = 0;
    assert!(Frontend::new(bad).is_err(), "zero window");
    let mut bad = config();
    bad.fft_size = 256; // power of two, but smaller than the 400-sample window
    assert!(Frontend::new(bad).is_err(), "FFT smaller than window");
    let mut bad = config();
    bad.fft_size = 500; // not a power of two
    bad.window_samples = 400;
    assert!(Frontend::new(bad).is_err(), "FFT not a power of two");
    let frontend = Frontend::new(config()).expect("a sane config builds");
    assert_eq!(frontend.channels(), 40);
}
