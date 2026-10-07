plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:code:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "code"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.code"
    }
}

// Native tree-sitter highlighter (Rust → libcode_ts.so, arm64-v8a). Built on every `code` build;
// TreeSitterNative loads it at runtime and highlights the 10 supported languages, with the regex
// tokenizer still covering the remaining languages. Requires the Android NDK +
// `rustup target add aarch64-linux-android`; the pinned grammar set is locked in
// code/src/main/rust/Cargo.lock.
androidComponents {
    onVariants { variant ->
        val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
    }
}
rustNativeLib("code_ts", "code")

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jgit)
    implementation(libs.androidx.webkit)
}
