plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "photo_library"
}

android {
    defaultConfig {
        versionCode = 20260718
        versionName = "v2.6.0"
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        noCompress += "tflite"
        // The on-device EdgeFace face embedder (edgeface.onnx) is already
        // compressed; store it uncompressed so ONNX Runtime can read it directly
        // from assets. (SigLIP2 semantic search now lives in OpenAssistant.)
        noCompress += "onnx"
    }
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

metadataScreenshots {
    permissions.addAll(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.ACCESS_MEDIA_LOCATION",
    )
    appops.add("MANAGE_MEDIA")
}

dependencies {
    implementation(libs.androidx.compose.foundation)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.maplibre.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.exifinterface)
    // Face detection (BlazeFace), face embedding (EdgeFace), and subject
    // segmentation (U²-Net) all run on ONNX Runtime (MIT, FOSS, no Play
    // Services / no MediaPipe). The native .so ships via :library:ocr, but this
    // module needs its own compile dependency to call the ORT API directly.
    implementation(libs.onnxruntime.android)

    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:ink"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)


    implementation(project(":library:widgets"))
    implementation(project(":library:biometric"))
    implementation(project(":library:ocr"))
    // Semantic photo search now delegates image/text embedding to the
    // OpenAssistant app via this thin cross-app client (no on-device CLIP).
    implementation(project(":sdk:openassistant"))

    // The metadata screenshot generator writes EXIF GPS into seeded JPEGs.
    androidTestImplementation(libs.androidx.exifinterface)
}
