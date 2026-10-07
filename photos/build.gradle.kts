plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:photos:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "photo_library"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        // The four .maml files are read straight out of the APK by :library:ml, which opens them
        // with `AssetManager.openFd` and streams them into the GPU. `openFd` throws outright for a
        // deflated entry, so this is required rather than an optimisation — and it costs nothing on
        // download size, since int8 and fp16 weights barely deflate.
        noCompress += "maml"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native pixel filters (Rust). See photos/src/main/rust/.
rustNativeLib("photos_fx", "photos")

dependencies {
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.fragment.ktx)
    implementation(project(":library:map"))
    implementation(project(":library:image"))
    implementation(libs.androidx.exifinterface)
    // No ncnn and no onnxruntime anywhere in this APK. Face detection (SCRFD 500M), face
    // embedding (MobileFaceNet), subject segmentation (U²-Net portable), PP-OCRv5 text
    // recognition and TinyCLIP semantic search all run on :library:ml, our own Vulkan compute
    // runtime.
    implementation(project(":library:ml"))

    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:ink"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)


    implementation(project(":library:widgets"))
    implementation(project(":library:biometric"))
    implementation(project(":library:ocr"))
}
