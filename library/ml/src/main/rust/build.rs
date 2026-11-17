//! Compiles `shaders/*.comp` to SPIR-V in `OUT_DIR`.
//!
//! The library and the host examples `include_bytes!` the compiled shaders
//! from `OUT_DIR`. Previously the library read them from the Gradle
//! `compileShaders` output (`library/ml/build/spirv`), so standalone
//! `cargo check` / `cargo test` failed in a clean checkout until that Gradle
//! task had run, and the examples referenced an `OUT_DIR` that no build
//! script ever populated. Compiling here uses the same compiler and flags
//! as the Gradle task (`--target-env=vulkan1.1 -O`, NDK `glslc`), so the
//! Android build embeds identical SPIR-V; the Gradle task's output is still
//! honoured as a fallback when no `glslc` can be found. When neither is
//! available the build fails loudly rather than embedding stub shaders.

use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::{exit, Command};

fn main() {
    if let Err(message) = run() {
        eprintln!("modelrunner build script: {message}");
        exit(1);
    }
}

fn run() -> Result<(), String> {
    let manifest_dir = env::var_os("CARGO_MANIFEST_DIR")
        .map(PathBuf::from)
        .ok_or_else(|| "CARGO_MANIFEST_DIR is not set".to_owned())?;
    let out_dir = env::var_os("OUT_DIR")
        .map(PathBuf::from)
        .ok_or_else(|| "OUT_DIR is not set".to_owned())?;
    let shader_dir = manifest_dir.join("shaders");
    println!("cargo:rerun-if-changed={}", shader_dir.display());
    println!("cargo:rerun-if-env-changed=GLSLC");
    println!("cargo:rerun-if-env-changed=ANDROID_HOME");
    println!("cargo:rerun-if-env-changed=ANDROID_SDK_ROOT");
    println!("cargo:rerun-if-env-changed=ANDROID_NDK_HOME");

    let mut sources = Vec::new();
    let entries = fs::read_dir(&shader_dir)
        .map_err(|error| format!("cannot read {}: {error}", shader_dir.display()))?;
    for entry in entries {
        let path = entry
            .map_err(|error| format!("cannot read {}: {error}", shader_dir.display()))?
            .path();
        if path.extension().is_some_and(|ext| ext == "comp") {
            sources.push(path);
        }
    }
    sources.sort();
    if sources.is_empty() {
        return Err(format!("no .comp shaders in {}", shader_dir.display()));
    }

    if let Some(glslc) = find_glslc() {
        for source in &sources {
            let file_name = source
                .file_name()
                .ok_or_else(|| format!("shader {} has no file name", source.display()))?
                .to_string_lossy()
                .into_owned();
            let output = out_dir.join(format!("{file_name}.spv"));
            let status = Command::new(&glslc)
                .args(["--target-env=vulkan1.1", "-O", "-o"])
                .arg(&output)
                .arg(source)
                .status()
                .map_err(|error| format!("cannot run {}: {error}", glslc.display()))?;
            if !status.success() {
                return Err(format!(
                    "{} failed on {} with {status}",
                    glslc.display(),
                    source.display()
                ));
            }
        }
        return Ok(());
    }

    // No compiler: copy the Gradle `compileShaders` output verbatim.
    let prebuilt = manifest_dir.join("../../../build/spirv");
    let mut copied = 0usize;
    for source in &sources {
        let file_name = source
            .file_name()
            .ok_or_else(|| format!("shader {} has no file name", source.display()))?
            .to_string_lossy()
            .into_owned();
        let from = prebuilt.join(format!("{file_name}.spv"));
        if from.is_file() {
            fs::copy(&from, out_dir.join(format!("{file_name}.spv")))
                .map_err(|error| format!("cannot copy {}: {error}", from.display()))?;
            copied += 1;
        }
    }
    if copied == sources.len() {
        return Ok(());
    }
    Err(format!(
        "glslc not found (set GLSLC, put it on PATH, or install the Android NDK) and \
         prebuilt SPIR-V is incomplete in {}; refusing to embed stub shaders",
        prebuilt.display()
    ))
}

/// `GLSLC`, then `PATH`, then the newest NDK under the Android SDK.
fn find_glslc() -> Option<PathBuf> {
    let exe = if cfg!(windows) { "glslc.exe" } else { "glslc" };
    if let Some(path) = env::var_os("GLSLC").map(PathBuf::from) {
        if path.is_file() {
            return Some(path);
        }
    }
    if let Some(path_var) = env::var_os("PATH") {
        for dir in env::split_paths(&path_var) {
            let candidate = dir.join(exe);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    let mut sdk_roots: Vec<PathBuf> = ["ANDROID_NDK_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT"]
        .iter()
        .filter_map(|key| env::var_os(key).map(PathBuf::from))
        .collect();
    if let Some(home) = env::var_os("HOME").map(PathBuf::from) {
        sdk_roots.push(home.join("Android/Sdk"));
    }
    let mut candidates = Vec::new();
    for root in &sdk_roots {
        // `ANDROID_NDK_HOME` points at the NDK itself; the others at the SDK,
        // whose NDKs live under `ndk/<version>/`.
        let ndk_dirs: Vec<PathBuf> = if root.join("shader-tools").is_dir() {
            vec![root.clone()]
        } else {
            match fs::read_dir(root.join("ndk")) {
                Ok(entries) => entries.filter_map(|entry| entry.ok().map(|e| e.path())).collect(),
                Err(_) => Vec::new(),
            }
        };
        for ndk in ndk_dirs {
            if let Ok(hosts) = fs::read_dir(ndk.join("shader-tools")) {
                for host in hosts.filter_map(|entry| entry.ok().map(|e| e.path())) {
                    let candidate = host.join(exe);
                    if candidate.is_file() {
                        candidates.push(candidate);
                    }
                }
            }
        }
    }
    candidates.sort();
    candidates.pop()
}
