//! The Vulkan compute ML runtime behind `:library:ml`.
//!
//! Runs the two pure-CNN vision models the repo ships — MediaPipe Selfie Segmentation
//! for `:camera`'s portrait bokeh, U^2-Net portable for `:photos`'s auto-select-subject
//! — replacing the CPU-only ncnn fork for both. ncnn stays for `:photos`'s two face
//! models and for `:library:ocr`, `:pdf`, `:translate` and `:speech`.
//!
//! # Layout
//!
//! * [`weights`] — the `.maml` reader. Ordered tensors, no topology.
//! * [`nets`] — the two hardcoded forward passes, and the small compiler that packs
//!   their activations into one arena and resolves every offset.
//! * [`preprocess`] — bitmap to fp16 NCHW, and the fp16 conversions.
//! * [`microfrontend`] — 16 kHz PCM to log-mel frames, for the audio nets.
//! * [`gate`] — Now Playing's always-on music detector, on the CPU. See its header.
//! * [`logmel`] — 16 kHz PCM to Gemma 4's 128-bin HTK log-mel, which is a different chain.
//! * [`vulkan`] — device, pipelines, the recorded command buffer.
//!
//! # GPU only, by decision
//!
//! There is no CPU fallback. Vulkan 1.1 is guaranteed at minSdk 31, but fp16 is not:
//! `VK_KHR_shader_float16_int8` was promoted only in 1.2, so the device extension and
//! its feature struct have to be asked for explicitly. Where they are missing the
//! runtime reports itself unavailable and the feature turns off — no bokeh, no
//! auto-subject-select — rather than falling back to a second implementation that would
//! have to be kept correct with no way to test it.
//!
//! # Nothing runs per frame
//!
//! A net is compiled to a flat [`nets::Plan`] once, and that plan is recorded into a
//! single command buffer once, at construction. An inference is then an upload, one
//! submit, one fence wait and one readback. That matters because `:camera` calls this
//! at ~15 fps while the UI is also using the GPU.
//!
//! # Host builds
//!
//! Everything except the JNI layer compiles and tests on the host, so `cargo test`
//! builds **both** networks in full, checks the `.maml` round trip, the arena arithmetic
//! and the preprocessing, with no device and no asset.
//!
//! [`vulkan`] builds on the host too — `ash`'s `loaded` feature finds whatever Vulkan
//! loader the platform has — which is the only way the shaders get executed anywhere but
//! a phone. Any device-backed test must therefore be `#[ignore]`d, so a host with no
//! Vulkan still passes `cargo test`. On an `x86_64-pc-windows-gnu` host, `libloading`
//! needs a `dlltool` on `PATH` to build; the NDK's `llvm-dlltool` works under that name.

pub mod frame;
pub mod gate;
pub mod knobs;
pub mod logmel;
pub mod logmel_extra;
pub mod microfrontend;
pub mod nets;
pub mod post;
pub mod preprocess;
// `#[macro_use]` so `timing!` is in scope for the modules declared after this one, which is
// every module that measures anything. `#[macro_export]` alone puts it at the crate root for
// other crates - the `examples/` are separate crates - but does not bring the bare name into
// scope here.
#[macro_use]
pub mod timing;
pub mod weights;

pub mod vulkan;

#[cfg(target_os = "android")]
mod bridge;
