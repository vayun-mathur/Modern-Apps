//! A `VkPipelineCache` kept on disk, so compiling every compute shader is paid once per install
//! rather than once per process.
//!
//! Building the ~46 pipelines took 918 ms on a Pixel 8 every time a process first used a model:
//! the translator's first sentence, the dictation's first word. The driver can serialise what it
//! compiled and accept it back next time, and validates the blob's header (vendor, device, driver
//! UUID) itself, so a stale or foreign file is ignored rather than trusted.
//!
//! # Where the file goes
//!
//! `MODELRUNNER_CACHE_DIR` when set - the cross-compiled test binaries set it. Otherwise, on
//! Android, the app's own `code_cache` directory, derived from the process name and the user id so
//! no Kotlin has to hand a path down: `/data/user/<uid / 100000>/<package>/code_cache`. Anything
//! that fails along the way means no cache, never an error: the pipelines are built exactly as
//! before.

use std::path::PathBuf;

use ash::vk;

/// The file name inside the cache directory.
const FILE: &str = "modelrunner_pipelines.bin";

/// Where the cache file lives, or `None` to run without one.
fn path() -> Option<PathBuf> {
    if let Some(dir) = crate::knobs::get("cache_dir") {
        return Some(PathBuf::from(dir).join(FILE));
    }
    android_code_cache().map(|dir| dir.join(FILE))
}

#[cfg(target_os = "android")]
fn android_code_cache() -> Option<PathBuf> {
    extern "C" {
        fn getuid() -> u32;
    }
    let cmdline = std::fs::read("/proc/self/cmdline").ok()?;
    let name = cmdline.split(|&b| b == 0).next()?;
    let name = std::str::from_utf8(name).ok()?;
    // A secondary process is `package:name`; its files are the package's.
    let package = name.split(':').next()?;
    if package.is_empty() || package.contains('/') {
        // A shell-launched binary, whose "name" is a path.
        return None;
    }
    // SAFETY: `getuid` takes nothing and cannot fail.
    let user = unsafe { getuid() } / 100_000;
    let dir = PathBuf::from(format!("/data/user/{user}/{package}/code_cache"));
    dir.is_dir().then_some(dir)
}

#[cfg(not(target_os = "android"))]
fn android_code_cache() -> Option<PathBuf> {
    None
}

/// A pipeline cache seeded from disk, or an empty one when there is nothing usable to seed it.
///
/// # Safety
///
/// `device` must be live; the returned cache must be destroyed with it.
pub(crate) unsafe fn open(device: &ash::Device) -> (vk::PipelineCache, Vec<u8>) {
    let seed = path().and_then(|p| std::fs::read(p).ok()).unwrap_or_default();
    let info = vk::PipelineCacheCreateInfo::default().initial_data(&seed);
    match device.create_pipeline_cache(&info, None) {
        Ok(cache) => (cache, seed),
        // A driver that rejects the seed outright rather than ignoring it: start empty.
        Err(_) => match device.create_pipeline_cache(&vk::PipelineCacheCreateInfo::default(), None) {
            Ok(cache) => (cache, Vec::new()),
            Err(_) => (vk::PipelineCache::null(), Vec::new()),
        },
    }
}

/// Write the cache back if it holds anything the seed did not, then destroy it.
///
/// Written to a temporary name and renamed, so a process killed mid-write leaves the old file or
/// none - never half of one for the next launch to hand the driver.
///
/// # Safety
///
/// `cache` must come from [`open`] on `device`, and no pipeline creation may be using it.
pub(crate) unsafe fn close(device: &ash::Device, cache: vk::PipelineCache, seed: &[u8]) {
    if cache == vk::PipelineCache::null() {
        return;
    }
    if let (Some(path), Ok(data)) = (path(), device.get_pipeline_cache_data(cache)) {
        if !data.is_empty() && data != seed {
            let staging = path.with_extension("tmp");
            if std::fs::write(&staging, &data).is_ok() {
                let _ = std::fs::rename(&staging, &path);
            }
        }
    }
    device.destroy_pipeline_cache(cache, None);
}

#[cfg(test)]
#[path = "pipeline_cache_tests.rs"]
mod pipeline_cache_tests;
