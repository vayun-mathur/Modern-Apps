plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:euicc:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "sim_card"
}

android {
    defaultConfig {
        versionCode = 20261005
        versionName = "v2.6.9"
        applicationId = "com.vayunmathur.euicc"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// SGP.22 core (ASN.1 + ES9+/ES10 + crypto) is native, loaded as libeuicc.so.
// See euicc/src/main/rust/.
rustNativeLib("euicc")

dependencies {
    // Compile-only stubs for the framework's @SystemApi eSIM LPA classes
    // (android.service.euicc.*, EuiccProfileInfo). Provided by the framework at
    // runtime on a system image; must NOT be packaged.
    compileOnly(project(":library:euicc-stubs"))
    // Rust reaches the SM-DP+ over HTTP through the flat-framed JNI bridge in
    // library:network (NativeHttpBridge), so libeuicc.so links no TLS of its own.
    implementation(project(":library:network"))
    // Local per-profile nicknames / prefs.
    implementation(libs.androidx.datastore.preferences)
    // QR activation-code scanning (CameraX preview + ZXing decode).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
