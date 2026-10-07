plugins {
    id("common-conventions-app")
    // No metadata convention. The viewfinder is the screen, and Layoutlib cannot open a
    // camera, so there is nothing a Compose preview could capture — with no SurfaceRequest
    // the preview falls back to a black Box. The listing images are hand-captured and
    // committed under metadata_data/photos/, which release.sh picks up directly.
}

launcherIcon {
    symbol = "photo_camera"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.camera"
    }
    androidResources {
        // selfie_segmentation.maml is read straight out of the APK by SelfieSegmenter, so
        // leave it uncompressed: fp16 weights barely deflate, and a compressed asset would
        // have to be inflated into a heap buffer before it could be uploaded.
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

// Native panorama stitcher + night burst aligner (Rust). See camera/src/main/rust/.
rustNativeLib("camera_stitch", "camera")

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    // On-device portrait segmentation. MediaPipe Selfie Segmentation (Apache-2.0) on our
    // own Vulkan compute runtime; see camera/src/main/assets/README.md.
    //
    // ncnn is gone from the whole tree, not just this app: `:translate` was the last consumer and
    // it now runs SMaLL-100 on `:library:ml` too, so `libncnn_android.so` is in no APK here.
    implementation(project(":library:ml"))
}
